package io.github.oleksiifenin.raa.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.Effect;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.ToolCall;
import io.github.oleksiifenin.raa.testkit.FakeDownstream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * One of the two processes of the two-JVM Q2 test ({@link TwoJvmConcurrencyTest}): {@code threads} threads call one
 * action at once, each with its own tool-call id.
 *
 * <p>Arguments: {@code <jdbc url> <user> <password> <downstream uri> <scope> <process name> <threads>}. Protocol on
 * standard output: {@value #READY} once every thread waits at the start line; after the line {@code go} on standard
 * input, {@value #OUTCOME}{@code <TAB>type<TAB>text} per finished call, {@value #DONE} when all are finished; after the
 * line {@code after}, one more call and {@value #AFTER}{@code <TAB>type<TAB>text}.
 */
public final class ConcurrencyChild {

    static final String READY = "RAA-READY";
    static final String OUTCOME = "RAA-OUTCOME";
    static final String DONE = "RAA-DONE";
    static final String AFTER = "RAA-AFTER";
    static final String TOOL = "refund";
    /** Longer than the parent ever holds the response; the lease stays live, renewed, meanwhile. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    private ConcurrencyChild() {
    }

    public static void main(String[] args) throws Exception {
        URI downstream = URI.create(args[3]);
        String scope = args[4];
        String process = args[5];
        int threads = Integer.parseInt(args[6]);
        HikariDataSource dataSource = journalPool(args[0], args[1], args[2], threads);
        HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

        ReliableActions actions = ReliableActions.builder(PostgresActionJournal.create(dataSource))
                .tool(TOOL, ActionPolicy.atLeastOnce()
                        .key((call, arguments) -> ActionKey.of(scope, call.toolName(), arguments.string("orderId")))
                        .maxEffectDuration(Duration.ofSeconds(30)))
                .lease(Duration.ofSeconds(10)).inProgressWait(Duration.ofSeconds(2)).build();
        actions.leaseRenewer().start();
        Effect effect = context -> post(http, downstream, context.idempotencyKey());

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch arrived = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> calls = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            ToolCall call = call(scope, "call-" + process + "-" + thread);
            calls.add(pool.submit(() -> {
                arrived.countDown();
                go.await();
                print(OUTCOME, actions.runner().run(call, effect));
                return null;
            }));
        }
        arrived.await(30, TimeUnit.SECONDS);
        System.out.println(READY);
        System.out.flush();
        expectLine(stdin, "go");
        go.countDown();
        for (Future<?> call : calls) {
            call.get(60, TimeUnit.SECONDS);
        }
        System.out.println(DONE);
        System.out.flush();
        expectLine(stdin, "after");
        print(AFTER, actions.runner().run(call(scope, "call-" + process + "-after"), effect));
        System.exit(0); // the lease renewer and HTTP client threads must not keep the JVM alive
    }

    /**
     * A journal pool as the README recommends, one connection per calling thread plus the renewer, filled before the
     * start: without a pool every operation opens a physical connection, and 64 concurrent set-ups on one machine use
     * up most of the 2.5 s operation timeout before the claim starts.
     */
    private static HikariDataSource journalPool(String url, String user, String password, int threads)
            throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(threads + 2);
        config.setConnectionTimeout(1000);
        config.setValidationTimeout(500);
        HikariDataSource pool = new HikariDataSource(config);
        List<Connection> filling = new ArrayList<>();
        for (int i = 0; i < threads + 2; i++) {
            filling.add(pool.getConnection());
        }
        for (Connection connection : filling) {
            connection.close();
        }
        return pool;
    }

    private static ToolCall call(String scope, String callId) {
        return new ToolCall(TOOL, "{\"orderId\":\"order-42\",\"amount\":30}",
                Map.of("orderId", "order-42", "amount", new BigDecimal("30")), callId, scope);
    }

    private static String post(HttpClient http, URI downstream, String idempotencyKey)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(downstream + "/effects"))
                .timeout(HTTP_TIMEOUT).header(FakeDownstream.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"order-42\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("downstream answered HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static void expectLine(BufferedReader stdin, String expected) throws IOException {
        String line = stdin.readLine();
        if (!expected.equals(line)) {
            throw new IllegalStateException("expected '" + expected + "' on standard input, got " + line);
        }
    }

    private static void print(String prefix, ActionOutcome outcome) {
        String text = outcome instanceof ActionOutcome.Succeeded ? ((ActionOutcome.Succeeded) outcome).resultText()
                : outcome instanceof ActionOutcome.Replayed ? ((ActionOutcome.Replayed) outcome).resultText()
                : outcome.toString();
        System.out.println(prefix + "\t" + outcome.getClass().getSimpleName() + "\t"
                + text.replace('\t', ' ').replace('\n', ' '));
        System.out.flush();
    }
}
