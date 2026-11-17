package io.github.oleksiifenin.raa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.FailureBasis;
import io.github.oleksiifenin.raa.storage.FailureKind;
import io.github.oleksiifenin.raa.storage.Resolution;
import io.github.oleksiifenin.raa.testkit.InMemoryActionJournal;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The runner against an in-memory journal whose clock only moves when a test advances it: one test per verdict of
 * the decision table (docs/DESIGN.md §6) and per outcome the model can be told.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ActionRunnerTest {

    private static final String SCOPE = "conversation-1";
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final Duration MAX_EFFECT = Duration.ofSeconds(60);
    private static final ActionKey ORDER_42 = ActionKey.of(SCOPE, "refund", "order-42");
    private static final KeyStrategy BY_ORDER =(call, args) -> ActionKey.of(SCOPE, call.toolName(),
            args.string("orderId"));

    private final MutableClock clock = new MutableClock();
    private final FaultyJournal journal = new FaultyJournal(new InMemoryActionJournal(clock));
    private final AtomicInteger effects = new AtomicInteger();
    private final List<String> idempotencyKeys = Collections.synchronizedList(new ArrayList<>());

    // ---- START → Succeeded, then REPLAY / CONFLICT

    @Test
    void firstCall_runsTheEffectOnce_withAttemptOneAndTheIdempotencyKey() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(effects).hasValue(1);
        assertThat(idempotencyKeys).containsExactly(
                ActionKey.of(SCOPE, "refund", "order-42").idempotencyKey(material("order-42", "10")));
        ActionRecord row = row("c1");
        assertThat(row.state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(row.resultText()).isEqualTo("refunded");
    }

    @Test
    void repeatedCall_withANewProviderCallId_isReplayed_withoutRunningTheEffect() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());
        runner.run(call("c1", "order-42", "10"), succeeding("refunded"));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10.00"), succeeding("again"));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Replayed.class, replayed -> {
            assertThat(replayed.resultText()).isEqualTo("refunded");
            assertThat(replayed.fromLookup()).isFalse();
            assertThat(replayed.completedAt()).isEqualTo(row("c1").updatedAt());
        });
        assertThat(effects).hasValue(1);
    }

    @Test
    void changedFreeTextArguments_areReplayed() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce().freeTextArguments("note"));
        runner.run(call("c1", Map.of("orderId", "order-42", "amount", 10, "note", "thanks")), succeeding("refunded"));

        ActionOutcome outcome = runner.run(call("c2", Map.of("orderId", "order-42", "amount", 10, "note", "sorry")),
                succeeding("again"));

        assertThat(outcome).isInstanceOf(ActionOutcome.Replayed.class);
        assertThat(effects).hasValue(1);
    }

    @Test
    void changedMaterialArguments_afterSuccess_areAConflictWithTheRecordedResult() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());
        runner.run(call("c1", "order-42", "10"), succeeding("refunded 10"));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "20"), succeeding("refunded 20"));

        assertThat(outcome).isEqualTo(new ActionOutcome.Conflict("refunded 10", false));
        assertThat(effects).hasValue(1);
    }

    @Test
    void changedMaterialArguments_whileTheOutcomeIsUnknown_areAConflictWithTheUnresolvedAttempt() {
        ActionRunner runner = runner(ActionPolicy.atLeastOnce());
        runner.run(call("c1", "order-42", "10"), failing(new IOException("timeout")));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "20"), succeeding("refunded 20"));

        assertThat(outcome).isEqualTo(new ActionOutcome.Conflict(null, true));
        assertThat(effects).hasValue(1);
    }

    // ---- IN_PROGRESS

    @Test
    void sameKeyWhileRunning_waitsForTheRunningCall_andReplaysItsResult() throws Exception {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<ActionOutcome> first = CompletableFuture.supplyAsync(() -> runner.run(
                call("c1", "order-42", "10"), blocking(running, release, "refunded")));
        await(running);

        CompletableFuture<ActionOutcome> second = CompletableFuture.supplyAsync(
                () -> runner.run(call("c2", "order-42", "10"), succeeding("again")));
        eventually(() -> journal.claims.get() >= 3); // first claim, then the second call claimed and polled again
        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(second.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ActionOutcome.Replayed.class,
                replayed -> assertThat(replayed.resultText()).isEqualTo("refunded"));
        assertThat(effects).hasValue(1);
    }

    @Test
    void sameKeyWhileRunning_beyondTheInProgressWait_isInProgressElsewhere() throws Exception {
        ActionRunner runner = runner(ActionPolicy.atMostOnce(), Duration.ofMillis(100));
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<ActionOutcome> first = CompletableFuture.supplyAsync(() -> runner.run(
                call("c1", "order-42", "10"), blocking(running, release, "refunded")));
        await(running);

        ActionOutcome second = runner.run(call("c2", "order-42", "10"), succeeding("again"));
        release.countDown();

        assertThat(second).isEqualTo(new ActionOutcome.InProgressElsewhere(clockNow().plus(LEASE)));
        assertThat(first.get(10, TimeUnit.SECONDS)).isInstanceOf(ActionOutcome.Succeeded.class);
        assertThat(effects).hasValue(1);
    }

    // ---- inline failure handling: Unknown advice per policy, Failed, Abandoned, Escalated

    @Test
    void indeterminateError_idempotentDownstream_isUnknownSafe_andTheNextCallRetriesWithTheSameKey() {
        ActionRunner runner = runner(ActionPolicy.idempotentDownstream(Duration.ofHours(1)));

        ActionOutcome first = runner.run(call("c1", "order-42", "10"), failing(new IOException("read timed out")));
        ActionOutcome second = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(first).isInstanceOfSatisfying(ActionOutcome.Unknown.class, unknown -> {
            assertThat(unknown.advice()).isEqualTo(RepeatAdvice.SAFE);
            assertThat(unknown.reason()).contains("read timed out");
        });
        assertThat(second).isEqualTo(new ActionOutcome.Succeeded("refunded", 2));
        assertThat(idempotencyKeys).hasSize(2).containsOnly(idempotencyKeys.get(0));
    }

    @Test
    void indeterminateError_atLeastOnce_isUnknownMayDuplicate() {
        ActionRunner runner = runner(ActionPolicy.atLeastOnce());

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Unknown.class,
                unknown -> assertThat(unknown.advice()).isEqualTo(RepeatAdvice.MAY_DUPLICATE));
        assertThat(row("c1").state()).isEqualTo(ActionState.UNKNOWN);
    }

    @Test
    void indeterminateError_atMostOnceWithoutLookup_isEscalatedAtOnce_andStaysEscalated() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());

        ActionOutcome first = runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        ActionOutcome second = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(first).isInstanceOf(ActionOutcome.Escalated.class);
        assertThat(row("c1").state()).isEqualTo(ActionState.ESCALATED);
        assertThat(second).isInstanceOf(ActionOutcome.Escalated.class);
        assertThat(effects).hasValue(1);
    }

    @Test
    void indeterminateError_reconcilable_isUnknownAskLater_untilTheLookupWindowOpens() {
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) -> {
            throw new AssertionError("no lookup before started_at + maxEffectDuration");
        }));

        ActionOutcome first = runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        ActionOutcome second = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(first).isInstanceOfSatisfying(ActionOutcome.Unknown.class,
                unknown -> assertThat(unknown.advice()).isEqualTo(RepeatAdvice.ASK_LATER));
        assertThat(second).isInstanceOfSatisfying(ActionOutcome.Unknown.class, unknown -> {
            assertThat(unknown.advice()).isEqualTo(RepeatAdvice.ASK_LATER);
            assertThat(unknown.reason()).contains(row("c1").startedAt().plus(MAX_EFFECT).toString());
        });
        assertThat(effects).hasValue(1);
    }

    @Test
    void rejectedAction_isFailed_andACorrectedCallStartsANewAttemptWithANewIdempotencyKey() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());

        ActionOutcome first = runner.run(call("c1", "order-42", "10"),
                failing(new ActionRejectedException("amount exceeds the order total")));
        ActionOutcome corrected = runner.run(call("c2", "order-42", "5"), succeeding("refunded 5"));

        assertThat(first).isEqualTo(new ActionOutcome.Failed("amount exceeds the order total", true));
        assertThat(corrected).isEqualTo(new ActionOutcome.Succeeded("refunded 5", 2));
        assertThat(idempotencyKeys).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void definiteFailureOfTheLastAttempt_isNotRetryable_andTheNextCallIsAbandoned() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce().maxAttempts(1));

        ActionOutcome first = runner.run(call("c1", "order-42", "10"),
                failing(new ActionRejectedException("order is closed")));
        ActionOutcome second = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(first).isEqualTo(new ActionOutcome.Failed("order is closed", false));
        assertThat(second).isInstanceOfSatisfying(ActionOutcome.Abandoned.class,
                abandoned -> assertThat(abandoned.reason()).contains("order is closed"));
        assertThat(effects).hasValue(1);
    }

    @Test
    void unknownOutcomeAfterTheDeadline_isEscalated_withoutAnotherAttempt() {
        ActionRunner runner = runner(ActionPolicy.atLeastOnce().deadline(Duration.ofMinutes(5)));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(Duration.ofMinutes(5));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isInstanceOf(ActionOutcome.Escalated.class);
        assertThat(effects).hasValue(1);
    }

    @Test
    void attemptWhoseProcessDied_isRetriedAfterItsLeaseExpired() {
        ActionRunner runner = runner(ActionPolicy.atLeastOnce());
        try {
            runner.run(call("c1", "order-42", "10"), context -> {
                effects.incrementAndGet();
                throw new SimulatedCrash();
            });
        } catch (SimulatedCrash expected) {
            // an Error is not handled: the row stays EXECUTING, as after a crash
        }
        assertThat(row("c1").state()).isEqualTo(ActionState.EXECUTING);

        ActionOutcome whileLeaseLive = runner(ActionPolicy.atLeastOnce(), Duration.ofMillis(1))
                .run(call("c2", "order-42", "10"), succeeding("refunded"));
        clock.advance(LEASE.plusMillis(1));
        ActionOutcome afterExpiry = runner.run(call("c3", "order-42", "10"), succeeding("refunded"));

        assertThat(whileLeaseLive).isInstanceOf(ActionOutcome.InProgressElsewhere.class);
        assertThat(afterExpiry).isEqualTo(new ActionOutcome.Succeeded("refunded", 2));
    }

    // ---- NEEDS_LOOKUP

    @Test
    void lookupFound_isReplayedFromTheLookup_withoutRunningTheEffectAgain() {
        AtomicReference<String> askedWith = new AtomicReference<>();
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) -> {
            askedWith.set(idempotencyKey);
            return new LookupResult.Found("refund R-1 exists");
        }));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Replayed.class, replayed -> {
            assertThat(replayed.resultText()).isEqualTo("refund R-1 exists");
            assertThat(replayed.fromLookup()).isTrue();
        });
        assertThat(askedWith).hasValue(idempotencyKeys.get(0));
        assertThat(effects).hasValue(1);
        assertThat(row("c1").state()).isEqualTo(ActionState.SUCCEEDED);
    }

    @Test
    void lookupNotFound_reconcilable_failsTheAttemptByLookup_andStartsTheNextOne() {
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) -> new LookupResult.NotFound()));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 2));
        assertThat(effects).hasValue(2);
    }

    @Test
    void lookupNotFound_atMostOnce_isEscalated() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce().lookup((key, idempotencyKey) ->
                new LookupResult.NotFound()));
        ActionOutcome first = runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(first).isInstanceOfSatisfying(ActionOutcome.Unknown.class,
                unknown -> assertThat(unknown.advice()).isEqualTo(RepeatAdvice.ASK_LATER));
        assertThat(outcome).isInstanceOf(ActionOutcome.Escalated.class);
        assertThat(row("c1").state()).isEqualTo(ActionState.ESCALATED);
        assertThat(effects).hasValue(1);
    }

    @Test
    void lookupUnreachable_reconcilable_isAskLater_withBackoff_andCountsTheFailure() {
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) ->
                new LookupResult.Unreachable("503")));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Unknown.class,
                unknown -> assertThat(unknown.advice()).isEqualTo(RepeatAdvice.ASK_LATER));
        ActionRecord row = row("c1");
        assertThat(row.state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(row.lookupFailures()).isEqualTo(1);
        assertThat(row.resolveAfter()).isEqualTo(clockNow().plus(Duration.ofSeconds(15)));
        assertThat(effects).hasValue(1);
    }

    @Test
    void callsDuringTheLookupBackoff_doNotLookUp_andAreToldWhenToCallAgain() {
        AtomicInteger lookups = new AtomicInteger();
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) -> {
            lookups.incrementAndGet();
            return new LookupResult.Unreachable("503");
        }).deadline(Duration.ofHours(4)));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));
        runner.run(call("c2", "order-42", "10"), succeeding("refunded"));
        Instant backoffEnds = row("c1").resolveAfter();

        List<ActionOutcome> duringBackoff = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            duringBackoff.add(runner.run(call("c" + (i + 3), "order-42", "10"), succeeding("refunded")));
        }
        int lookupsDuringBackoff = lookups.get();
        clock.advance(Duration.ofSeconds(15));
        runner.run(call("c13", "order-42", "10"), succeeding("refunded"));

        assertThat(duringBackoff).allSatisfy(outcome -> assertThat(outcome).isInstanceOfSatisfying(
                ActionOutcome.Unknown.class, unknown -> {
                    assertThat(unknown.advice()).isEqualTo(RepeatAdvice.ASK_LATER);
                    assertThat(unknown.reason()).contains(backoffEnds.toString());
                }));
        assertThat(lookupsDuringBackoff).isEqualTo(1);
        assertThat(lookups).hasValue(2);
        assertThat(row("c1").state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(row("c1").lookupFailures()).isEqualTo(2);
        assertThat(effects).hasValue(1);
    }

    @Test
    void callDuringTheLookupBackoff_idempotentDownstream_retriesWithTheSameKey_withoutALookup() {
        AtomicInteger lookups = new AtomicInteger();
        ActionRunner runner = runner(ActionPolicy.idempotentDownstream(Duration.ofHours(1))
                .lookup((key, idempotencyKey) -> {
                    lookups.incrementAndGet();
                    return new LookupResult.Found("refunded earlier");
                }));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));
        // A reconciler's lookup was unreachable: the row now backs off for 15 s.
        journal.resolve(ORDER_42.hash(), 1, new Resolution.LookupUnreachable(Duration.ofSeconds(15)));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 2));
        assertThat(lookups).hasValue(0);
        assertThat(idempotencyKeys).hasSize(2).containsOnly(idempotencyKeys.get(0));
    }

    @Test
    void callAtTheDeadlineInsideTheLookupBackoff_looksUpOnceMore_beforeEscalating() {
        AtomicInteger lookups = new AtomicInteger();
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) -> {
            lookups.incrementAndGet();
            return new LookupResult.Unreachable("503");
        }).deadline(Duration.ofMinutes(5)));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));
        journal.resolve(ORDER_42.hash(), 1, new Resolution.LookupUnreachable(Duration.ofMinutes(10)));

        ActionOutcome beforeDeadline = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));
        clock.advance(Duration.ofMinutes(5));
        ActionOutcome atDeadline = runner.run(call("c3", "order-42", "10"), succeeding("refunded"));

        assertThat(beforeDeadline).isInstanceOfSatisfying(ActionOutcome.Unknown.class, unknown -> assertThat(
                unknown.reason()).contains(row("c1").deadlineAt().toString())); // not the end of the 10 min backoff
        assertThat(atDeadline).isInstanceOf(ActionOutcome.Escalated.class);
        assertThat(lookups).hasValue(1);
        assertThat(row("c1").state()).isEqualTo(ActionState.ESCALATED);
        assertThat(effects).hasValue(1);
    }

    @Test
    void callWhileAReconcilerHoldsTheRow_looksUpItself() {
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) ->
                new LookupResult.Found("refunded by the first attempt")));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));
        assertThat(journal.takeReconcileBatch(Duration.ofMinutes(1), 10)).hasSize(1);

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Replayed.class, replayed -> {
            assertThat(replayed.resultText()).isEqualTo("refunded by the first attempt");
            assertThat(replayed.fromLookup()).isTrue();
        });
        assertThat(effects).hasValue(1);
    }

    @Test
    void lookupUnreachable_idempotentDownstream_doesNotBlockTheSafeRetry() {
        ActionRunner runner = runner(ActionPolicy.idempotentDownstream(Duration.ofHours(1))
                .lookup((key, idempotencyKey) -> new LookupResult.Unreachable("503")));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 2));
    }

    @Test
    void lookupThatDoesNotAnswerInTime_countsAsUnreachable() {
        CountDownLatch never = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            return new LookupResult.Found("too late");
        }).lookupTimeout(Duration.ofMillis(100)));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Unknown.class,
                unknown -> assertThat(unknown.advice()).isEqualTo(RepeatAdvice.ASK_LATER));
        assertThat(row("c1").lookupFailures()).isEqualTo(1);
        eventually(interrupted::get); // the timed-out lookup is cancelled
    }

    @Test
    void eighthUnreachableLookup_escalates() {
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) ->
                new LookupResult.Unreachable("503")).deadline(Duration.ofHours(4)));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));

        List<ActionOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            outcomes.add(runner.run(call("c" + (i + 2), "order-42", "10"), succeeding("refunded")));
            clock.advance(Duration.ofMinutes(11)); // beyond the longest backoff
        }

        assertThat(outcomes.subList(0, 7)).allMatch(ActionOutcome.Unknown.class::isInstance);
        assertThat(outcomes.get(7)).isInstanceOfSatisfying(ActionOutcome.Escalated.class,
                escalated -> assertThat(escalated.reason()).contains("unreachable 8 times"));
    }

    @Test
    void lookupBackoff_doublesFromFifteenSeconds_upToTenMinutes() {
        assertThat(ActionRunner.lookupBackoff(0)).isEqualTo(Duration.ofSeconds(15));
        assertThat(ActionRunner.lookupBackoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(ActionRunner.lookupBackoff(5)).isEqualTo(Duration.ofMinutes(8));
        assertThat(ActionRunner.lookupBackoff(6)).isEqualTo(Duration.ofMinutes(10));
        assertThat(ActionRunner.lookupBackoff(40)).isEqualTo(Duration.ofMinutes(10));
    }

    // ---- journal failures

    @Test
    void journalDownAtClaim_isJournalUnavailable_andTheEffectDoesNotRun() {
        ActionRunner runner = runner(ActionPolicy.atLeastOnce());
        journal.failClaims.set(true);

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.JournalUnavailable.class,
                unavailable -> assertThat(unavailable.reason()).isEqualTo("database unavailable"));
        assertThat(effects).hasValue(0);
    }

    @Test
    void recordWriteFailingBeforeTheJournal_isRetried_untilItLands() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());
        journal.recordFailuresBeforeWrite.set(2);

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(journal.recordCalls).hasValue(3);
        assertThat(row("c1").state()).isEqualTo(ActionState.SUCCEEDED);
    }

    @Test
    void recordWriteFailingAfterItCommitted_isReadBackByTheRetry_andKeepsTheResult() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());
        journal.recordFailuresAfterWrite.set(1);

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(journal.recordCalls).hasValue(2);
        assertThat(row("c1").resultText()).isEqualTo("refunded");
    }

    @Test
    void definiteFailureRecordFailingAfterItCommitted_staysFailed() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());
        journal.recordFailuresAfterWrite.set(1);

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"),
                failing(new ActionRejectedException("order is closed")));

        assertThat(outcome).isEqualTo(new ActionOutcome.Failed("order is closed", true));
        ActionRecord row = row("c1");
        assertThat(row.state()).isEqualTo(ActionState.FAILED);
        assertThat(row.failureBasis()).isEqualTo(FailureBasis.DEFINITE);
    }

    @Test
    void recordBudgetExhausted_stillReturnsTheRealResult_andLeavesTheRowToItsLease() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce(), Duration.ofSeconds(2), Duration.ofMillis(200));
        journal.recordFailuresBeforeWrite.set(Integer.MAX_VALUE);

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(journal.recordCalls.get()).isGreaterThan(1);
        assertThat(row("c1").state()).isEqualTo(ActionState.EXECUTING);
    }

    @Test
    void interruptAfterTheEffect_isClearedForTheRecordWrite_andRestoredAfterwards() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());
        AtomicBoolean interruptedDuringRecord = new AtomicBoolean(true);
        journal.beforeRecord = thread -> interruptedDuringRecord.set(thread.isInterrupted());

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), context -> {
            effects.incrementAndGet();
            Thread.currentThread().interrupt();
            return "refunded";
        });
        boolean interruptedAfterRun = Thread.interrupted(); // also clears it for the next test

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(interruptedDuringRecord).isFalse();
        assertThat(interruptedAfterRun).isTrue();
        assertThat(row("c1").state()).isEqualTo(ActionState.SUCCEEDED);
    }

    // ---- arguments, context and lease registration

    @Test
    void argumentsTheKeyStrategyCannotRead_failWithoutClaiming() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());

        ActionOutcome outcome = runner.run(call("c1", Map.of("amount", 10)), succeeding("refunded"));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Failed.class, failed -> {
            assertThat(failed.error()).contains("orderId").contains("missing");
            assertThat(failed.retryable()).isTrue();
        });
        assertThat(journal.claims).hasValue(0);
    }

    @Test
    void callWithoutAScope_whoseKeyNeedsOne_failsAsInvalidArguments_withoutClaiming() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce()
                .key((call, args) -> ActionKey.of(call.scope(), call.toolName(), args.string("orderId"))));
        Map<String, Object> arguments = Map.of("orderId", "order-42", "amount", new BigDecimal("10"));

        ActionOutcome outcome = runner.run(new ToolCall("refund", arguments.toString(), arguments, "c1", null),
                succeeding("refunded"));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Failed.class, failed -> {
            assertThat(failed.error()).startsWith("invalid arguments").contains("scope");
            assertThat(failed.retryable()).isTrue();
        });
        assertThat(journal.claims).hasValue(0);
        assertThat(effects).hasValue(0);
    }

    @Test
    void nullResult_isRecordedAsEmptyText() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), context -> null);

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("", 1));
        assertThat(row("c1").resultText()).isEmpty();
    }

    @Test
    void runningAttempt_isRegisteredForLeaseRenewal_onlyWhileItsEffectRuns() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), ReliableActions.DEFAULT_IN_PROGRESS_WAIT,
                ReliableActions.DEFAULT_RECORD_RETRY_BUDGET);
        AtomicInteger inFlightDuringEffect = new AtomicInteger();
        AtomicInteger renewedDuringEffect = new AtomicInteger();

        actions.runner().run(call("c1", "order-42", "10"), context -> {
            inFlightDuringEffect.set(actions.leaseRenewer().inFlightCount());
            clock.advance(Duration.ofSeconds(5));
            renewedDuringEffect.set(actions.leaseRenewer().renewNow());
            return "refunded";
        });

        assertThat(inFlightDuringEffect).hasValue(1);
        assertThat(renewedDuringEffect).hasValue(1);
        assertThat(actions.leaseRenewer().inFlightCount()).isZero();
        assertThat(actions.leaseRenewer().renewNow()).isZero();
    }

    @Test
    void attemptOverrunningItsMaximumEffectDuration_isNoLongerRenewed() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), ReliableActions.DEFAULT_IN_PROGRESS_WAIT,
                ReliableActions.DEFAULT_RECORD_RETRY_BUDGET);
        AtomicInteger renewedAfterOverrun = new AtomicInteger(-1);

        actions.runner().run(call("c1", "order-42", "10"), context -> {
            clock.advance(MAX_EFFECT);
            renewedAfterOverrun.set(actions.leaseRenewer().renewNow());
            return "refunded";
        });

        assertThat(renewedAfterOverrun).hasValue(0);
    }

    // ---- review follow-ups: interrupts, journal and lookup failures, hostile values, concurrency

    @Test
    void interruptedCaller_doesNotCountALookupFailure_andStartsNothing() {
        AtomicInteger lookups = new AtomicInteger();
        ActionRunner runner = runner(ActionPolicy.idempotentDownstream(Duration.ofHours(1))
                .lookup((key, idempotencyKey) -> {
                    lookups.incrementAndGet();
                    return new LookupResult.Found("x");
                }));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));

        Thread.currentThread().interrupt();
        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));
        boolean stillInterrupted = Thread.interrupted();

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Unknown.class,
                unknown -> assertThat(unknown.advice()).isEqualTo(RepeatAdvice.ASK_LATER));
        assertThat(stillInterrupted).isTrue();
        assertThat(lookups).hasValue(0);
        assertThat(effects).hasValue(1);
        assertThat(row("c1").lookupFailures()).isZero();
    }

    @Test
    void journalFailingWhileResolvingALookup_isBeingVerified_neverNotPerformed() {
        // Changed 2026-11-17 with the author's approval: this was JournalUnavailable ("not performed"), which is false
        // when the lookup found the effect.
        ActionRunner notFound = runner(ActionPolicy.reconcilable((key, idempotencyKey) -> new LookupResult.NotFound()));
        notFound.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        ActionRunner found = runner(ActionPolicy.reconcilable((key, idempotencyKey) ->
                new LookupResult.Found("refunded r-1")).key((call, args) -> ActionKey.of(SCOPE, call.toolName(), "other")));
        found.run(call("c3", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));
        journal.failResolves.set(true);

        List<ActionOutcome> outcomes = List.of(notFound.run(call("c2", "order-42", "10"), succeeding("refunded")),
                found.run(call("c4", "order-42", "10"), succeeding("refunded")));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOfSatisfying(
                ActionOutcome.Unknown.class, unknown -> {
                    assertThat(unknown.advice()).isEqualTo(RepeatAdvice.ASK_LATER);
                    assertThat(unknown.reason()).contains("could not be recorded").endsWith("call again later");
                }));
        assertThat(row("c1").state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(row("c3").state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(effects).hasValue(2);
    }

    @Test
    void lookupThatThrows_countsAsUnreachable() {
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) -> {
            throw new IllegalStateException("downstream 500");
        }));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));

        ActionOutcome outcome = runner.run(call("c2", "order-42", "10"), succeeding("refunded"));

        assertThat(outcome).isInstanceOf(ActionOutcome.Unknown.class);
        assertThat(row("c1").lookupFailures()).isEqualTo(1);
    }

    @Test
    void classifierThatFailsOrAnswersNothing_leavesTheOutcomeUnknown_neverNotApplied() {
        ActionRunner throwing = runner(ActionPolicy.atLeastOnce().failureClassifier((error, started) -> {
            throw new IllegalStateException("classifier bug");
        }));
        ActionRunner silent = actions(ActionPolicy.atLeastOnce().failureClassifier((error, started) -> null)
                .key((call, args) -> ActionKey.of(SCOPE, call.toolName(), "other")),
                ReliableActions.DEFAULT_IN_PROGRESS_WAIT, ReliableActions.DEFAULT_RECORD_RETRY_BUDGET).runner();

        assertThat(throwing.run(call("c1", "order-42", "10"), failing(new ActionRejectedException("no"))))
                .isInstanceOf(ActionOutcome.Unknown.class);
        assertThat(silent.run(call("c2", "order-42", "10"), failing(new ActionRejectedException("no"))))
                .isInstanceOf(ActionOutcome.Unknown.class);
    }

    // ---- side-effect marker (docs/OPEN-QUESTIONS.md, OQ8)

    @Test
    void errorBeforeTheMarker_withoutTheDeclaration_countsAsStarted_andIsUnknown() {
        ActionRunner runner = runner(ActionPolicy.atLeastOnce());

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), failing(new IOException("refused")));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Unknown.class,
                unknown -> assertThat(unknown.advice()).isEqualTo(RepeatAdvice.MAY_DUPLICATE));
        assertThat(row("c1").state()).isEqualTo(ActionState.UNKNOWN);
    }

    @Test
    void errorBeforeTheMarker_withTheDeclaration_isFailed_andAnAtMostOnceActionMayStartAgain() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce().usesSideEffectMarker());

        ActionOutcome first = runner.run(call("c1", "order-42", "10"), failing(new IOException("refused")));
        ActionRecord failed = row("c1");
        ActionOutcome second = runner.run(call("c2", "order-42", "10"), succeedingAfterMarker("refunded"));

        assertThat(first).isEqualTo(new ActionOutcome.Failed("refused", true));
        assertThat(failed.state()).isEqualTo(ActionState.FAILED);
        assertThat(failed.failureBasis()).isEqualTo(FailureBasis.DEFINITE);
        assertThat(second).isEqualTo(new ActionOutcome.Succeeded("refunded", 2));
        assertThat(effects).hasValue(2);
    }

    @Test
    void errorAfterTheMarker_withTheDeclaration_isUnknown_soAnAtMostOnceActionIsEscalated() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce().usesSideEffectMarker());

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"),
                failingAfterMarker(new IOException("read timed out")));

        assertThat(outcome).isInstanceOf(ActionOutcome.Escalated.class);
        assertThat(row("c1").state()).isEqualTo(ActionState.ESCALATED);
    }

    @Test
    void classifier_isToldWhetherTheMarkerWasPassed_onlyWhenTheToolDeclaresIt() {
        List<Boolean> declared = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> undeclared = Collections.synchronizedList(new ArrayList<>());
        ActionRunner withMarker = runner(ActionPolicy.atLeastOnce().usesSideEffectMarker()
                .failureClassifier(recordingInto(declared)));
        ActionRunner withoutMarker = actions(ActionPolicy.atLeastOnce().failureClassifier(recordingInto(undeclared))
                .key((call, args) -> ActionKey.of(SCOPE, call.toolName(), "other")),
                ReliableActions.DEFAULT_IN_PROGRESS_WAIT, ReliableActions.DEFAULT_RECORD_RETRY_BUDGET).runner();

        withMarker.run(call("c1", "order-42", "10"), failing(new IOException("refused")));
        withMarker.run(call("c2", "order-42", "10"), failingAfterMarker(new IOException("read timed out")));
        withoutMarker.run(call("c3", "order-42", "10"), failing(new IOException("refused")));
        withoutMarker.run(call("c4", "order-42", "10"), failingAfterMarker(new IOException("read timed out")));

        assertThat(declared).containsExactly(false, true);
        assertThat(undeclared).containsExactly(true, true);
    }

    @Test
    void effectInterruptedWhileWaiting_keepsTheInterruptFlag() {
        ActionRunner runner = runner(ActionPolicy.atLeastOnce());

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), failing(new InterruptedException("stop")));
        boolean interruptedAfterRun = Thread.interrupted();

        assertThat(outcome).isInstanceOf(ActionOutcome.Unknown.class);
        assertThat(interruptedAfterRun).isTrue();
        assertThat(row("c1").state()).isEqualTo(ActionState.UNKNOWN);
    }

    @Test
    void numberOutOfRange_failsAsInvalidArguments_withoutClaiming() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());

        ActionOutcome outcome = runner.run(call("c1", Map.of("orderId", "order-42",
                "amount", new BigDecimal("100E+2147483647"))), succeeding("refunded"));

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Failed.class,
                failed -> assertThat(failed.error()).contains("out of range"));
        assertThat(journal.claims).hasValue(0);
    }

    @Test
    void indeterminateErrorWithANulCharacter_atMostOnce_isStillEscalatedInTheJournal() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), failing(new IOException("bad\u0000reply")));

        assertThat(outcome).isInstanceOf(ActionOutcome.Escalated.class);
        assertThat(row("c1").state()).isEqualTo(ActionState.ESCALATED);
        assertThat(row("c1").lastError()).contains("bad\uFFFDreply");
    }

    @Test
    void resultWithNulCharacters_isRecordedWithReplacementCharacters_andReturnedUnchanged() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());

        ActionOutcome outcome = runner.run(call("c1", "order-42", "10"), context -> "a\u0000b");

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("a\u0000b", 1));
        assertThat(row("c1").resultText()).isEqualTo("a\uFFFDb");
        assertThat(journal.recordCalls).hasValue(1);
    }

    @Test
    void startedRenewer_renewsOnItsOwn_untilClosed() {
        ReliableActions fast = ReliableActions.builder(journal).tool("refund",
                ActionPolicy.atMostOnce().key(BY_ORDER).maxEffectDuration(MAX_EFFECT)).lease(Duration.ofMillis(40))
                .build();
        fast.leaseRenewer().start();
        try {
            ActionOutcome outcome = fast.runner().run(call("c1", "order-42", "10"), context -> {
                int before = journal.renewCalls.get();
                eventually(() -> journal.renewCalls.get() >= before + 2);
                return "refunded";
            });
            assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        } finally {
            fast.leaseRenewer().close();
        }
    }

    @Test
    void manyConcurrentCallsOfOneAction_performItExactlyOnce() throws Exception {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());
        int callers = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<ActionOutcome>> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            for (int i = 0; i < callers; i++) {
                String callId = "c" + i;
                outcomes.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return runner.run(call(callId, "order-42", "10"), succeeding("refunded"));
                }, pool));
            }
            start.countDown();
            for (CompletableFuture<ActionOutcome> outcome : outcomes) {
                assertThat(outcome.get(20, TimeUnit.SECONDS)).isInstanceOfAny(ActionOutcome.Succeeded.class,
                        ActionOutcome.Replayed.class, ActionOutcome.InProgressElsewhere.class);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(effects).hasValue(1);
        assertThat(outcomes.stream().filter(f -> f.join() instanceof ActionOutcome.Succeeded)).hasSize(1);
    }

    // ---- inspect mode (memory repair, docs/DESIGN.md §6)

    @Test
    void inspect_ofACallIdThatNeverClaimed_saysYouMayCallAgain_andClaimsNothing() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());

        ActionOutcome outcome = runner.inspect(call("c1", "order-42", "10"));

        assertThat(outcome).isEqualTo(new ActionOutcome.Failed(ActionRunner.NOT_STARTED, true));
        assertThat(journal.claims).hasValue(0);
    }

    @Test
    void inspect_ofASucceededAction_isTheRecordedResult() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());
        runner.run(call("c1", "order-42", "10"), succeeding("refunded"));

        assertThat(runner.inspect(call("c1", "order-42", "10")))
                .isInstanceOfSatisfying(ActionOutcome.Replayed.class,
                        replayed -> assertThat(replayed.resultText()).isEqualTo("refunded"));
        assertThat(effects).hasValue(1);
    }

    @Test
    void inspect_whereARealCallWouldRetry_saysWhetherRepeatingIsSafe_andStartsNothing() {
        ActionRunner idempotent = runner(ActionPolicy.idempotentDownstream(Duration.ofHours(1)));
        idempotent.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        ActionRunner atLeastOnce = runner(ActionPolicy.atLeastOnce()
                .key((call, args) -> ActionKey.of(SCOPE, call.toolName(), "other")));
        atLeastOnce.run(call("c2", "order-42", "10"), failing(new IOException("reset")));

        ActionOutcome safe = idempotent.inspect(call("c1", "order-42", "10"));
        ActionOutcome mayDuplicate = atLeastOnce.inspect(call("c2", "order-42", "10"));

        assertThat(safe).isInstanceOfSatisfying(ActionOutcome.Unknown.class,
                unknown -> assertThat(unknown.advice()).isEqualTo(RepeatAdvice.SAFE));
        assertThat(mayDuplicate).isInstanceOfSatisfying(ActionOutcome.Unknown.class,
                unknown -> assertThat(unknown.advice()).isEqualTo(RepeatAdvice.MAY_DUPLICATE));
        assertThat(row("c1").state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(row("c1").attempt()).isEqualTo(1);
        assertThat(effects).hasValue(2);
    }

    @Test
    void inspect_ofADeadAttempt_movesItToUnknown_andAtMostOnceIsEscalated() {
        ActionRunner idempotent = runner(ActionPolicy.idempotentDownstream(Duration.ofHours(1)));
        crash(idempotent, "c1", "order-42");
        ActionRunner atMostOnce = runner(ActionPolicy.atMostOnce()
                .key((call, args) -> ActionKey.of(SCOPE, call.toolName(), "other")));
        crash(atMostOnce, "c2", "order-42");
        clock.advance(LEASE.plusMillis(1));

        ActionOutcome safe = idempotent.inspect(call("c1", "order-42", "10"));
        ActionOutcome escalated = atMostOnce.inspect(call("c2", "order-42", "10"));

        assertThat(safe).isInstanceOfSatisfying(ActionOutcome.Unknown.class,
                unknown -> assertThat(unknown.advice()).isEqualTo(RepeatAdvice.SAFE));
        assertThat(row("c1").state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(escalated).isInstanceOf(ActionOutcome.Escalated.class);
        assertThat(row("c2").state()).isEqualTo(ActionState.ESCALATED);
        assertThat(effects).hasValue(2);
    }

    @Test
    void inspect_ofAnAttemptWithALiveLease_isPending_withoutWaiting() {
        ActionRunner runner = runner(ActionPolicy.atLeastOnce(), Duration.ofSeconds(20));
        crash(runner, "c1", "order-42");

        long started = System.nanoTime();
        ActionOutcome outcome = runner.inspect(call("c1", "order-42", "10"));

        assertThat(outcome).isInstanceOf(ActionOutcome.InProgressElsewhere.class);
        assertThat(System.nanoTime() - started).as("no in-progress wait").isLessThan(TimeUnit.SECONDS.toNanos(5));
        assertThat(row("c1").state()).isEqualTo(ActionState.EXECUTING);
    }

    @Test
    void inspect_ofADefinitelyFailedAttempt_saysYouMayCallAgain_andLeavesTheRow() {
        ActionRunner runner = runner(ActionPolicy.atMostOnce());
        runner.run(call("c1", "order-42", "10"), failing(new ActionRejectedException("declined")));

        assertThat(runner.inspect(call("c1", "order-42", "10")))
                .isEqualTo(new ActionOutcome.Failed(ActionRunner.NOT_STARTED, true));
        assertThat(row("c1").state()).isEqualTo(ActionState.FAILED);
        assertThat(row("c1").attempt()).isEqualTo(1);
    }

    @Test
    void inspect_resolvesADueLookup_likeARealCall() {
        ActionRunner runner = runner(ActionPolicy.reconcilable((key, idempotencyKey) ->
                new LookupResult.Found("refunded r-1")));
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        clock.advance(MAX_EFFECT.plusMillis(1));

        assertThat(runner.inspect(call("c1", "order-42", "10")))
                .isInstanceOfSatisfying(ActionOutcome.Replayed.class, replayed -> {
                    assertThat(replayed.resultText()).isEqualTo("refunded r-1");
                    assertThat(replayed.fromLookup()).isTrue();
                });
        assertThat(row("c1").state()).isEqualTo(ActionState.SUCCEEDED);
    }

    @Test
    void inspect_ofACallIdSupersededByARegeneratedCall_getsTheActionsAnswer() {
        ActionRunner runner = runner(ActionPolicy.atLeastOnce());
        runner.run(call("c1", "order-42", "10"), failing(new IOException("reset")));
        runner.run(call("c2", "order-42", "10"), succeeding("refunded on retry"));

        assertThat(runner.inspect(call("c1", "order-42", "10")))
                .isInstanceOfSatisfying(ActionOutcome.Replayed.class,
                        replayed -> assertThat(replayed.resultText()).isEqualTo("refunded on retry"));
    }

    @Test
    void inspect_ofACallThatClaimedAnotherKeyThanItsArgumentsGiveNow_refusesToAnswer() {
        runner(ActionPolicy.atMostOnce()).run(call("c1", "order-42", "10"), succeeding("refunded"));
        ActionRunner changedKey = runner(ActionPolicy.atMostOnce()
                .key((call, args) -> ActionKey.of(SCOPE, "refund-v2", args.string("orderId"))));

        assertThatThrownBy(() -> changedKey.inspect(call("c1", "order-42", "10")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("key strategy");
    }

    @Test
    void inspect_needsTheProviderCallId() {
        ActionRunner runner = runner(ActionPolicy.atLeastOnce());
        Map<String, Object> arguments = Map.of("orderId", "order-42", "amount", new BigDecimal("10"));

        assertThatThrownBy(() -> runner.inspect(new ToolCall("refund", "{}", arguments, null, SCOPE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers

    private ActionRunner runner(JournaledPolicy<?> policy) {
        return runner(policy, ReliableActions.DEFAULT_IN_PROGRESS_WAIT);
    }

    private ActionRunner runner(JournaledPolicy<?> policy, Duration inProgressWait) {
        return runner(policy, inProgressWait, ReliableActions.DEFAULT_RECORD_RETRY_BUDGET);
    }

    private ActionRunner runner(JournaledPolicy<?> policy, Duration inProgressWait, Duration recordRetryBudget) {
        return actions(policy, inProgressWait, recordRetryBudget).runner();
    }

    private ReliableActions actions(JournaledPolicy<?> policy, Duration inProgressWait, Duration recordRetryBudget) {
        JournaledPolicy<?> complete = policy.keyStrategy().isPresent() ? policy : policy.key(BY_ORDER);
        if (complete.maxEffectDuration().isEmpty()) {
            complete = complete.maxEffectDuration(MAX_EFFECT);
        }
        return ReliableActions.builder(journal).tool("refund", complete).lease(LEASE)
                .inProgressWait(inProgressWait).recordRetryBudget(recordRetryBudget).build();
    }

    private static ToolCall call(String callId, String orderId, String amount) {
        return call(callId, Map.of("orderId", orderId, "amount", new BigDecimal(amount)));
    }

    private static ToolCall call(String callId, Map<String, ?> arguments) {
        return new ToolCall("refund", arguments.toString(), arguments, callId, SCOPE);
    }

    private static byte[] material(String orderId, String amount) {
        return new ArgumentsHasher().hash(Map.of("orderId", orderId, "amount", new BigDecimal(amount)),
                Set.of()).material();
    }

    private Effect succeeding(String resultText) {
        return context -> {
            effects.incrementAndGet();
            idempotencyKeys.add(context.idempotencyKey());
            return resultText;
        };
    }

    private Effect failing(Exception error) {
        return context -> {
            effects.incrementAndGet();
            idempotencyKeys.add(context.idempotencyKey());
            throw error;
        };
    }

    private Effect failingAfterMarker(Exception error) {
        return context -> {
            effects.incrementAndGet();
            context.beforeSideEffect();
            throw error;
        };
    }

    private Effect succeedingAfterMarker(String resultText) {
        return context -> {
            effects.incrementAndGet();
            context.beforeSideEffect();
            return resultText;
        };
    }

    private static FailureClassifier recordingInto(List<Boolean> effectStarted) {
        return (error, started) -> {
            effectStarted.add(started);
            return FailureKind.INDETERMINATE;
        };
    }

    private Effect blocking(CountDownLatch running, CountDownLatch release, String resultText) {
        return context -> {
            effects.incrementAndGet();
            running.countDown();
            await(release);
            return resultText;
        };
    }

    private void crash(ActionRunner runner, String callId, String orderId) {
        try {
            runner.run(call(callId, orderId, "10"), context -> {
                effects.incrementAndGet();
                throw new SimulatedCrash();
            });
        } catch (SimulatedCrash expected) {
            // an Error is not handled: the row stays EXECUTING, as after a crash
        }
    }

    private ActionRecord row(String callId) {
        return journal.findByProviderCallId(ActionKey.of(SCOPE, "refund").scopeHash(), callId).orElseThrow().action();
    }

    private Instant clockNow() {
        return clock.instant();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("latch released in time").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void eventually(BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition reached in time").isLessThan(end);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5)); // polling interval, not synchronization
        }
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }
}
