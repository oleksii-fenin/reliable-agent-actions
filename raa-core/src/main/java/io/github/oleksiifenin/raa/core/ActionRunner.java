package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.core.ArgumentsHasher.ArgumentHashes;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.CallRecord;
import io.github.oleksiifenin.raa.storage.ClaimRequest;
import io.github.oleksiifenin.raa.storage.ClaimResult;
import io.github.oleksiifenin.raa.storage.FailureKind;
import io.github.oleksiifenin.raa.storage.JournalException;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import io.github.oleksiifenin.raa.storage.RecordOutcome;
import io.github.oleksiifenin.raa.storage.RecordResult;
import io.github.oleksiifenin.raa.storage.Resolution;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs tool calls through the journal (docs/DESIGN.md §6): claims the action, runs the effect only when the decision
 * table starts an attempt, records the outcome and tells the caller what happened. Thread-safe; obtained from
 * {@link ReliableActions#runner()}.
 *
 * <p>No transaction is open while the effect or an outcome lookup runs. A journal failure before the effect returns
 * {@link ActionOutcome.JournalUnavailable} without running it; a journal failure after the effect never hides the
 * effect's result from the caller.
 */
public final class ActionRunner {

    private static final System.Logger LOG = System.getLogger(ActionRunner.class.getName());

    /** Claims that do not start, wait or finish (lookup resolutions) before the runner gives up. */
    private static final int MAX_SETTLE_ROUNDS = 16;
    /** Consecutive unreachable lookups after which the outcome is escalated (docs/DESIGN.md §6, reconciler). */
    static final int MAX_UNREACHABLE_LOOKUPS = 8;
    private static final Duration LOOKUP_BACKOFF_BASE = Duration.ofSeconds(15);
    private static final Duration LOOKUP_BACKOFF_MAX = Duration.ofMinutes(10);
    private static final long FIRST_PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
    private static final long MAX_PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(400);

    private final ActionJournal journal;
    private final Map<String, ActionPolicy> policies;
    private final Map<String, DecisionTable> tables = new HashMap<>();
    private final String owner;
    private final Duration lease;
    private final Duration inProgressWait;
    private final Duration recordRetryBudget;
    private final LeaseRenewer leaseRenewer;
    private final boolean storeKeyText;
    private final ArgumentsHasher hasher = new ArgumentsHasher();
    private final TimedLookup timedLookup = new TimedLookup();

    ActionRunner(ActionJournal journal, Map<String, ActionPolicy> policies, String owner, Duration lease,
            Duration inProgressWait, Duration recordRetryBudget, LeaseRenewer leaseRenewer, boolean storeKeyText) {
        this.journal = journal;
        this.storeKeyText = storeKeyText;
        this.policies = policies;
        this.owner = owner;
        this.lease = lease;
        this.inProgressWait = inProgressWait;
        this.recordRetryBudget = recordRetryBudget;
        this.leaseRenewer = leaseRenewer;
        policies.forEach((toolName, policy) -> {
            if (policy instanceof JournaledPolicy<?>) {
                tables.put(toolName, new DecisionTable((JournaledPolicy<?>) policy));
            }
        });
    }

    /**
     * Runs one tool call.
     *
     * @param call   the call as the model emitted it
     * @param effect the tool's side effect; runs at most once per attempt this call starts
     * @return what happened; never {@code null}
     * @throws IllegalArgumentException if the tool has no policy, or is declared {@link ActionPolicy#none()} (a
     *                                  read-only tool is called directly, not through the runner)
     */
    public ActionOutcome run(ToolCall call, Effect effect) {
        Objects.requireNonNull(effect, "effect");
        Prepared prepared = prepare(call, call.providerCallId());
        return prepared.failure != null ? prepared.failure : new Execution(prepared, effect).run();
    }

    /**
     * Answers a tool call without performing anything (docs/DESIGN.md §6, "Inspect mode"), for memory repair: what a
     * repeated call would be told, except that where it would start an attempt, it says whether repeating is safe. Only
     * transitions that need no effect are applied: an expired attempt becomes {@code UNKNOWN}, a due outcome lookup
     * resolves the row, an unresolvable outcome is escalated. The call is found through the call history by its
     * provider tool-call id, so an id superseded by a regenerated call still gets the action's answer.
     *
     * @param call the call as the model emitted it; its provider tool-call id is required
     * @return what happened, {@link ActionOutcome.InProgressElsewhere} for an attempt that still holds a live lease
     *         (nothing can be said yet), {@link ActionOutcome.Failed} with {@link #NOT_STARTED} if this call id never
     *         claimed the action, or {@link ActionOutcome.JournalUnavailable} if the journal could not be read — here it
     *         means "cannot tell", not "not performed"
     * @throws IllegalArgumentException if the tool has no journaled policy or the call has no provider tool-call id
     * @throws IllegalStateException    if the call history maps the call to another action than its arguments give now
     *                                  (the key strategy changed); answering it would describe the wrong action
     */
    public ActionOutcome inspect(ToolCall call) {
        Objects.requireNonNull(call, "call");
        if (call.providerCallId() == null) {
            throw new IllegalArgumentException("inspect needs the provider tool-call id of the call");
        }
        Prepared prepared = prepare(call, null); // the claim must not touch the call history
        if (prepared.failure != null) {
            return prepared.failure;
        }
        try {
            Optional<CallRecord> mapped = journal.findByProviderCallId(prepared.key.scopeHash(), call.providerCallId());
            if (mapped.isEmpty()) {
                return new ActionOutcome.Failed(NOT_STARTED, true);
            }
            if (!Arrays.equals(mapped.get().action().keyHash(), prepared.key.hash())) {
                throw new IllegalStateException("call " + call.providerCallId() + " of tool " + call.toolName()
                        + " claimed another action than its arguments give now (has the key strategy changed?)");
            }
            return new Execution(prepared, null).run();
        } catch (JournalException e) {
            return new ActionOutcome.JournalUnavailable(message(e));
        } catch (InspectDecider.NoActionRowException e) {
            return new ActionOutcome.Failed(NOT_STARTED, true);
        }
    }

    /** The answer of inspect mode for a call that started nothing. */
    public static final String NOT_STARTED = "you may call again with the same arguments";

    /** Policy, key, hashes and claim request of one call, or why the call fails before anything is claimed. */
    private Prepared prepare(ToolCall call, String providerCallId) {
        Objects.requireNonNull(call, "call");
        ActionPolicy policy = policies.get(call.toolName());
        if (policy == null) {
            throw new IllegalArgumentException("no policy is declared for tool " + call.toolName());
        }
        if (!(policy instanceof JournaledPolicy<?>)) {
            throw new IllegalArgumentException("tool " + call.toolName() + " is declared none(): it is not journaled,"
                    + " call it directly");
        }
        JournaledPolicy<?> journaled = (JournaledPolicy<?>) policy;
        ActionKey key;
        ArgumentHashes hashes;
        try {
            key = journaled.keyStrategy().orElseThrow().keyFor(call, new TreeArguments(call.arguments()));
            Objects.requireNonNull(key, "the key strategy returned no key");
            hashes = hasher.hash(call.arguments(), journaled.freeTextArguments());
        } catch (IllegalArgumentException e) {
            // Nothing was claimed or performed; the model may correct the arguments.
            return Prepared.failed(new ActionOutcome.Failed("invalid arguments: " + message(e), true));
        } catch (NullPointerException e) {
            // A null key component, typically a call without a scope. Not performed either; logged with its stack
            // trace, because it can also be a bug in the key strategy.
            LOG.log(System.Logger.Level.WARNING, "the key strategy of tool " + call.toolName() + " failed with a null"
                    + " value; the call is not performed", e);
            return Prepared.failed(new ActionOutcome.Failed("invalid arguments: " + message(e), true));
        }
        ClaimRequest request = new ClaimRequest(key.hash(), key.scopeHash(), keyText(key), call.toolName(),
                journaled.journalName(), hashes.material(), hashes.all(), null, providerCallId, owner, lease,
                journaled.maxEffectDuration().orElseThrow(), journaled.deadline());
        return new Prepared(journaled, tables.get(call.toolName()), key, request, null);
    }

    /** The prepared parts of a call; {@code failure} set if it ends before the claim. */
    private static final class Prepared {
        final JournaledPolicy<?> policy;
        final DecisionTable table;
        final ActionKey key;
        final ClaimRequest request;
        final ActionOutcome failure;

        Prepared(JournaledPolicy<?> policy, DecisionTable table, ActionKey key, ClaimRequest request,
                ActionOutcome failure) {
            this.policy = policy;
            this.table = table;
            this.key = key;
            this.request = request;
            this.failure = failure;
        }

        static Prepared failed(ActionOutcome failure) {
            return new Prepared(null, null, null, null, failure);
        }
    }

    /** The key text to store (ADR-0004), or {@code null} if disabled or not storable. */
    private String keyText(ActionKey key) {
        if (!storeKeyText) {
            return null;
        }
        String text = key.toText();
        if (text.indexOf('\u0000') >= 0) {
            LOG.log(System.Logger.Level.WARNING, "the key of an action of tool {0} contains U+0000, which the journal"
                    + " cannot store; it is stored without key text, so the reconciler cannot look it up",
                    key.name());
            return null;
        }
        return text;
    }

    /** The state of one {@link #run} call. */
    private final class Execution {

        private final JournaledPolicy<?> policy;
        private final ActionKey key;
        private final ClaimRequest request;
        private final Effect effect;
        /** Inspect mode: no effect, no attempt started, no waiting for a live lease. */
        private final boolean inspect;
        private DecisionTable table;
        private boolean resolvedByOwnLookup;
        private boolean waiting;
        private long waitEndNanos;
        private long pollPauseNanos;

        /** {@code effect == null} means inspect mode. */
        Execution(Prepared prepared, Effect effect) {
            this.policy = prepared.policy;
            this.table = prepared.table;
            this.key = prepared.key;
            this.request = prepared.request;
            this.effect = effect;
            this.inspect = effect == null;
        }

        ActionOutcome run() {
            int settleRounds = 0;
            while (true) {
                ClaimResult claim;
                try {
                    claim = journal.claim(request, inspect ? new InspectDecider(table) : table);
                } catch (JournalException e) {
                    return new ActionOutcome.JournalUnavailable(message(e));
                }
                ActionRecord row = claim.record();
                switch (claim.decision().verdict()) {
                    case START:
                        return execute(row.attempt());
                    case WOULD_START:
                        return wouldStart(row);
                    case REPLAY:
                        return new ActionOutcome.Replayed(textOf(row), row.updatedAt(), resolvedByOwnLookup);
                    case CONFLICT:
                        return row.state() == ActionState.SUCCEEDED
                                ? new ActionOutcome.Conflict(textOf(row), false)
                                : new ActionOutcome.Conflict(null, true);
                    case IN_PROGRESS:
                        if (inspect || !pauseWhileInProgress()) {
                            return new ActionOutcome.InProgressElsewhere(row.leaseUntil());
                        }
                        continue;
                    case NEEDS_LOOKUP:
                        ActionOutcome failed = lookUpAndResolve(row);
                        if (failed != null) {
                            return failed;
                        }
                        break;
                    case ASK_LATER:
                        return new ActionOutcome.Unknown(RepeatAdvice.ASK_LATER,
                                "the outcome is being verified; call again after " + verifiableAfter(row));
                    case ESCALATED:
                        return new ActionOutcome.Escalated(withLastError(
                                "the outcome could not be verified automatically; an operator must resolve it", row));
                    case ABANDONED:
                        return new ActionOutcome.Abandoned(withLastError("no attempts are left", row));
                    default:
                        throw new IllegalStateException("unhandled verdict " + claim.decision().verdict());
                }
                settleRounds++;
                if (settleRounds >= MAX_SETTLE_ROUNDS) {
                    // Only reachable under heavy contention on one key; the row stays as the journal has it.
                    return new ActionOutcome.Unknown(RepeatAdvice.ASK_LATER, "the outcome is being verified by"
                            + " concurrent calls; call again later");
                }
            }
        }

        /** Inspect mode, rows 1/12/15: what a repeated call would do, and whether repeating is safe. */
        private ActionOutcome wouldStart(ActionRecord row) {
            if (row.state() == ActionState.FAILED) {
                return new ActionOutcome.Failed(NOT_STARTED, true); // row 15: the earlier attempt was not applied
            }
            String reason = withLastError("the outcome of the earlier attempt is unknown", row);
            return policy instanceof IdempotentDownstream
                    ? new ActionOutcome.Unknown(RepeatAdvice.SAFE, reason)
                    : new ActionOutcome.Unknown(RepeatAdvice.MAY_DUPLICATE, reason);
        }

        /** Runs the effect of a started attempt and records its outcome. */
        private ActionOutcome execute(int attempt) {
            KeyAttempt inFlight = new KeyAttempt(request.keyHash(), attempt);
            leaseRenewer.register(inFlight);
            leaseRenewer.warnIfNotStarted();
            try {
                ActionContext context =
                        new ActionContext(key, key.idempotencyKey(request.materialArgsHash()), attempt);
                String resultText;
                try {
                    resultText = context.runOnCurrentThread(effect);
                } catch (Exception e) {
                    boolean effectStarted = !policy.isSideEffectMarkerUsed() || context.sideEffectStarted();
                    ActionOutcome outcome = recordFailure(attempt, e, effectStarted);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return outcome;
                }
                return recordSuccess(attempt, resultText == null ? "" : resultText);
            } finally {
                leaseRenewer.unregister(inFlight);
            }
        }

        private ActionOutcome recordSuccess(int attempt, String resultText) {
            String recordedText = storable(resultText);
            RecordResult result = withRecordRetries(
                    () -> journal.recordSuccess(request.keyHash(), attempt, recordedText), attempt);
            if (result != null) {
                if (result.outcome() == RecordOutcome.SUPERSEDED) {
                    LOG.log(System.Logger.Level.INFO, "stale success: attempt {0} of tool {1} finished after a newer"
                            + " attempt started", attempt, request.toolName());
                }
                ActionState before = result.stateBefore().orElse(null);
                if (result.outcome() == RecordOutcome.APPLIED
                        && (before == ActionState.FAILED || before == ActionState.ABANDONED)) {
                    LOG.log(System.Logger.Level.WARNING, "late result corrected: attempt {0} of tool {1} succeeded"
                            + " after its row was {2}", attempt, request.toolName(), before);
                }
            }
            // This call performed the effect: whatever the journal says, the caller gets the real result.
            return new ActionOutcome.Succeeded(resultText, attempt);
        }

        private ActionOutcome recordFailure(int attempt, Exception error, boolean effectStarted) {
            FailureKind kind = classify(error, effectStarted);
            String errorText = message(error);
            String recordedError = storable(errorText);
            RecordResult result = withRecordRetries(
                    () -> journal.recordFailure(request.keyHash(), attempt, kind, recordedError), attempt);
            if (result != null && result.outcome() == RecordOutcome.ALREADY_SUCCEEDED) {
                ActionRecord row = result.record().orElseThrow();
                return new ActionOutcome.Replayed(textOf(row), row.updatedAt(), false);
            }
            if (kind == FailureKind.DEFINITE) {
                return new ActionOutcome.Failed(errorText, attempt < policy.maxAttempts());
            }
            return unknownOutcome(attempt, errorText, result);
        }

        private FailureKind classify(Exception error, boolean effectStarted) {
            // A classifier that fails or answers nothing leaves the outcome unknown, never "not applied".
            try {
                FailureKind kind = policy.failureClassifier().classify(error, effectStarted);
                return kind == null ? FailureKind.INDETERMINATE : kind;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "failure classifier of tool " + request.toolName() + " failed", e);
                return FailureKind.INDETERMINATE;
            }
        }

        private ActionOutcome unknownOutcome(int attempt, String errorText, RecordResult result) {
            String reason = "the outcome is unknown: " + errorText;
            if (policy instanceof IdempotentDownstream) {
                return new ActionOutcome.Unknown(RepeatAdvice.SAFE, reason);
            }
            if (policy instanceof AtLeastOnce) {
                return new ActionOutcome.Unknown(RepeatAdvice.MAY_DUPLICATE, reason);
            }
            if (policy instanceof AtMostOnce && policy.lookup().isEmpty()) {
                // Rows 10/11: nothing can resolve an unknown at-most-once outcome but an operator.
                if (result != null && result.outcome() == RecordOutcome.APPLIED) {
                    try {
                        journal.resolve(request.keyHash(), attempt, new Resolution.Escalate(storable(reason)));
                    } catch (JournalException e) {
                        // The next claim or the reconciler escalates the UNKNOWN row instead.
                        LOG.log(System.Logger.Level.WARNING, "could not escalate an unknown outcome", e);
                    }
                }
                return new ActionOutcome.Escalated(reason + "; an at-most-once action is never retried, an operator"
                        + " must resolve it");
            }
            return new ActionOutcome.Unknown(RepeatAdvice.ASK_LATER, reason + "; it will be verified");
        }

        /**
         * Writes an attempt's outcome, retrying journal failures within the record budget. A retry is also the
         * read-back of an ambiguous write: if the first write landed, the retry reports it (for example
         * {@code ALREADY_SUCCEEDED}). The thread's interrupt flag is cleared for the writes and restored afterwards.
         *
         * @return the result, or {@code null} if the budget ran out; the row then resolves through its lease
         */
        private RecordResult withRecordRetries(Supplier<RecordResult> write, int attempt) {
            boolean interrupted = Thread.interrupted();
            try {
                long endNanos = System.nanoTime() + recordRetryBudget.toNanos();
                long pauseNanos = FIRST_PAUSE_NANOS;
                while (true) {
                    try {
                        return write.get();
                    } catch (JournalException e) {
                        long remainingNanos = endNanos - System.nanoTime();
                        if (remainingNanos <= 0) {
                            LOG.log(System.Logger.Level.WARNING, "could not record the outcome of attempt " + attempt
                                    + " of tool " + request.toolName() + " within " + recordRetryBudget
                                    + "; it resolves when the lease expires", e);
                            return null;
                        }
                        if (!pause(Math.min(pauseNanos, remainingNanos))) {
                            interrupted = true;
                        }
                        pauseNanos = Math.min(pauseNanos * 2, MAX_PAUSE_NANOS);
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /** Row 5: waits a little before claiming again; {@code false} once the in-progress wait is used up. */
        private boolean pauseWhileInProgress() {
            long now = System.nanoTime();
            if (!waiting) {
                waiting = true;
                waitEndNanos = now + inProgressWait.toNanos();
                pollPauseNanos = FIRST_PAUSE_NANOS;
            }
            long remainingNanos = waitEndNanos - now;
            if (remainingNanos <= 0) {
                return false;
            }
            if (!pause(Math.min(pollPauseNanos, remainingNanos))) {
                Thread.currentThread().interrupt();
                return false;
            }
            pollPauseNanos = Math.min(pollPauseNanos * 2, MAX_PAUSE_NANOS);
            return true;
        }

        /**
         * Row 8: asks the downstream outside any transaction and resolves the row; the caller claims again.
         *
         * @return an outcome if the journal failed or the thread was interrupted, otherwise {@code null}
         */
        private ActionOutcome lookUpAndResolve(ActionRecord row) {
            OutcomeLookup lookup = policy.lookup().orElseThrow(
                    () -> new IllegalStateException("NEEDS_LOOKUP for a policy without a lookup: " + policy));
            if (Thread.currentThread().isInterrupted()) {
                return interruptedBeforeVerification();
            }
            LookupResult result = lookUp(lookup, key.idempotencyKey(row.materialArgsHash()));
            if (result == null) {
                // Interrupted while waiting: the downstream did not fail, so nothing is written and nothing starts.
                return interruptedBeforeVerification();
            }
            Resolution resolution;
            if (result instanceof LookupResult.Found) {
                resolution = new Resolution.Applied(storable(((LookupResult.Found) result).resultText()));
                resolvedByOwnLookup = true;
            } else if (result instanceof LookupResult.NotFound) {
                resolution = policy instanceof AtMostOnce
                        ? new Resolution.Escalate("the outcome lookup did not find the action; an at-most-once"
                                + " action is never retried")
                        : new Resolution.NotApplied();
            } else {
                String reason = storable(((LookupResult.Unreachable) result).reason()); // may be stored
                int failures = row.lookupFailures() + 1;
                resolution = failures >= MAX_UNREACHABLE_LOOKUPS
                        ? new Resolution.Escalate("the outcome lookup was unreachable " + failures + " times: " + reason)
                        : new Resolution.LookupUnreachable(lookupBackoff(row.lookupFailures()));
                table = table.afterUnreachableLookup(row.attempt());
            }
            try {
                // A lost compare-and-set means someone else resolved or restarted the row; the next claim sees it.
                journal.resolve(row.keyHash(), row.attempt(), resolution);
            } catch (JournalException e) {
                // Not "not performed": the lookup may have found the effect. The row stays as it was and is resolved by
                // the next call or the reconciler.
                return new ActionOutcome.Unknown(RepeatAdvice.ASK_LATER, "the outcome lookup could not be recorded ("
                        + message(e) + "); call again later");
            }
            return null;
        }

        /** Asks the downstream with the policy's timeout; {@code null} if this thread was interrupted meanwhile. */
        private LookupResult lookUp(OutcomeLookup lookup, String idempotencyKey) {
            return timedLookup.lookUp(lookup, key, idempotencyKey, policy.lookupTimeout());
        }

        private ActionOutcome interruptedBeforeVerification() {
            return new ActionOutcome.Unknown(RepeatAdvice.ASK_LATER,
                    "interrupted before the outcome could be verified; call again later");
        }

        private String verifiableAfter(ActionRecord row) {
            Instant windowOpens = row.startedAt().plus(row.maxEffectDuration());
            Instant after = row.resolveAfter() != null && row.resolveAfter().isAfter(windowOpens)
                    ? row.resolveAfter()
                    : windowOpens;
            // At the deadline row 8 looks up regardless of a backoff, so no caller needs to wait longer.
            return (after.isAfter(row.deadlineAt()) ? row.deadlineAt() : after).toString();
        }
    }

    /** {@code min(2^n × 15 s, 10 min)} for the n-th consecutive unreachable lookup, counting from 0. */
    static Duration lookupBackoff(int previousFailures) {
        if (previousFailures >= 6) { // 2^6 × 15 s = 16 min, already above the cap
            return LOOKUP_BACKOFF_MAX;
        }
        Duration backoff = LOOKUP_BACKOFF_BASE.multipliedBy(1L << previousFailures);
        return backoff.compareTo(LOOKUP_BACKOFF_MAX) > 0 ? LOOKUP_BACKOFF_MAX : backoff;
    }

    /** Sleeps; {@code false} if interrupted (the flag is then clear). */
    private static boolean pause(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * Returns text a journal can store: NUL characters, which PostgreSQL text rejects on every try, are replaced by
     * U+FFFD. Otherwise a retried write could never land and the row would resolve through its lease.
     */
    static String storable(String text) {
        return text.indexOf('\u0000') < 0 ? text : text.replace('\u0000', '\uFFFD');
    }

    private static String textOf(ActionRecord row) {
        return Objects.requireNonNullElse(row.resultText(), "");
    }

    private static String withLastError(String reason, ActionRecord row) {
        return row.lastError() == null ? reason : reason + "; last error: " + row.lastError();
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null ? error.getClass().getName() : error.getMessage();
    }
}
