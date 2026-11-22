package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.oleksiifenin.raa.core.ActionContext;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.AtMostOnce;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.ToolCall;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.FailureKind;
import io.github.oleksiifenin.raa.storage.JournalException;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import io.github.oleksiifenin.raa.storage.Resolution;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * Database faults of DESIGN §8/§9 and scenario Q6, with Toxiproxy between the journal and PostgreSQL and a HikariCP
 * pool configured as the README recommends (ADR-0006): a blackholed or stopped database, a database that is down, a
 * response or a request lost at {@code COMMIT} of the claim and of the record, and an exhausted pool.
 */
// A separate thread: a read blocked on a dead socket ignores interrupts, so a regression fails instead of hanging.
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseFaultTest {

    /** Q6: the model gets an answer within this time when the journal is unavailable. */
    private static final Duration Q6 = Duration.ofSeconds(3);
    /** One journal operation: the default operation timeout plus the latency of the abort. */
    private static final Duration OPERATION_BOUND = PostgresActionJournal.DEFAULT_OPERATION_TIMEOUT.plusMillis(500);
    private static final Duration LEASE = Duration.ofSeconds(1);
    private static final int CONTROL_PORT = 8474;
    private static final int PROXY_PORT = 8666;

    private static Network network;
    private static PostgreSQLContainer postgres;
    private static ToxiproxyContainer toxiproxy;
    private static Proxy proxy;
    private static HikariDataSource pool;
    private static NetworkFaults faults;
    private static ActionJournal journal;
    private static DataSource direct;
    private static ActionJournal directJournal;
    /** Through the proxy without a pool: a new physical connection per operation. */
    private static ActionJournal unpooledJournal;

    private final String scope = "conversation-" + UUID.randomUUID();
    private final AtomicInteger effects = new AtomicInteger();

    @BeforeAll
    static void startDatabaseBehindToxiproxy() throws IOException, SQLException {
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer(System.getProperty("raa.postgres.image", "postgres:18-alpine"))
                .withNetwork(network).withNetworkAliases("postgres")
                .withStartupAttempts(3); // rootless Docker sometimes fails a port mapping ("address already in use")
        postgres.start();
        // Only the control port and one proxy port: the module exposes 33 ports by default, and each mapping is a chance
        // for the rootless Docker port collision that fails container start-up.
        toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.13.0").withNetwork(network)
                .withExposedPorts(CONTROL_PORT, PROXY_PORT).withStartupAttempts(3);
        toxiproxy.start();
        proxy = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
                .createProxy("postgres", "0.0.0.0:" + PROXY_PORT, "postgres:5432");

        PGSimpleDataSource directSource = new PGSimpleDataSource();
        directSource.setUrl(postgres.getJdbcUrl());
        directSource.setUser(postgres.getUsername());
        directSource.setPassword(postgres.getPassword());
        direct = directSource;
        directJournal = PostgresActionJournal.create(direct);
        try (Connection connection = direct.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(PostgresTestDatabase.readSchema());
        }

        pool = recommendedPool("jdbc:postgresql://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(PROXY_PORT)
                + "/" + postgres.getDatabaseName(), 4);
        PGSimpleDataSource unpooled = new PGSimpleDataSource();
        unpooled.setUrl(pool.getJdbcUrl());
        unpooled.setUser(postgres.getUsername());
        unpooled.setPassword(postgres.getPassword());
        unpooled.setConnectTimeout(1);
        unpooled.setLoginTimeout(2);
        unpooledJournal = PostgresActionJournal.create(unpooled);
        faults = new NetworkFaults(pool);
        journal = PostgresActionJournal.create(faults); // the default operation timeout, 2.5 s
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            pool.close();
        }
        if (toxiproxy != null) {
            toxiproxy.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @AfterEach
    void healTheNetwork() throws IOException {
        faults.disarm();
        proxy.enable();
        for (Toxic toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    // ---- Q6: the database cannot be reached

    @Test
    void blackholedDatabase_claimIsJournalUnavailableWithinQ6_andNothingRunsOrIsWritten() throws IOException {
        ReliableActions actions = actions(ActionPolicy.atMostOnce());
        warmUpPooledConnection(); // the claim then runs on an open connection and meets the blackhole in a read

        Toxic blackhole = proxy.toxics().timeout("blackhole", ToxicDirection.UPSTREAM, 0);
        Timed<ActionOutcome> outcome = timed(() -> actions.runner().run(call("c1"), this::refund));
        dropHeldData(blackhole);

        assertThat(outcome.value()).isInstanceOfSatisfying(ActionOutcome.JournalUnavailable.class,
                unavailable -> assertThat(unavailable.reason()).contains("operation timeout"));
        assertThat(outcome.elapsed()).isLessThan(Q6);
        assertThat(effects).hasValue(0);
        assertThat(callRow("c1")).as("nothing reached the database").isEmpty();
        assertThat(eventually(() -> actions.runner().run(call("c2"), this::refund)))
                .isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
    }

    @Test
    void databaseDown_claimIsJournalUnavailableWithinQ6() throws IOException {
        ReliableActions actions = actions(ActionPolicy.atMostOnce());

        proxy.disable(); // connections are closed and new ones refused
        Timed<ActionOutcome> outcome = timed(() -> actions.runner().run(call("c1"), this::refund));
        proxy.enable();

        assertThat(outcome.value()).isInstanceOf(ActionOutcome.JournalUnavailable.class);
        assertThat(outcome.elapsed()).isLessThan(Q6);
        assertThat(effects).hasValue(0);
        assertThat(eventually(() -> actions.runner().run(call("c2"), this::refund)))
                .isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
    }

    @Test
    void stoppedDatabaseContainer_claimIsJournalUnavailableWithinQ6_andNothingIsCommitted() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce());
        warmUpPooledConnection();

        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(postgres.getContainerId()).exec();
        Timed<ActionOutcome> outcome;
        try {
            outcome = timed(() -> actions.runner().run(call("c1"), this::refund));
        } finally {
            docker.unpauseContainerCmd(postgres.getContainerId()).exec();
        }

        assertThat(outcome.value()).isInstanceOf(ActionOutcome.JournalUnavailable.class);
        assertThat(outcome.elapsed()).isLessThan(Q6);
        assertThat(effects).hasValue(0);
        assertThat(callRow("c1")).as("the claim was not committed").isEmpty();
        assertThat(eventually(() -> actions.runner().run(call("c2"), this::refund)))
                .isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
    }

    @Test
    void exhaustedPool_claimIsJournalUnavailableWithinQ6_andWorksOnceAConnectionIsFree() throws SQLException {
        try (HikariDataSource smallPool = recommendedPool(postgres.getJdbcUrl(), 2)) {
            ReliableActions actions = ReliableActions.builder(PostgresActionJournal.create(smallPool))
                    .tool("refund", complete(ActionPolicy.atMostOnce())).lease(LEASE).build();
            List<Connection> held = new ArrayList<>();
            held.add(smallPool.getConnection());
            held.add(smallPool.getConnection());

            Timed<ActionOutcome> outcome = timed(() -> actions.runner().run(call("c1"), this::refund));
            for (Connection connection : held) {
                connection.close();
            }

            assertThat(outcome.value()).isInstanceOfSatisfying(ActionOutcome.JournalUnavailable.class,
                    unavailable -> assertThat(unavailable.reason()).contains("Connection is not available"));
            assertThat(outcome.elapsed()).isLessThan(Q6);
            assertThat(effects).hasValue(0);
            assertThat(actions.runner().run(call("c2"), this::refund))
                    .isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        }
    }

    @Test
    void everyJournalOperation_endsWithinItsTimeout_whileTheDatabaseIsBlackholed() throws IOException {
        byte[] keyHash = ActionKey.of(scope, "refund", "order-42").hash();
        byte[] scopeHash = ActionKey.of(scope, "refund").scopeHash();
        KeyAttempt attempt = new KeyAttempt(keyHash, Instant.EPOCH, 1);
        Map<String, Runnable> operations = Map.of(
                "recordSuccess", () -> journal.recordSuccess(attempt, "refunded"),
                "recordFailure", () -> journal.recordFailure(attempt, FailureKind.DEFINITE, "rejected"),
                "markUnknownIfLeaseExpired", () -> journal.markUnknownIfLeaseExpired(attempt, "expired"),
                "renewLeases", () -> journal.renewLeases("owner", List.of(attempt), LEASE),
                "takeReconcileBatch", () -> journal.takeReconcileBatch(LEASE, 10),
                "resolve", () -> journal.resolve(attempt, new Resolution.Escalate("operator")),
                "findByProviderCallId", () -> journal.findByProviderCallId(scopeHash, "c1"));

        for (Map.Entry<String, Runnable> operation : operations.entrySet()) {
            warmUpPooledConnection();
            Toxic blackhole = proxy.toxics().timeout("blackhole", ToxicDirection.UPSTREAM, 0);
            long started = System.nanoTime();
            assertThatThrownBy(operation.getValue()::run).as(operation.getKey())
                    .isInstanceOf(JournalException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            dropHeldData(blackhole);
            assertThat(elapsed).as(operation.getKey()).isLessThan(OPERATION_BOUND);
        }
    }

    /**
     * A response that keeps trickling in never lets a read time out; the operation is still cut off at its timeout
     * (the connection is aborted).
     */
    @ParameterizedTest(name = "pooled = {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void trickledResponse_isCutOffAtTheOperationTimeout(boolean pooled) throws IOException {
        ActionJournal trickled = pooled ? journal : unpooledJournal;
        String large = "r".repeat(4 << 20); // 4 MB at 20 KB/s would take more than three minutes
        ReliableActions actions = actions(ActionPolicy.atMostOnce());
        assertThat(actions.runner().run(call("c1"), context -> large)).isEqualTo(new ActionOutcome.Succeeded(large, 1));
        warmUpPooledConnection();

        Toxic trickle = proxy.toxics().bandwidth("trickle", ToxicDirection.DOWNSTREAM, 20);
        long started = System.nanoTime();
        assertThatThrownBy(() -> trickled.findByProviderCallId(ActionKey.of(scope, "refund").scopeHash(), "c1"))
                .isInstanceOf(JournalException.class).hasMessageContaining("connection aborted");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        dropHeldData(trickle);

        assertThat(elapsed).isLessThan(OPERATION_BOUND);
        assertThat(eventually(() -> actions.runner().run(call("c2"), this::refund)))
                .as("the aborted connection is not used again").isInstanceOf(ActionOutcome.Replayed.class);
    }

    /**
     * A large write to a database that stops reading blocks in the socket write, where no read timeout applies; the
     * record is still cut off at its timeout, and the caller gets the effect's result.
     */
    @Test
    void writeBlockedByASilentDatabase_isCutOffAtTheOperationTimeout() {
        String large = "w".repeat(64 << 20); // more than the socket buffers between the driver and the database hold
        AtomicLong effectReturned = new AtomicLong();
        ReliableActions actions = ReliableActions.builder(journal).tool("refund", complete(ActionPolicy.atMostOnce()))
                .lease(LEASE).recordRetryBudget(Duration.ofMillis(1)).build(); // one record try
        faults.throttleUpstreamBefore("set state = 'SUCCEEDED'");

        ActionOutcome outcome = actions.runner().run(call("c1"), context -> {
            effectReturned.set(System.nanoTime());
            return large;
        });
        Duration recording = Duration.ofNanos(System.nanoTime() - effectReturned.get());
        faults.dropHeldUpstream();

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded(large, 1));
        assertThat(recording).isLessThan(OPERATION_BOUND);
        assertThat(callRow("c1").orElseThrow().state()).as("the record did not land").isEqualTo(ActionState.EXECUTING);
        assertThat(journal.findByProviderCallId(ActionKey.of(scope, "refund").scopeHash(), "c1"))
                .as("the aborted connection is not used again").isPresent();
    }

    // ---- ambiguous commits (DESIGN §8)

    enum CommitFault {
        /** COMMIT reaches the database and commits; its response never comes back. */
        LOST_RESPONSE,
        /** COMMIT never reaches the database (dropped); the transaction is rolled back when the connection closes. */
        LOST_REQUEST
    }

    static Stream<Arguments> claimCommitFaults() {
        return Stream.of(
                Arguments.of(CommitFault.LOST_RESPONSE, ActionPolicy.atMostOnce(), true),
                Arguments.of(CommitFault.LOST_RESPONSE, ActionPolicy.idempotentDownstream(Duration.ofHours(1)), true),
                Arguments.of(CommitFault.LOST_REQUEST, ActionPolicy.atMostOnce(), false));
    }

    /**
     * §8 "claim commit ambiguous": the caller returns {@code JournalUnavailable} without running the effect. If the claim
     * did commit, the row expires to {@code UNKNOWN} and the next call follows the policy: {@code AT_MOST_ONCE}
     * escalates (a false escalation, accepted), {@code IDEMPOTENT_DOWNSTREAM} starts the next attempt.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("claimCommitFaults")
    void faultAtClaimCommit_isJournalUnavailable_andTheEffectDoesNotRun(CommitFault fault, JournaledPolicy<?> policy,
                                                                          boolean committed) {
        ReliableActions actions = actions(policy);
        faults.arm(1, fault); // the claim is the first commit of the call

        Timed<ActionOutcome> outcome = timed(() -> actions.runner().run(call("c1"), this::refund));

        assertThat(outcome.value()).isInstanceOf(ActionOutcome.JournalUnavailable.class);
        assertThat(outcome.elapsed()).isLessThan(Q6);
        assertThat(effects).hasValue(0);
        Optional<ActionRecord> row = callRow("c1");
        assertThat(row.isPresent()).as("claim committed").isEqualTo(committed);
        if (!committed) {
            assertThat(actions.runner().run(call("c2"), this::refund))
                    .isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
            assertThat(effects).hasValue(1);
            return;
        }
        assertThat(row.get().state()).isEqualTo(ActionState.EXECUTING);
        assertThat(row.get().attempt()).isEqualTo(1);
        awaitDatabaseTime(row.get().leaseUntil());

        ActionOutcome next = actions.runner().run(call("c2"), this::refund);

        if (policy instanceof AtMostOnce) {
            assertThat(next).isInstanceOf(ActionOutcome.Escalated.class);
            assertThat(effects).as("an at-most-once action is never retried").hasValue(0);
        } else {
            assertThat(next).isEqualTo(new ActionOutcome.Succeeded("refunded", 2));
            assertThat(effects).hasValue(1);
        }
    }

    /**
     * §8 "record write fails or is ambiguous": the record is retried within its budget; the retry reads back a commit
     * that landed ({@code ALREADY_SUCCEEDED}) or writes the one that did not. The caller gets the real result and the
     * row is {@code SUCCEEDED} without waiting for the lease.
     */
    @ParameterizedTest
    @EnumSource(CommitFault.class)
    void faultAtRecordCommit_isRetried_andTheRowRecordsTheSuccess(CommitFault fault) {
        ReliableActions actions = actions(ActionPolicy.atMostOnce());
        faults.arm(2, fault); // claim, then the record

        ActionOutcome outcome = actions.runner().run(call("c1"), this::refund);

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(faults.count()).as("claim, the faulty record, its retry").isEqualTo(3);
        ActionRecord row = callRow("c1").orElseThrow();
        assertThat(row.state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(row.resultText()).isEqualTo("refunded");
        assertThat(actions.runner().run(call("c2"), this::refund)).isInstanceOfSatisfying(
                ActionOutcome.Replayed.class, replayed -> assertThat(replayed.resultText()).isEqualTo("refunded"));
        assertThat(effects).hasValue(1);
    }

    // ---- helpers

    private String refund(ActionContext context) {
        effects.incrementAndGet();
        return "refunded";
    }

    private ReliableActions actions(JournaledPolicy<?> policy) {
        return ReliableActions.builder(journal).tool("refund", complete(policy)).lease(LEASE).build();
    }

    private JournaledPolicy<?> complete(JournaledPolicy<?> policy) {
        return policy.key((call, args) -> ActionKey.of(scope, call.toolName(), args.string("orderId")))
                .maxEffectDuration(Duration.ofSeconds(5));
    }

    private ToolCall call(String callId) {
        return new ToolCall("refund", "{\"orderId\":\"order-42\"}", Map.of("orderId", "order-42"), callId, scope);
    }

    private Optional<ActionRecord> callRow(String callId) {
        return directJournal.findByProviderCallId(ActionKey.of(scope, "refund").scopeHash(), callId)
                .map(record -> record.action());
    }

    /**
     * The pool settings the README recommends for Q6: a pool wait and a validation below the operation timeout, and
     * driver timeouts for new connections.
     */
    private static HikariDataSource recommendedPool(String jdbcUrl, int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(size);
        config.setConnectionTimeout(1000);
        config.setValidationTimeout(500);
        config.addDataSourceProperty("connectTimeout", "1");
        config.addDataSourceProperty("loginTimeout", "2");
        return new HikariDataSource(config);
    }

    /** One successful operation, so that the next one is handed the same, just used connection. */
    private void warmUpPooledConnection() {
        journal.findByProviderCallId(ActionKey.of(scope, "refund").scopeHash(), "warm-up");
    }

    /**
     * Ends a fault: closes the connections through the proxy (the timeout toxic drops data but keeps them open, and a
     * throttled one would still deliver what it holds) and removes the toxic.
     */
    private static void dropHeldData(Toxic blackhole) throws IOException {
        proxy.disable();
        blackhole.remove();
        proxy.enable();
    }

    private static void awaitDatabaseTime(Instant instant) {
        while (!databaseNow().isAfter(instant)) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50)); // polling database time, not synchronization
        }
    }

    private static Instant databaseNow() {
        try (Connection connection = direct.getConnection();
             PreparedStatement statement = connection.prepareStatement("select clock_timestamp()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Retries a call until the pool has replaced the connections the fault broke (at most 10 s). */
    private static ActionOutcome eventually(Supplier<ActionOutcome> call) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            ActionOutcome outcome = call.get();
            if (!(outcome instanceof ActionOutcome.JournalUnavailable) || System.nanoTime() - end > 0) {
                return outcome;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100)); // polling with a timeout
        }
    }

    private static <T> Timed<T> timed(Supplier<T> action) {
        long started = System.nanoTime();
        T value = action.get();
        return new Timed<>(value, Duration.ofNanos(System.nanoTime() - started));
    }

    private record Timed<T>(T value, Duration elapsed) {
    }

    /**
     * A data source whose connections run network faults at chosen points: around the n-th {@code COMMIT} since
     * {@link #arm} (a Toxiproxy timeout toxic in one direction, removed after the commit), or before preparing a chosen
     * statement (requests are throttled until {@link #dropHeldUpstream}).
     */
    private static final class NetworkFaults implements DataSource {

        private final DataSource delegate;
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicReference<CommitFault> fault = new AtomicReference<>();
        private final AtomicReference<String> holdBefore = new AtomicReference<>();
        private final AtomicReference<Toxic> held = new AtomicReference<>();
        private volatile int faultyCommit;

        NetworkFaults(DataSource delegate) {
            this.delegate = delegate;
        }

        void arm(int commit, CommitFault commitFault) {
            commits.set(0);
            faultyCommit = commit;
            fault.set(commitFault);
        }

        /** Before the statement, the database side reads 1 KB/s: buffers fill up and the driver's write blocks. */
        void throttleUpstreamBefore(String sqlFragment) {
            holdBefore.set(sqlFragment);
        }

        void dropHeldUpstream() {
            Toxic toxic = held.getAndSet(null);
            if (toxic != null) {
                try {
                    dropHeldData(toxic);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        void disarm() {
            fault.set(null);
            holdBefore.set(null);
            dropHeldUpstream();
        }

        int count() {
            return commits.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (instance, method, args) -> {
                        if ("commit".equals(method.getName())) {
                            CommitFault armed = commits.incrementAndGet() == faultyCommit ? fault.get() : null;
                            if (armed != null) {
                                commitThrough(armed, connection);
                                return null;
                            }
                        }
                        String fragment = holdBefore.get();
                        if ("prepareStatement".equals(method.getName()) && fragment != null
                                && ((String) args[0]).contains(fragment) && holdBefore.compareAndSet(fragment, null)) {
                            held.set(proxy.toxics().bandwidth("held-write", ToxicDirection.UPSTREAM, 1));
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private static void commitThrough(CommitFault armed, Connection connection) throws SQLException {
            ToxicDirection direction = armed == CommitFault.LOST_RESPONSE
                    ? ToxicDirection.DOWNSTREAM : ToxicDirection.UPSTREAM;
            Toxic toxic;
            try {
                toxic = proxy.toxics().timeout("commit-fault", direction, 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            try {
                connection.commit();
            } finally {
                try {
                    if (armed == CommitFault.LOST_REQUEST) {
                        dropHeldData(toxic);
                    } else {
                        toxic.remove();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }
}
