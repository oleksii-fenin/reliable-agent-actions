package io.github.oleksiifenin.raa.postgres;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.Effect;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.LookupResult;
import io.github.oleksiifenin.raa.core.OutcomeLookup;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.RepeatAdvice;
import io.github.oleksiifenin.raa.core.ToolCall;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.testkit.FakeDownstream;
import io.github.oleksiifenin.raa.testkit.FaultInjector;
import io.github.oleksiifenin.raa.testkit.FaultPoint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The process the kill matrix starts and kills (docs/DESIGN.md §9, §9.1): a plain, scripted caller of one refund tool.
 *
 * <p>Arguments: {@code <role> <policy> <fault point> <jdbc url> <user> <password> <downstream uri> <scope>}. Roles:
 * {@code first} makes call {@code call-1} and stops at the armed fault point; {@code resume} waits until the lease of
 * the first attempt expired and its lookup window opened (database time), then calls again with new tool-call ids,
 * following the scripted-model rules of §9.1, and prints {@value #FINAL} with the outcome; {@code bypass-first} and
 * {@code bypass-resume} do the same without the library (negative control).
 */
public final class KillMatrixChild {

    /** Prefix of the line with the final outcome: {@code RAA-FINAL<TAB>type<TAB>fromLookup<TAB>text<TAB>calls}. */
    static final String FINAL = "RAA-FINAL";
    /** Printed by {@code random-first} right before the call; the randomized kill counts its delay from here. */
    static final String CALLING = "RAA-CALLING";

    static final Duration LEASE = Duration.ofSeconds(2);
    static final Duration MAX_EFFECT = Duration.ofSeconds(3);
    static final Duration DEADLINE = Duration.ofSeconds(60);
    static final String TOOL = "refund";
    static final String FIRST_CALL_ID = "call-1";
    private static final Duration HTTP_TIMEOUT = Duration.ofMillis(2500); // below MAX_EFFECT
    private static final Duration RESUME_MARGIN = Duration.ofMillis(500);
    private static final int MAX_RECALLS = 3;
    private static final Pattern CALL_AGAIN_AFTER = Pattern.compile("call again after (\\S+)");

    private final String policyName;
    private final FaultInjector injector;
    private final DataSource dataSource;
    private final URI downstream;
    private final String scope;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    private KillMatrixChild(String[] args) {
        this.policyName = args[1];
        FaultPoint point = "NONE".equals(args[2]) ? null : FaultPoint.valueOf(args[2]);
        this.injector = "pause-first".equals(args[0]) && point != null
                ? FaultInjector.resumable(point, System.out, System.in)
                : new FaultInjector(point, System.out);
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(args[3]);
        source.setUser(args[4]);
        source.setPassword(args[5]);
        this.dataSource = source;
        this.downstream = URI.create(args[6]);
        this.scope = args[7];
    }

    public static void main(String[] args) throws Exception {
        KillMatrixChild child = new KillMatrixChild(args);
        switch (args[0]) {
            case "first":
            case "pause-first":
                child.first();
                break;
            case "resume":
                child.resume();
                break;
            case "random-first":
                child.randomFirst();
                break;
            case "random-resume":
                child.randomResume();
                break;
            case "bypass-first":
                child.bypassFirst();
                break;
            case "bypass-resume":
                child.bypassResume();
                break;
            default:
                throw new IllegalArgumentException("unknown role " + args[0]);
        }
        System.out.flush();
        System.exit(0); // the lease renewer and HTTP client threads must not keep the JVM alive
    }

    private void first() {
        ReliableActions actions = actions(PostgresActionJournal.create(dataSource));
        actions.leaseRenewer().start();
        ActionOutcome outcome = actions.runner().run(call(FIRST_CALL_ID), effect());
        injector.reach(FaultPoint.AFTER_RECORD_BEFORE_MEMORY);
        printFinal(outcome, 1);
    }

    /**
     * Like {@code first}, but announces the call so that the parent can kill it at a random moment. A call of another
     * action first loads the classes and opens the paths (journal, runner), so that the announced call takes about as
     * long as in a warm process; its effect does not reach the downstream.
     */
    private void randomFirst() {
        ReliableActions actions = actions(PostgresActionJournal.create(dataSource));
        actions.leaseRenewer().start();
        Map<String, Object> warmUp = Map.of("orderId", "warm-up", "amount", new BigDecimal("1"));
        actions.runner().run(new ToolCall(TOOL, warmUp.toString(), warmUp, "warm-up", scope), context -> "warm");
        try { // the HTTP client too: a lookup request, which applies nothing
            http.send(HttpRequest.newBuilder(URI.create(downstream + "/effects/warm-up")).timeout(HTTP_TIMEOUT).GET()
                    .build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new UncheckedIOException("warm-up request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        System.out.println(CALLING);
        System.out.flush();
        ActionOutcome outcome = actions.runner().run(call(FIRST_CALL_ID), effect());
        printFinal(outcome, 1);
    }

    private void resume() {
        ActionJournal journal = PostgresActionJournal.create(dataSource);
        ActionRecord first = journal.findByProviderCallId(key().scopeHash(), FIRST_CALL_ID).orElseThrow().action();
        awaitDatabaseTime(first.startedAt().plus(MAX_EFFECT).plus(RESUME_MARGIN));
        callAgain(actions(journal));
    }

    /**
     * Resumes after a kill at a random moment: the first call may have claimed nothing (killed before its claim
     * committed), and the reconciler runs in the background meanwhile (docs/DESIGN.md §9.1, randomized job).
     */
    private void randomResume() {
        ActionJournal journal = PostgresActionJournal.create(dataSource);
        journal.findByProviderCallId(key().scopeHash(), FIRST_CALL_ID).ifPresent(first ->
                awaitDatabaseTime(first.action().startedAt().plus(MAX_EFFECT).plus(RESUME_MARGIN)));
        ReliableActions actions = actions(journal);
        ScheduledExecutorService reconciling = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "reconciler");
            thread.setDaemon(true);
            return thread;
        });
        reconciling.scheduleWithFixedDelay(() -> {
            try {
                actions.reconciler().runOnce();
            } catch (RuntimeException e) {
                System.err.println("reconciler run failed: " + e);
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        callAgain(actions);
    }

    /** The scripted-model rules of §9.1 from a new tool-call id on. */
    private void callAgain(ReliableActions actions) {
        actions.leaseRenewer().start();
        ActionOutcome outcome = null;
        int calls = 0;
        while (calls <= MAX_RECALLS) {
            calls++;
            outcome = actions.runner().run(call("call-" + (calls + 1)), effect());
            if (outcome instanceof ActionOutcome.Unknown
                    && ((ActionOutcome.Unknown) outcome).advice() == RepeatAdvice.ASK_LATER) {
                awaitDatabaseTime(statedTime((ActionOutcome.Unknown) outcome));
                continue;
            }
            boolean mayCallAgain = outcome instanceof ActionOutcome.Unknown
                    || outcome instanceof ActionOutcome.Failed && ((ActionOutcome.Failed) outcome).retryable();
            if (!mayCallAgain) {
                break; // a result, Replayed, Conflict, Escalated, Abandoned: the model stops
            }
        }
        printFinal(outcome, calls);
    }

    private void bypassFirst() throws Exception {
        injector.reach(FaultPoint.AFTER_CLAIM);
        String response = post(bypassKey());
        injector.reach(FaultPoint.AFTER_RESPONSE_BEFORE_RECORD);
        injector.reach(FaultPoint.AFTER_RECORD_BEFORE_MEMORY);
        System.out.println(FINAL + "\tSucceeded\tfalse\t" + response + "\t1");
    }

    private void bypassResume() throws Exception {
        // No journal: the tool result never reached the model, so it calls the tool again.
        String response = post(bypassKey());
        System.out.println(FINAL + "\tSucceeded\tfalse\t" + response + "\t1");
    }

    private ReliableActions actions(ActionJournal journal) {
        return ReliableActions.builder(journal).tool(TOOL, policy()).lease(LEASE).build();
    }

    private JournaledPolicy<?> policy() {
        JournaledPolicy<?> policy;
        switch (policyName) {
            case "IDEMPOTENT_DOWNSTREAM":
                policy = ActionPolicy.idempotentDownstream(Duration.ofHours(1));
                break;
            case "RECONCILABLE":
                policy = ActionPolicy.reconcilable(lookup());
                break;
            case "AT_MOST_ONCE":
                policy = ActionPolicy.atMostOnce();
                break;
            case "AT_LEAST_ONCE":
                policy = ActionPolicy.atLeastOnce();
                break;
            default:
                throw new IllegalArgumentException("unknown policy " + policyName);
        }
        return policy.key((call, args) -> ActionKey.of(scope, call.toolName(), args.string("orderId")))
                .maxEffectDuration(MAX_EFFECT).deadline(DEADLINE).maxAttempts(3);
    }

    private Effect effect() {
        return context -> {
            injector.reach(FaultPoint.AFTER_CLAIM);
            String response = post(context.idempotencyKey());
            injector.reach(FaultPoint.AFTER_RESPONSE_BEFORE_RECORD);
            return response;
        };
    }

    private OutcomeLookup lookup() {
        return (key, idempotencyKey) -> {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create(downstream + "/effects/" + idempotencyKey)).timeout(HTTP_TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return new LookupResult.Found(response.body());
                }
                return response.statusCode() == 404
                        ? new LookupResult.NotFound()
                        : new LookupResult.Unreachable("HTTP " + response.statusCode());
            } catch (IOException e) {
                return new LookupResult.Unreachable(e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new LookupResult.Unreachable("interrupted");
            }
        };
    }

    private String post(String idempotencyKey) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(downstream + "/effects"))
                .timeout(HTTP_TIMEOUT).header(FakeDownstream.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"order-42\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("downstream answered HTTP " + response.statusCode());
        }
        return response.body();
    }

    private ToolCall call(String callId) {
        Map<String, Object> arguments = Map.of("orderId", "order-42", "amount", new BigDecimal("30"));
        return new ToolCall(TOOL, "{\"orderId\":\"order-42\",\"amount\":30}", arguments, callId, scope);
    }

    private ActionKey key() {
        return ActionKey.of(scope, TOOL);
    }

    private String bypassKey() {
        return "bypass-" + scope;
    }

    private Instant statedTime(ActionOutcome.Unknown unknown) {
        Matcher matcher = CALL_AGAIN_AFTER.matcher(unknown.reason());
        return matcher.find() ? Instant.parse(matcher.group(1)) : databaseNow().plusSeconds(1);
    }

    private void awaitDatabaseTime(Instant instant) {
        while (!databaseNow().isAfter(instant)) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50)); // polling database time, not synchronization
        }
    }

    private Instant databaseNow() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select clock_timestamp()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void printFinal(ActionOutcome outcome, int calls) {
        String type = outcome.getClass().getSimpleName();
        boolean fromLookup = outcome instanceof ActionOutcome.Replayed && ((ActionOutcome.Replayed) outcome).fromLookup();
        System.out.println(FINAL + "\t" + type + "\t" + fromLookup + "\t" + oneLine(text(outcome)) + "\t" + calls);
    }

    private static String text(ActionOutcome outcome) {
        if (outcome instanceof ActionOutcome.Succeeded) {
            return ((ActionOutcome.Succeeded) outcome).resultText();
        }
        if (outcome instanceof ActionOutcome.Replayed) {
            return ((ActionOutcome.Replayed) outcome).resultText();
        }
        return outcome.toString();
    }

    private static String oneLine(String text) {
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
