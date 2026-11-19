package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.postgres.KillMatrixTest.Final;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.testkit.FakeDownstream;
import io.github.oleksiifenin.raa.testkit.FaultInjector;
import io.github.oleksiifenin.raa.testkit.FaultPoint;
import io.github.oleksiifenin.raa.testkit.JvmProcess;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Quality scenario Q1p (docs/DESIGN.md §2, §8 "Process paused > lease", §9 "Pause test"): a child JVM is paused with
 * SIGSTOP inside the effect of attempt 1, its lease expires (its renewer is paused too), a second child calls again and
 * follows the scripted-model rules of §9.1, then the first child is resumed with SIGCONT and finishes its call.
 *
 * <p>Asserted per cell: effects applied downstream, the second child's outcome, and what the paused attempt's late
 * journal write did. A late write of a superseded attempt (or onto an already succeeded row) changes nothing — the row
 * after it is the row before it. A late success of the same attempt does land (docs/DESIGN.md §14, N1): with
 * {@code AT_MOST_ONCE} the second caller escalated attempt 1, and the paused attempt then records the success it really
 * had. The duplicates of {@code AT_LEAST_ONCE} (by definition) and of {@code RECONCILABLE} after a {@code NotFound}
 * lookup while the request was still in the paused process (the documented non-guarantee) are asserted, not hidden.
 *
 * <p>Paused points: {@code AFTER_CLAIM}, inside the effect before the request is sent (it reaches the downstream only
 * after the pause), and {@code AFTER_RESPONSE_BEFORE_RECORD} (the effect is applied, the record write comes after the
 * pause). Not {@code EFFECT_APPLIED_RESPONSE_HELD}: the paused child's HTTP timeout runs on monotonic time, which keeps
 * running under SIGSTOP, so after SIGCONT the held response races the timeout; for the journal that point is the same
 * as {@code AFTER_RESPONSE_BEFORE_RECORD} (applied, record pending).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PauseTest {

    private static final PostgresTestDatabase DATABASE = PostgresTestDatabase.get();
    private static final DataSource DATA_SOURCE = DATABASE.newDataSource();
    private static final ActionJournal JOURNAL = PostgresActionJournal.create(DATA_SOURCE);
    private static final Duration CHILD_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RESUME_MARGIN = Duration.ofMillis(500);

    enum Policy { IDEMPOTENT_DOWNSTREAM, RECONCILABLE, AT_MOST_ONCE, AT_LEAST_ONCE }

    /**
     * One expected cell. Effect numbers are the downstream's counter for the key ({@code effect <n> for <key>}); 0 means
     * the call got no effect text.
     */
    private static final class Expected {
        final int effects;
        final String secondOutcome;
        final boolean secondFromLookup;
        final int secondEffect;
        final int pausedEffect;
        final int finalAttempt;
        final int finalEffect;
        /** Whether the paused attempt's late write changes the row (same attempt, row not yet succeeded). */
        final boolean lateWriteLands;

        Expected(int effects, String secondOutcome, boolean secondFromLookup, int secondEffect, int pausedEffect,
                int finalAttempt, int finalEffect, boolean lateWriteLands) {
            this.effects = effects;
            this.secondOutcome = secondOutcome;
            this.secondFromLookup = secondFromLookup;
            this.secondEffect = secondEffect;
            this.pausedEffect = pausedEffect;
            this.finalAttempt = finalAttempt;
            this.finalEffect = finalEffect;
            this.lateWriteLands = lateWriteLands;
        }
    }

    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        for (FaultPoint point : List.of(FaultPoint.AFTER_CLAIM, FaultPoint.AFTER_RESPONSE_BEFORE_RECORD)) {
            for (Policy policy : Policy.values()) {
                cells.add(Arguments.of(point, policy));
            }
        }
        return cells.stream();
    }

    /** Derived from the decision table (§6), the fencing rules (§5 SPI) and §8 "Process paused > lease". */
    private static Expected expected(FaultPoint point, Policy policy) {
        boolean beforeRequest = point == FaultPoint.AFTER_CLAIM;
        switch (policy) {
            case IDEMPOTENT_DOWNSTREAM:
                // Row 6 → 12: attempt 2 with the same idempotency key; the downstream deduplicates the other request.
                return new Expected(1, "Succeeded", false, 1, 1, 2, 1, false);
            case RECONCILABLE:
                return beforeRequest
                        // Lookup NotFound → FAILED(LOOKUP) → row 15: attempt 2 applies; the paused request lands after
                        // it — the documented duplicate (§8). Its stale record write is fenced.
                        ? new Expected(2, "Succeeded", false, 1, 2, 2, 1, false)
                        // Lookup Found → SUCCEEDED attempt 1; the paused success finds the row already succeeded.
                        : new Expected(1, "Replayed", true, 1, 1, 1, 1, false);
            case AT_MOST_ONCE:
                // Row 6 → 11: escalated, never retried; the paused attempt's success is the same attempt and lands.
                return new Expected(1, "Escalated", false, 0, 1, 1, 1, true);
            case AT_LEAST_ONCE:
                // Row 6 → 10/12: attempt 2 applies again (a duplicate by definition); the stale write is fenced.
                return beforeRequest
                        ? new Expected(2, "Succeeded", false, 1, 2, 2, 1, false)
                        : new Expected(2, "Succeeded", false, 2, 1, 2, 2, false);
            default:
                throw new IllegalStateException(policy.name());
        }
    }

    @ParameterizedTest(name = "{0} x {1}")
    @MethodSource("cells")
    void pausedPastItsLease_whileAnotherCallRetries_thenResumed_endsAsDerived(FaultPoint point, Policy policy) {
        String scope = "pause-" + point + "-" + policy + "-" + UUID.randomUUID();
        try (FakeDownstream downstream = FakeDownstream.start(policy == Policy.IDEMPOTENT_DOWNSTREAM,
                policy == Policy.RECONCILABLE, false);
             JvmProcess paused = JvmProcess.start(KillMatrixChild.class,
                     childArguments("pause-first", policy, point, downstream, scope))) {
            assertThat(paused.command()).as("the pid is the JVM itself, not a wrapper")
                    .hasValueSatisfying(command -> assertThat(command).endsWith("/java"));
            paused.awaitLine(FaultInjector.MARKER + point.name(), CHILD_TIMEOUT);
            pauseOutsideATransaction(paused, scope);
            ActionRecord atPause = row(scope);
            assertThat(atPause.state()).as("state at the pause").isEqualTo(ActionState.EXECUTING);
            assertThat(atPause.attempt()).isEqualTo(1);
            paused.send("continue"); // read only after SIGCONT

            // The pause froze the lease renewer: once the lookup window opened and the lease of the pause ran out (a
            // renewal just before the pause moves it), the lease is still the one of the pause.
            Instant windowOpens = atPause.startedAt().plus(KillMatrixChild.MAX_EFFECT);
            awaitDatabaseTime(later(windowOpens, atPause.leaseUntil()).plus(RESUME_MARGIN));
            ActionRecord beforeRetry = row(scope);
            assertThat(paused.isStopped()).as("still paused").isTrue();
            assertThat(beforeRetry.state()).isEqualTo(ActionState.EXECUTING);
            assertThat(beforeRetry.leaseUntil()).as("lease not renewed while paused").isEqualTo(atPause.leaseUntil())
                    .isBefore(databaseNow());

            Final second = runToEnd(childArguments("resume", policy, null, downstream, scope));
            ActionRecord beforeLateWrite = row(scope);
            assertThat(paused.isStopped()).as("paused during the whole retry").isTrue();

            paused.resume();
            Final first = Final.parse(paused.awaitLine(KillMatrixChild.FINAL, CHILD_TIMEOUT));
            assertThat(paused.awaitExit(CHILD_TIMEOUT)).as("exit code; output:\n" + paused.output()).isZero();
            ActionRecord end = row(scope);

            Expected expected = expected(point, policy);
            String idempotencyKey = ActionKey.of(scope, KillMatrixChild.TOOL, "order-42")
                    .idempotencyKey(end.materialArgsHash());
            assertThat(downstream.appliedEffects(idempotencyKey)).as("effects applied").isEqualTo(expected.effects);
            assertThat(downstream.totalEffects()).as("effects under any key").isEqualTo(expected.effects);
            assertThat(second.outcome).as("second caller: " + second).isEqualTo(expected.secondOutcome);
            assertThat(second.fromLookup).as("second caller's result from the lookup")
                    .isEqualTo(expected.secondFromLookup);
            if (expected.secondEffect > 0) {
                assertThat(second.text).isEqualTo(effectText(expected.secondEffect, idempotencyKey));
            }
            // The paused call performed its effect, so its caller gets the real result whatever the journal says.
            assertThat(first.outcome).as("paused caller: " + first).isEqualTo("Succeeded");
            assertThat(first.text).isEqualTo(effectText(expected.pausedEffect, idempotencyKey));

            assertThat(end.state()).as("final state").isEqualTo(ActionState.SUCCEEDED);
            assertThat(end.attempt()).as("final attempt").isEqualTo(expected.finalAttempt);
            assertThat(end.resultText()).as("recorded result").isEqualTo(effectText(expected.finalEffect,
                    idempotencyKey));
            if (expected.lateWriteLands) {
                assertThat(beforeLateWrite.state()).isEqualTo(ActionState.ESCALATED);
                assertThat(end.updatedAt()).isAfter(beforeLateWrite.updatedAt());
            } else {
                assertUnchanged(beforeLateWrite, end); // the stale write touched 0 rows
            }
        }
    }

    /**
     * Pauses the child at a moment its lease renewer holds no lock on the row: a renewal locks the row for a few
     * milliseconds, and a process stopped inside that transaction would keep the lock, so the retry would wait for it
     * instead of deciding. The probe takes the row lock without waiting; if it is held, the child runs on briefly and is
     * paused again.
     */
    private static void pauseOutsideATransaction(JvmProcess child, String scope) {
        byte[] keyHash = ActionKey.of(scope, KillMatrixChild.TOOL, "order-42").hash();
        for (int tries = 1; ; tries++) {
            child.pause();
            if (rowLockIsFree(keyHash)) {
                return;
            }
            assertThat(tries).as("the row lock was held at every pause").isLessThan(20);
            child.resume();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20)); // let the renewal commit; bounded by the tries
        }
    }

    private static boolean rowLockIsFree(byte[] keyHash) {
        try (Connection connection = DATA_SOURCE.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select 1 from raa_action where key_hash = ? for update nowait")) {
                statement.setBytes(1, keyHash);
                statement.executeQuery().close();
                return true;
            } catch (SQLException e) {
                if ("55P03".equals(e.getSQLState())) { // lock_not_available
                    return false;
                }
                throw e;
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static void assertUnchanged(ActionRecord before, ActionRecord after) {
        assertThat(after.state()).as("state").isEqualTo(before.state());
        assertThat(after.attempt()).as("attempt").isEqualTo(before.attempt());
        assertThat(after.resultText()).as("result").isEqualTo(before.resultText());
        assertThat(after.lastError()).as("last error").isEqualTo(before.lastError());
        assertThat(after.failureBasis()).as("failure basis").isEqualTo(before.failureBasis());
        assertThat(after.updatedAt()).as("updated at: the late write changed nothing").isEqualTo(before.updatedAt());
    }

    private static String effectText(int effect, String idempotencyKey) {
        return "effect " + effect + " for " + idempotencyKey;
    }

    private static Final runToEnd(List<String> arguments) {
        try (JvmProcess child = JvmProcess.start(KillMatrixChild.class, arguments)) {
            String line = child.awaitLine(KillMatrixChild.FINAL, CHILD_TIMEOUT);
            assertThat(child.awaitExit(CHILD_TIMEOUT)).as("exit code; output:\n" + child.output()).isZero();
            return Final.parse(line);
        }
    }

    private static List<String> childArguments(String role, Policy policy, FaultPoint point,
            FakeDownstream downstream, String scope) {
        List<String> arguments = new ArrayList<>(List.of(role, policy.name(), point == null ? "NONE" : point.name()));
        arguments.addAll(DATABASE.connectionArguments());
        arguments.add(downstream.uri().toString());
        arguments.add(scope);
        return arguments;
    }

    private static ActionRecord row(String scope) {
        return JOURNAL.findByProviderCallId(ActionKey.of(scope, KillMatrixChild.TOOL).scopeHash(),
                KillMatrixChild.FIRST_CALL_ID).orElseThrow().action();
    }

    private static void awaitDatabaseTime(Instant instant) {
        while (!databaseNow().isAfter(instant)) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50)); // polling database time, not synchronization
        }
    }

    private static Instant databaseNow() {
        try (Connection connection = DATA_SOURCE.getConnection();
             PreparedStatement statement = connection.prepareStatement("select clock_timestamp()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
