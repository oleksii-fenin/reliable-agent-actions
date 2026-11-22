package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.ToolCall;
import io.github.oleksiifenin.raa.storage.ActionState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Overhead (Q3) and throughput (Q5) of a journaled action on PostgreSQL (docs/DESIGN.md §2, §9). A measurement, not a
 * gate: it asserts only that every action succeeded exactly once and writes the numbers to
 * {@code build/overhead/results.txt} and the log.
 *
 * <p>The effect does nothing, so the time of {@code ActionRunner.run} is the added latency: the claim transaction, the
 * record transaction and the work of the runtime around them. Every action has its own key (the happy path of a first
 * call). The journal uses HikariCP with the settings the README recommends, against a database of its own. Two floors
 * on the same pool show where the time goes: a {@code select 1} transaction (one round trip) and a transaction that
 * inserts one row (a round trip and a durable commit, which a journaled action needs twice).
 *
 * <p>Off by default. {@code raa.overhead.actions} (Gradle property {@code overheadActions}) is the number of measured
 * actions per phase; 1 000 more warm the JVM and the pool before each phase. Phase 1 runs them one after another (Q3),
 * phase 2 on {@link #THREADS} threads in a closed loop (Q5): each thread starts its next call when the previous one
 * returns, so the pool is saturated and the latencies of phase 2 are those at saturation, not at a given offered load.
 * Run it alone ({@code --tests}), with {@code --rerun} so that Gradle does not treat an earlier run as up to date.
 */
@EnabledIfSystemProperty(named = "raa.overhead.actions", matches = "[1-9][0-9]*")
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class OverheadBenchmarkTest {

    private static final System.Logger LOG = System.getLogger(OverheadBenchmarkTest.class.getName());
    private static final int WARM_UP = 1000;
    private static final int THREADS = 8;
    private static final int POOL_SIZE = 10;
    private static final String SCOPE = "overhead";
    private static final Path RESULTS = Path.of("build", "overhead", "results.txt");

    private final int actions = Integer.getInteger("raa.overhead.actions");
    private final AtomicInteger effects = new AtomicInteger();
    private final AtomicInteger nextOrder = new AtomicInteger();

    @Test
    void journaledActions_sequentiallyAndOnEightThreads() throws Exception {
        DataSource database = PostgresTestDatabase.get().newDatabase();
        try (HikariDataSource pool = recommendedPool(database)) {
            PostgresActionJournal journal = PostgresActionJournal.create(pool);
            ReliableActions reliable = ReliableActions.builder(journal)
                    .tool("refund", ActionPolicy.atMostOnce()
                            .key((call, args) -> ActionKey.of(SCOPE, call.toolName(), args.string("orderId")))
                            .maxEffectDuration(Duration.ofSeconds(60)))
                    .build();

            sequential(reliable, WARM_UP);
            long[] floor = roundTrips(pool, actions, "select 1");
            execute(pool, "create table overhead_floor (id bigserial primary key, value integer not null)");
            long[] writeFloor = roundTrips(pool, actions, "insert into overhead_floor (value) values (1)");
            long[] sequential = sequential(reliable, actions);
            concurrent(reliable, WARM_UP);
            long started = System.nanoTime();
            long[] concurrent = concurrent(reliable, actions);
            long elapsed = System.nanoTime() - started;

            int total = 2 * WARM_UP + 2 * actions;
            assertThat(effects).hasValue(total);
            assertThat(journal.count(ActionState.SUCCEEDED)).isEqualTo(total);
            report(pool, floor, writeFloor, sequential, concurrent, elapsed);
        }
    }

    /** Runs {@code count} first calls one after another and returns the duration of each. */
    private long[] sequential(ReliableActions reliable, int count) {
        long[] durations = new long[count];
        for (int i = 0; i < count; i++) {
            durations[i] = timedAction(reliable);
        }
        return durations;
    }

    /** Runs {@code count} first calls on {@link #THREADS} threads that start together. */
    private long[] concurrent(ReliableActions reliable, int count) throws Exception {
        long[] durations = new long[count];
        AtomicInteger next = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                workers.add(executor.submit(() -> {
                    start.await();
                    for (int i = next.getAndIncrement(); i < count && !Thread.currentThread().isInterrupted();
                            i = next.getAndIncrement()) {
                        durations[i] = timedAction(reliable);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get();
            }
        } finally {
            executor.shutdownNow();
        }
        return durations;
    }

    private long timedAction(ReliableActions reliable) {
        String orderId = "order-" + nextOrder.incrementAndGet();
        ToolCall call = new ToolCall("refund", "{\"orderId\":\"" + orderId + "\"}", Map.of("orderId", orderId),
                "call-" + orderId, SCOPE);
        long started = System.nanoTime();
        ActionOutcome outcome = reliable.runner().run(call, context -> {
            effects.incrementAndGet();
            return "refunded";
        });
        long duration = System.nanoTime() - started;
        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        return duration;
    }

    /** {@code sql} in a pooled transaction of its own, {@code count} times. */
    private static long[] roundTrips(DataSource pool, int count, String sql) throws SQLException {
        long[] durations = new long[count];
        for (int i = 0; i < count; i++) {
            long started = System.nanoTime();
            try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute(sql);
                connection.commit();
            }
            durations[i] = System.nanoTime() - started;
        }
        return durations;
    }

    private static void execute(DataSource pool, String sql) throws SQLException {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static HikariDataSource recommendedPool(DataSource database) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(database);
        config.setMaximumPoolSize(POOL_SIZE);
        config.setConnectionTimeout(1000);
        config.setValidationTimeout(500);
        return new HikariDataSource(config);
    }

    private void report(DataSource pool, long[] floor, long[] writeFloor, long[] sequential, long[] concurrent,
            long elapsedNanos) throws SQLException, IOException {
        String text = String.join("\n",
                "Overhead of a journaled action (OverheadBenchmarkTest), " + actions + " measured actions per phase"
                        + " after " + WARM_UP + " warm-up actions",
                "  environment: " + environment(pool),
                "  floor, pooled select-1 transaction: " + percentiles(floor),
                "  floor, pooled one-row insert:       " + percentiles(writeFloor),
                "  Q3 sequential, 1 thread:           " + percentiles(sequential),
                "  Q5 concurrent, " + THREADS + " threads, pool " + POOL_SIZE + ": " + percentiles(concurrent),
                String.format(Locale.ROOT, "  Q5 throughput: %.0f actions/s", concurrent.length / (elapsedNanos / 1e9)),
                "");
        LOG.log(System.Logger.Level.INFO, text);
        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, text, StandardCharsets.UTF_8);
    }

    private static String environment(DataSource pool) throws SQLException {
        List<String> settings = new ArrayList<>();
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            for (String query : List.of("select version()", "show synchronous_commit", "show fsync")) {
                try (ResultSet resultSet = statement.executeQuery(query)) {
                    resultSet.next();
                    settings.add(resultSet.getString(1));
                }
            }
        }
        return "Java " + Runtime.version() + ", " + Runtime.getRuntime().availableProcessors() + " CPUs, "
                + System.getProperty("os.name") + " " + System.getProperty("os.version") + "; " + settings.get(0)
                + "; synchronous_commit=" + settings.get(1) + ", fsync=" + settings.get(2);
    }

    /** p50, p90, p99 and max in milliseconds. */
    static String percentiles(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, "p50 %.2f ms, p90 %.2f ms, p99 %.2f ms, max %.2f ms",
                millis(percentile(sorted, 50)), millis(percentile(sorted, 90)), millis(percentile(sorted, 99)),
                millis(sorted[sorted.length - 1]));
    }

    /** The nearest-rank percentile of sorted values: the value at rank ceil(percent × n / 100), in integers. */
    static long percentile(long[] sorted, int percent) {
        long rank = ((long) percent * sorted.length + 99) / 100;
        return sorted[(int) Math.max(rank, 1) - 1];
    }

    private static double millis(long nanos) {
        return nanos / 1e6;
    }
}
