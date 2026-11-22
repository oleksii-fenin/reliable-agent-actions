package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.ClaimDecision;
import io.github.oleksiifenin.raa.storage.ClaimRequest;
import io.github.oleksiifenin.raa.storage.ClaimResult;
import io.github.oleksiifenin.raa.storage.ClaimVerdict;
import io.github.oleksiifenin.raa.storage.JournalException;
import io.github.oleksiifenin.raa.storage.JournalWrite;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** PostgreSQL-specific transaction properties that the storage-neutral TCK cannot observe. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PostgresTransactionTest {

    private final PostgresTestDatabase database = PostgresTestDatabase.get();

    @Test
    void claimDecides_insideAReadCommittedTransaction_evenWhenTheSessionDefaultIsSerializable() throws Exception {
        AtomicReference<Connection> lastConnection = new AtomicReference<>();
        DataSource serializableByDefault = recordingDataSource(lastConnection);
        ActionJournal journal = PostgresActionJournal.create(serializableByDefault);
        AtomicReference<String> isolationInDecider = new AtomicReference<>();
        AtomicReference<Boolean> autoCommitInDecider = new AtomicReference<>();
        ActionKey key = ActionKey.of("tx-" + System.nanoTime(), "refund");

        journal.claim(request(key), (request, row, now) -> {
            isolationInDecider.set(currentIsolation(lastConnection.get()));
            autoCommitInDecider.set(autoCommit(lastConnection.get()));
            return new ClaimDecision(ClaimVerdict.START, new JournalWrite.StartAttempt(1));
        });

        assertThat(isolationInDecider.get()).isEqualTo("read committed");
        assertThat(autoCommitInDecider.get()).isFalse();
    }

    @Test
    void markUnknownAndRenewalRacingOnOneRow_neverBothSucceed() throws Exception {
        for (int round = 0; round < 10; round++) {
            ActionJournal journal = PostgresActionJournal.create(database.newDataSource());
            ActionKey key = ActionKey.of("race-" + System.nanoTime(), "refund");
            KeyAttempt attempt = KeyAttempt.of(journal.claim(request(key), (request, row, now) ->
                    new ClaimDecision(ClaimVerdict.START, new JournalWrite.StartAttempt(1))).record());

            // Expire the lease (committed), then hold the row lock so both writers queue behind it and race.
            database.execute("update raa_action set lease_until = clock_timestamp() - interval '1 second'"
                    + " where key_hash = '\\x" + hex(key.hash()) + "'");
            try (Connection holder = database.newDataSource().getConnection()) {
                holder.setAutoCommit(false);
                try (PreparedStatement lock = holder.prepareStatement(
                        "select 1 from raa_action where key_hash = ? for update")) {
                    lock.setBytes(1, key.hash());
                    assertThat(lock.executeQuery().next()).isTrue();
                }
                CompletableFuture<Boolean> marked = CompletableFuture.supplyAsync(
                        () -> journal.markUnknownIfLeaseExpired(attempt, "lease expired"));
                CompletableFuture<Integer> renewed = CompletableFuture.supplyAsync(
                        () -> journal.renewLeases("owner-1", List.of(attempt),
                                Duration.ofMinutes(5)));
                awaitWaitingForLock(2);
                holder.commit();

                boolean markedUnknown = marked.get(30, TimeUnit.SECONDS);
                boolean leaseRenewed = renewed.get(30, TimeUnit.SECONDS) == 1;
                assertThat(markedUnknown ^ leaseRenewed)
                        .as("exactly one of markUnknown (%s) and renewal (%s) wins", markedUnknown, leaseRenewed)
                        .isTrue();
            }
        }
    }

    @Test
    void renewalThatWaitedForTheRowLockPastTheMaximumEffectDuration_renewsNothing() throws Exception {
        ActionJournal journal = PostgresActionJournal.create(database.newDataSource());
        ActionKey key = ActionKey.of("overrun-" + System.nanoTime(), "refund");
        byte[] arguments = key.hash();
        KeyAttempt attempt = KeyAttempt.of(journal.claim(new ClaimRequest(key.hash(), key.scopeHash(), null, "refund",
                "AT_LEAST_ONCE", arguments, arguments, null, null, "owner-1", Duration.ofMinutes(5),
                Duration.ofMillis(500), Duration.ofMinutes(15)), (request, row, now) ->
                new ClaimDecision(ClaimVerdict.START, new JournalWrite.StartAttempt(1))).record());

        try (Connection holder = database.newDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "select 1 from raa_action where key_hash = ? for update")) {
                lock.setBytes(1, key.hash());
                assertThat(lock.executeQuery().next()).isTrue();
            }
            CompletableFuture<Integer> renewed = CompletableFuture.supplyAsync(() -> journal.renewLeases("owner-1",
                    List.of(attempt), Duration.ofMinutes(5)));
            awaitWaitingForLock(1);
            awaitDatabaseTimePastMaxEffect(key);
            holder.commit();

            assertThat(renewed.get(30, TimeUnit.SECONDS)).isZero();
        }
    }

    /** DESIGN §9, "retention delete during claim": the claim waits for the delete and then starts attempt 1. */
    @Test
    void claimWaitingForARowThatRetentionDeletes_decidesWithoutTheRow() throws Exception {
        ActionJournal journal = PostgresActionJournal.create(database.newDataSource());
        ActionKey key = ActionKey.of("retained-" + System.nanoTime(), "refund");
        ActionRecord claimed = journal.claim(request(key), (request, row, now) ->
                new ClaimDecision(ClaimVerdict.START, new JournalWrite.StartAttempt(1))).record();
        journal.recordSuccess(KeyAttempt.of(claimed), "done");
        List<Optional<ActionRecord>> rowsSeen = new CopyOnWriteArrayList<>();

        try (Connection deleting = database.newDataSource().getConnection()) {
            deleting.setAutoCommit(false);
            try (PreparedStatement delete = deleting.prepareStatement(
                    "delete from raa_action where key_hash = ? and state = 'SUCCEEDED'")) {
                delete.setBytes(1, key.hash());
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }
            CompletableFuture<ClaimResult> claim = CompletableFuture.supplyAsync(
                    () -> journal.claim(request(key), (request, row, now) -> {
                        rowsSeen.add(row);
                        return row.isPresent()
                                ? new ClaimDecision(ClaimVerdict.REPLAY, new JournalWrite.None())
                                : new ClaimDecision(ClaimVerdict.START, new JournalWrite.StartAttempt(1));
                    }));
            awaitWaitingForLock(1);
            deleting.commit();

            ClaimResult result = claim.get(30, TimeUnit.SECONDS);
            assertThat(rowsSeen).containsExactly(Optional.empty());
            assertThat(result.decision().verdict()).isEqualTo(ClaimVerdict.START);
            assertThat(result.record().attempt()).isEqualTo(1);
            assertThat(result.record().state()).isEqualTo(ActionState.EXECUTING);
        }
    }

    private void awaitDatabaseTimePastMaxEffect(ActionKey key) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try (Connection connection = database.newDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("select clock_timestamp() > started_at"
                     + " + max_effect_duration from raa_action where key_hash = ?")) {
            statement.setBytes(1, key.hash());
            while (true) {
                try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getBoolean(1)) {
                        return;
                    }
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("database time did not pass the maximum effect duration");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
        }
    }

    @Test
    void storageFailures_areReportedAsJournalException() {
        DataSource unreachable = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    throw new SQLException("connection refused");
                });
        ActionJournal journal = PostgresActionJournal.create(unreachable);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> journal.markUnknownIfLeaseExpired(new KeyAttempt(new byte[32], Instant.EPOCH, 1), "x"))
                .isInstanceOf(JournalException.class)
                .hasMessageContaining("markUnknownIfLeaseExpired")
                .hasRootCauseMessage("connection refused");
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) {
            text.append(String.format("%02x", b));
        }
        return text.toString();
    }

    private void awaitWaitingForLock(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (PostgresActionJournalTckTest.waitingForLock() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(count + " transactions did not start waiting for the lock");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    /** Hands out connections whose session default isolation is SERIALIZABLE and remembers the last one. */
    private DataSource recordingDataSource(AtomicReference<Connection> lastConnection) {
        DataSource delegate = database.newDataSource();
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = method.invoke(delegate, args);
                    if (result instanceof Connection) {
                        Connection connection = (Connection) result;
                        try (PreparedStatement statement = connection.prepareStatement(
                                "set session characteristics as transaction isolation level serializable")) {
                            statement.execute();
                        }
                        lastConnection.set(connection);
                    }
                    return result;
                });
    }

    private static String currentIsolation(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("show transaction_isolation");
             java.sql.ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean autoCommit(Connection connection) {
        try {
            return connection.getAutoCommit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClaimRequest request(ActionKey key) {
        byte[] arguments = key.hash();
        return new ClaimRequest(key.hash(), key.scopeHash(), null, "refund", "AT_LEAST_ONCE", arguments, arguments,
                null, null, "owner-1", Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofMinutes(15));
    }
}
