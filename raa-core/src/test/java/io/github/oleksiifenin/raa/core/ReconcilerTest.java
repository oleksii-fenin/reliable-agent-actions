package io.github.oleksiifenin.raa.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.FailureBasis;
import io.github.oleksiifenin.raa.testkit.InMemoryActionJournal;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The reconciler against an in-memory journal whose clock only moves when a test advances it: one test per step of
 * docs/DESIGN.md §6 (Reconciler) and per rule of ADR-0004 (lookups need key text).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ReconcilerTest {

    private static final String SCOPE = "conversation-1";
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final Duration MAX_EFFECT = Duration.ofSeconds(60);
    private static final Duration DEADLINE = Duration.ofMinutes(15);
    private static final ActionKey ORDER_42 = ActionKey.of(SCOPE, "refund", "order-42");
    private static final KeyStrategy BY_ORDER = (call, args) -> ActionKey.of(SCOPE, call.toolName(),
            args.string("orderId"));

    private final MutableClock clock = new MutableClock();
    private final InMemoryActionJournal journal = new InMemoryActionJournal(clock);
    private final AtomicInteger lookups = new AtomicInteger();
    private final List<String> lookedUpIdempotencyKeys = new ArrayList<>();

    // ---- step 2: expired leases, lookups

    @Test
    void attemptWhoseProcessDied_becomesUnknown_onceItsLeaseExpired() {
        ReliableActions actions = actions(ActionPolicy.atLeastOnce(), true);
        crash(actions);

        int takenWhileLive = actions.reconciler().runOnce();
        clock.advance(LEASE.plusMillis(1));
        int takenAfterExpiry = actions.reconciler().runOnce();

        assertThat(takenWhileLive).isZero();
        assertThat(takenAfterExpiry).isEqualTo(1);
        assertThat(row().state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(row().lastError()).contains("lease expired");
    }

    @Test
    void unknownReconcilable_isLookedUpWithItsKeyAndIdempotencyKey_onceTheLookupWindowOpens() {
        ReliableActions actions = actions(ActionPolicy.reconcilable(answering(new LookupResult.Found("refund r-1"))),
                true);
        fail(actions);

        actions.reconciler().runOnce(); // window not open yet: no lookup
        int lookupsBeforeWindow = lookups.get();
        clock.advance(Reconciler.DEFAULT_HOLD.plusMillis(1)); // past the hold and the window (60 s < 2 min)
        actions.reconciler().runOnce();

        assertThat(lookupsBeforeWindow).isZero();
        assertThat(lookups).hasValue(1);
        assertThat(lookedUpIdempotencyKeys).containsExactly(ORDER_42.idempotencyKey(row().materialArgsHash()));
        assertThat(row().state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(row().resultText()).isEqualTo("refund r-1");
        assertThat(actions.runner().run(call("c2"), context -> "again"))
                .isInstanceOfSatisfying(ActionOutcome.Replayed.class,
                        replayed -> assertThat(replayed.resultText()).isEqualTo("refund r-1"));
    }

    @Test
    void lookupNotFound_failsTheAttemptByLookup_soTheNextCallRetries() {
        ReliableActions actions = actions(ActionPolicy.reconcilable(answering(new LookupResult.NotFound())), true);
        fail(actions);
        clock.advance(MAX_EFFECT.plusMillis(1));

        actions.reconciler().runOnce();

        assertThat(row().state()).isEqualTo(ActionState.FAILED);
        assertThat(row().failureBasis()).isEqualTo(FailureBasis.LOOKUP);
        assertThat(actions.runner().run(call("c2"), context -> "refunded"))
                .isEqualTo(new ActionOutcome.Succeeded("refunded", 2));
    }

    @Test
    void lookupNotFound_atMostOnce_isEscalated_neverRetried() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce()
                .lookup(answering(new LookupResult.NotFound())), true);
        crash(actions);
        clock.advance(MAX_EFFECT.plusMillis(1)); // lease expired and window open

        actions.reconciler().runOnce(); // EXECUTING -> UNKNOWN
        actions.reconciler().runOnce(); // lookup

        assertThat(row().state()).isEqualTo(ActionState.ESCALATED);
    }

    @Test
    void unreachableLookups_backOff_andTheEighthEscalates() {
        ReliableActions actions = actions(ActionPolicy.reconcilable(answering(new LookupResult.Unreachable("503")))
                .deadline(Duration.ofHours(4)), true);
        fail(actions);
        clock.advance(MAX_EFFECT.plusMillis(1));

        Instant firstLookupAt = clock.instant();
        actions.reconciler().runOnce();
        ActionRecord afterFirst = row();
        int takenDuringBackoff = actions.reconciler().runOnce();
        for (int i = 1; i < 8; i++) {
            clock.advance(Duration.ofMinutes(10).plusMillis(1)); // the longest backoff
            actions.reconciler().runOnce();
        }

        assertThat(afterFirst.state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(afterFirst.lookupFailures()).isEqualTo(1);
        assertThat(afterFirst.resolveAfter()).isEqualTo(firstLookupAt.plus(Duration.ofSeconds(15)));
        assertThat(takenDuringBackoff).isZero();
        assertThat(lookups).hasValue(8);
        assertThat(row().state()).isEqualTo(ActionState.ESCALATED);
        assertThat(row().lastError()).contains("unreachable 8 times");
    }

    // ---- step 3: the deadline

    @Test
    void atTheDeadline_anUnreachableLookupEscalates() {
        ReliableActions actions = actions(ActionPolicy.reconcilable(answering(new LookupResult.Unreachable("503"))),
                true);
        fail(actions);
        clock.advance(DEADLINE);

        actions.reconciler().runOnce();

        assertThat(lookups).hasValue(1);
        assertThat(row().state()).isEqualTo(ActionState.ESCALATED);
        assertThat(row().lastError()).contains("at the deadline");
    }

    @Test
    void atTheDeadline_lookupNotFound_failsByLookup_andTheNextBatchAbandons() {
        ReliableActions actions = actions(ActionPolicy.reconcilable(answering(new LookupResult.NotFound())), true);
        fail(actions);
        clock.advance(DEADLINE);

        actions.reconciler().runOnce();
        ActionState afterLookup = row().state();
        actions.reconciler().runOnce();

        assertThat(afterLookup).isEqualTo(ActionState.FAILED);
        assertThat(row().state()).isEqualTo(ActionState.ABANDONED);
    }

    @Test
    void failedBeforeTheDeadline_isLeftAlone_andAbandonedAtIt() {
        ReliableActions actions = actions(ActionPolicy.atLeastOnce(), true);
        actions.runner().run(call("c1"), context -> {
            throw new ActionRejectedException("card declined");
        });

        int takenBeforeDeadline = actions.reconciler().runOnce();
        clock.advance(DEADLINE);
        actions.reconciler().runOnce();

        assertThat(takenBeforeDeadline).isZero();
        assertThat(row().state()).isEqualTo(ActionState.ABANDONED);
    }

    @Test
    void unknownWithoutALookup_waitsForACall_untilTheDeadline_thenEscalates() {
        ReliableActions actions = actions(ActionPolicy.atLeastOnce(), true);
        fail(actions);
        clock.advance(MAX_EFFECT.plusMillis(1));

        actions.reconciler().runOnce();
        ActionState beforeDeadline = row().state();
        clock.advance(DEADLINE);
        actions.reconciler().runOnce();

        assertThat(beforeDeadline).isEqualTo(ActionState.UNKNOWN);
        assertThat(row().state()).isEqualTo(ActionState.ESCALATED);
    }

    @Test
    void unknownAtMostOnceWithoutALookup_isEscalatedAtOnce() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), true);
        crash(actions);
        clock.advance(LEASE.plusMillis(1));

        actions.reconciler().runOnce(); // EXECUTING -> UNKNOWN
        actions.reconciler().runOnce();

        assertThat(row().state()).isEqualTo(ActionState.ESCALATED);
    }

    @Test
    void unreachableReasonWithANulCharacter_stillEscalates_atTheDeadline() {
        ReliableActions atDeadline = actions(ActionPolicy.reconcilable(answering(
                new LookupResult.Unreachable("bad\u0000gateway"))), true);
        fail(atDeadline);
        clock.advance(DEADLINE);

        atDeadline.reconciler().runOnce();

        assertThat(row().state()).isEqualTo(ActionState.ESCALATED);
        assertThat(row().lastError()).contains("bad\uFFFDgateway");
    }

    @Test
    void unreachableReasonWithANulCharacter_stillEscalates_afterTheEighthFailure() {
        ReliableActions actions = actions(ActionPolicy.reconcilable(answering(
                new LookupResult.Unreachable("bad\u0000gateway"))).deadline(Duration.ofHours(4)), true);
        fail(actions);
        clock.advance(MAX_EFFECT.plusMillis(1));

        for (int i = 0; i < 8; i++) {
            actions.reconciler().runOnce();
            clock.advance(Duration.ofMinutes(10).plusMillis(1));
        }

        assertThat(row().state()).isEqualTo(ActionState.ESCALATED);
        assertThat(row().lastError()).contains("unreachable 8 times", "bad\uFFFDgateway");
    }

    // ---- ADR-0004: key text

    @Test
    void withoutKeyText_theReconcilerDoesNotLookUp_theNextCallDoes() {
        ReliableActions actions = actions(ActionPolicy.reconcilable(answering(new LookupResult.Found("refund r-1"))),
                false);
        fail(actions);
        clock.advance(MAX_EFFECT.plusMillis(1));

        actions.reconciler().runOnce();
        int reconcilerLookups = lookups.get();
        ActionOutcome nextCall = actions.runner().run(call("c2"), context -> "again");

        assertThat(row().keyText()).isNull();
        assertThat(reconcilerLookups).isZero();
        assertThat(nextCall).isInstanceOfSatisfying(ActionOutcome.Replayed.class,
                replayed -> assertThat(replayed.fromLookup()).isTrue());
    }

    @Test
    void withoutKeyText_aDeclaredLookup_isEscalatedAtTheDeadline() {
        ReliableActions actions = actions(ActionPolicy.reconcilable(answering(new LookupResult.Found("refund r-1"))),
                false);
        fail(actions);
        clock.advance(DEADLINE);

        actions.reconciler().runOnce();

        assertThat(lookups).hasValue(0);
        assertThat(row().state()).isEqualTo(ActionState.ESCALATED);
        assertThat(row().lastError()).contains("no key text");
    }

    @Test
    void keyText_isStoredOnlyWhenEnabled_andReadsBackAsTheKey() {
        fail(actions(ActionPolicy.atLeastOnce(), true));
        String stored = row().keyText();

        assertThat(stored).isEqualTo(ORDER_42.toText());
        assertThat(ActionKey.parse(stored)).isEqualTo(ORDER_42);
    }

    @Test
    void keyWithANulCharacter_isStoredWithoutKeyText() {
        ReliableActions actions = actions(ActionPolicy.atLeastOnce(), true);

        ActionOutcome outcome = actions.runner().run(call("c1", "order\u000042"), context -> "refunded");

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(journal.findByProviderCallId(ORDER_42.scopeHash(), "c1").orElseThrow().action().keyText())
                .isNull();
    }

    // ---- retention

    @Test
    void eachRun_deletesFinishedActionsOlderThanTheRetention_soTheirKeyExecutesAgain() {
        ReliableActions actions = ReliableActions.builder(journal)
                .tool("refund", ActionPolicy.atLeastOnce().key(BY_ORDER).maxEffectDuration(MAX_EFFECT))
                .lease(LEASE).retention(Duration.ofDays(7)).build();
        actions.runner().run(call("c1"), context -> "refunded r-1");
        fail(actions(ActionPolicy.atLeastOnce(), false), "c9", "order-9"); // UNKNOWN: never deleted

        clock.advance(Duration.ofDays(7));
        actions.reconciler().runOnce();
        boolean keptAtTheRetention = journal.findByProviderCallId(ORDER_42.scopeHash(), "c1").isPresent();
        clock.advance(Duration.ofMillis(1));
        actions.reconciler().runOnce();

        assertThat(keptAtTheRetention).isTrue();
        assertThat(journal.findByProviderCallId(ORDER_42.scopeHash(), "c1")).isEmpty();
        assertThat(journal.findByProviderCallId(ORDER_42.scopeHash(), "c9")).isPresent();
        AtomicInteger effects = new AtomicInteger();
        assertThat(actions.runner().run(call("c2"), context -> "refunded r-2 " + effects.incrementAndGet()))
                .isEqualTo(new ActionOutcome.Succeeded("refunded r-2 1", 1));
    }

    @Test
    void aFailingRetentionDelete_isLogged_andTheRunStillReturns() {
        FaultyJournal faulty = new FaultyJournal(journal);
        faulty.failDeletes.set(true);
        ReliableActions actions = ReliableActions.builder(faulty)
                .tool("refund", ActionPolicy.atLeastOnce().key(BY_ORDER).maxEffectDuration(MAX_EFFECT))
                .lease(LEASE).build();
        crash(actions);
        clock.advance(LEASE.plusMillis(1));

        int taken = actions.reconciler().runOnce();

        assertThat(taken).isEqualTo(1);
        assertThat(row().state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(faulty.deleteCalls).hasValue(1);
    }

    // ---- unresolved gauge (docs/DESIGN.md §10)

    @Test
    void eachRun_reportsTheUnresolvedRowsAfterHandlingItsBatch() {
        List<String> reports = new ArrayList<>();
        ReliableActions actions = withTelemetry(journal, reporting(reports));
        fail(actions, "c1", "order-1");
        clock.advance(DEADLINE.plusMillis(1)); // order-1 is escalated by the next run, at its deadline
        fail(actions, "c2", "order-2");
        fail(actions, "c3", "order-3");

        actions.reconciler().runOnce();

        assertThat(reports).containsExactly("unknown 2 escalated 1");
    }

    @Test
    void withoutTelemetry_nothingIsCounted() {
        FaultyJournal faulty = new FaultyJournal(journal);
        ReliableActions actions = ReliableActions.builder(faulty)
                .tool("refund", ActionPolicy.atLeastOnce().key(BY_ORDER).maxEffectDuration(MAX_EFFECT))
                .lease(LEASE).build();
        fail(actions);

        actions.reconciler().runOnce();

        assertThat(faulty.countCalls).hasValue(0);
    }

    @Test
    void aFailingCount_isLogged_notReported_andTheNextRunReportsAgain() {
        FaultyJournal faulty = new FaultyJournal(journal);
        List<String> reports = new ArrayList<>();
        ReliableActions actions = withTelemetry(faulty, reporting(reports));
        fail(actions);
        faulty.failCounts.set(true);

        int taken = actions.reconciler().runOnce();
        List<String> reportsWhileFailing = List.copyOf(reports);
        faulty.failCounts.set(false);
        actions.reconciler().runOnce();

        assertThat(taken).isEqualTo(1);
        assertThat(reportsWhileFailing).isEmpty();
        assertThat(reports).containsExactly("unknown 1 escalated 0");
    }

    // ---- helpers

    private static ReliableActions withTelemetry(ActionJournal storage,
            ActionTelemetry telemetry) {
        return ReliableActions.builder(storage)
                .tool("refund", ActionPolicy.atLeastOnce().key(BY_ORDER).maxEffectDuration(MAX_EFFECT)
                        .deadline(DEADLINE))
                .lease(LEASE).telemetry(telemetry).build();
    }

    private static ActionTelemetry reporting(List<String> reports) {
        return new ActionTelemetry() {
            @Override
            public void unresolvedActions(long unknown, long escalated) {
                reports.add("unknown " + unknown + " escalated " + escalated);
            }
        };
    }

    private ReliableActions actions(JournaledPolicy<?> policy, boolean storeKeyText) {
        JournaledPolicy<?> complete = policy.key(BY_ORDER).maxEffectDuration(MAX_EFFECT);
        return ReliableActions.builder(journal).tool("refund", complete).lease(LEASE).storeKeyText(storeKeyText)
                .build();
    }

    private OutcomeLookup answering(LookupResult result) {
        return (key, idempotencyKey) -> {
            assertThat(key).isEqualTo(ORDER_42);
            lookups.incrementAndGet();
            lookedUpIdempotencyKeys.add(idempotencyKey);
            return result;
        };
    }

    /** First call ends UNKNOWN: an indeterminate error. */
    private void fail(ReliableActions actions) {
        fail(actions, "c1", "order-42");
    }

    private static void fail(ReliableActions actions, String callId, String orderId) {
        actions.runner().run(call(callId, orderId), context -> {
            throw new IOException("connection reset");
        });
    }

    /** First call stays EXECUTING, as if the process died during the effect. */
    private void crash(ReliableActions actions) {
        try {
            actions.runner().run(call("c1"), context -> {
                throw new SimulatedCrash();
            });
        } catch (SimulatedCrash expected) {
            // an Error is not handled: the row stays EXECUTING
        }
    }

    private static ToolCall call(String callId) {
        return call(callId, "order-42");
    }

    private static ToolCall call(String callId, String orderId) {
        Map<String, Object> arguments = Map.of("orderId", orderId, "amount", new BigDecimal("10"));
        return new ToolCall("refund", arguments.toString(), arguments, callId, SCOPE);
    }

    private ActionRecord row() {
        return journal.findByProviderCallId(ORDER_42.scopeHash(), "c1").orElseThrow().action();
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }
}
