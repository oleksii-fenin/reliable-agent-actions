package io.github.oleksiifenin.raa.examples.refund;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.LookupResult;
import io.github.oleksiifenin.raa.langchain4j.RepairReport;
import io.github.oleksiifenin.raa.postgres.PostgresActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.testkit.FaultInjector;
import io.github.oleksiifenin.raa.testkit.FaultPoint;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The process the LangChain4j kill matrix starts and kills: the {@link RefundAgent} itself, with the fault points
 * added from outside (docs/DESIGN.md §9.1).
 *
 * <p>Arguments: {@code <role> <policy> <fault point> <jdbc url> <user> <password> <payment API uri> <conversation>}.
 * Roles: {@code first} asks for the refund and stops at the armed point; {@code resume-a} waits until the first
 * attempt's lease expired and its lookup window opened, then retries the conversation from the start (mode (a));
 * {@code resume-b} waits the same, repairs the memory and continues the conversation (mode (b)); {@code bypass-first}
 * and {@code bypass-resume} do mode (a) with the refund tool registered without the library (negative control).
 * Prints {@value #FINAL} with the final answer and the model's re-calls.
 */
public final class AgentChild {

    static final String FINAL = "RAA-FINAL";
    static final String REPAIR = "RAA-REPAIR";
    static final String CONVERSATION_REQUEST = "refund order 42";
    static final String REFUND_ARGS = "{\"orderId\":\"order-42\",\"amount\":30}";
    static final Duration LEASE = Duration.ofSeconds(2);
    static final Duration MAX_EFFECT = Duration.ofSeconds(3);
    static final Duration DEADLINE = Duration.ofSeconds(60);
    static final String FIRST_CALL_ID = "first-1";
    private static final Duration HTTP_TIMEOUT = Duration.ofMillis(2500); // below MAX_EFFECT
    private static final Duration RESUME_MARGIN = Duration.ofMillis(500);
    private static final Pattern CALL_AGAIN_AFTER = Pattern.compile("call again after (\\S+)");

    private final String role;
    private final String policy;
    private final FaultInjector injector;
    private final DataSource dataSource;
    private final URI paymentApi;
    private final String conversation;

    private AgentChild(String[] args) {
        this.role = args[0];
        this.policy = args[1];
        this.injector = new FaultInjector("NONE".equals(args[2]) ? null : FaultPoint.valueOf(args[2]), System.out);
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(args[3]);
        source.setUser(args[4]);
        source.setPassword(args[5]);
        this.dataSource = source;
        this.paymentApi = URI.create(args[6]);
        this.conversation = args[7];
    }

    public static void main(String[] args) {
        AgentChild child = new AgentChild(args);
        String answer;
        switch (child.role) {
            case "first":
                answer = child.first();
                break;
            case "resume-a":
            case "resume-b":
                answer = child.resume();
                break;
            case "bypass-first":
            case "bypass-resume":
                answer = child.bypass();
                break;
            default:
                throw new IllegalArgumentException("unknown role " + child.role);
        }
        System.out.println(FINAL + "\t" + answer.replace('\t', ' ').replace('\n', ' '));
        System.out.flush();
        System.exit(0); // the lease renewer and HTTP client threads must not keep the JVM alive
    }

    private String first() {
        RefundAgent agent = agent("first-");
        agent.actions().leaseRenewer().start();
        return agent.assistant().chat(conversation, CONVERSATION_REQUEST);
    }

    private String resume() {
        ActionRecord first = PostgresActionJournal.create(dataSource)
                .findByProviderCallId(ActionKey.of(conversation, "refund").scopeHash(), FIRST_CALL_ID).orElseThrow()
                .action();
        awaitDatabaseTime(first.startedAt().plus(MAX_EFFECT).plus(RESUME_MARGIN));
        RefundAgent agent = agent("resume-");
        agent.actions().leaseRenewer().start();
        String answer;
        if ("resume-a".equals(role)) {
            agent.forget(conversation); // the conversation is retried from the start
            answer = agent.assistant().chat(conversation, CONVERSATION_REQUEST);
        } else {
            RepairReport report = agent.repair(conversation);
            for (RepairReport.Entry entry : report.entries()) {
                System.out.println(REPAIR + "\t" + entry.status() + "\t" + entry.text().replace('\n', ' '));
            }
            answer = agent.assistant().chat(conversation, "continue");
        }
        for (int round = 0; round < 3 && answer.startsWith("being verified"); round++) {
            Matcher matcher = CALL_AGAIN_AFTER.matcher(answer);
            awaitDatabaseTime(matcher.find() ? Instant.parse(matcher.group(1)) : databaseNow().plusSeconds(1));
            answer = agent.assistant().chat(conversation, "check again");
        }
        return answer;
    }

    /** The same conversation with the refund tool registered as plain LangChain4j, without the library. */
    private String bypass() {
        JdbcChatMemoryStore store = new JdbcChatMemoryStore(dataSource);
        store.deleteMessages(conversation); // mode (a): the conversation starts from the beginning
        RefundAgent.Assistant assistant = AiServices.builder(RefundAgent.Assistant.class)
                .chatModel(new ScriptedRefundModel(role + "-", REFUND_ARGS))
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder().id(id).maxMessages(50)
                        .chatMemoryStore(store).build())
                .tools(new UnprotectedTools(payments(), "bypass-" + conversation))
                .build();
        return assistant.chat(conversation, CONVERSATION_REQUEST);
    }

    private RefundAgent agent(String callIdPrefix) {
        return RefundAgent.builder(dataSource, payments(), new ScriptedRefundModel(callIdPrefix, REFUND_ARGS))
                .refundPolicy(policy())
                .maxEffectDuration(MAX_EFFECT)
                .lease(LEASE)
                .toolDecorator(this::stopAfterTheRecord)
                .build();
    }

    private Payments payments() {
        Payments http = new HttpPayments(paymentApi, HTTP_TIMEOUT);
        return new Payments() {
            @Override
            public String refund(String orderId, BigDecimal amount, String idempotencyKey) throws IOException {
                injector.reach(FaultPoint.AFTER_CLAIM);
                String confirmation = http.refund(orderId, amount, idempotencyKey);
                injector.reach(FaultPoint.AFTER_RESPONSE_BEFORE_RECORD);
                return confirmation;
            }

            @Override
            public LookupResult findRefund(String idempotencyKey) {
                return http.findRefund(idempotencyKey);
            }
        };
    }

    /** Stops after the journal recorded the result, before LangChain4j adds it to the memory. */
    private ToolExecutor stopAfterTheRecord(ToolExecutor journaled) {
        return new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                String result = journaled.execute(request, memoryId);
                injector.reach(FaultPoint.AFTER_RECORD_BEFORE_MEMORY);
                return result;
            }

            @Override
            public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
                ToolExecutionResult result = journaled.executeWithContext(request, context);
                injector.reach(FaultPoint.AFTER_RECORD_BEFORE_MEMORY);
                return result;
            }
        };
    }

    private JournaledPolicy<?> policy() {
        JournaledPolicy<?> journaled;
        switch (policy) {
            case "IDEMPOTENT_DOWNSTREAM":
                journaled = ActionPolicy.idempotentDownstream(Duration.ofHours(1));
                break;
            case "RECONCILABLE":
                Payments lookup = new HttpPayments(paymentApi, HTTP_TIMEOUT);
                journaled = ActionPolicy.reconcilable((key, idempotencyKey) -> lookup.findRefund(idempotencyKey));
                break;
            case "AT_MOST_ONCE":
                journaled = ActionPolicy.atMostOnce();
                break;
            case "AT_LEAST_ONCE":
                journaled = ActionPolicy.atLeastOnce();
                break;
            default:
                throw new IllegalArgumentException("unknown policy " + policy);
        }
        return journaled.deadline(DEADLINE).maxAttempts(3);
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

    /** The refund tool without the library: a fixed key, no journal. */
    public static final class UnprotectedTools {
        private final Payments payments;
        private final String key;

        UnprotectedTools(Payments payments, String key) {
            this.payments = payments;
            this.key = key;
        }

        @Tool("Refunds an order")
        public String refund(@P("the order id") String orderId, @P("the amount") BigDecimal amount) throws IOException {
            return payments.refund(orderId, amount, key);
        }
    }
}
