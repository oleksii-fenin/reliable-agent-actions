package io.github.oleksiifenin.raa.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.CallRecord;
import io.github.oleksiifenin.raa.storage.ClaimDecider;
import io.github.oleksiifenin.raa.storage.ClaimDecision;
import io.github.oleksiifenin.raa.storage.ClaimRequest;
import io.github.oleksiifenin.raa.storage.ClaimResult;
import io.github.oleksiifenin.raa.storage.ClaimVerdict;
import io.github.oleksiifenin.raa.storage.FailureBasis;
import io.github.oleksiifenin.raa.storage.FailureKind;
import io.github.oleksiifenin.raa.storage.JournalException;
import io.github.oleksiifenin.raa.storage.JournalWrite;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import io.github.oleksiifenin.raa.storage.Resolution;
import io.github.oleksiifenin.raa.tck.ActionJournalTck;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Runs storage conformance and deterministic clock checks against the in-memory journal. */
class InMemoryActionJournalTckTest extends ActionJournalTck {

    private final InMemoryActionJournal journal = new InMemoryActionJournal();

    @Override
    protected ActionJournal journal() {
        return journal;
    }

    @Override
    protected void awaitTransactionsWaitingForLock(int count) {
        eventually(() -> journal.transactionsWaitingForLock() >= count);
    }

    @Test
    void injectedClockAndIntervals_useMicrosecondsAndStrictLeaseBoundaries() {
        MutableClock clock = new MutableClock();
        InMemoryActionJournal storage = new InMemoryActionJournal(clock);
        ClaimRequest request = new ClaimRequest(newKey(), newKey(), "readable", "tool", "policy", newKey(),
                newKey(), null, "call", "owner", Duration.ofNanos(1), Duration.ofNanos(2001),
                Duration.ofNanos(3001));
        ActionRecord row = storage.claim(request, start(1)).record();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        assertThat(row.createdAt()).isEqualTo(now);
        assertThat(row.startedAt()).isEqualTo(now);
        assertThat(row.updatedAt()).isEqualTo(now);
        assertThat(row.leaseUntil()).isEqualTo(now.plusNanos(1000));
        assertThat(row.maxEffectDuration()).isEqualTo(Duration.ofNanos(3000));
        assertThat(row.deadlineAt()).isEqualTo(now.plusNanos(4000));
        clock.set(row.leaseUntil());
        assertThat(storage.markUnknownIfLeaseExpired(request.keyHash(), 1, "expired")).isFalse();
        assertThat(storage.renewLeases("owner", List.of(new KeyAttempt(request.keyHash(), 1),
                new KeyAttempt(request.keyHash().clone(), 1)), Duration.ofNanos(1))).isEqualTo(1);
        assertThat(snapshot(storage, request).updatedAt()).isEqualTo(now);
        clock.set(now.plusNanos(3000));
        assertThat(storage.renewLeases("owner", List.of(new KeyAttempt(request.keyHash(), 1)), LONG_LEASE)).isZero();
        assertThat(storage.markUnknownIfLeaseExpired(request.keyHash(), 1, "expired")).isTrue();
    }

    @Test
    void nextAttempt_replacesAllAttemptFieldsButPreservesIdentityAndOriginalDeadline() {
        MutableClock clock = new MutableClock();
        InMemoryActionJournal storage = new InMemoryActionJournal(clock);
        ClaimRequest first = richRequest(newKey(), newKey(), "original", "call", "{\"a\":1}");
        ActionRecord original = storage.claim(first, start(1)).record();
        storage.recordFailure(first.keyHash(), 1, FailureKind.DEFINITE, "error");
        storage.claim(first, fixed(ClaimVerdict.NEEDS_LOOKUP, new JournalWrite.ChangeState(ActionState.UNKNOWN)));
        storage.resolve(first.keyHash(), 1, new Resolution.LookupUnreachable(Duration.ofSeconds(1)));
        clock.set(clock.instant().plusSeconds(1));
        ClaimRequest next = new ClaimRequest(first.keyHash().clone(), newKey(), null, "new-tool", "new-policy",
                newKey(), newKey(), "[false]", "call", "new-owner", Duration.ofSeconds(3),
                Duration.ofSeconds(7), Duration.ofDays(1));

        ActionRecord row = storage.claim(next, start(2)).record();

        assertThat(row.keyText()).isEqualTo("original");
        assertThat(row.keyHash()).isEqualTo(first.keyHash());
        assertThat(row.scopeHash()).isEqualTo(first.scopeHash());
        assertThat(row.createdAt()).isEqualTo(original.createdAt());
        assertThat(row.deadlineAt()).isEqualTo(original.deadlineAt());
        assertThat(row.toolName()).isEqualTo(next.toolName());
        assertThat(row.policy()).isEqualTo(next.policy());
        assertThat(row.leaseOwner()).isEqualTo(next.leaseOwner());
        assertThat(row.maxEffectDuration()).isEqualTo(next.maxEffectDuration());
        assertThat(row.leaseUntil()).isEqualTo(row.startedAt().plus(next.lease()));
        assertThat(row.startedAt()).isEqualTo(row.updatedAt()).isAfter(original.startedAt());
        assertThat(row.allArgsHash()).isEqualTo(next.allArgsHash());
        assertThat(row.materialArgsHash()).isEqualTo(next.materialArgsHash());
        assertThat(row.argumentsJson()).isEqualTo("[false]");
        assertThat(row.lookupFailures()).isZero();
        assertThat(row.resultText()).isNull();
        assertThat(row.lastError()).isNull();
        assertThat(row.failureBasis()).isNull();
        assertThat(row.resolveAfter()).isNull();
    }

    @Test
    void resolutions_preserveColumnsThatTheirUpdatesDoNotName() {
        MutableClock clock = new MutableClock();
        InMemoryActionJournal storage = new InMemoryActionJournal(clock);
        ClaimRequest request = richRequest(newKey(), newKey(), null, null, null);
        storage.claim(request, start(1));
        storage.recordFailure(request.keyHash(), 1, FailureKind.DEFINITE, "keep error");
        storage.claim(request, fixed(ClaimVerdict.NEEDS_LOOKUP, new JournalWrite.ChangeState(ActionState.UNKNOWN)));
        storage.resolve(request.keyHash(), 1, new Resolution.LookupUnreachable(Duration.ofNanos(1)));
        ActionRecord before = snapshot(storage, request);
        assertThat(before.failureBasis()).isEqualTo(FailureBasis.DEFINITE);
        assertThat(before.resolveAfter()).isEqualTo(before.updatedAt().plusNanos(1000));

        storage.resolve(request.keyHash(), 1, new Resolution.Applied("found"));

        ActionRecord after = snapshot(storage, request);
        assertThat(after.lastError()).isEqualTo("keep error");
        assertThat(after.lookupFailures()).isEqualTo(1);
        assertThat(after.failureBasis()).isNull();
        assertThat(after.resultText()).isEqualTo("found");
        assertThat(after.resolveAfter()).isNull();
        assertThat(after.startedAt()).isEqualTo(before.startedAt());

        storage.claim(request, fixed(ClaimVerdict.NEEDS_LOOKUP, new JournalWrite.ChangeState(ActionState.UNKNOWN)));
        assertThat(snapshot(storage, request).resultText()).isEqualTo("found");
        storage.resolve(request.keyHash(), 1, new Resolution.NotApplied());
        storage.resolve(request.keyHash(), 1, new Resolution.Abandon("deadline"));
        assertThat(snapshot(storage, request).failureBasis()).isEqualTo(FailureBasis.LOOKUP);
        storage.recordSuccess(request.keyHash(), 1, "late");
        ActionRecord late = snapshot(storage, request);
        assertThat(late.lastError()).isEqualTo("deadline");
        assertThat(late.lookupFailures()).isEqualTo(1);
        assertThat(late.failureBasis()).isNull();
    }

    @Test
    void callHistory_keepsTheOriginalKeyAndSeenTimeUnlessThatCallStartsAnotherAttempt() {
        MutableClock clock = new MutableClock();
        InMemoryActionJournal storage = new InMemoryActionJournal(clock);
        ClaimRequest first = richRequest(newKey(), newKey(), null, "same-call", null);
        storage.claim(first, start(1));
        CallRecord original = storage.findByProviderCallId(first.scopeHash(), "same-call").orElseThrow();
        clock.set(clock.instant().plusSeconds(1));
        ClaimRequest collision = richRequest(newKey(), first.scopeHash().clone(), null, "same-call", null);
        storage.claim(collision, start(1));
        storage.claim(first, fixed(ClaimVerdict.IN_PROGRESS, new JournalWrite.None()));
        CallRecord unchanged = storage.findByProviderCallId(first.scopeHash(), "same-call").orElseThrow();
        assertThat(unchanged.action().keyHash()).isEqualTo(first.keyHash());
        assertThat(unchanged.seenAt()).isEqualTo(original.seenAt());
        storage.recordFailure(first.keyHash(), 1, FailureKind.DEFINITE, "retry");
        storage.claim(first, start(2));
        CallRecord retried = storage.findByProviderCallId(first.scopeHash(), "same-call").orElseThrow();
        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(retried.seenAt()).isAfter(original.seenAt());
    }

    @Test
    void reconcile_ordersNullResolveTimesFirstThenUnsignedKeys_andChangesOnlyTheHold() {
        MutableClock clock = new MutableClock();
        InMemoryActionJournal storage = new InMemoryActionJournal(clock);
        byte[] low = new byte[32];
        byte[] high = new byte[32];
        high[0] = (byte) 0x80;
        ClaimRequest first = richRequest(high, newKey(), null, null, null);
        ClaimRequest second = richRequest(low, newKey(), null, null, null);
        ActionRecord highBefore = storage.claim(first, start(1)).record();
        ActionRecord lowBefore = storage.claim(second, start(1)).record();
        ClaimRequest unknown = richRequest(newKey(), newKey(), null, null, null);
        storage.claim(unknown, start(1));
        storage.recordFailure(unknown.keyHash(), 1, FailureKind.INDETERMINATE, "unknown");
        clock.set(highBefore.leaseUntil().plusNanos(1000));

        List<ActionRecord> batch = storage.takeReconcileBatch(Duration.ofNanos(1), 2);

        assertThat(batch).extracting(row -> row.keyHash()[0]).containsExactly((byte) 0, (byte) 0x80);
        assertThat(batch.get(0).updatedAt()).isEqualTo(lowBefore.updatedAt());
        assertThat(batch.get(1).updatedAt()).isEqualTo(highBefore.updatedAt());
        assertThat(batch.get(0).state()).isEqualTo(ActionState.EXECUTING);
        assertThat(batch.get(0).leaseUntil()).isEqualTo(lowBefore.leaseUntil());
        assertThat(batch.get(0).resolveAfter()).isEqualTo(clock.instant().plusNanos(1000));
        assertThat(storage.takeReconcileBatch(LONG_LEASE, 2)).singleElement()
                .satisfies(row -> assertThat(row.keyHash()).isEqualTo(unknown.keyHash()));
    }

    @Test
    void waitingOperations_readTimeAfterTheLockWait() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryActionJournal storage = new InMemoryActionJournal(clock);
        ClaimRequest request = richRequest(newKey(), newKey(), null, null, null);
        ActionRecord before = storage.claim(request, start(1)).record();
        CountDownLatch deciding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<ClaimResult> holder = async(() -> storage.claim(request, (call, row, now) -> {
            deciding.countDown();
            await(release);
            return new ClaimDecision(ClaimVerdict.IN_PROGRESS, new JournalWrite.None());
        }));
        try {
            await(deciding);
            CompletableFuture<Integer> renewal = async(() -> storage.renewLeases("owner",
                    List.of(new KeyAttempt(request.keyHash(), 1)), LONG_LEASE));
            CompletableFuture<Boolean> marking = async(() -> storage.markUnknownIfLeaseExpired(request.keyHash(),
                    1, "expired"));
            AtomicReference<Instant> decisionTime = new AtomicReference<>();
            CompletableFuture<ClaimResult> claim = async(() -> storage.claim(request, (call, row, now) -> {
                decisionTime.set(now);
                return new ClaimDecision(ClaimVerdict.IN_PROGRESS, new JournalWrite.None());
            }));
            eventually(() -> storage.transactionsWaitingForLock() == 3);
            Instant afterWait = before.startedAt().plus(request.maxEffectDuration()).plusSeconds(1);
            clock.set(afterWait);
            release.countDown();
            assertThat(renewal.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(marking.get(10, TimeUnit.SECONDS)).isTrue();
            claim.get(10, TimeUnit.SECONDS);
            assertThat(decisionTime.get()).isEqualTo(afterWait);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(storage.transactionsWaitingForLock()).isZero();
    }

    @Test
    void failedExistingDecidersAndInvalidAttemptNumbers_leaveTheRowAndHistoryUnchanged() {
        ClaimRequest request = richRequest(newKey(), newKey(), null, "call", null);
        ActionRecord original = journal.claim(request, start(1)).record();
        CallRecord history = journal.findByProviderCallId(request.scopeHash(), "call").orElseThrow();
        IllegalStateException failure = new IllegalStateException("decider failed");
        assertThatThrownBy(() -> journal.claim(request, (call, row, now) -> { throw failure; })).isSameAs(failure);
        assertThatThrownBy(() -> journal.claim(request, start(3))).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot(journal, request)).isEqualTo(original);
        assertThat(journal.findByProviderCallId(request.scopeHash(), "call")).contains(history);
        ClaimRequest missing = richRequest(newKey(), newKey(), null, "bad-call", null);
        assertThatThrownBy(() -> journal.claim(missing, start(2))).isInstanceOf(IllegalStateException.class);
        assertThat(journal.findByProviderCallId(missing.scopeHash(), "bad-call")).isEmpty();
        assertThat(journal.claim(missing, start(1)).record().attempt()).isEqualTo(1);
    }

    @Test
    void historyValidationFailure_rollsBackTheStagedAttempt() {
        ClaimRequest first = richRequest(newKey(), newKey(), null, "call", null);
        ActionRecord original = journal.claim(first, start(1)).record();
        ClaimRequest invalid = richRequest(first.keyHash(), first.scopeHash(), "replacement", "x".repeat(201), null);

        assertThatThrownBy(() -> journal.claim(invalid, start(2))).isInstanceOf(JournalException.class);

        assertThat(snapshot(journal, first)).isEqualTo(original);
        assertThat(journal.findByProviderCallId(first.scopeHash(), "call").orElseThrow().attempt()).isEqualTo(1);
        assertThat(journal.findByProviderCallId(first.scopeHash(), invalid.providerCallId())).isEmpty();
    }

    @Test
    void renewalWaitingPastMaximumDuration_renewsNothingEvenWithoutACompetingStateChange() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryActionJournal storage = new InMemoryActionJournal(clock);
        ClaimRequest request = richRequest(newKey(), newKey(), null, null, null);
        ActionRecord before = storage.claim(request, start(1)).record();
        CountDownLatch deciding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<ClaimResult> holder = async(() -> storage.claim(request, (call, row, now) -> {
            deciding.countDown();
            await(release);
            return new ClaimDecision(ClaimVerdict.IN_PROGRESS, new JournalWrite.None());
        }));
        try {
            await(deciding);
            CompletableFuture<Integer> renewal = async(() -> storage.renewLeases("owner",
                    List.of(new KeyAttempt(request.keyHash(), 1)), LONG_LEASE));
            eventually(() -> storage.transactionsWaitingForLock() == 1);
            clock.set(before.startedAt().plus(request.maxEffectDuration()));
            release.countDown();
            assertThat(renewal.get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(snapshot(storage, request).leaseUntil()).isEqualTo(before.leaseUntil());
        assertThat(snapshot(storage, request).state()).isEqualTo(ActionState.EXECUTING);
    }

    @Test
    void resolveWaitingForTheLock_setsBackoffFromTheTimeAfterTheWait() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryActionJournal storage = new InMemoryActionJournal(clock);
        ClaimRequest request = richRequest(newKey(), newKey(), null, null, null);
        ActionRecord original = storage.claim(request, start(1)).record();
        storage.recordFailure(request.keyHash(), 1, FailureKind.INDETERMINATE, "unknown");
        CountDownLatch deciding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<ClaimResult> holder = async(() -> storage.claim(request, (call, row, now) -> {
            deciding.countDown();
            await(release);
            return new ClaimDecision(ClaimVerdict.ASK_LATER, new JournalWrite.None());
        }));
        Duration backoff = Duration.ofSeconds(5);
        Instant afterWait = original.startedAt().plusSeconds(30);
        try {
            await(deciding);
            CompletableFuture<Boolean> resolution = async(() -> storage.resolve(request.keyHash(), 1,
                    new Resolution.LookupUnreachable(backoff)));
            eventually(() -> storage.transactionsWaitingForLock() == 1);
            clock.set(afterWait);
            release.countDown();
            assertThat(resolution.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        ActionRecord resolved = snapshot(storage, request);
        assertThat(resolved.updatedAt()).isEqualTo(afterWait);
        assertThat(resolved.resolveAfter()).isEqualTo(afterWait.plus(backoff));
    }

    @Test
    void reconcileSamplesOneTimeForAllRowsEvenWhenTheClockAdvancesOnEveryRead() {
        MutableClock clock = new MutableClock();
        InMemoryActionJournal storage = new InMemoryActionJournal(clock);
        ClaimRequest first = richRequest(newKey(), newKey(), null, null, null);
        ClaimRequest second = richRequest(newKey(), newKey(), null, null, null);
        ActionRecord original = storage.claim(first, start(1)).record();
        storage.claim(second, start(1));
        Instant batchTime = original.leaseUntil().plusSeconds(1);
        clock.set(batchTime);
        clock.tick(Duration.ofNanos(1000));

        List<ActionRecord> batch = storage.takeReconcileBatch(LONG_LEASE, 2);

        assertThat(batch).hasSize(2).allSatisfy(row ->
                assertThat(row.resolveAfter()).isEqualTo(batchTime.plus(LONG_LEASE)));
    }

    @Test
    void claimSamplesWriteTimeAgainAfterTheDeciderReturns() {
        MutableClock clock = new MutableClock();
        InMemoryActionJournal storage = new InMemoryActionJournal(clock);
        ClaimRequest request = richRequest(newKey(), newKey(), null, null, null);
        Instant writeTime = clock.instant().plusSeconds(10).truncatedTo(ChronoUnit.MICROS);

        ActionRecord row = storage.claim(request, (call, current, now) -> {
            assertThat(now).isBefore(writeTime);
            clock.set(writeTime);
            return new ClaimDecision(ClaimVerdict.START, new JournalWrite.StartAttempt(1));
        }).record();

        assertThat(row.startedAt()).isEqualTo(writeTime);
        assertThat(row.leaseUntil()).isEqualTo(writeTime.plus(request.lease()));
    }

    @Test
    void argumentsJson_usesJsonbTextSemanticsAndRejectsInvalidInputWithoutWriting() {
        String input = " {\"long\":1e2,\"a\":0,\"a\":[true,null,{\"x\":\"\\u0061\\n\"}],\"bb\":-0.50} ";
        ClaimRequest request = richRequest(newKey(), newKey(), null, "call", input);
        ActionRecord row = journal.claim(request, start(1)).record();
        assertThat(row.argumentsJson()).isEqualTo("{\"a\": [true, null, {\"x\": \"a\\n\"}], \"bb\": -0.50, \"long\": 100}");
        List<String> inputs = List.of("null", "\"\\ud83d\\ude00\"", "1.230e-5", "{\"é\":1,\"bb\":2,\"a\":3}");
        List<String> outputs = List.of("null", "\"😀\"", "0.00001230", "{\"a\": 3, \"bb\": 2, \"é\": 1}");
        for (int index = 0; index < inputs.size(); index++) {
            ClaimRequest valid = richRequest(newKey(), newKey(), null, null, inputs.get(index));
            assertThat(journal.claim(valid, start(1)).record().argumentsJson()).isEqualTo(outputs.get(index));
        }
        ClaimRequest ignored = richRequest(request.keyHash(), request.scopeHash(), null, null, "invalid JSON");
        assertThat(snapshot(journal, ignored).argumentsJson()).isEqualTo(row.argumentsJson());
        for (String invalid : List.of("{", "[1,]", "01", "true false", "\"\\u0000\"", "\"\\ud800\"")) {
            ClaimRequest bad = richRequest(newKey(), newKey(), null, "bad-call", invalid);
            assertThatThrownBy(() -> journal.claim(bad, start(1))).isInstanceOf(JournalException.class);
            assertThat(journal.findByProviderCallId(bad.scopeHash(), "bad-call")).isEmpty();
        }
    }

    private static ClaimRequest richRequest(byte[] key, byte[] scope, String keyText, String callId, String json) {
        return new ClaimRequest(key, scope, keyText, "tool", "policy", newKey(), newKey(), json, callId,
                "owner", Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMinutes(1));
    }

    private static ClaimDecider start(int attempt) {
        return (request, row, now) -> new ClaimDecision(ClaimVerdict.START, new JournalWrite.StartAttempt(attempt));
    }

    private static ActionRecord snapshot(InMemoryActionJournal storage, ClaimRequest request) {
        return storage.claim(request, fixed(ClaimVerdict.IN_PROGRESS, new JournalWrite.None())).record();
    }

    private static <T> CompletableFuture<T> async(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, runnable -> {
            Thread thread = new Thread(runnable, "memory-journal-test");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("latch was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-11-11T00:00:00.123456789Z"));
        private volatile Duration tick = Duration.ZERO;

        void set(Instant instant) {
            time.set(instant);
        }

        void tick(Duration duration) {
            tick = duration;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return time.getAndUpdate(current -> current.plus(tick));
        }
    }
}
