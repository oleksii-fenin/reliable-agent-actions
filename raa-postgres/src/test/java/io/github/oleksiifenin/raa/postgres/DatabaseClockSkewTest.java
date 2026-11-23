package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.LookupResult;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.RepeatAdvice;
import io.github.oleksiifenin.raa.core.ToolCall;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import io.github.oleksiifenin.raa.storage.ManualResolution;
import io.github.oleksiifenin.raa.tck.ActionJournalTck;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lease and deadline decisions use database time, never the JVM clock (README, docs/DESIGN.md §6 Time, §9 Clock). The
 * journal database's clock is one hour ahead of the JVM clock or one hour behind it, and every decision comes out as
 * with the clocks in sync. A decision that read the JVM clock would be an hour off: with the database ahead, a lease or
 * a deadline would not end for another hour; with the database behind, it would have ended an hour ago. Most scenarios
 * catch such a regression in one direction only, so each runs in both; neither direction may be dropped
 * (docs/DESIGN.md §13, ADR-0008).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DatabaseClockSkewTest {

    private static final Duration SHIFT = Duration.ofHours(1);
    /**
     * Far more than the clocks of a test container and its host differ, far less than the shift. The harness check
     * assumes the container reads the host's wall clock, as local and rootless Docker do.
     */
    private static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(1);
    private static final String SCOPE = "conversation-1";
    private static final ActionKey ORDER_42 = ActionKey.of(SCOPE, "refund", "order-42");
    // Longer than the test timeout, so no CI pause ends them by themselves; the tests end them in database time.
    private static final Duration LEASE = Duration.ofSeconds(90);
    private static final Duration MAX_EFFECT = Duration.ofMinutes(3);
    /** Shorter than the shift, so a decision on the JVM clock of a database behind it would see it passed. */
    private static final Duration DEADLINE = Duration.ofMinutes(15);

    // One database per direction for the two runs of the storage suite, as its other run shares one database.
    private static final Database AHEAD = new Database(SHIFT);
    private static final Database BEHIND = new Database(SHIFT.negated());

    private final AtomicInteger effects = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();

    static Stream<Duration> clockShifts() {
        return Stream.of(SHIFT, SHIFT.negated());
    }

    /** The harness check: the clocks really are apart, and the journal writes the database's times. */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void theDatabaseClock_isApartFromTheJvmClock_andTheJournalWritesItsTimes(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(ActionPolicy.atLeastOnce());

        ActionOutcome outcome = actions.runner().run(call("c1"), context -> "refunded");

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        Instant jvmNow = Instant.now();
        ActionRecord row = database.row();
        for (Instant databaseTime : new Instant[] {database.now(), row.createdAt(), row.startedAt(), row.updatedAt()}) {
            assertThat(Duration.between(jvmNow.plus(shift), databaseTime).abs()).isLessThan(CLOCK_TOLERANCE);
        }
        assertThat(row.deadlineAt()).isEqualTo(row.createdAt().plus(DEADLINE));
    }

    /** Row 5 of the decision table (docs/DESIGN.md §6): while the lease is live, another call does not start. */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void aLiveLease_keepsAnotherCallOut(Duration shift) throws Exception {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(ActionPolicy.atLeastOnce());
        ExecutorService otherCaller = Executors.newSingleThreadExecutor();
        AtomicReference<ActionOutcome> second = new AtomicReference<>();
        try {
            ActionOutcome first = actions.runner().run(call("c1"), context -> {
                effects.incrementAndGet();
                Future<ActionOutcome> other = otherCaller.submit(() -> actions.runner().run(call("c2"), again -> {
                    effects.incrementAndGet();
                    return "refunded twice";
                }));
                second.set(other.get(30, TimeUnit.SECONDS));
                return "refunded";
            });

            assertThat(second.get()).isInstanceOf(ActionOutcome.InProgressElsewhere.class);
            assertThat(first).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
            assertThat(effects).hasValue(1);
        } finally {
            otherCaller.shutdownNow();
        }
    }

    /** The lease renewer extends a running attempt's lease from the database time of the renewal. */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void theLeaseRenewer_extendsTheLease_fromTheDatabaseTime(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(ActionPolicy.atLeastOnce());
        Instant[] renewal = new Instant[3];

        ActionOutcome outcome = actions.runner().run(call("c1"), context -> {
            renewal[0] = database.now();
            int renewed = actions.leaseRenewer().renewNow();
            renewal[1] = database.now();
            renewal[2] = database.row().leaseUntil();
            return renewed == 1 ? "refunded" : "renewed " + renewed;
        });

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(renewal[2]).isBetween(renewal[0].plus(LEASE), renewal[1].plus(LEASE));
    }

    /** Rows 6 and 12: once the lease has expired, before the deadline, the next call starts attempt 2. */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void anExpiredLease_beforeTheDeadline_letsTheNextCallStartAnotherAttempt(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(ActionPolicy.atLeastOnce());
        crash(actions);
        database.update("update raa_action set lease_until = clock_timestamp() - interval '1 millisecond'");

        ActionOutcome retried = actions.runner().run(call("c2"), context -> "refunded");

        assertThat(retried).isEqualTo(new ActionOutcome.Succeeded("refunded", 2));
    }

    /**
     * Rows 6 and 10: after the deadline no attempt starts and the action escalates. The lease ended two hours ago, so a
     * decision on the JVM clock would see it expired too and differ only in the deadline.
     */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void afterTheDeadline_noAttemptStarts_andTheActionEscalates(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(ActionPolicy.atLeastOnce());
        crash(actions);
        database.update("update raa_action set started_at = clock_timestamp() - interval '2 hours',"
                + " lease_until = clock_timestamp() - interval '2 hours',"
                + " deadline_at = clock_timestamp() - interval '1 millisecond'");

        ActionOutcome retried = actions.runner().run(call("c2"), context -> {
            effects.incrementAndGet();
            return "refunded";
        });

        assertThat(retried).isInstanceOf(ActionOutcome.Escalated.class);
        assertThat(effects).hasValue(0);
        assertThat(database.row().state()).isEqualTo(ActionState.ESCALATED);
    }

    /**
     * Rows 8 and 9: after the lease expired, the lookup waits until the maximum effect duration has passed since the
     * attempt started, so a slow effect can still land.
     */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void theLookupWindow_opensOnlyAfterTheMaximumEffectDuration(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(reconcilable());
        crash(actions);
        database.update("update raa_action set lease_until = clock_timestamp() - interval '1 millisecond'");

        Instant beforeTheCall = database.now();
        ActionOutcome insideTheWindow = actions.runner().run(call("c2"), context -> "refunded");
        Instant afterTheCall = database.now();
        ActionRecord unknown = database.row();
        int lookupsInsideTheWindow = lookups.get();
        database.update("update raa_action set started_at = started_at - " + (MAX_EFFECT.toMillis() + 1000)
                + " * interval '1 millisecond'");
        ActionOutcome afterTheWindow = actions.runner().run(call("c3"), context -> "refunded");

        // The call marked the attempt unknown with the database's time, due for the reconciler at once, and names
        // the moment the window opens.
        assertThat(unknown.state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(unknown.updatedAt()).isBetween(beforeTheCall, afterTheCall);
        assertThat(unknown.resolveAfter()).isEqualTo(unknown.updatedAt());
        assertThat(insideTheWindow).isInstanceOfSatisfying(ActionOutcome.Unknown.class, outcome -> {
            assertThat(outcome.advice()).isEqualTo(RepeatAdvice.ASK_LATER);
            assertThat(outcome.reason()).contains(unknown.startedAt().plus(MAX_EFFECT).toString());
        });
        assertThat(lookupsInsideTheWindow).isZero();
        assertThat(afterTheWindow).isInstanceOfSatisfying(ActionOutcome.Replayed.class,
                replayed -> assertThat(replayed.fromLookup()).isTrue());
        assertThat(lookups).hasValue(1);
    }

    /** Row 8: after an unreachable lookup, no call looks up again until the backoff ends. */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void aLookupBackoff_holdsTheNextLookupBack_untilItEnds(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(reconcilable());
        crash(actions);
        // As after one unreachable lookup: unknown, lookup window open, backoff running for ten minutes.
        database.update("update raa_action set state = 'UNKNOWN', lease_owner = null, lease_until = null,"
                + " started_at = started_at - " + (MAX_EFFECT.toMillis() + 1000) + " * interval '1 millisecond',"
                + " lookup_failures = 1, resolve_after = clock_timestamp() + interval '10 minutes'");

        ActionOutcome inTheBackoff = actions.runner().run(call("c2"), context -> "refunded");
        Instant backoffEnds = database.row().resolveAfter();
        int lookupsInTheBackoff = lookups.get();
        database.update("update raa_action set resolve_after = clock_timestamp() - interval '1 millisecond'");
        ActionOutcome afterTheBackoff = actions.runner().run(call("c3"), context -> "refunded");

        assertThat(inTheBackoff).isInstanceOfSatisfying(ActionOutcome.Unknown.class, outcome -> {
            assertThat(outcome.advice()).isEqualTo(RepeatAdvice.ASK_LATER);
            assertThat(outcome.reason()).contains(backoffEnds.toString()); // the time the backoff ends
        });
        assertThat(lookupsInTheBackoff).isZero();
        assertThat(afterTheBackoff).isInstanceOfSatisfying(ActionOutcome.Replayed.class,
                replayed -> assertThat(replayed.fromLookup()).isTrue());
        assertThat(lookups).hasValue(1);
    }

    /**
     * The reconciler (docs/DESIGN.md §6 Reconciler): it leaves a live attempt alone, marks it unknown once the lease
     * has expired, does not look it up before the lookup window opens, and then resolves it through its lookup. Its
     * time is the database time of the take.
     */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void theReconciler_resolvesADeadAttempt_throughItsLookup_onlyAfterTheLeaseAndTheLookupWindow(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(reconcilable());
        crash(actions);

        int takenWhileLeaseLive = actions.reconciler().runOnce();
        database.update("update raa_action set lease_until = clock_timestamp() - interval '1 millisecond'");
        int takenExpired = actions.reconciler().runOnce();
        ActionState afterExpiry = database.row().state();
        int takenInsideTheWindow = actions.reconciler().runOnce();
        int lookupsInsideTheWindow = lookups.get();
        // The window opens, and the reconciler's hold on the row ends.
        database.update("update raa_action set started_at = started_at - " + (MAX_EFFECT.toMillis() + 1000)
                + " * interval '1 millisecond', resolve_after = clock_timestamp() - interval '1 millisecond'");
        int takenAfterTheWindow = actions.reconciler().runOnce();

        assertThat(takenWhileLeaseLive).isZero();
        assertThat(takenExpired).isEqualTo(1);
        assertThat(afterExpiry).isEqualTo(ActionState.UNKNOWN);
        assertThat(takenInsideTheWindow).isEqualTo(1);
        assertThat(lookupsInsideTheWindow).isZero();
        assertThat(takenAfterTheWindow).isEqualTo(1);
        assertThat(database.row().state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(lookups).hasValue(1);
    }

    /**
     * The reconciler abandons a failed action at its deadline, not before: the take picks a failed row only once its
     * deadline has passed, and the reconciler checks the deadline again at the time of the take.
     */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void theReconciler_abandonsAFailedAction_onlyAfterItsDeadline(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(ActionPolicy.atLeastOnce());
        crash(actions);
        // As after a definite failure: every transition into FAILED makes the row due for the reconciler.
        database.update("update raa_action set state = 'FAILED', failure_basis = 'DEFINITE', lease_owner = null,"
                + " lease_until = null, resolve_after = clock_timestamp()");

        int takenBeforeTheDeadline = actions.reconciler().runOnce();
        ActionState beforeTheDeadline = database.row().state();
        // The deadline passes, and the reconciler's hold on the row ends.
        database.update("update raa_action set deadline_at = clock_timestamp() - interval '1 millisecond',"
                + " resolve_after = clock_timestamp() - interval '1 millisecond'");
        int takenAfterTheDeadline = actions.reconciler().runOnce();

        assertThat(takenBeforeTheDeadline).isZero();
        assertThat(beforeTheDeadline).isEqualTo(ActionState.FAILED);
        assertThat(takenAfterTheDeadline).isEqualTo(1);
        assertThat(database.row().state()).isEqualTo(ActionState.ABANDONED);
    }

    /** The reconciler escalates an unknown action that has no lookup at its deadline, not before (rows 10 and 12). */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void theReconciler_escalatesAnUnknownActionWithoutALookup_onlyAfterItsDeadline(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(ActionPolicy.atLeastOnce());
        crash(actions);
        // As after markUnknownIfLeaseExpired: unknown and due for the reconciler.
        database.update("update raa_action set state = 'UNKNOWN', lease_owner = null, lease_until = null,"
                + " resolve_after = clock_timestamp()");

        int takenBeforeTheDeadline = actions.reconciler().runOnce();
        ActionState beforeTheDeadline = database.row().state();
        database.update("update raa_action set deadline_at = clock_timestamp() - interval '1 millisecond',"
                + " resolve_after = clock_timestamp() - interval '1 millisecond'");
        int takenAfterTheDeadline = actions.reconciler().runOnce();

        assertThat(takenBeforeTheDeadline).isEqualTo(1);
        assertThat(beforeTheDeadline).isEqualTo(ActionState.UNKNOWN);
        assertThat(takenAfterTheDeadline).isEqualTo(1);
        assertThat(database.row().state()).isEqualTo(ActionState.ESCALATED);
    }

    /**
     * An unreachable lookup in the reconciler: before the deadline the row backs off, with the times of the database,
     * and at the deadline it escalates.
     */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void theReconciler_backsOffAfterAnUnreachableLookup_andEscalatesOnlyAfterTheDeadline(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(ActionPolicy.reconcilable((key, idempotencyKey) -> {
            lookups.incrementAndGet();
            return new LookupResult.Unreachable("the mail server does not answer");
        }));
        crash(actions);
        // Unknown, lookup window open, due for the reconciler.
        database.update("update raa_action set state = 'UNKNOWN', lease_owner = null, lease_until = null,"
                + " started_at = started_at - " + (MAX_EFFECT.toMillis() + 1000) + " * interval '1 millisecond',"
                + " resolve_after = clock_timestamp()");

        Instant beforeTheLookup = database.now();
        int takenBeforeTheDeadline = actions.reconciler().runOnce();
        Instant afterTheLookup = database.now();
        ActionRecord backedOff = database.row();
        database.update("update raa_action set deadline_at = clock_timestamp() - interval '1 millisecond',"
                + " resolve_after = clock_timestamp() - interval '1 millisecond'");
        int takenAfterTheDeadline = actions.reconciler().runOnce();

        assertThat(takenBeforeTheDeadline).isEqualTo(1);
        assertThat(backedOff.state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(backedOff.lookupFailures()).isEqualTo(1);
        assertThat(backedOff.updatedAt()).isBetween(beforeTheLookup, afterTheLookup);
        assertThat(backedOff.resolveAfter()).isAfter(backedOff.updatedAt());
        assertThat(takenAfterTheDeadline).isEqualTo(1);
        assertThat(lookups).hasValue(2);
        assertThat(database.row().state()).isEqualTo(ActionState.ESCALATED);
    }

    /**
     * An operator's resolution of an escalated action is stamped with the database time: retention measures the age
     * of a finished row from that stamp.
     */
    @ParameterizedTest
    @MethodSource("clockShifts")
    void anOperatorsResolution_isStampedWithTheDatabaseTime(Duration shift) {
        Database database = new Database(shift);
        ReliableActions actions = database.actions(ActionPolicy.atLeastOnce());
        crash(actions);
        database.update("update raa_action set state = 'ESCALATED', lease_owner = null, lease_until = null");

        Instant beforeTheResolution = database.now();
        boolean resolved = actions.admin().resolveManually(KeyAttempt.of(database.row()),
                new ManualResolution.Applied("refund r-1"));
        Instant afterTheResolution = database.now();

        assertThat(resolved).isTrue();
        ActionRecord row = database.row();
        assertThat(row.state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(row.updatedAt()).isBetween(beforeTheResolution, afterTheResolution);
    }

    /** The storage conformance suite with the database clock one hour ahead of the JVM clock. */
    @Nested
    class StorageSuite_withTheDatabaseClockAhead extends StorageSuite {
        StorageSuite_withTheDatabaseClockAhead() {
            super(AHEAD);
        }
    }

    /** The storage conformance suite with the database clock one hour behind the JVM clock. */
    @Nested
    class StorageSuite_withTheDatabaseClockBehind extends StorageSuite {
        StorageSuite_withTheDatabaseClockBehind() {
            super(BEHIND);
        }
    }

    abstract static class StorageSuite extends ActionJournalTck {

        private final Database database;

        StorageSuite(Database database) {
            this.database = database;
        }

        @Override
        protected ActionJournal journal() {
            return database.journal;
        }

        @Override
        protected void awaitTransactionsWaitingForLock(int count) {
            eventually(() -> database.transactionsWaitingForLock() >= count);
        }
    }

    private void crash(ReliableActions actions) {
        try {
            actions.runner().run(call("c1"), context -> {
                effects.incrementAndGet();
                throw new SimulatedCrash();
            });
        } catch (SimulatedCrash expected) {
            // the row stays EXECUTING, as after a crash
        }
        effects.set(0);
    }

    private JournaledPolicy<?> reconcilable() {
        return ActionPolicy.reconcilable((key, idempotencyKey) -> {
            lookups.incrementAndGet();
            return new LookupResult.Found("refund r-1 for " + idempotencyKey);
        });
    }

    private static ToolCall call(String callId) {
        Map<String, Object> arguments = Map.of("orderId", "order-42", "amount", new BigDecimal("10"));
        return new ToolCall("refund", "{\"orderId\":\"order-42\",\"amount\":10}", arguments, callId, SCOPE);
    }

    /** A database of its own with a shifted clock: the reconciler takes any row that needs it. */
    private static final class Database {

        private final DataSource dataSource;
        private final ActionJournal journal;

        Database(Duration clockShift) {
            dataSource = PostgresTestDatabase.get().newDatabase(clockShift);
            journal = PostgresActionJournal.create(dataSource);
        }

        ReliableActions actions(JournaledPolicy<?> policy) {
            JournaledPolicy<?> complete = policy
                    .key((call, args) -> ActionKey.of(SCOPE, call.toolName(), args.string("orderId")))
                    .maxEffectDuration(MAX_EFFECT)
                    .deadline(DEADLINE);
            return ReliableActions.builder(journal).tool("refund", complete).lease(LEASE)
                    // The reconciler needs the key text for its lookups (ADR-0004).
                    .inProgressWait(Duration.ofMillis(200)).storeKeyText(true).build();
        }

        ActionRecord row() {
            return journal.findByProviderCallId(ORDER_42.scopeHash(), "c1").orElseThrow().action();
        }

        /** Runs an update that must change the database's only action row. */
        void update(String sql) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                assertThat(statement.executeUpdate()).isEqualTo(1);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        Instant now() {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("select clock_timestamp()");
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getObject(1, OffsetDateTime.class).toInstant();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        int transactionsWaitingForLock() {
            String sql = "select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                    + " and datname = current_database()";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }
}
