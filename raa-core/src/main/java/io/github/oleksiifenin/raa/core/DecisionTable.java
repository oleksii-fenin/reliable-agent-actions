package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.ClaimDecider;
import io.github.oleksiifenin.raa.storage.ClaimDecision;
import io.github.oleksiifenin.raa.storage.ClaimRequest;
import io.github.oleksiifenin.raa.storage.ClaimVerdict;
import io.github.oleksiifenin.raa.storage.FailureBasis;
import io.github.oleksiifenin.raa.storage.JournalWrite;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The claim decision table of docs/DESIGN.md §6 for one journaled policy. Rows are evaluated top to bottom, first
 * match wins. A pure function of the request, the locked row and database time; storage applies the result in the
 * claim transaction.
 *
 * <p>Time rules: the deadline has passed when {@code now >= deadline_at}; a lease is live while
 * {@code now <= lease_until} (it expires when {@code lease_until < now}, as in {@code markUnknownIfLeaseExpired});
 * the lookup window is open when {@code now > started_at + max_effect_duration} of the row.
 *
 * <p>Lookups are never performed here. Row 8 returns {@link ClaimVerdict#NEEDS_LOOKUP}; the caller performs the
 * lookup, resolves the row and claims again — after an unreachable lookup with {@link #afterUnreachableLookup(int)}, which
 * continues as row 8 prescribes instead of asking for the lookup again.
 */
public final class DecisionTable implements ClaimDecider {

    private static final JournalWrite NO_WRITE = new JournalWrite.None();

    private static final int NO_UNREACHABLE_LOOKUP = 0;

    private final JournaledPolicy<?> policy;
    private final int unreachableLookupAttempt;

    /**
     * Creates the table for a policy.
     *
     * @param policy the tool's policy
     * @throws IllegalStateException if the policy is not valid (see {@link JournaledPolicy#validate()})
     */
    public DecisionTable(JournaledPolicy<?> policy) {
        this(policy, NO_UNREACHABLE_LOOKUP);
    }

    private DecisionTable(JournaledPolicy<?> policy, int unreachableLookupAttempt) {
        Objects.requireNonNull(policy, "policy").validate();
        this.policy = policy;
        this.unreachableLookupAttempt = unreachableLookupAttempt;
    }

    /**
     * Returns the table for the claim that follows an unreachable lookup (row 8, {@code Unreachable}). The
     * continuation applies only while the row is still at {@code attempt}; if another caller started a newer attempt
     * in between, the table behaves as if no lookup had been made.
     *
     * @param attempt the attempt whose outcome the lookup could not determine
     * @return a table that does not ask for that attempt's lookup again
     */
    public DecisionTable afterUnreachableLookup(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1: " + attempt);
        }
        return new DecisionTable(policy, attempt);
    }

    @Override
    public ClaimDecision decide(ClaimRequest request, Optional<ActionRecord> current, Instant databaseNow) {
        return evaluate(request, current, databaseNow).decision();
    }

    /** Evaluates the table and also reports which rows matched, in order (row 6 is followed by an UNKNOWN row). */
    Evaluation evaluate(ClaimRequest request, Optional<ActionRecord> current, Instant databaseNow) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(databaseNow, "databaseNow");
        if (!request.policy().equals(policy.journalName())) {
            throw new IllegalArgumentException("request for policy " + request.policy()
                    + " evaluated by the decision table of " + policy.journalName());
        }
        if (current.isEmpty()) {
            return Evaluation.of(1, ClaimVerdict.START, new JournalWrite.StartAttempt(1));
        }
        ActionRecord row = current.get();
        Facts facts = new Facts(request, row, databaseNow);
        switch (row.state()) {
            case SUCCEEDED:
                return facts.materialArgsDiffer
                        ? Evaluation.of(2, ClaimVerdict.CONFLICT, NO_WRITE)
                        : Evaluation.of(3, ClaimVerdict.REPLAY, NO_WRITE);
            case EXECUTING:
                return executing(facts);
            case UNKNOWN:
                return unknown(facts);
            case FAILED:
                return failed(facts);
            case ABANDONED:
                return Evaluation.of(16, ClaimVerdict.ABANDONED, NO_WRITE);
            case ESCALATED:
                return Evaluation.of(16, ClaimVerdict.ESCALATED, NO_WRITE);
            default:
                throw new IllegalStateException("unhandled state " + row.state());
        }
    }

    private Evaluation executing(Facts facts) {
        if (facts.materialArgsDiffer) {
            return Evaluation.of(4, ClaimVerdict.CONFLICT, NO_WRITE);
        }
        if (facts.leaseLive()) {
            return Evaluation.of(5, ClaimVerdict.IN_PROGRESS, NO_WRITE);
        }
        // Row 6: the lease expired, so the attempt's outcome is unknown. Re-evaluate as UNKNOWN; if that decision
        // writes nothing itself, the move to UNKNOWN is the write.
        Evaluation asUnknown = unknown(facts);
        JournalWrite write = asUnknown.decision().write() instanceof JournalWrite.None
                ? new JournalWrite.ChangeState(ActionState.UNKNOWN)
                : asUnknown.decision().write();
        ClaimDecision decision = new ClaimDecision(asUnknown.decision().verdict(), write);
        return withLeadingRow(6, new Evaluation(asUnknown.rows(), decision));
    }

    private Evaluation unknown(Facts facts) {
        boolean idempotentDownstream = policy instanceof IdempotentDownstream;
        if (facts.materialArgsDiffer || idempotentDownstream && facts.allArgsDiffer) {
            return Evaluation.of(7, ClaimVerdict.CONFLICT, NO_WRITE);
        }
        if (policy.lookup().isPresent()) {
            boolean retrySafe = idempotentDownstream || policy instanceof AtLeastOnce;
            if (facts.row.attempt() != unreachableLookupAttempt) {
                if (!facts.lookupWindowOpen()) {
                    return Evaluation.of(9, ClaimVerdict.ASK_LATER, NO_WRITE);
                }
                // Row 8 honors the backoff of an earlier unreachable lookup (OQ9), except when the continuation
                // would escalate: then one more lookup is the last chance to resolve the row without an operator.
                boolean continuationEscalates = facts.deadlinePassed() || retrySafe && facts.attemptsExhausted();
                if (!facts.inLookupBackoff() || continuationEscalates) {
                    return Evaluation.of(8, ClaimVerdict.NEEDS_LOOKUP, NO_WRITE);
                }
            }
            // Row 8, lookup unreachable or still backing off: a retry the policy already makes safe is not blocked
            // (continue at row 10); otherwise ask later until the deadline, then continue at row 10.
            if (!retrySafe && !facts.deadlinePassed()) {
                return Evaluation.of(8, ClaimVerdict.ASK_LATER, NO_WRITE);
            }
            return withLeadingRow(8, escalateOrRetry(facts));
        }
        return escalateOrRetry(facts);
    }

    /** Rows 10-12. */
    private Evaluation escalateOrRetry(Facts facts) {
        if (facts.deadlinePassed() || facts.attemptsExhausted()) {
            return Evaluation.of(10, ClaimVerdict.ESCALATED, new JournalWrite.ChangeState(ActionState.ESCALATED));
        }
        if (policy instanceof AtMostOnce) {
            return Evaluation.of(11, ClaimVerdict.ESCALATED, new JournalWrite.ChangeState(ActionState.ESCALATED));
        }
        if (policy instanceof IdempotentDownstream || policy instanceof AtLeastOnce) {
            return Evaluation.of(12, ClaimVerdict.START, new JournalWrite.StartAttempt(facts.row.attempt() + 1));
        }
        // RECONCILABLE always has a lookup, so rows 8-10 decided before.
        throw new IllegalStateException("no decision-table row matches UNKNOWN for " + policy);
    }

    private static Evaluation withLeadingRow(int row, Evaluation evaluation) {
        List<Integer> rows = new ArrayList<>();
        rows.add(row);
        rows.addAll(evaluation.rows());
        return new Evaluation(rows, evaluation.decision());
    }

    private Evaluation failed(Facts facts) {
        if (facts.deadlinePassed() || facts.attemptsExhausted()) {
            return Evaluation.of(13, ClaimVerdict.ABANDONED, new JournalWrite.ChangeState(ActionState.ABANDONED));
        }
        if (facts.row.failureBasis() == FailureBasis.LOOKUP && facts.materialArgsDiffer) {
            return Evaluation.of(14, ClaimVerdict.CONFLICT, NO_WRITE);
        }
        return Evaluation.of(15, ClaimVerdict.START, new JournalWrite.StartAttempt(facts.row.attempt() + 1));
    }

    /** The comparisons the rows are made of, computed once per evaluation. */
    private final class Facts {
        final ActionRecord row;
        final Instant now;
        final boolean materialArgsDiffer;
        final boolean allArgsDiffer;

        Facts(ClaimRequest request, ActionRecord row, Instant now) {
            this.row = row;
            this.now = now;
            this.materialArgsDiffer = !Arrays.equals(row.materialArgsHash(), request.materialArgsHash());
            this.allArgsDiffer = !Arrays.equals(row.allArgsHash(), request.allArgsHash());
        }

        boolean leaseLive() {
            return row.leaseUntil() != null && !row.leaseUntil().isBefore(now);
        }

        boolean deadlinePassed() {
            return !now.isBefore(row.deadlineAt());
        }

        boolean attemptsExhausted() {
            return row.attempt() >= policy.maxAttempts();
        }

        boolean lookupWindowOpen() {
            return now.isAfter(row.startedAt().plus(row.maxEffectDuration()));
        }

        /**
         * Whether the backoff after an unreachable lookup is still running. A future {@code resolve_after} without
         * a lookup failure is a reconciler's hold on the row, not a backoff: callers may look up meanwhile (a
         * lookup only reads; both resolutions are compare-and-set).
         */
        boolean inLookupBackoff() {
            return row.lookupFailures() > 0 && row.resolveAfter() != null && row.resolveAfter().isAfter(now);
        }
    }

    /**
     * A decision plus the table rows that produced it.
     *
     * @param rows     matched rows in evaluation order
     * @param decision the decision
     */
    record Evaluation(List<Integer> rows, ClaimDecision decision) {
        Evaluation {
            rows = List.copyOf(rows);
        }

        static Evaluation of(int row, ClaimVerdict verdict, JournalWrite write) {
            return new Evaluation(List.of(row), new ClaimDecision(verdict, write));
        }
    }
}
