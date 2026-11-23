package io.github.oleksiifenin.raa.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.DecisionTable;
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
import io.github.oleksiifenin.raa.storage.JournalWrite;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import io.github.oleksiifenin.raa.storage.ManualResolution;
import io.github.oleksiifenin.raa.storage.RecordOutcome;
import io.github.oleksiifenin.raa.storage.RecordResult;
import io.github.oleksiifenin.raa.storage.Resolution;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Conformance suite for {@link ActionJournal} implementations (docs/DESIGN.md §4, §9). A storage module extends this
 * class in its tests and supplies a journal on a running, empty-schema database; JUnit runs every inherited test.
 *
 * <p>Tests use real database time with short leases and wait by polling with a timeout, never by fixed sleeps. Keys
 * are random per test, so the database may be shared across tests and need not be emptied. The retention tests delete
 * every finished row older than a few hundred milliseconds, so the database must not be shared with tests that run at
 * the same time and expect their finished rows to stay.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public abstract class ActionJournalTck {

    /** Lease that does not expire during a test. */
    protected static final Duration LONG_LEASE = Duration.ofMinutes(5);
    /** Lease that expires quickly. */
    protected static final Duration SHORT_LEASE = Duration.ofMillis(200);
    /** Upper bound for every poll. */
    protected static final Duration POLL_TIMEOUT = Duration.ofSeconds(15);

    /** For subclasses; a storage module's test class extends this suite. */
    protected ActionJournalTck() {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String OWNER = "tck-owner";
    private static final byte[] MATERIAL = hash();
    private static final byte[] ALL_ARGS = hash();

    /**
     * Returns the journal under test. Called once per test; may return the same instance.
     *
     * @return the journal
     */
    protected abstract ActionJournal journal();

    /**
     * Blocks until at least {@code count} other transactions are waiting for a row lock held by the test, or fails
     * after a timeout. Used to prove that a claim waits for a concurrent claim instead of deciding on a stale row.
     *
     * @param count number of waiting transactions
     */
    protected abstract void awaitTransactionsWaitingForLock(int count);

    // ---- claim

    @Test
    void claim_withoutRow_insertsAttemptOneWithDatabaseTimes() {
        ClaimRequest request = request(newKey(), "call-1", LONG_LEASE);

        ClaimResult result = journal().claim(request, table());

        assertThat(result.decision().verdict()).isEqualTo(ClaimVerdict.START);
        ActionRecord row = result.record();
        assertThat(row.keyHash()).isEqualTo(request.keyHash());
        assertThat(row.state()).isEqualTo(ActionState.EXECUTING);
        assertThat(row.attempt()).isEqualTo(1);
        assertThat(row.leaseOwner()).isEqualTo(OWNER);
        assertThat(row.toolName()).isEqualTo("refund");
        assertThat(row.policy()).isEqualTo("AT_LEAST_ONCE");
        assertThat(row.maxEffectDuration()).isEqualTo(request.maxEffectDuration());
        assertThat(row.startedAt()).isEqualTo(row.createdAt()).isEqualTo(row.updatedAt());
        assertThat(row.leaseUntil()).isEqualTo(row.startedAt().plus(LONG_LEASE));
        assertThat(row.deadlineAt()).isEqualTo(row.createdAt().plus(request.deadline()));
        assertThat(row.materialArgsHash()).isEqualTo(request.materialArgsHash());
        assertThat(row.allArgsHash()).isEqualTo(request.allArgsHash());
        assertThat(row.scopeHash()).isEqualTo(request.scopeHash());
        assertThat(row.resolveAfter()).isNull();
        assertThat(row.lookupFailures()).isZero();
        assertThat(row.failureBasis()).isNull();
        assertThat(row.resultText()).isNull();
    }

    @Test
    void claim_passesTheLockedRowAndDatabaseTimeToTheDecider() {
        byte[] key = newKey();
        RecordingDecider first = new RecordingDecider(table());
        ActionRecord created = journal().claim(request(key, null, LONG_LEASE), first).record();
        RecordingDecider second = new RecordingDecider(table());

        ClaimResult again = journal().claim(request(key, null, LONG_LEASE), second);

        assertThat(first.rowsSeen).containsExactly(Optional.empty());
        assertThat(second.rowsSeen).hasSize(1);
        assertThat(second.rowsSeen.get(0)).hasValueSatisfying(row -> assertThat(row.attempt()).isEqualTo(1));
        assertThat(second.timesSeen.get(0)).isAfterOrEqualTo(created.updatedAt());
        assertThat(again.decision().verdict()).isEqualTo(ClaimVerdict.IN_PROGRESS);
        assertThat(again.record().updatedAt()).isEqualTo(created.updatedAt());
    }

    @Test
    void claim_startsTheNextAttempt_replacingAttemptColumns_butKeepingTheDeadline() {
        byte[] key = newKey();
        ActionRecord first = journal().claim(request(key, null, LONG_LEASE), table()).record();
        journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "invalid amount");
        ClaimRequest corrected = request(key, null, LONG_LEASE, hash(), hash());

        ClaimResult second = journal().claim(corrected, table());

        assertThat(second.decision().write()).isEqualTo(new JournalWrite.StartAttempt(2));
        ActionRecord row = second.record();
        assertThat(row.state()).isEqualTo(ActionState.EXECUTING);
        assertThat(row.attempt()).isEqualTo(2);
        assertThat(row.createdAt()).isEqualTo(first.createdAt());
        assertThat(row.deadlineAt()).isEqualTo(first.deadlineAt());
        assertThat(row.startedAt()).isAfterOrEqualTo(first.startedAt());
        assertThat(row.leaseUntil()).isEqualTo(row.startedAt().plus(LONG_LEASE));
        assertThat(row.materialArgsHash()).isEqualTo(corrected.materialArgsHash());
        assertThat(row.allArgsHash()).isEqualTo(corrected.allArgsHash());
        assertThat(row.failureBasis()).isNull();
        assertThat(row.lastError()).isNull();
        assertThat(row.resolveAfter()).isNull();
    }

    @Test
    void claim_changeStateToUnknown_clearsTheLeaseAndSetsResolveAfter() {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());

        ActionRecord row = journal().claim(request(key, null, LONG_LEASE),
                fixed(ClaimVerdict.NEEDS_LOOKUP, new JournalWrite.ChangeState(ActionState.UNKNOWN))).record();

        assertThat(row.state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(row.leaseOwner()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.resolveAfter()).isEqualTo(row.updatedAt());
        assertThat(row.attempt()).isEqualTo(1);
    }

    @Test
    void claim_changeStateToEscalatedOrAbandoned_clearsResolveAfter() {
        byte[] escalatedKey = newKey();
        byte[] abandonedKey = newKey();
        moveToUnknown(escalatedKey);
        journal().claim(request(abandonedKey, null, LONG_LEASE), table());
        journal().recordFailure(at(abandonedKey, 1), FailureKind.DEFINITE, "no");

        ActionRecord escalated = journal().claim(request(escalatedKey, null, LONG_LEASE),
                fixed(ClaimVerdict.ESCALATED, new JournalWrite.ChangeState(ActionState.ESCALATED))).record();
        ActionRecord abandoned = journal().claim(request(abandonedKey, null, LONG_LEASE),
                fixed(ClaimVerdict.ABANDONED, new JournalWrite.ChangeState(ActionState.ABANDONED))).record();

        assertThat(escalated.state()).isEqualTo(ActionState.ESCALATED);
        assertThat(escalated.resolveAfter()).isNull();
        assertThat(abandoned.state()).isEqualTo(ActionState.ABANDONED);
        assertThat(abandoned.resolveAfter()).isNull();
    }

    @Test
    void claim_rollsBackWhenTheDeciderFails() {
        byte[] key = newKey();
        IllegalStateException failure = new IllegalStateException("decider bug");

        assertThatThrownBy(() -> journal().claim(request(key, "call-1", LONG_LEASE), (request, row, now) -> {
            throw failure;
        })).isSameAs(failure);

        RecordingDecider next = new RecordingDecider(table());
        journal().claim(request(key, null, LONG_LEASE), next);
        assertThat(next.rowsSeen).containsExactly(Optional.empty());
        assertThat(journal().findByProviderCallId(scope(), "call-1")).isEmpty();
    }

    @Test
    void claim_twoFirstClaimsAtOnce_startExactlyOneAttempt_andTheOtherDecidesAgainOnTheRow() throws Exception {
        byte[] key = newKey();
        CyclicBarrier bothSawNoRow = new CyclicBarrier(2);
        RecordingDecider a = new RecordingDecider(waitOnEmpty(bothSawNoRow));
        RecordingDecider b = new RecordingDecider(waitOnEmpty(bothSawNoRow));

        CompletableFuture<ClaimResult> first = runAsync(() -> journal().claim(request(key, null, LONG_LEASE), a));
        CompletableFuture<ClaimResult> second = runAsync(() -> journal().claim(request(key, null, LONG_LEASE), b));
        List<ClaimVerdict> verdicts = List.of(first.get(30, TimeUnit.SECONDS).decision().verdict(),
                second.get(30, TimeUnit.SECONDS).decision().verdict());

        assertThat(verdicts).containsExactlyInAnyOrder(ClaimVerdict.START, ClaimVerdict.IN_PROGRESS);
        RecordingDecider loser = verdicts.get(0) == ClaimVerdict.START ? b : a;
        assertThat(loser.rowsSeen).hasSize(2);
        assertThat(loser.rowsSeen.get(0)).isEmpty();
        assertThat(loser.rowsSeen.get(1)).isPresent();
    }

    @Test
    void claim_waitsForTheRowLockOfARunningClaim_andDecidesOnItsCommittedWrite() throws Exception {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());
        CountDownLatch holderDeciding = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        ClaimDecider holder = (request, row, now) -> {
            holderDeciding.countDown();
            await(releaseHolder);
            return new ClaimDecision(ClaimVerdict.NEEDS_LOOKUP, new JournalWrite.ChangeState(ActionState.UNKNOWN));
        };
        RecordingDecider waiter = new RecordingDecider(fixed(ClaimVerdict.ASK_LATER, new JournalWrite.None()));

        CompletableFuture<ClaimResult> held = runAsync(() -> journal().claim(request(key, null, LONG_LEASE), holder));
        await(holderDeciding);
        CompletableFuture<ClaimResult> waiting =
                runAsync(() -> journal().claim(request(key, null, LONG_LEASE), waiter));
        awaitTransactionsWaitingForLock(1);
        assertThat(waiter.rowsSeen).isEmpty();
        releaseHolder.countDown();
        held.get(30, TimeUnit.SECONDS);
        waiting.get(30, TimeUnit.SECONDS);

        assertThat(waiter.rowsSeen).hasSize(1);
        assertThat(waiter.rowsSeen.get(0)).hasValueSatisfying(
                row -> assertThat(row.state()).isEqualTo(ActionState.UNKNOWN));
    }

    @Test
    void claim_writesCallHistory_mappingEveryCallIdToTheAction() {
        byte[] key = newKey();
        journal().claim(request(key, "call-1", LONG_LEASE), table());
        journal().claim(request(key, "call-2", LONG_LEASE), table()); // in progress: mapped to attempt 1
        journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "no");
        journal().claim(request(key, "call-3", LONG_LEASE), table()); // starts attempt 2

        CallRecord first = journal().findByProviderCallId(scope(), "call-1").orElseThrow();
        CallRecord second = journal().findByProviderCallId(scope(), "call-2").orElseThrow();
        CallRecord third = journal().findByProviderCallId(scope(), "call-3").orElseThrow();

        assertThat(first.attempt()).isEqualTo(1);
        assertThat(second.attempt()).isEqualTo(1);
        assertThat(third.attempt()).isEqualTo(2);
        assertThat(first.action().keyHash()).isEqualTo(key);
        assertThat(first.action().attempt()).isEqualTo(2);
        assertThat(first.providerCallId()).isEqualTo("call-1");
        assertThat(journal().findByProviderCallId(scope(), "call-unknown")).isEmpty();
        assertThat(journal().findByProviderCallId(hash(), "call-1")).isEmpty();
    }

    @Test
    void claim_callHistory_updatesTheAttemptOnlyWhenTheSameCallStartsOne() {
        byte[] key = newKey();
        journal().claim(request(key, "call-1", LONG_LEASE), table());
        journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "no");

        journal().claim(request(key, "call-1", LONG_LEASE), table());

        assertThat(journal().findByProviderCallId(scope(), "call-1").orElseThrow().attempt()).isEqualTo(2);
    }

    @Test
    void claim_callHistory_keepsTheAttemptACallStarted_whenTheSameCallLaterStartsNothing() {
        byte[] key = newKey();
        journal().claim(request(key, "call-1", LONG_LEASE), table());
        journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "no");
        journal().claim(request(key, "call-2", LONG_LEASE), table()); // starts attempt 2

        ClaimResult again = journal().claim(request(key, "call-1", LONG_LEASE), table()); // attempt 2 is live

        assertThat(again.decision().verdict()).isEqualTo(ClaimVerdict.IN_PROGRESS);
        assertThat(journal().findByProviderCallId(scope(), "call-1").orElseThrow().attempt()).isEqualTo(1);
    }

    // ---- recordSuccess

    @Test
    void recordSuccess_landsOnTheExecutingAttempt() {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());

        RecordResult result = journal().recordSuccess(at(key, 1), "refund 42 done");

        assertThat(result.outcome()).isEqualTo(RecordOutcome.APPLIED);
        assertThat(result.stateBefore()).contains(ActionState.EXECUTING);
        ActionRecord row = result.record().orElseThrow();
        assertThat(row.state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(row.resultText()).isEqualTo("refund 42 done");
        assertThat(row.leaseOwner()).isNull();
        assertThat(row.leaseUntil()).isNull();
        assertThat(row.resolveAfter()).isNull();
        assertThat(read(key).state()).isEqualTo(ActionState.SUCCEEDED);
    }

    @Test
    void recordSuccess_aLateSuccessOfTheSameAttemptLandsInEveryUnresolvedOrResolvedState() {
        for (ActionState state : List.of(ActionState.UNKNOWN, ActionState.FAILED, ActionState.ESCALATED,
                ActionState.ABANDONED)) {
            byte[] key = newKey();
            moveTo(key, state);

            RecordResult result = journal().recordSuccess(at(key, 1), "late");

            assertThat(result.outcome()).as("from %s", state).isEqualTo(RecordOutcome.APPLIED);
            assertThat(result.stateBefore()).as("from %s", state).contains(state);
            assertThat(read(key).state()).as("from %s", state).isEqualTo(ActionState.SUCCEEDED);
            assertThat(read(key).failureBasis()).as("from %s", state).isNull();
        }
    }

    @Test
    void recordSuccess_onASucceededAttempt_keepsTheFirstResult() {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());
        journal().recordSuccess(at(key, 1), "first");

        RecordResult result = journal().recordSuccess(at(key, 1), "second");

        assertThat(result.outcome()).isEqualTo(RecordOutcome.ALREADY_SUCCEEDED);
        assertThat(result.record().orElseThrow().resultText()).isEqualTo("first");
        assertThat(read(key).resultText()).isEqualTo("first");
    }

    @Test
    void recordSuccess_ofASupersededAttempt_isFenced() {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());
        journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "no");
        ActionRecord second = journal().claim(request(key, null, LONG_LEASE), table()).record();

        RecordResult result = journal().recordSuccess(at(key, 1), "stale");

        assertThat(result.outcome()).isEqualTo(RecordOutcome.SUPERSEDED);
        ActionRecord row = read(key);
        assertThat(row.state()).isEqualTo(ActionState.EXECUTING);
        assertThat(row.attempt()).isEqualTo(2);
        assertThat(row.resultText()).isNull();
        assertThat(row.updatedAt()).isEqualTo(second.updatedAt());
    }

    @Test
    void recordWrites_withoutARow_reportMissing() {
        assertThat(journal().recordSuccess(absent(), "x").outcome()).isEqualTo(RecordOutcome.MISSING);
        assertThat(journal().recordFailure(absent(), FailureKind.DEFINITE, "x").outcome())
                .isEqualTo(RecordOutcome.MISSING);
    }

    // ---- recordFailure

    @Test
    void recordFailure_definite_movesToFailedWithBasisDefinite() {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());

        RecordResult result = journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "invalid amount");

        assertThat(result.outcome()).isEqualTo(RecordOutcome.APPLIED);
        ActionRecord row = result.record().orElseThrow();
        assertThat(row.state()).isEqualTo(ActionState.FAILED);
        assertThat(row.failureBasis()).isEqualTo(FailureBasis.DEFINITE);
        assertThat(row.lastError()).isEqualTo("invalid amount");
        assertThat(row.resolveAfter()).isEqualTo(row.updatedAt());
        assertThat(row.leaseUntil()).isNull();
    }

    @Test
    void recordFailure_indeterminate_movesToUnknown() {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());

        ActionRecord row = journal().recordFailure(at(key, 1), FailureKind.INDETERMINATE, "timeout").record()
                .orElseThrow();

        assertThat(row.state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(row.failureBasis()).isNull();
        assertThat(row.resolveAfter()).isEqualTo(row.updatedAt());
    }

    @Test
    void recordFailure_aLateDefiniteErrorResolvesUnknown() {
        byte[] key = newKey();
        moveToUnknown(key);

        RecordResult result = journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "rejected");

        assertThat(result.outcome()).isEqualTo(RecordOutcome.APPLIED);
        assertThat(result.stateBefore()).contains(ActionState.UNKNOWN);
        assertThat(read(key).state()).isEqualTo(ActionState.FAILED);
    }

    @Test
    void recordFailure_afterTheRowWasResolved_changesNothing() {
        for (ActionState state : List.of(ActionState.FAILED, ActionState.ESCALATED, ActionState.ABANDONED)) {
            byte[] key = newKey();
            moveTo(key, state);
            ActionRecord before = read(key);

            RecordResult result = journal().recordFailure(at(key, 1), FailureKind.INDETERMINATE, "late");

            assertThat(result.outcome()).as("from %s", state).isEqualTo(RecordOutcome.ALREADY_RESOLVED);
            assertThat(read(key).updatedAt()).as("from %s", state).isEqualTo(before.updatedAt());
        }
        byte[] succeeded = newKey();
        moveTo(succeeded, ActionState.SUCCEEDED);
        assertThat(journal().recordFailure(at(succeeded, 1), FailureKind.DEFINITE, "late").outcome())
                .isEqualTo(RecordOutcome.ALREADY_SUCCEEDED);
    }

    @Test
    void recordFailure_ofASupersededAttempt_isFenced() {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());
        journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "no");
        journal().claim(request(key, null, LONG_LEASE), table());

        assertThat(journal().recordFailure(at(key, 1), FailureKind.INDETERMINATE, "stale").outcome())
                .isEqualTo(RecordOutcome.SUPERSEDED);
        assertThat(read(key).state()).isEqualTo(ActionState.EXECUTING);
    }

    // ---- markUnknownIfLeaseExpired

    @Test
    void markUnknown_onlyAfterTheLeaseExpired_andOnlyForTheCurrentExecutingAttempt() {
        byte[] live = newKey();
        byte[] expiring = newKey();
        journal().claim(request(live, null, LONG_LEASE), table());
        journal().claim(request(expiring, null, SHORT_LEASE), table());

        assertThat(journal().markUnknownIfLeaseExpired(at(live, 1), "lease expired")).isFalse();
        assertThat(journal().markUnknownIfLeaseExpired(at(expiring, 2), "lease expired")).isFalse();
        eventually(() -> journal().markUnknownIfLeaseExpired(at(expiring, 1), "lease expired"));

        ActionRecord row = read(expiring);
        assertThat(row.state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(row.lastError()).isEqualTo("lease expired");
        assertThat(row.resolveAfter()).isEqualTo(row.updatedAt());
        assertThat(row.leaseUntil()).isNull();
        assertThat(journal().markUnknownIfLeaseExpired(at(expiring, 1), "again")).isFalse();
        assertThat(read(live).state()).isEqualTo(ActionState.EXECUTING);
    }

    // ---- renewLeases

    @Test
    void renewLeases_extendsOnlyOwnCurrentExecutingAttempts() {
        byte[] own = newKey();
        byte[] otherOwner = newKey();
        byte[] unknown = newKey();
        ActionRecord before = journal().claim(request(own, null, SHORT_LEASE), table()).record();
        journal().claim(request(otherOwner, null, SHORT_LEASE, "other-owner"), table());
        moveToUnknown(unknown);

        int renewed = journal().renewLeases(OWNER, List.of(at(own, 1), at(own, 2),
                at(otherOwner, 1), at(unknown, 1)), LONG_LEASE);

        assertThat(renewed).isEqualTo(1);
        assertThat(read(own).leaseUntil()).isAfterOrEqualTo(before.leaseUntil().minus(SHORT_LEASE).plus(LONG_LEASE));
        assertThat(read(unknown).leaseUntil()).isNull();
        assertThat(journal().renewLeases(OWNER, List.of(), LONG_LEASE)).isZero();
    }

    @Test
    void renewLeases_ofASupersededAttempt_isFenced_evenForTheSameOwner() {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());
        journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "no");
        ActionRecord second = journal().claim(request(key, null, SHORT_LEASE), table()).record();

        assertThat(journal().renewLeases(OWNER, List.of(at(key, 1)), LONG_LEASE)).isZero();
        assertThat(read(key).leaseUntil()).isEqualTo(second.leaseUntil());
    }

    @Test
    void renewLeases_renewsManyAttemptsInOneCall() {
        byte[] first = newKey();
        byte[] second = newKey();
        journal().claim(request(first, null, SHORT_LEASE), table());
        journal().claim(request(second, null, SHORT_LEASE), table());

        assertThat(journal().renewLeases(OWNER, List.of(at(first, 1), at(second, 1)),
                LONG_LEASE)).isEqualTo(2);
    }

    @Test
    void renewLeases_stopsAfterTheMaximumEffectDuration_soAnOverrunningEffectLosesItsLease() {
        byte[] key = newKey();
        ClaimRequest shortEffect = new ClaimRequest(key, scope(), null, "refund", "AT_LEAST_ONCE", hash(), hash(),
                null, null, OWNER, SHORT_LEASE, Duration.ofMillis(300), Duration.ofMinutes(1));
        journal().claim(shortEffect, table());

        eventually(() -> journal().renewLeases(OWNER, List.of(at(key, 1)), SHORT_LEASE) == 0);
        eventually(() -> journal().markUnknownIfLeaseExpired(at(key, 1), "overrun"));

        assertThat(read(key).state()).isEqualTo(ActionState.UNKNOWN);
    }

    // ---- takeReconcileBatch

    @Test
    void takeReconcileBatch_takesRowsThatNeedTheReconciler_andHoldsThem() {
        byte[] expired = newKey();
        byte[] unknown = newKey();
        byte[] failedPastDeadline = newKey();
        byte[] live = newKey();
        byte[] failedBeforeDeadline = newKey();
        byte[] succeeded = newKey();
        journal().claim(request(expired, null, SHORT_LEASE), table());
        moveToUnknown(unknown);
        journal().claim(new ClaimRequest(failedPastDeadline, scope(), null, "refund", "AT_LEAST_ONCE", hash(),
                hash(), null, null, OWNER, LONG_LEASE, Duration.ofMillis(100), Duration.ofMillis(400)), table());
        journal().recordFailure(at(failedPastDeadline, 1), FailureKind.DEFINITE, "no");
        journal().claim(request(live, null, LONG_LEASE), table());
        moveTo(failedBeforeDeadline, ActionState.FAILED);
        moveTo(succeeded, ActionState.SUCCEEDED);
        List<byte[]> mine = List.of(expired, unknown, failedPastDeadline, live, failedBeforeDeadline, succeeded);

        List<byte[]> taken = new ArrayList<>();
        eventually(() -> {
            for (ActionRecord row : journal().takeReconcileBatch(LONG_LEASE, 10_000)) {
                if (contains(mine, row.keyHash())) {
                    taken.add(row.keyHash());
                }
            }
            return contains(taken, expired) && contains(taken, unknown) && contains(taken, failedPastDeadline);
        });

        assertThat(contains(taken, live) || contains(taken, failedBeforeDeadline) || contains(taken, succeeded))
                .as("rows that need no reconciler were taken").isFalse();
        assertThat(taken).as("a held row was taken twice").hasSize(3);
        assertThat(journal().takeReconcileBatch(LONG_LEASE, 10_000)).noneMatch(row -> contains(mine, row.keyHash()));
    }

    @Test
    void takeReconcileBatch_doesNotTakeAHeldRowAgain_untilTheHoldEnds() {
        byte[] heldLong = newKey();
        byte[] heldShort = newKey();
        moveToUnknown(heldLong);

        eventually(() -> takenNow(heldLong, LONG_LEASE));
        assertThat(takenNow(heldLong, LONG_LEASE)).as("taken again during a 5-minute hold").isFalse();
        moveToUnknown(heldShort); // created after the long take, which would otherwise hold it too
        eventually(() -> takenNow(heldShort, Duration.ofMillis(300)));
        eventually(() -> takenNow(heldShort, LONG_LEASE));
    }

    @Test
    void takeReconcileBatch_skipsRowsLockedByAClaim_insteadOfWaiting() throws Exception {
        byte[] locked = newKey();
        byte[] free = newKey();
        moveToUnknown(locked);
        moveToUnknown(free);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<ClaimResult> holder = runAsync(() -> journal().claim(request(locked, null, LONG_LEASE),
                (request, row, now) -> {
                    lockHeld.countDown();
                    await(release);
                    return new ClaimDecision(ClaimVerdict.ASK_LATER, new JournalWrite.None());
                }));
        try {
            await(lockHeld);
            List<byte[]> taken = new ArrayList<>();
            CompletableFuture<Void> taking = runAsync(() -> {
                eventually(() -> {
                    journal().takeReconcileBatch(LONG_LEASE, 10_000).forEach(row -> taken.add(row.keyHash()));
                    return contains(taken, free);
                });
                return null;
            });

            taking.get(20, TimeUnit.SECONDS);
            assertThat(contains(taken, locked)).as("a row locked by a running claim was taken").isFalse();
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void takeReconcileBatch_respectsTheLimit() {
        moveToUnknown(newKey());
        moveToUnknown(newKey());

        assertThat(journal().takeReconcileBatch(Duration.ofMillis(1), 1)).hasSizeLessThanOrEqualTo(1);
        assertThatThrownBy(() -> journal().takeReconcileBatch(LONG_LEASE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- resolve

    @Test
    void resolve_applied_recordsTheLookupResult() {
        byte[] key = newKey();
        moveToUnknown(key);

        assertThat(journal().resolve(at(key, 1), new Resolution.Applied("found downstream"))).isTrue();

        ActionRecord row = read(key);
        assertThat(row.state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(row.resultText()).isEqualTo("found downstream");
        assertThat(row.resolveAfter()).isNull();
    }

    @Test
    void resolve_notApplied_movesToFailedWithBasisLookup() {
        byte[] key = newKey();
        moveToUnknown(key);

        assertThat(journal().resolve(at(key, 1), new Resolution.NotApplied())).isTrue();

        ActionRecord row = read(key);
        assertThat(row.state()).isEqualTo(ActionState.FAILED);
        assertThat(row.failureBasis()).isEqualTo(FailureBasis.LOOKUP);
        assertThat(row.resolveAfter()).isEqualTo(row.updatedAt());
    }

    @Test
    void resolve_lookupUnreachable_countsTheFailureAndBacksOff() {
        byte[] key = newKey();
        moveToUnknown(key);
        Duration backoff = Duration.ofSeconds(30);

        assertThat(journal().resolve(at(key, 1), new Resolution.LookupUnreachable(backoff))).isTrue();
        assertThat(journal().resolve(at(key, 1), new Resolution.LookupUnreachable(backoff))).isTrue();

        ActionRecord row = read(key);
        assertThat(row.state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(row.lookupFailures()).isEqualTo(2);
        assertThat(row.resolveAfter()).isEqualTo(row.updatedAt().plus(backoff));
    }

    @Test
    void resolve_escalateAndAbandon() {
        byte[] unknown = newKey();
        byte[] failed = newKey();
        moveToUnknown(unknown);
        moveTo(failed, ActionState.FAILED);

        assertThat(journal().resolve(at(unknown, 1), new Resolution.Escalate("8 lookups unreachable"))).isTrue();
        assertThat(journal().resolve(at(failed, 1), new Resolution.Abandon("deadline passed"))).isTrue();

        assertThat(read(unknown).state()).isEqualTo(ActionState.ESCALATED);
        assertThat(read(unknown).lastError()).isEqualTo("8 lookups unreachable");
        assertThat(read(unknown).resolveAfter()).isNull();
        assertThat(read(failed).state()).isEqualTo(ActionState.ABANDONED);
        assertThat(read(failed).resolveAfter()).isNull();
    }

    @Test
    void resolve_isACompareAndSetOnAttemptAndSourceState() {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());

        assertThat(journal().resolve(at(key, 1), new Resolution.Applied("x"))).as("from EXECUTING").isFalse();
        assertThat(journal().resolve(at(key, 1), new Resolution.Abandon("x"))).as("Abandon from EXECUTING").isFalse();
        journal().recordFailure(at(key, 1), FailureKind.INDETERMINATE, "timeout");
        assertThat(journal().resolve(at(key, 2), new Resolution.Applied("x"))).as("other attempt").isFalse();
        assertThat(journal().resolve(at(key, 1), new Resolution.Abandon("x"))).as("Abandon from UNKNOWN").isFalse();
        assertThat(journal().resolve(absent(), new Resolution.NotApplied())).as("no row").isFalse();
        assertThat(read(key).state()).isEqualTo(ActionState.UNKNOWN);
    }


    // ---- retention

    @Test
    void retention_deletesOnlyFinishedRowsOlderThanTheRetention_withTheirCallHistory() {
        byte[] succeeded = newKey();
        journal().claim(request(succeeded, "call-retained", LONG_LEASE), table());
        journal().recordSuccess(at(succeeded, 1), "done");
        byte[] abandoned = newKey();
        moveTo(abandoned, ActionState.ABANDONED);
        List<byte[]> kept = new ArrayList<>();
        for (ActionState state : List.of(ActionState.EXECUTING, ActionState.FAILED, ActionState.UNKNOWN,
                ActionState.ESCALATED)) {
            byte[] key = newKey();
            moveTo(key, state);
            kept.add(key);
        }
        awaitAge(kept.get(kept.size() - 1), RETENTION); // the youngest row: every row is past the retention

        journal().deleteTerminalOlderThan(Duration.ofHours(1), 1000);
        assertThat(exists(succeeded)).as("a row younger than the retention").isTrue();
        deleteAllOlderThan(RETENTION);

        assertThat(exists(succeeded)).as("SUCCEEDED").isFalse();
        assertThat(exists(abandoned)).as("ABANDONED").isFalse();
        assertThat(journal().findByProviderCallId(scope(), "call-retained")).isEmpty();
        for (byte[] key : kept) {
            assertThat(exists(key)).as("unfinished rows are never deleted").isTrue();
        }
    }

    @Test
    void retention_aDeletedKeyExecutesAgain() {
        byte[] key = newKey();
        moveTo(key, ActionState.SUCCEEDED);
        awaitAge(key, RETENTION);
        deleteAllOlderThan(RETENTION);

        ClaimResult again = journal().claim(request(key, null, LONG_LEASE), table());

        assertThat(again.decision().verdict()).isEqualTo(ClaimVerdict.START);
        assertThat(again.record().attempt()).isEqualTo(1);
    }

    @Test
    void retention_writesOfTheDeletedRowsAttempts_neverLandOnTheRowCreatedAgain() {
        byte[] key = newKey();
        journal().claim(request(key, null, LONG_LEASE), table());
        KeyAttempt old = at(key, 1);
        journal().recordSuccess(old, "old result");
        awaitAge(key, RETENTION);
        deleteAllOlderThan(RETENTION);
        ActionRecord recreated = journal().claim(request(key, null, SHORT_LEASE), table()).record();
        assertThat(recreated.attempt()).as("the key starts again at attempt 1").isEqualTo(old.attempt());
        assertThat(recreated.createdAt()).isNotEqualTo(old.createdAt());

        // A writer of the deleted row (for example frozen for longer than the retention) wakes up.
        assertThat(journal().recordSuccess(old, "old result").outcome()).isEqualTo(RecordOutcome.MISSING);
        assertThat(journal().recordFailure(old, FailureKind.DEFINITE, "old error").outcome())
                .isEqualTo(RecordOutcome.MISSING);
        assertThat(journal().renewLeases(OWNER, List.of(old), LONG_LEASE)).isZero();
        assertThat(read(key).leaseUntil()).isEqualTo(recreated.leaseUntil());
        eventually(() -> databaseNow(key).isAfter(recreated.leaseUntil())); // the new lease has expired
        assertThat(journal().markUnknownIfLeaseExpired(old, "old expiry")).isFalse();
        assertThat(journal().markUnknownIfLeaseExpired(at(key, 1), "lease expired")).isTrue();
        assertThat(journal().resolve(old, new Resolution.Escalate("old escalation"))).isFalse();
        assertThat(journal().resolve(old, new Resolution.Applied("old lookup"))).isFalse();
        journal().resolve(at(key, 1), new Resolution.Escalate("operator needed"));
        assertThat(journal().resolveManually(old, new ManualResolution.Applied("old manual"))).isFalse();

        ActionRecord row = read(key);
        assertThat(row.state()).isEqualTo(ActionState.ESCALATED);
        assertThat(row.resultText()).isNull();
        assertThat(row.lastError()).isEqualTo("operator needed");
        assertThat(journal().resolveManually(at(key, 1), new ManualResolution.Applied("new result"))).isTrue();
        assertThat(read(key).resultText()).isEqualTo("new result");
    }

    @Test
    void retention_respectsTheBatchSize_andRejectsInvalidArguments() {
        for (int i = 0; i < 3; i++) {
            moveTo(newKey(), ActionState.SUCCEEDED);
        }
        byte[] last = newKey();
        moveTo(last, ActionState.SUCCEEDED);
        awaitAge(last, RETENTION);

        assertThat(journal().deleteTerminalOlderThan(RETENTION, 1)).isEqualTo(1);
        assertThatThrownBy(() -> journal().deleteTerminalOlderThan(RETENTION, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal().deleteTerminalOlderThan(Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retention_skipsARowLockedByAClaim_insteadOfWaiting() throws Exception {
        byte[] locked = newKey();
        moveTo(locked, ActionState.SUCCEEDED);
        awaitAge(locked, RETENTION);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<ClaimResult> holder = runAsync(() -> journal().claim(request(locked, null, LONG_LEASE),
                (request, row, now) -> {
                    lockHeld.countDown();
                    await(release);
                    return new ClaimDecision(ClaimVerdict.REPLAY, new JournalWrite.None());
                }));
        try {
            await(lockHeld);
            CompletableFuture<Void> deleting = runAsync(() -> {
                deleteAllOlderThan(RETENTION);
                return null;
            });

            deleting.get(20, TimeUnit.SECONDS); // returns although the row stays locked
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
        assertThat(exists(locked)).as("a row locked by a running claim was deleted").isTrue();
        deleteAllOlderThan(RETENTION);
        assertThat(exists(locked)).as("deleted once the claim committed").isFalse();
    }

    // ---- operator operations

    @Test
    void find_listsOneState_leastRecentlyChangedFirst_upToTheLimit() {
        byte[] first = newKey();
        byte[] second = newKey();
        moveTo(first, ActionState.ESCALATED);
        moveTo(second, ActionState.ESCALATED);
        moveTo(newKey(), ActionState.UNKNOWN);

        List<ActionRecord> escalated = journal().find(ActionState.ESCALATED, 100_000);

        assertThat(escalated).allSatisfy(row -> assertThat(row.state()).isEqualTo(ActionState.ESCALATED));
        assertThat(escalated).extracting(ActionRecord::updatedAt).isSorted();
        List<byte[]> keys = escalated.stream().map(ActionRecord::keyHash).toList();
        assertThat(indexOf(keys, first)).isNotNegative().isLessThan(indexOf(keys, second));
        assertThat(journal().find(ActionState.ESCALATED, 1)).hasSize(1);
        assertThatThrownBy(() -> journal().find(ActionState.ESCALATED, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void count_countsTheRowsInAState() {
        long unknownBefore = journal().count(ActionState.UNKNOWN);
        long escalatedBefore = journal().count(ActionState.ESCALATED);
        moveTo(newKey(), ActionState.UNKNOWN);
        moveTo(newKey(), ActionState.ESCALATED);
        moveTo(newKey(), ActionState.ESCALATED);

        assertThat(journal().count(ActionState.UNKNOWN)).isEqualTo(unknownBefore + 1);
        assertThat(journal().count(ActionState.ESCALATED)).isEqualTo(escalatedBefore + 2);
    }

    @Test
    void resolveManually_applied_movesAnEscalatedActionToSucceeded_andLaterCallsReplayIt() {
        byte[] key = newKey();
        moveTo(key, ActionState.ESCALATED);

        assertThat(journal().resolveManually(at(key, 1), new ManualResolution.Applied("refund r-7 found"))).isTrue();

        ActionRecord row = read(key);
        assertThat(row.state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(row.resultText()).isEqualTo("refund r-7 found");
        assertThat(row.resolveAfter()).isNull();
        assertThat(journal().claim(request(key, null, LONG_LEASE), table()).decision().verdict())
                .isEqualTo(ClaimVerdict.REPLAY);
    }

    @Test
    void resolveManually_notApplied_movesAnEscalatedActionToAbandoned() {
        byte[] key = newKey();
        moveTo(key, ActionState.ESCALATED);

        assertThat(journal().resolveManually(at(key, 1), new ManualResolution.NotApplied())).isTrue();

        ActionRecord row = read(key);
        assertThat(row.state()).isEqualTo(ActionState.ABANDONED);
        assertThat(row.lastError()).isEqualTo(ManualResolution.NOT_APPLIED_ERROR);
        assertThat(row.resolveAfter()).isNull();
    }

    @Test
    void resolveManually_isACompareAndSetOnAttemptAndTheEscalatedState() {
        byte[] escalated = newKey();
        moveTo(escalated, ActionState.ESCALATED);
        byte[] unknown = newKey();
        moveTo(unknown, ActionState.UNKNOWN);
        ManualResolution applied = new ManualResolution.Applied("x");

        assertThat(journal().resolveManually(at(escalated, 2), applied)).as("other attempt").isFalse();
        assertThat(journal().resolveManually(at(unknown, 1), applied)).as("from UNKNOWN").isFalse();
        assertThat(journal().resolveManually(absent(), applied)).as("no row").isFalse();
        assertThat(journal().resolveManually(at(escalated, 1), new ManualResolution.NotApplied())).isTrue();
        assertThat(journal().resolveManually(at(escalated, 1), applied)).as("already resolved").isFalse();
        assertThat(read(escalated).state()).isEqualTo(ActionState.ABANDONED);
        assertThat(read(unknown).state()).isEqualTo(ActionState.UNKNOWN);
    }

    @Test
    void redact_erasesEveryText_keepsTheRowAndItsUpdateTime() {
        byte[] key = newKey();
        journal().claim(requestWithTexts(key, LONG_LEASE), table());
        ActionRecord succeeded = journal().recordSuccess(at(key, 1), "refunded order-42").record().orElseThrow();
        assertThat(succeeded.keyText()).isNotNull();
        assertThat(succeeded.argumentsJson()).isNotNull();

        assertThat(journal().redact(key)).isTrue();

        ActionRecord row = read(key);
        assertThat(row.state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(row.keyText()).isNull();
        assertThat(row.argumentsJson()).isNull();
        assertThat(row.resultText()).isNull();
        assertThat(row.lastError()).isNull();
        assertThat(row.redactedAt()).isNotNull().isAfterOrEqualTo(succeeded.updatedAt());
        assertThat(row.updatedAt()).isEqualTo(succeeded.updatedAt());
        assertThat(journal().claim(request(key, null, LONG_LEASE), table()).decision().verdict())
                .as("the key is still deduplicated").isEqualTo(ClaimVerdict.REPLAY);
        assertThat(journal().redact(key)).as("again").isTrue();
        assertThat(read(key).redactedAt()).isEqualTo(row.redactedAt());
        assertThat(journal().redact(newKey())).as("no row").isFalse();
    }

    @Test
    void redact_laterWritesToTheRowStoreNoText() {
        // The same writes on rows that are not redacted store their texts; so each null below comes from the redaction.
        List<Observed> plain = writeEveryText(false);
        List<Observed> redacted = writeEveryText(true);

        assertThat(plain).allSatisfy(write -> assertThat(write.text()).as(write.label()).isNotNull());
        assertThat(redacted).allSatisfy(write -> assertThat(write.text()).as(write.label()).isNull());
        assertThat(redacted).extracting(Observed::state).as("every write landed")
                .containsExactlyElementsOf(plain.stream().map(Observed::state).toList());
    }

    /** One write and the row after it: its state and the text column the write sets. */
    private record Observed(String label, ActionState state, String text) {}

    /** Every write that stores a text, each on a row redacted before it when {@code redact} is set. */
    private List<Observed> writeEveryText(boolean redact) {
        List<Observed> observed = new ArrayList<>();
        byte[] running = newKey();
        journal().claim(requestWithTexts(running, LONG_LEASE), table());
        redactIf(redact, running);
        journal().recordSuccess(at(running, 1), "refunded order-42");
        observed.add(new Observed("late success", read(running).state(), read(running).resultText()));

        byte[] retried = newKey();
        journal().claim(requestWithTexts(retried, LONG_LEASE), table());
        redactIf(redact, retried);
        journal().recordFailure(at(retried, 1), FailureKind.DEFINITE, "order-42 is locked");
        observed.add(new Observed("definite failure", read(retried).state(), read(retried).lastError()));
        ActionRecord second = journal().claim(requestWithTexts(retried, LONG_LEASE), table()).record();
        observed.add(new Observed("key text of attempt " + second.attempt(), second.state(), second.keyText()));
        observed.add(new Observed("arguments of attempt " + second.attempt(), second.state(),
                second.argumentsJson()));
        journal().recordFailure(at(retried, 2), FailureKind.INDETERMINATE, "timeout for order-42");
        observed.add(new Observed("indeterminate failure", read(retried).state(), read(retried).lastError()));
        journal().resolve(at(retried, 2), new Resolution.Escalate("order-42 unverifiable"));
        observed.add(new Observed("escalation", read(retried).state(), read(retried).lastError()));
        journal().resolveManually(at(retried, 2), new ManualResolution.Applied("refund of order-42"));
        observed.add(new Observed("manual resolution", read(retried).state(), read(retried).resultText()));

        byte[] expired = newKey();
        journal().claim(requestWithTexts(expired, SHORT_LEASE), table());
        redactIf(redact, expired);
        eventually(() -> journal().markUnknownIfLeaseExpired(at(expired, 1), "lease of order-42 expired"));
        observed.add(new Observed("lease expiry", read(expired).state(), read(expired).lastError()));
        journal().resolve(at(expired, 1), new Resolution.Applied("found order-42"));
        observed.add(new Observed("lookup", read(expired).state(), read(expired).resultText()));
        return observed;
    }

    private void redactIf(boolean redact, byte[] key) {
        if (redact) {
            assertThat(journal().redact(key)).isTrue();
        }
    }

    // ---- helpers

    /** Age the retention tests wait for; every finished row older than that is deleted by them. */
    private static final Duration RETENTION = Duration.ofMillis(300);

    /** Whether the row exists, by a claim whose decider fails (and so rolls back) when there is none. */
    private boolean exists(byte[] key) {
        try {
            journal().claim(request(key, null, LONG_LEASE), (request, row, now) -> {
                if (row.isEmpty()) {
                    throw new NoRow();
                }
                return new ClaimDecision(ClaimVerdict.IN_PROGRESS, new JournalWrite.None());
            });
            return true;
        } catch (NoRow expected) {
            return false;
        }
    }

    /** Thrown by the decider of {@link #exists} for a missing row. */
    private static final class NoRow extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Attempt {@code attempt} of the row of {@code key} as it exists now: the fencing token a writer of that attempt
     * holds.
     *
     * @param key     an existing row
     * @param attempt the attempt number
     * @return the token
     */
    protected final KeyAttempt at(byte[] key, int attempt) {
        return new KeyAttempt(key, read(key).createdAt(), attempt);
    }

    /** A token for a key that has no row. */
    private static KeyAttempt absent() {
        return new KeyAttempt(newKey(), Instant.EPOCH, 1);
    }

    /** Waits until the database time is more than {@code age} past the row's last change. */
    private void awaitAge(byte[] key, Duration age) {
        Instant changed = read(key).updatedAt();
        eventually(() -> databaseNow(key).isAfter(changed.plus(age)));
    }

    private Instant databaseNow(byte[] key) {
        Instant[] seen = new Instant[1];
        journal().claim(request(key, null, LONG_LEASE), (request, row, now) -> {
            seen[0] = now;
            return new ClaimDecision(ClaimVerdict.IN_PROGRESS, new JournalWrite.None());
        });
        return seen[0];
    }

    /** Deletes batches until none is full, so every unlocked finished row older than {@code retention} is gone. */
    private void deleteAllOlderThan(Duration retention) {
        int batch = 1000;
        long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        while (journal().deleteTerminalOlderThan(retention, batch) == batch) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("retention did not finish within " + POLL_TIMEOUT);
            }
        }
    }

    private static int indexOf(List<byte[]> keys, byte[] key) {
        for (int i = 0; i < keys.size(); i++) {
            if (Arrays.equals(keys.get(i), key)) {
                return i;
            }
        }
        return -1;
    }

    /** A claim request that stores key text and arguments, which may contain personal data. */
    private ClaimRequest requestWithTexts(byte[] key, Duration lease) {
        return new ClaimRequest(key, scope(), "tck:refund:order-42", "refund", "AT_LEAST_ONCE", MATERIAL, ALL_ARGS,
                "{\"orderId\":\"order-42\"}", null, OWNER, lease, Duration.ofMinutes(1), Duration.ofMinutes(10));
    }

    /**
     * Reads the row through a claim that writes nothing.
     *
     * @param key the key hash of an existing row
     * @return the row as the claim saw it
     */
    protected final ActionRecord read(byte[] key) {
        return journal().claim(request(key, null, LONG_LEASE), (request, row, now) -> {
            if (row.isEmpty()) {
                throw new AssertionError("no row to read");
            }
            return new ClaimDecision(ClaimVerdict.IN_PROGRESS, new JournalWrite.None());
        }).record();
    }

    /**
     * Creates the row with attempt 1 and moves it to {@code state}.
     *
     * @param key the key hash of a row that does not exist yet
     * @param state the state to leave the row in
     */
    protected final void moveTo(byte[] key, ActionState state) {
        journal().claim(request(key, null, LONG_LEASE), table());
        switch (state) {
            case EXECUTING:
                return;
            case SUCCEEDED:
                journal().recordSuccess(at(key, 1), "done");
                return;
            case FAILED:
                journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "no");
                return;
            case UNKNOWN:
                journal().recordFailure(at(key, 1), FailureKind.INDETERMINATE, "timeout");
                return;
            case ESCALATED:
                journal().recordFailure(at(key, 1), FailureKind.INDETERMINATE, "timeout");
                journal().resolve(at(key, 1), new Resolution.Escalate("operator needed"));
                return;
            case ABANDONED:
                journal().recordFailure(at(key, 1), FailureKind.DEFINITE, "no");
                journal().resolve(at(key, 1), new Resolution.Abandon("deadline"));
                return;
            default:
                throw new IllegalArgumentException(state.name());
        }
    }

    private void moveToUnknown(byte[] key) {
        moveTo(key, ActionState.UNKNOWN);
    }

    private boolean takenNow(byte[] key, Duration hold) {
        return journal().takeReconcileBatch(hold, 10_000).stream().anyMatch(row -> Arrays.equals(row.keyHash(), key));
    }

    /**
     * The real decision table of the core for an at-least-once policy.
     *
     * @return a new decision table
     */
    protected static DecisionTable table() {
        return new DecisionTable(ActionPolicy.atLeastOnce()
                .key((call, args) -> ActionKey.of("tck", "refund"))
                .maxEffectDuration(Duration.ofMinutes(1))
                .deadline(Duration.ofMinutes(10)));
    }

    /**
     * A decider that always returns the same decision for an existing row and starts attempt 1 otherwise.
     *
     * @param verdict the verdict for an existing row
     * @param write the write for an existing row
     * @return the decider
     */
    protected static ClaimDecider fixed(ClaimVerdict verdict, JournalWrite write) {
        ClaimDecision decision = new ClaimDecision(verdict, write);
        return (request, row, now) -> row.isPresent()
                ? decision
                : new ClaimDecision(ClaimVerdict.START, new JournalWrite.StartAttempt(1));
    }

    private static ClaimDecider waitOnEmpty(CyclicBarrier barrier) {
        DecisionTable table = table();
        return (request, row, now) -> {
            if (row.isEmpty()) {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the other claim did not reach the decider", e);
                }
            }
            return table.decide(request, row, now);
        };
    }

    /**
     * A claim request with this test's scope and argument hashes and this suite's owner.
     *
     * @param key the key hash
     * @param providerCallId the tool-call id of the model provider, or {@code null}
     * @param lease the lease of a new attempt
     * @return the request
     */
    protected final ClaimRequest request(byte[] key, String providerCallId, Duration lease) {
        return request(key, providerCallId, lease, OWNER);
    }

    private ClaimRequest request(byte[] key, String providerCallId, Duration lease, String owner) {
        return new ClaimRequest(key, scope(), null, "refund", "AT_LEAST_ONCE", MATERIAL, ALL_ARGS, null,
                providerCallId, owner, lease, Duration.ofMinutes(1), Duration.ofMinutes(10));
    }

    private ClaimRequest request(byte[] key, String providerCallId, Duration lease, byte[] material,
                                        byte[] allArgs) {
        return new ClaimRequest(key, scope(), null, "refund", "AT_LEAST_ONCE", material, allArgs, null,
                providerCallId, OWNER, lease, Duration.ofMinutes(1), Duration.ofMinutes(10));
    }

    // JUnit creates one instance per test, so every test has its own scope (call ids never collide across tests).
    private final byte[] scope = hash();

    private byte[] scope() {
        return scope;
    }

    /**
     * A random key hash.
     *
     * @return 32 random bytes
     */
    protected static byte[] newKey() {
        return hash();
    }

    private static byte[] hash() {
        byte[] hash = new byte[32];
        RANDOM.nextBytes(hash);
        return hash;
    }

    private static boolean contains(List<byte[]> keys, byte[] key) {
        return keys.stream().anyMatch(candidate -> Arrays.equals(candidate, key));
    }

    /**
     * Polls {@code condition} every 20 ms until it holds, failing after {@link #POLL_TIMEOUT}.
     *
     * @param condition the condition to wait for
     */
    protected static void eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + POLL_TIMEOUT);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("latch not released within 30 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static <T> CompletableFuture<T> runAsync(java.util.function.Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, runnable -> {
            Thread thread = new Thread(runnable, "tck-claim");
            thread.setDaemon(true);
            thread.start();
        });
    }

    /** Wraps a decider and records what it was called with. */
    private static final class RecordingDecider implements ClaimDecider {
        final List<Optional<ActionRecord>> rowsSeen = Collections.synchronizedList(new ArrayList<>());
        final List<Instant> timesSeen = Collections.synchronizedList(new ArrayList<>());
        private final ClaimDecider delegate;

        RecordingDecider(ClaimDecider delegate) {
            this.delegate = delegate;
        }

        @Override
        public ClaimDecision decide(ClaimRequest request, Optional<ActionRecord> current, Instant databaseNow) {
            rowsSeen.add(current);
            timesSeen.add(databaseNow);
            return delegate.decide(request, current, databaseNow);
        }
    }
}
