package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.JournalException;
import io.github.oleksiifenin.raa.storage.Resolution;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves actions nobody is waiting for (docs/DESIGN.md §6, Reconciler): moves attempts whose lease expired to
 * {@code UNKNOWN}, resolves {@code UNKNOWN} rows through the tool's {@link OutcomeLookup}, and escalates or abandons rows
 * at their deadline. Never executes an effect (ADR-0001). Safe to run on many processes at once: each batch is taken
 * with {@code SKIP LOCKED} and reserved for {@link #DEFAULT_HOLD}, and every change is a compare-and-set on the attempt.
 *
 * <p>Time comes from the database only: rows taken by {@link ActionJournal#takeReconcileBatch} carry
 * {@code resolve_after = clock_timestamp() + hold}, so {@code resolve_after - hold} is the database time of the take.
 *
 * <p>A lookup needs the action key, which the journal has only as text when {@link
 * ReliableActions.Builder#storeKeyText(boolean)} is on (ADR-0004). Rows without usable key text are left to the next
 * call, which looks up inline, and are escalated at their deadline.
 */
public final class Reconciler {

    /** How long a taken batch is reserved; covers a full batch of lookups at the default timeout. */
    static final Duration DEFAULT_HOLD = Duration.ofMinutes(2);
    /** Rows per {@link #runOnce()}. */
    static final int DEFAULT_BATCH_SIZE = 20;

    private static final System.Logger LOG = System.getLogger(Reconciler.class.getName());

    private final ActionJournal journal;
    private final Map<String, ActionPolicy> policies;
    private final Duration hold;
    private final int batchSize;
    private final TimedLookup timedLookup = new TimedLookup();

    private final ActionTelemetry telemetry;

    Reconciler(ActionJournal journal, Map<String, ActionPolicy> policies, Duration hold, int batchSize,
            ActionTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.hold = Objects.requireNonNull(hold, "hold");
        this.batchSize = batchSize;
    }

    /**
     * Takes one batch and handles every row in it. A journal failure on one row is logged and the next row is handled;
     * the failed row is taken again after the hold.
     *
     * @return the number of rows taken
     * @throws JournalException if the batch itself cannot be taken
     */
    public int runOnce() {
        List<ActionRecord> batch = journal.takeReconcileBatch(hold, batchSize);
        for (ActionRecord row : batch) {
            if (Thread.currentThread().isInterrupted()) {
                break; // the remaining rows are taken again after the hold
            }
            try {
                reconcile(row);
            } catch (JournalException e) {
                LOG.log(System.Logger.Level.WARNING, "could not reconcile an action of tool " + row.toolName(), e);
            }
        }
        return batch.size();
    }

    private void reconcile(ActionRecord row) {
        ActionPolicy policy = policies.get(row.toolName());
        if (!(policy instanceof JournaledPolicy<?>)) {
            LOG.log(System.Logger.Level.WARNING, "no journaled policy is configured for tool {0}; its {1} action is"
                    + " left for an operator", row.toolName(), row.state());
            return;
        }
        JournaledPolicy<?> journaled = (JournaledPolicy<?>) policy;
        Instant now = Objects.requireNonNull(row.resolveAfter(), "a taken row has resolve_after").minus(hold);
        boolean deadlinePassed = !now.isBefore(row.deadlineAt());
        switch (row.state()) {
            case EXECUTING:
                // The next batch takes the row as UNKNOWN: markUnknownIfLeaseExpired sets resolve_after = now.
                if (journal.markUnknownIfLeaseExpired(row.keyHash(), row.attempt(),
                        "the lease expired; the process running the attempt is presumed dead")) {
                    telemetry.signal(ActionSignal.LEASE_EXPIRED, row.toolName());
                    telemetry.reconcilerResolution(ReconcilerResult.MARKED_UNKNOWN, row.toolName());
                }
                return;
            case UNKNOWN:
                unknown(row, journaled, now, deadlinePassed);
                return;
            case FAILED:
                if (deadlinePassed) {
                    resolve(row, new Resolution.Abandon("the deadline passed"));
                }
                return;
            default:
                return; // taken concurrently after a change; nothing to do
        }
    }

    private void unknown(ActionRecord row, JournaledPolicy<?> policy, Instant now, boolean deadlinePassed) {
        ActionKey key = policy.lookup().isPresent() ? keyOf(row) : null;
        if (key == null) {
            // Rows 10/11 without a usable lookup: at-most-once is never retried, others wait for a call or the deadline.
            if (policy instanceof AtMostOnce && policy.lookup().isEmpty() || deadlinePassed) {
                resolve(row, new Resolution.Escalate(policy.lookup().isPresent()
                        ? "the outcome could not be looked up (no key text stored) before the deadline"
                        : "the outcome is unknown and the tool has no outcome lookup"));
            }
            return;
        }
        boolean windowOpen = now.isAfter(row.startedAt().plus(row.maxEffectDuration()));
        if (!windowOpen) {
            return; // the attempt may still land; taken again after the hold
        }
        LookupResult result = timedLookup.lookUp(policy.lookup().orElseThrow(), key,
                key.idempotencyKey(row.materialArgsHash()), policy.lookupTimeout());
        if (result == null) {
            return; // interrupted; runOnce stops and the row is taken again after the hold
        }
        resolve(row, resolution(row, policy, result, deadlinePassed));
    }

    /** Writes a resolution and reports it if it landed. */
    private void resolve(ActionRecord row, Resolution resolution) {
        if (journal.resolve(row.keyHash(), row.attempt(), resolution)) {
            telemetry.reconcilerResolution(resultOf(resolution), row.toolName());
        }
    }

    private static ReconcilerResult resultOf(Resolution resolution) {
        if (resolution instanceof Resolution.Applied) {
            return ReconcilerResult.APPLIED;
        }
        if (resolution instanceof Resolution.NotApplied) {
            return ReconcilerResult.NOT_APPLIED;
        }
        if (resolution instanceof Resolution.LookupUnreachable) {
            return ReconcilerResult.LOOKUP_UNREACHABLE;
        }
        return resolution instanceof Resolution.Escalate ? ReconcilerResult.ESCALATED : ReconcilerResult.ABANDONED;
    }

    private static Resolution resolution(ActionRecord row, JournaledPolicy<?> policy, LookupResult result,
            boolean deadlinePassed) {
        if (result instanceof LookupResult.Found) {
            return new Resolution.Applied(ActionRunner.storable(((LookupResult.Found) result).resultText()));
        }
        if (result instanceof LookupResult.NotFound) {
            // Past the deadline the next batch abandons the FAILED(LOOKUP) row.
            return policy instanceof AtMostOnce
                    ? new Resolution.Escalate("the outcome lookup did not find the action; an at-most-once action is"
                            + " never retried")
                    : new Resolution.NotApplied();
        }
        String reason = ActionRunner.storable(((LookupResult.Unreachable) result).reason()); // stored on escalation
        int failures = row.lookupFailures() + 1;
        if (deadlinePassed) {
            return new Resolution.Escalate("the outcome lookup was unreachable at the deadline: " + reason);
        }
        if (failures >= ActionRunner.MAX_UNREACHABLE_LOOKUPS) {
            return new Resolution.Escalate("the outcome lookup was unreachable " + failures + " times: " + reason);
        }
        return new Resolution.LookupUnreachable(ActionRunner.lookupBackoff(row.lookupFailures()));
    }

    /** The key from the row's key text, or {@code null} if there is none or it does not match the row. */
    private static ActionKey keyOf(ActionRecord row) {
        if (row.keyText() == null) {
            return null;
        }
        try {
            ActionKey key = ActionKey.parse(row.keyText());
            if (!Arrays.equals(key.hash(), row.keyHash())) {
                LOG.log(System.Logger.Level.WARNING, "the key text of an action of tool {0} does not match its key"
                        + " hash; it is not looked up", row.toolName());
                return null;
            }
            return key;
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "unreadable key text of an action of tool " + row.toolName()
                    + "; it is not looked up", e);
            return null;
        }
    }
}
