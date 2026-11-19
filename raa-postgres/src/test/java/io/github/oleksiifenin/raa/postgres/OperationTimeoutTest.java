package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.ToolCall;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The operation timeout of ADR-0006 against a healthy database: a row lock held by a frozen process and a slow pool.
 * Network faults are in {@link DatabaseFaultTest}.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OperationTimeoutTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final String scope = "conversation-" + UUID.randomUUID();
    private final AtomicInteger effects = new AtomicInteger();

    @Test
    void defaultTimeout_isTwoAndAHalfSeconds_andTheTimeoutMustBePositive() {
        DataSource dataSource = PostgresTestDatabase.get().newDataSource();

        assertThat(PostgresActionJournal.create(dataSource).operationTimeout()).isEqualTo(Duration.ofMillis(2500));
        assertThat(PostgresActionJournal.DEFAULT_OPERATION_TIMEOUT).isEqualTo(Duration.ofMillis(2500));
        assertThat(PostgresActionJournal.create(dataSource, TIMEOUT).operationTimeout()).isEqualTo(TIMEOUT);
        assertThatThrownBy(() -> PostgresActionJournal.create(dataSource, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostgresActionJournal.create(dataSource, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rowLockHeldByAFrozenProcess_isCancelledByTheDatabaseWithinTheBudget_andTheConnectionStaysInUse()
            throws SQLException {
        try (HikariDataSource pool = singleConnectionPool()) {
            ReliableActions actions = actions(PostgresActionJournal.create(pool, TIMEOUT));
            assertThat(actions.runner().run(call("c1"), this::refund)).isInstanceOf(ActionOutcome.Succeeded.class);
            int backendBefore = backendPid(pool);

            ActionOutcome blocked;
            long elapsedNanos;
            // A transaction that holds the row lock and does nothing, as a paused process would.
            try (Connection holder = PostgresTestDatabase.get().newDataSource().getConnection()) {
                holder.setAutoCommit(false);
                try (PreparedStatement lock = holder.prepareStatement(
                        "select 1 from raa_action where key_hash = ? for update")) {
                    lock.setBytes(1, ActionKey.of(scope, "refund", "order-42").hash());
                    assertThat(lock.executeQuery().next()).isTrue();
                }
                long started = System.nanoTime();
                blocked = actions.runner().run(call("c2"), this::refund);
                elapsedNanos = System.nanoTime() - started;
                holder.rollback();
            }

            assertThat(blocked).isInstanceOfSatisfying(ActionOutcome.JournalUnavailable.class,
                    unavailable -> assertThat(unavailable.reason())
                            .as("cancelled by the database (statement or lock timeout), not by the socket")
                            .contains("canceling statement due to"));
            assertThat(Duration.ofNanos(elapsedNanos)).isLessThan(TIMEOUT.plusMillis(300));
            assertThat(backendPid(pool)).as("the same database connection, not broken by the timeout")
                    .isEqualTo(backendBefore);
            assertThat(actions.runner().run(call("c3"), this::refund)).isInstanceOf(ActionOutcome.Replayed.class);
            assertThat(effects).hasValue(1);
        }
    }

    @Test
    void budgetUsedUpWhileWaitingForAConnection_failsTheClaimWithoutTouchingTheDatabase() {
        DataSource slowPool = new SlowDataSource(PostgresTestDatabase.get().newDataSource(), TIMEOUT.plusMillis(200));
        ReliableActions actions = actions(PostgresActionJournal.create(slowPool, TIMEOUT));

        ActionOutcome outcome = actions.runner().run(call("c1"), this::refund);

        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.JournalUnavailable.class,
                unavailable -> assertThat(unavailable.reason())
                        .contains("was used up before the transaction began").contains("operation timeout"));
        assertThat(effects).hasValue(0);
        assertThat(PostgresActionJournal.create(PostgresTestDatabase.get().newDataSource())
                .findByProviderCallId(ActionKey.of(scope, "refund").scopeHash(), "c1")).isEmpty();
    }

    /** Committed is committed: a connection that cannot be closed cleanly afterwards does not turn it into a failure. */
    @Test
    void closeFailingAfterACommit_doesNotFailTheOperation() {
        DataSource failingClose = new FailingCloseDataSource(PostgresTestDatabase.get().newDataSource());
        ReliableActions actions = actions(PostgresActionJournal.create(failingClose, TIMEOUT));

        assertThat(actions.runner().run(call("c1"), this::refund)).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(actions.runner().run(call("c2"), this::refund)).isInstanceOf(ActionOutcome.Replayed.class);
        assertThat(effects).hasValue(1);
    }

    private String refund(io.github.oleksiifenin.raa.core.ActionContext context) {
        effects.incrementAndGet();
        return "refunded";
    }

    private ReliableActions actions(PostgresActionJournal journal) {
        return ReliableActions.builder(journal)
                .tool("refund", ActionPolicy.atMostOnce()
                        .key((call, args) -> ActionKey.of(scope, call.toolName(), args.string("orderId")))
                        .maxEffectDuration(Duration.ofSeconds(10)))
                .build();
    }

    private ToolCall call(String callId) {
        return new ToolCall("refund", "{\"orderId\":\"order-42\"}", Map.of("orderId", "order-42"), callId, scope);
    }

    private static int backendPid(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static HikariDataSource singleConnectionPool() {
        HikariConfig config = new HikariConfig();
        config.setDataSource(PostgresTestDatabase.get().newDataSource());
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(1000);
        return new HikariDataSource(config);
    }

    /** Connections whose close closes them and then throws, as a pool's failed reset would. */
    private static final class FailingCloseDataSource extends SlowDataSource {

        FailingCloseDataSource(DataSource delegate) {
            super(delegate, Duration.ZERO);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (instance, method, args) -> {
                        try {
                            Object value = method.invoke(connection, args);
                            if ("close".equals(method.getName())) {
                                throw new SQLException("reset failed on close", "08003");
                            }
                            return value;
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    /** A pool that hands out each connection only after a delay, as an overloaded pool would. */
    private static class SlowDataSource implements DataSource {

        private final DataSource delegate;
        private final Duration delay;

        SlowDataSource(DataSource delegate, Duration delay) {
            this.delegate = delegate;
            this.delay = delay;
        }

        @Override
        public Connection getConnection() throws SQLException {
            long end = System.nanoTime() + delay.toNanos();
            while (System.nanoTime() - end < 0) {
                LockSupport.parkNanos(end - System.nanoTime()); // the injected fault itself, not synchronization
            }
            return delegate.getConnection();
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
