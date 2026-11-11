package io.github.oleksiifenin.raa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.ClaimDecision;
import io.github.oleksiifenin.raa.storage.ClaimRequest;
import io.github.oleksiifenin.raa.storage.ClaimVerdict;
import io.github.oleksiifenin.raa.storage.FailureBasis;
import io.github.oleksiifenin.raa.storage.JournalWrite;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** One test group per row of the decision table in docs/DESIGN.md §6, each run for the policies it applies to. */
class DecisionTableTest {

    static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    static final Duration MAX_EFFECT = Duration.ofSeconds(3);
    static final byte[] MATERIAL = filled(1);
    static final byte[] OTHER_MATERIAL = filled(2);
    static final byte[] ALL_ARGS = filled(3);
    static final byte[] OTHER_ALL_ARGS = filled(4);

    static final JournalWrite NONE = new JournalWrite.None();
    static final JournalWrite TO_UNKNOWN = new JournalWrite.ChangeState(ActionState.UNKNOWN);
    static final JournalWrite TO_ESCALATED = new JournalWrite.ChangeState(ActionState.ESCALATED);
    static final JournalWrite TO_ABANDONED = new JournalWrite.ChangeState(ActionState.ABANDONED);

    static final OutcomeLookup LOOKUP = (key, idempotencyKey) -> new LookupResult.NotFound();

    /** The journaled policies, with and without a declared lookup (Reconcilable always has one). */
    enum Policy {
        IDEMPOTENT_DOWNSTREAM(false),
        IDEMPOTENT_DOWNSTREAM_WITH_LOOKUP(true),
        RECONCILABLE(true),
        AT_MOST_ONCE(false),
        AT_MOST_ONCE_WITH_LOOKUP(true),
        AT_LEAST_ONCE(false),
        AT_LEAST_ONCE_WITH_LOOKUP(true);

        final boolean hasLookup;

        Policy(boolean hasLookup) {
            this.hasLookup = hasLookup;
        }

        JournaledPolicy<?> create() {
            switch (this) {
                case IDEMPOTENT_DOWNSTREAM:
                    return configure(ActionPolicy.idempotentDownstream(Duration.ofHours(24)));
                case IDEMPOTENT_DOWNSTREAM_WITH_LOOKUP:
                    return configure(ActionPolicy.idempotentDownstream(Duration.ofHours(24))).lookup(LOOKUP);
                case RECONCILABLE:
                    return configure(ActionPolicy.reconcilable(LOOKUP));
                case AT_MOST_ONCE:
                    return configure(ActionPolicy.atMostOnce());
                case AT_MOST_ONCE_WITH_LOOKUP:
                    return configure(ActionPolicy.atMostOnce()).lookup(LOOKUP);
                case AT_LEAST_ONCE:
                    return configure(ActionPolicy.atLeastOnce());
                case AT_LEAST_ONCE_WITH_LOOKUP:
                    return configure(ActionPolicy.atLeastOnce()).lookup(LOOKUP);
                default:
                    throw new IllegalStateException(name());
            }
        }

        boolean retriesUnknown() {
            return this == IDEMPOTENT_DOWNSTREAM || this == IDEMPOTENT_DOWNSTREAM_WITH_LOOKUP
                    || this == AT_LEAST_ONCE || this == AT_LEAST_ONCE_WITH_LOOKUP;
        }

        boolean idempotentDownstream() {
            return this == IDEMPOTENT_DOWNSTREAM || this == IDEMPOTENT_DOWNSTREAM_WITH_LOOKUP;
        }

        private static <P extends JournaledPolicy<P>> P configure(P policy) {
            return policy.key((call, args) -> ActionKey.of("conversation-1", "refund", "order-42"))
                    .maxEffectDuration(MAX_EFFECT)
                    .deadline(Duration.ofSeconds(60))
                    .maxAttempts(3);
        }
    }

    static Stream<Policy> withLookup() {
        return Arrays.stream(Policy.values()).filter(p -> p.hasLookup);
    }

    static Stream<Policy> withoutLookup() {
        return Arrays.stream(Policy.values()).filter(p -> !p.hasLookup);
    }

    // ---- row 1

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row1_noRow_startsAttemptOne(Policy policy) {
        Result result = decide(policy, null, request(policy, MATERIAL, ALL_ARGS));

        result.is(List.of(1), ClaimVerdict.START, new JournalWrite.StartAttempt(1));
    }

    // ---- rows 2-3

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row2_succeededWithDifferentMaterialArgs_conflicts(Policy policy) {
        Result result = decide(policy, row(ActionState.SUCCEEDED), request(OTHER_MATERIAL, ALL_ARGS));

        result.is(List.of(2), ClaimVerdict.CONFLICT, NONE);
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row3_succeededWithSameMaterialArgs_replays(Policy policy) {
        Result result = decide(policy, row(ActionState.SUCCEEDED), request(MATERIAL, OTHER_ALL_ARGS));

        result.is(List.of(3), ClaimVerdict.REPLAY, NONE);
    }

    // ---- rows 4-6

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row4_executingWithDifferentMaterialArgs_conflicts_evenWithExpiredLease(Policy policy) {
        Result live = decide(policy, executing(NOW.plusSeconds(1)), request(OTHER_MATERIAL, ALL_ARGS));
        Result expired = decide(policy, executing(NOW.minusSeconds(1)), request(OTHER_MATERIAL, ALL_ARGS));

        live.is(List.of(4), ClaimVerdict.CONFLICT, NONE);
        expired.is(List.of(4), ClaimVerdict.CONFLICT, NONE);
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row5_executingWithLiveLease_isInProgress_untilAndIncludingLeaseEnd(Policy policy) {
        Result before = decide(policy, executing(NOW.plusSeconds(1)), request(MATERIAL, ALL_ARGS));
        Result atLeaseEnd = decide(policy, executing(NOW), request(MATERIAL, ALL_ARGS));

        before.is(List.of(5), ClaimVerdict.IN_PROGRESS, NONE);
        atLeaseEnd.is(List.of(5), ClaimVerdict.IN_PROGRESS, NONE);
    }

    @ParameterizedTest
    @MethodSource("withoutLookup")
    void row6_executingWithExpiredLease_isReevaluatedAsUnknown_withoutLookup(Policy policy) {
        Result result = decide(policy, executing(NOW.minusMillis(1)), request(MATERIAL, ALL_ARGS));

        if (policy.retriesUnknown()) {
            result.is(List.of(6, 12), ClaimVerdict.START, new JournalWrite.StartAttempt(2));
        } else {
            result.is(List.of(6, 11), ClaimVerdict.ESCALATED, TO_ESCALATED);
        }
    }

    @ParameterizedTest
    @MethodSource("withLookup")
    void row6_executingWithExpiredLease_persistsUnknown_whenTheUnknownRowWritesNothing(Policy policy) {
        ActionRecord windowOpen = executing(NOW.minusMillis(1));
        ActionRecord windowClosed = Row.of(ActionState.EXECUTING).leaseUntil(NOW.minusMillis(1))
                .startedAt(NOW.minusSeconds(1)).build();

        decide(policy, windowOpen, request(MATERIAL, ALL_ARGS))
                .is(List.of(6, 8), ClaimVerdict.NEEDS_LOOKUP, TO_UNKNOWN);
        decide(policy, windowClosed, request(MATERIAL, ALL_ARGS))
                .is(List.of(6, 9), ClaimVerdict.ASK_LATER, TO_UNKNOWN);
    }

    @Test
    void row6_executingWithExpiredLease_andOnlyFreeTextChanged_persistsUnknownAndConflictsForIdempotentDownstream() {
        Result result = decide(Policy.IDEMPOTENT_DOWNSTREAM, executing(NOW.minusMillis(1)),
                request(MATERIAL, OTHER_ALL_ARGS));

        result.is(List.of(6, 7), ClaimVerdict.CONFLICT, TO_UNKNOWN);
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row6_executingWithoutLeaseEnd_isTreatedAsExpired(Policy policy) {
        Result result = decide(policy, executing(null), request(MATERIAL, ALL_ARGS));

        assertThat(result.rows.get(0)).isEqualTo(6);
    }

    // ---- row 7

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row7_unknownWithDifferentMaterialArgs_conflicts_beforeAnyLookup(Policy policy) {
        Result result = decide(policy, row(ActionState.UNKNOWN), request(OTHER_MATERIAL, OTHER_ALL_ARGS));

        result.is(List.of(7), ClaimVerdict.CONFLICT, NONE);
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row7_unknownWithOnlyFreeTextChanged_conflictsOnlyForIdempotentDownstream(Policy policy) {
        Result result = decide(policy, row(ActionState.UNKNOWN), request(MATERIAL, OTHER_ALL_ARGS));

        if (policy.idempotentDownstream()) {
            result.is(List.of(7), ClaimVerdict.CONFLICT, NONE);
        } else {
            assertThat(result.rows).doesNotContain(7);
        }
    }

    // ---- rows 8-9

    @ParameterizedTest
    @MethodSource("withLookup")
    void row8_unknownWithLookupAfterMaxEffectDuration_needsLookup(Policy policy) {
        Result result = decide(policy, row(ActionState.UNKNOWN), request(MATERIAL, ALL_ARGS));

        result.is(List.of(8), ClaimVerdict.NEEDS_LOOKUP, NONE);
    }

    @ParameterizedTest
    @MethodSource("withLookup")
    void row8_isConsultedBeforeRow10_evenWithAttemptsExhaustedOrDeadlinePassed(Policy policy) {
        ActionRecord exhausted = Row.of(ActionState.UNKNOWN).attempt(3).build();
        ActionRecord pastDeadline = Row.of(ActionState.UNKNOWN).deadlineAt(NOW).build();

        decide(policy, exhausted, request(MATERIAL, ALL_ARGS)).is(List.of(8), ClaimVerdict.NEEDS_LOOKUP, NONE);
        decide(policy, pastDeadline, request(MATERIAL, ALL_ARGS)).is(List.of(8), ClaimVerdict.NEEDS_LOOKUP, NONE);
    }

    @ParameterizedTest
    @MethodSource("withLookup")
    void row8_unreachableLookup_retriesWhenThePolicyMakesItSafe_elseAsksLaterBeforeTheDeadline(Policy policy) {
        Result result = decideAfterUnreachable(policy, row(ActionState.UNKNOWN), request(MATERIAL, ALL_ARGS));

        if (policy.retriesUnknown()) {
            result.is(List.of(8, 12), ClaimVerdict.START, new JournalWrite.StartAttempt(2));
        } else {
            result.is(List.of(8), ClaimVerdict.ASK_LATER, NONE);
        }
    }

    @ParameterizedTest
    @MethodSource("withLookup")
    void row8_unreachableLookup_atTheDeadline_continuesAtRow10(Policy policy) {
        ActionRecord atDeadline = Row.of(ActionState.UNKNOWN).deadlineAt(NOW).build();

        Result result = decideAfterUnreachable(policy, atDeadline, request(MATERIAL, ALL_ARGS));

        result.is(List.of(8, 10), ClaimVerdict.ESCALATED, TO_ESCALATED);
    }

    @ParameterizedTest
    @MethodSource("withLookup")
    void row8_unreachableLookup_withAttemptsExhausted_escalatesWhenRetryWouldBeSafe(Policy policy) {
        ActionRecord exhausted = Row.of(ActionState.UNKNOWN).attempt(3).build();

        Result result = decideAfterUnreachable(policy, exhausted, request(MATERIAL, ALL_ARGS));

        if (policy.retriesUnknown()) {
            result.is(List.of(8, 10), ClaimVerdict.ESCALATED, TO_ESCALATED);
        } else {
            result.is(List.of(8), ClaimVerdict.ASK_LATER, NONE);
        }
    }

    @ParameterizedTest
    @MethodSource("withLookup")
    void row8_unreachableLookup_doesNotSkipRow7_norApplyToANewerAttempt(Policy policy) {
        DecisionTable table = new DecisionTable(policy.create()).afterUnreachableLookup(1);
        ActionRecord newerAttemptWindowClosed = Row.of(ActionState.UNKNOWN).attempt(2)
                .startedAt(NOW.minusSeconds(1)).build();

        new Result(table.evaluate(request(policy, OTHER_MATERIAL, ALL_ARGS), Optional.of(row(ActionState.UNKNOWN)),
                NOW)).is(List.of(7), ClaimVerdict.CONFLICT, NONE);
        new Result(table.evaluate(request(policy, MATERIAL, ALL_ARGS), Optional.of(newerAttemptWindowClosed), NOW))
                .is(List.of(9), ClaimVerdict.ASK_LATER, NONE);
    }

    @ParameterizedTest
    @MethodSource("withLookup")
    void row8_unreachableLookup_skipsRow9_evenIfTheWindowLooksClosed(Policy policy) {
        ActionRecord windowClosed = Row.of(ActionState.UNKNOWN).startedAt(NOW.minusSeconds(1)).build();

        Result result = decideAfterUnreachable(policy, windowClosed, request(MATERIAL, ALL_ARGS));

        assertThat(result.rows).containsExactlyElementsOf(policy.retriesUnknown() ? List.of(8, 12) : List.of(8));
    }

    @ParameterizedTest
    @MethodSource("withLookup")
    void row6_thenRow8_unreachableLookup_onAnExpiredLease(Policy policy) {
        Result result = decideAfterUnreachable(policy, executing(NOW.minusMillis(1)), request(MATERIAL, ALL_ARGS));

        if (policy.retriesUnknown()) {
            result.is(List.of(6, 8, 12), ClaimVerdict.START, new JournalWrite.StartAttempt(2));
        } else {
            result.is(List.of(6, 8), ClaimVerdict.ASK_LATER, TO_UNKNOWN);
        }
    }

    @ParameterizedTest
    @MethodSource("withoutLookup")
    void row6_thenRow10_escalatesAnExpiredLeaseDirectly_atTheDeadlineOrWithAttemptsExhausted(Policy policy) {
        ActionRecord atDeadline = Row.of(ActionState.EXECUTING).deadlineAt(NOW).build();
        ActionRecord exhausted = Row.of(ActionState.EXECUTING).attempt(3).build();

        decide(policy, atDeadline, request(MATERIAL, ALL_ARGS))
                .is(List.of(6, 10), ClaimVerdict.ESCALATED, TO_ESCALATED);
        decide(policy, exhausted, request(MATERIAL, ALL_ARGS))
                .is(List.of(6, 10), ClaimVerdict.ESCALATED, TO_ESCALATED);
    }

    @ParameterizedTest
    @MethodSource("withLookup")
    void row9_unknownWithLookupBeforeMaxEffectDuration_asksLater_upToAndIncludingTheWindowStart(Policy policy) {
        ActionRecord windowAtNow = Row.of(ActionState.UNKNOWN).startedAt(NOW.minus(MAX_EFFECT)).build();
        ActionRecord windowLater = Row.of(ActionState.UNKNOWN).startedAt(NOW.minusSeconds(1)).build();

        decide(policy, windowAtNow, request(MATERIAL, ALL_ARGS)).is(List.of(9), ClaimVerdict.ASK_LATER, NONE);
        decide(policy, windowLater, request(MATERIAL, ALL_ARGS)).is(List.of(9), ClaimVerdict.ASK_LATER, NONE);
    }

    // ---- rows 10-12 (no lookup declared)

    @ParameterizedTest
    @MethodSource("withoutLookup")
    void row10_unknownAtOrAfterTheDeadline_escalates(Policy policy) {
        ActionRecord atDeadline = Row.of(ActionState.UNKNOWN).deadlineAt(NOW).build();
        ActionRecord afterDeadline = Row.of(ActionState.UNKNOWN).deadlineAt(NOW.minusSeconds(1)).build();

        decide(policy, atDeadline, request(MATERIAL, ALL_ARGS)).is(List.of(10), ClaimVerdict.ESCALATED, TO_ESCALATED);
        decide(policy, afterDeadline, request(MATERIAL, ALL_ARGS))
                .is(List.of(10), ClaimVerdict.ESCALATED, TO_ESCALATED);
    }

    @ParameterizedTest
    @MethodSource("withoutLookup")
    void row10_unknownWithAttemptsExhausted_escalates(Policy policy) {
        ActionRecord exhausted = Row.of(ActionState.UNKNOWN).attempt(3).build();

        decide(policy, exhausted, request(MATERIAL, ALL_ARGS)).is(List.of(10), ClaimVerdict.ESCALATED, TO_ESCALATED);
    }

    @Test
    void row11_unknownAtMostOnce_escalates() {
        Result result = decide(Policy.AT_MOST_ONCE, row(ActionState.UNKNOWN), request(MATERIAL, ALL_ARGS));

        result.is(List.of(11), ClaimVerdict.ESCALATED, TO_ESCALATED);
    }

    @ParameterizedTest
    @EnumSource(value = Policy.class, names = {"IDEMPOTENT_DOWNSTREAM", "AT_LEAST_ONCE"})
    void row12_unknownRetryablePolicy_startsNextAttempt(Policy policy) {
        ActionRecord secondAttempt = Row.of(ActionState.UNKNOWN).attempt(2).build();

        Result result = decide(policy, secondAttempt, request(MATERIAL, ALL_ARGS));

        result.is(List.of(12), ClaimVerdict.START, new JournalWrite.StartAttempt(3));
    }

    @Test
    void row12_atLeastOnce_retriesWhenOnlyFreeTextChanged() {
        Result result = decide(Policy.AT_LEAST_ONCE, row(ActionState.UNKNOWN), request(MATERIAL, OTHER_ALL_ARGS));

        result.is(List.of(12), ClaimVerdict.START, new JournalWrite.StartAttempt(2));
    }

    // ---- rows 13-15

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row13_failedAtTheDeadlineOrWithAttemptsExhausted_isAbandoned_beforeTheConflictCheckOfRow14(Policy policy) {
        for (FailureBasis basis : FailureBasis.values()) {
            ActionRecord atDeadline = Row.of(ActionState.FAILED).failureBasis(basis).deadlineAt(NOW).build();
            ActionRecord exhausted = Row.of(ActionState.FAILED).failureBasis(basis).attempt(3).build();

            decide(policy, atDeadline, request(OTHER_MATERIAL, ALL_ARGS))
                    .is(List.of(13), ClaimVerdict.ABANDONED, TO_ABANDONED);
            decide(policy, exhausted, request(MATERIAL, ALL_ARGS))
                    .is(List.of(13), ClaimVerdict.ABANDONED, TO_ABANDONED);
        }
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row14_failedByLookupWithDifferentMaterialArgs_conflicts(Policy policy) {
        ActionRecord failedByLookup = Row.of(ActionState.FAILED).failureBasis(FailureBasis.LOOKUP).build();

        Result result = decide(policy, failedByLookup, request(OTHER_MATERIAL, ALL_ARGS));

        result.is(List.of(14), ClaimVerdict.CONFLICT, NONE);
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row15_failedDefinitely_startsNextAttempt_alsoWithCorrectedArgs(Policy policy) {
        ActionRecord failed = Row.of(ActionState.FAILED).failureBasis(FailureBasis.DEFINITE).build();

        decide(policy, failed, request(MATERIAL, ALL_ARGS))
                .is(List.of(15), ClaimVerdict.START, new JournalWrite.StartAttempt(2));
        decide(policy, failed, request(OTHER_MATERIAL, OTHER_ALL_ARGS))
                .is(List.of(15), ClaimVerdict.START, new JournalWrite.StartAttempt(2));
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row15_failedByLookupWithSameMaterialArgs_startsNextAttempt(Policy policy) {
        ActionRecord failedByLookup = Row.of(ActionState.FAILED).failureBasis(FailureBasis.LOOKUP).build();

        Result result = decide(policy, failedByLookup, request(MATERIAL, OTHER_ALL_ARGS));

        result.is(List.of(15), ClaimVerdict.START, new JournalWrite.StartAttempt(2));
    }

    // ---- row 16

    @ParameterizedTest
    @EnumSource(Policy.class)
    void row16_abandonedOrEscalated_reportsTheSameOutcome_alsoWithDifferentArgs(Policy policy) {
        for (ClaimRequest request : List.of(request(MATERIAL, ALL_ARGS), request(OTHER_MATERIAL, OTHER_ALL_ARGS))) {
            decide(policy, row(ActionState.ABANDONED), request).is(List.of(16), ClaimVerdict.ABANDONED, NONE);
            decide(policy, row(ActionState.ESCALATED), request).is(List.of(16), ClaimVerdict.ESCALATED, NONE);
        }
    }

    // ---- contract of the decider

    @Nested
    class Contract {

        @ParameterizedTest
        @EnumSource(ActionState.class)
        void decide_returnsTheDecisionOfEvaluate(ActionState state) {
            DecisionTable table = new DecisionTable(Policy.AT_LEAST_ONCE.create());
            ActionRecord current = state == ActionState.FAILED
                    ? Row.of(state).failureBasis(FailureBasis.DEFINITE).build()
                    : Row.of(state).build();
            ClaimRequest request = request(Policy.AT_LEAST_ONCE, MATERIAL, ALL_ARGS);

            ClaimDecision decided = table.decide(request, Optional.of(current), NOW);

            assertThat(decided).isEqualTo(table.evaluate(request, Optional.of(current), NOW).decision());
        }

        @Test
        void rejectsARequestForAnotherPolicy() {
            DecisionTable table = new DecisionTable(Policy.AT_LEAST_ONCE.create());
            ClaimRequest request = request(Policy.AT_MOST_ONCE, MATERIAL, ALL_ARGS);

            assertThatThrownBy(() -> table.decide(request, Optional.empty(), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("AT_MOST_ONCE");
        }

        @Test
        void rejectsAnInvalidPolicy() {
            assertThatThrownBy(() -> new DecisionTable(ActionPolicy.atLeastOnce()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("key(KeyStrategy)");
        }
    }

    // ---- fixtures

    /** Evaluates {@code hashes} (a request whose hashes are used) as a request of {@code policy}. */
    private static Result decide(Policy policy, ActionRecord current, ClaimRequest hashes) {
        DecisionTable table = new DecisionTable(policy.create());
        return new Result(table.evaluate(asPolicy(policy, hashes), Optional.ofNullable(current), NOW));
    }

    /** Evaluates after an unreachable lookup of attempt 1 (attempt 3 when the row is at attempt 3). */
    private static Result decideAfterUnreachable(Policy policy, ActionRecord current, ClaimRequest hashes) {
        DecisionTable table = new DecisionTable(policy.create()).afterUnreachableLookup(current.attempt());
        return new Result(table.evaluate(asPolicy(policy, hashes), Optional.of(current), NOW));
    }

    private static ClaimRequest asPolicy(Policy policy, ClaimRequest hashes) {
        return request(policy, hashes.materialArgsHash(), hashes.allArgsHash());
    }

    private static ActionRecord row(ActionState state) {
        return Row.of(state).build();
    }

    private static ActionRecord executing(Instant leaseUntil) {
        return Row.of(ActionState.EXECUTING).leaseUntil(leaseUntil).build();
    }

    static ClaimRequest request(byte[] materialArgsHash, byte[] allArgsHash) {
        return request(Policy.AT_LEAST_ONCE, materialArgsHash, allArgsHash);
    }

    static ClaimRequest request(Policy policy, byte[] materialArgsHash, byte[] allArgsHash) {
        return new ClaimRequest(filled(9), filled(8), null, "refund", policy.create().journalName(), materialArgsHash, allArgsHash, null,
                "call-1", "owner-1", Duration.ofSeconds(2), MAX_EFFECT, Duration.ofSeconds(60));
    }

    static byte[] filled(int value) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) value);
        return hash;
    }

    private static final class Result {
        final List<Integer> rows;
        final ClaimDecision decision;

        Result(DecisionTable.Evaluation evaluation) {
            this.rows = evaluation.rows();
            this.decision = evaluation.decision();
        }

        void is(List<Integer> expectedRows, ClaimVerdict expectedVerdict, JournalWrite expectedWrite) {
            assertThat(rows).as("matched rows").isEqualTo(expectedRows);
            assertThat(decision.verdict()).as("verdict").isEqualTo(expectedVerdict);
            assertThat(decision.write()).as("write").isEqualTo(expectedWrite);
        }
    }

    /**
     * Builds journal rows. Defaults: attempt 1 of 3, started 10 s ago (lookup window open), deadline in 50 s, lease
     * ended 1 s ago, stored argument hashes equal to {@link #MATERIAL} and {@link #ALL_ARGS}.
     */
    static final class Row {
        private final ActionState state;
        private int attempt = 1;
        private Instant leaseUntil = NOW.minusSeconds(1);
        private Instant startedAt = NOW.minusSeconds(10);
        private Instant deadlineAt = NOW.plusSeconds(50);
        private FailureBasis failureBasis;

        private Row(ActionState state) {
            this.state = state;
        }

        static Row of(ActionState state) {
            return new Row(state);
        }

        Row attempt(int value) {
            attempt = value;
            return this;
        }

        Row leaseUntil(Instant value) {
            leaseUntil = value;
            return this;
        }

        Row startedAt(Instant value) {
            startedAt = value;
            return this;
        }

        Row deadlineAt(Instant value) {
            deadlineAt = value;
            return this;
        }

        Row failureBasis(FailureBasis value) {
            failureBasis = value;
            return this;
        }

        ActionRecord build() {
            Instant createdAt = NOW.minusSeconds(10);
            return new ActionRecord(filled(9), null, "refund", "TEST", state, attempt, "owner-0", leaseUntil,
                    MAX_EFFECT, createdAt, deadlineAt, startedAt, createdAt, null, 0, failureBasis, ALL_ARGS,
                    filled(8), MATERIAL, null, null, null);
        }
    }
}
