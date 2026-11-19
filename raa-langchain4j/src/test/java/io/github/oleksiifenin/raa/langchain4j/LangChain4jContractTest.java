package io.github.oleksiifenin.raa.langchain4j;

import static io.github.oleksiifenin.raa.langchain4j.ScriptedChatModel.call;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolService;
import io.github.oleksiifenin.raa.core.ActionContext;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.ActionTelemetry;
import io.github.oleksiifenin.raa.core.ActionRejectedException;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.testkit.InMemoryActionJournal;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The adapter through a real LangChain4j {@code AiServices} with a scripted chat model (docs/DESIGN.md §9, "LangChain4j
 * contract"; ADR-0002, compliance). Runs on the version given by {@code -Plangchain4jVersion}, by default the latest.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LangChain4jContractTest {

    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final String REFUND_ARGS = "{\"orderId\":\"order-42\",\"amount\":30}";

    interface Assistant {
        String chat(@MemoryId String conversation, @UserMessage String message);
    }

    interface AssistantWithoutMemoryId {
        String chat(String message);
    }

    /** A refund tool that counts its runs; {@code failure} makes the next run throw. */
    public static final class RefundTools {
        final AtomicInteger runs = new AtomicInteger();
        final List<String> idempotencyKeys = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean overlapped = new AtomicBoolean();
        volatile CountDownLatch holdFirstRunUntil;

        @Tool("Refunds an order")
        public String refund(@P("the order id") String orderId, @P("the amount") BigDecimal amount) throws Exception {
            runs.incrementAndGet();
            idempotencyKeys.add(ActionContext.current().idempotencyKey());
            CountDownLatch holdUntil = holdFirstRunUntil;
            if (holdUntil != null && runs.get() == 1) {
                overlapped.set(holdUntil.await(10, TimeUnit.SECONDS)); // false: the other call never started
            }
            Throwable toThrow = failure.getAndSet(null);
            if (toThrow instanceof Exception) {
                throw (Exception) toThrow;
            }
            if (toThrow instanceof Error) {
                throw (Error) toThrow;
            }
            // LangChain4j 1.14.0 passes 30 as 30.0, 1.23.0 as 30: the text must not depend on the scale.
            return "refunded " + amount.stripTrailingZeros().toPlainString() + " for " + orderId;
        }

        @Tool("Looks an order up")
        public String lookUpOrder(@P("the order id") String orderId) {
            return "order " + orderId + " is paid";
        }
    }

    /** A tool whose result is the final answer. */
    public static final class ImmediateTools {
        @Tool(value = "Cancels a subscription", returnBehavior = ReturnBehavior.IMMEDIATE)
        public String cancel(@P("the subscription id") String subscriptionId) {
            return "cancelled " + subscriptionId;
        }
    }

    private final TestClock clock = new TestClock();
    private final InMemoryActionJournal journal = new InMemoryActionJournal(clock);
    private final RefundTools tools = new RefundTools();

    // ---- wiring

    @Test
    void wrap_keepsSpecificationsAndReturnBehavior_andLeavesReadOnlyToolsAsTheyAre() {
        List<AiServiceTool> original = ToolService.findTools(tools);

        List<AiServiceTool> wrapped = ReliableTools.wrap(tools, actions(ActionPolicy.atLeastOnce()));

        assertThat(wrapped).hasSameSizeAs(original);
        for (AiServiceTool tool : wrapped) {
            AiServiceTool before = original.stream().filter(o -> o.name().equals(tool.name())).findFirst()
                    .orElseThrow();
            assertThat(tool.toolSpecification()).isEqualTo(before.toolSpecification());
            assertThat(tool.returnBehavior()).isEqualTo(before.returnBehavior());
            if (tool.name().equals("refund")) {
                assertThat(tool.toolExecutor()).isInstanceOf(ReliableToolExecutor.class);
            } else {
                assertThat(tool.toolExecutor()).isNotInstanceOf(ReliableToolExecutor.class);
            }
        }
    }

    @Test
    void wrap_rejectsAToolWithoutAPolicy() {
        ReliableActions withoutLookUp = ReliableActions.builder(journal).tool("refund", policy(ActionPolicy.atLeastOnce()))
                .build();

        assertThatThrownBy(() -> ReliableTools.wrap(tools, withoutLookUp))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lookUpOrder has no policy");
    }

    @Test
    void wrap_rejectsImmediateReturn_unlessAllowedForThatTool() {
        ReliableActions actions = ReliableActions.builder(journal).tool("cancel", policy(ActionPolicy.atMostOnce()))
                .build();

        assertThatThrownBy(() -> ReliableTools.wrap(new ImmediateTools(), actions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("returns immediately");
        assertThat(ReliableTools.builder(actions).allowImmediateReturn("cancel").build().wrap(new ImmediateTools()))
                .singleElement().satisfies(tool -> assertThat(tool.returnBehavior()).isEqualTo(ReturnBehavior.IMMEDIATE));
    }

    // ---- through AiServices

    @Test
    void toolRunsOnce_andARegeneratedCallWithANewIdGetsTheRecordedResult() {
        ScriptedChatModel model = new ScriptedChatModel()
                .callsTools(call("t1", "refund", REFUND_ARGS)).answers("done")
                .callsTools(call("t2", "refund", "{\"amount\":30.00,\"orderId\":\"order-42\"}")).answers("done again");
        Assistant assistant = assistant(model, actions(ActionPolicy.atLeastOnce()));

        assistant.chat("conversation-1", "refund order 42");
        List<String> firstResults = model.lastToolResults();
        assistant.chat("conversation-1", "please refund order 42");

        assertThat(firstResults).containsExactly("refunded 30 for order-42");
        assertThat(model.lastToolResults()).last().isEqualTo("refunded 30 for order-42");
        assertThat(tools.runs).hasValue(1);
    }

    @Test
    void afterACrashThatLostTheMemory_theReissuedCallIsReplayed_notRunAgain() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce());
        assistant(new ScriptedChatModel().callsTools(call("t1", "refund", REFUND_ARGS)).answers("done"), actions)
                .chat("conversation-1", "refund order 42");
        // A new process: fresh chat memory, the model asks again with a new tool-call id.
        ScriptedChatModel resumed = new ScriptedChatModel().callsTools(call("t9", "refund", REFUND_ARGS)).answers("ok");

        assistant(resumed, actions).chat("conversation-1", "refund order 42");

        assertThat(tools.runs).hasValue(1);
        assertThat(resumed.lastToolResults()).containsExactly("refunded 30 for order-42");
    }

    @Test
    void crashDuringTheTool_isRetriedAfterTheLease_withTheSameIdempotencyKey() {
        ReliableActions actions = actions(ActionPolicy.idempotentDownstream(Duration.ofHours(1)));
        tools.failure.set(new SimulatedCrash());
        assertThatThrownBy(() -> assistant(new ScriptedChatModel().callsTools(call("t1", "refund", REFUND_ARGS))
                .answers("unused"), actions).chat("conversation-1", "refund order 42"))
                .isInstanceOf(SimulatedCrash.class); // an Error is not handled: the row stays EXECUTING, as after a crash
        assertThat(journal.findByProviderCallId(ActionKey.of("conversation-1", "refund").scopeHash(), "t1")
                .orElseThrow().action().state()).isEqualTo(ActionState.EXECUTING);
        clock.advance(LEASE.plusMillis(1));
        ScriptedChatModel resumed = new ScriptedChatModel().callsTools(call("t2", "refund", REFUND_ARGS)).answers("ok");

        assistant(resumed, actions).chat("conversation-1", "refund order 42");

        assertThat(tools.runs).hasValue(2);
        assertThat(tools.idempotencyKeys).hasSize(2).containsOnly(tools.idempotencyKeys.get(0));
        assertThat(resumed.lastToolResults()).containsExactly("refunded 30 for order-42");
    }

    @Test
    void toolExceptions_reachTheClassifier_andTheModelIsToldWhatHappened() {
        ScriptedChatModel model = new ScriptedChatModel()
                .callsTools(call("t1", "refund", REFUND_ARGS)).answers("noted")
                .callsTools(call("t2", "refund", "{\"orderId\":\"order-43\",\"amount\":5}")).answers("noted");
        Assistant assistant = assistant(model, actions(ActionPolicy.atLeastOnce()));

        tools.failure.set(new ActionRejectedException("card declined"));
        assistant.chat("conversation-1", "refund order 42");
        List<String> rejected = model.lastToolResults();
        tools.failure.set(new IOException("connection reset"));
        assistant.chat("conversation-1", "refund order 43");

        assertThat(rejected).containsExactly("not performed: card declined");
        assertThat(model.lastToolResults()).last().isEqualTo("outcome unknown; calling again may perform it twice —"
                + " repeat only if a duplicate is acceptable");
        assertThat(journal.findByProviderCallId(ActionKey.of("conversation-1", "refund").scopeHash(), "t1")
                .orElseThrow().action().state()).isEqualTo(ActionState.FAILED);
    }

    @Test
    void withoutMemoryId_theToolIsNotPerformed_becauseConversationsWouldShareKeys() {
        ScriptedChatModel model = new ScriptedChatModel().callsTools(call("t1", "refund", REFUND_ARGS)).answers("no");
        AssistantWithoutMemoryId assistant = AiServices.builder(AssistantWithoutMemoryId.class).chatModel(model)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
                .tools(ReliableTools.wrap(tools, actions(ActionPolicy.atLeastOnce()))).build();

        assistant.chat("refund order 42");

        assertThat(tools.runs).hasValue(0);
        assertThat(model.lastToolResults()).singleElement().asString().startsWith("not performed: invalid arguments")
                .contains("this conversation has no scope: add @MemoryId");
    }

    @Test
    void invalidArguments_areNotPerformed_andNothingIsClaimed() {
        ScriptedChatModel model = new ScriptedChatModel()
                .callsTools(call("t1", "refund", "{\"orderId\":\"order-42\",\"amount\":\"thirty\"}")).answers("no");

        assistant(model, actions(ActionPolicy.atLeastOnce())).chat("conversation-1", "refund");

        assertThat(tools.runs).hasValue(0);
        assertThat(model.lastToolResults()).singleElement().asString()
                .isEqualTo("not performed: invalid arguments: 'thirty' is not a number");
        assertThat(journal.findByProviderCallId(ActionKey.of("conversation-1", "refund").scopeHash(), "t1")).isEmpty();
    }

    @Test
    void concurrentToolExecution_ofTheSameAction_runsTheToolOnce() throws Exception {
        // The first run is held until both tool calls have started on the pool, so they provably overlap; the second
        // one waits for the first (row 5) and gets its result.
        CountDownLatch bothStarted = new CountDownLatch(2);
        tools.holdFirstRunUntil = bothStarted;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Executor counting = task -> pool.execute(() -> {
            bothStarted.countDown();
            task.run();
        });
        ScriptedChatModel model = new ScriptedChatModel()
                .callsTools(call("t1", "refund", REFUND_ARGS), call("t2", "refund", REFUND_ARGS)).answers("done");
        Assistant assistant = AiServices.builder(Assistant.class).chatModel(model)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
                .tools(ReliableTools.wrap(tools, actions(ActionPolicy.atLeastOnce())))
                .executeToolsConcurrently(counting).build();
        try {
            assistant.chat("conversation-1", "refund order 42 twice");
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(tools.overlapped).as("both calls ran at the same time").isTrue();
        assertThat(tools.runs).hasValue(1);
        assertThat(model.lastToolResults()).containsExactly("refunded 30 for order-42", "refunded 30 for order-42");
    }

    // ---- the executor alone

    @Test
    void argumentErrorsOfTheDecoratedExecutor_meanNotPerformed() {
        ToolSpecification specification = ToolService.findTools(tools).stream()
                .filter(tool -> tool.name().equals("refund")).findFirst().orElseThrow().toolSpecification();
        ToolExecutor rejectsArguments = (request, memoryId) -> {
            throw new ToolArgumentsException(new IllegalArgumentException("amount is required"));
        };
        ReliableToolExecutor executor = new ReliableToolExecutor(rejectsArguments, specification,
                actions(ActionPolicy.atMostOnce()).runner(), OutcomeRenderer.standard(), new ArgumentsParser(),
                ActionTelemetry.none());

        String result = executor.execute(ToolExecutionRequest.builder().id("t1").name("refund").arguments(REFUND_ARGS)
                .build(), "conversation-1");

        assertThat(result).startsWith("not performed: invalid arguments");
        assertThat(journal.findByProviderCallId(ActionKey.of("conversation-1", "refund").scopeHash(), "t1")
                .orElseThrow().action().state()).isEqualTo(ActionState.FAILED); // DEFINITE, not UNKNOWN
    }

    @Test
    void aFreshResultIsPassedOnAsTheToolReturnedIt() {
        ToolSpecification specification = ToolService.findTools(tools).stream()
                .filter(tool -> tool.name().equals("refund")).findFirst().orElseThrow().toolSpecification();
        ToolExecutionResult original = ToolExecutionResult.builder().result(42).resultText("42 refunded").build();
        ToolExecutor delegate = new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                throw new AssertionError("executeWithContext is used");
            }

            @Override
            public ToolExecutionResult executeWithContext(ToolExecutionRequest request,
                    dev.langchain4j.invocation.InvocationContext context) {
                return original;
            }
        };
        ReliableToolExecutor executor = new ReliableToolExecutor(delegate, specification,
                actions(ActionPolicy.atMostOnce()).runner(), OutcomeRenderer.standard(), new ArgumentsParser(),
                ActionTelemetry.none());
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("t1").name("refund").arguments(REFUND_ARGS)
                .build();

        ToolExecutionResult first = executor.executeWithContext(request,
                dev.langchain4j.invocation.InvocationContext.builder().chatMemoryId("conversation-1").build());
        ToolExecutionResult replayed = executor.executeWithContext(request,
                dev.langchain4j.invocation.InvocationContext.builder().chatMemoryId("conversation-1").build());

        assertThat(first).isSameAs(original);
        assertThat(replayed.resultText()).isEqualTo("42 refunded");
        assertThat(replayed.isError()).isFalse();
    }

    @Test
    void withoutMemoryId_aKeyStrategyWithItsOwnScope_works_andOtherErrorsKeepTheirText() {
        ReliableActions ownScope = ReliableActions.builder(journal)
                .tool("refund", ActionPolicy.atLeastOnce()
                        .key((call, args) -> ActionKey.of("tenant-7", call.toolName(), args.string("orderId")))
                        .maxEffectDuration(Duration.ofSeconds(60)))
                .tool("lookUpOrder", ActionPolicy.none()).build();
        ScriptedChatModel model = new ScriptedChatModel()
                .callsTools(call("t1", "refund", REFUND_ARGS)).answers("done")
                .callsTools(call("t2", "refund", "{\"amount\":30}")).answers("done");
        AssistantWithoutMemoryId assistant = AiServices.builder(AssistantWithoutMemoryId.class).chatModel(model)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
                .tools(ReliableTools.wrap(tools, ownScope)).build();

        assistant.chat("refund order 42");
        List<String> first = model.lastToolResults();
        assistant.chat("refund without an order id");

        assertThat(first).containsExactly("refunded 30 for order-42");
        assertThat(model.lastToolResults()).last().asString().startsWith("not performed: invalid arguments")
                .doesNotContain("@MemoryId");
    }

    @Test
    void aNonStringMemoryId_isTheScope() {
        ToolExecutionResult result = executor((request, memoryId) -> "refunded", ActionPolicy.atLeastOnce())
                .executeWithContext(refundRequest("t1"),
                        dev.langchain4j.invocation.InvocationContext.builder().chatMemoryId(42L).build());

        assertThat(result.resultText()).isEqualTo("refunded");
        assertThat(journal.findByProviderCallId(ActionKey.of("42", "refund").scopeHash(), "t1")).isPresent();
    }

    @Test
    void aToolResultMarkedAsError_countsAsAnExceptionAfterTheEffectMayHaveStarted() {
        ToolExecutor reportsError = new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                throw new AssertionError("executeWithContext is used");
            }

            @Override
            public ToolExecutionResult executeWithContext(ToolExecutionRequest request,
                    dev.langchain4j.invocation.InvocationContext context) {
                return ToolExecutionResult.builder().isError(true).resultText("gateway timeout").build();
            }
        };

        ToolExecutionResult result = executor(reportsError, ActionPolicy.atLeastOnce()).executeWithContext(
                refundRequest("t1"), dev.langchain4j.invocation.InvocationContext.builder()
                        .chatMemoryId("conversation-1").build());

        assertThat(result.isError()).isFalse();
        assertThat(result.resultText()).startsWith("outcome unknown");
        assertThat(journal.findByProviderCallId(ActionKey.of("conversation-1", "refund").scopeHash(), "t1")
                .orElseThrow().action().state()).isEqualTo(ActionState.UNKNOWN);
    }

    @Test
    void aWrappedExecutionExceptionWithoutCause_isStillAnUnknownOutcome() {
        ToolExecutor throwsBare = (request, memoryId) -> {
            throw new dev.langchain4j.exception.ToolExecutionException((Throwable) null);
        };

        String result = executor(throwsBare, ActionPolicy.atLeastOnce()).execute(refundRequest("t1"), "conversation-1");

        assertThat(result).startsWith("outcome unknown");
    }

    @Test
    void testsRunOnTheRequestedLangChain4jVersion() {
        String requested = System.getProperty("raa.langchain4j.version");
        String jar = AiServices.class.getProtectionDomain().getCodeSource().getLocation().toString();

        assertThat(requested).as("system property set by the build").isNotBlank();
        assertThat(jar).endsWith("/langchain4j-" + requested + ".jar");
    }

    // ---- helpers

    private ReliableToolExecutor executor(ToolExecutor delegate, JournaledPolicy<?> policy) {
        ToolSpecification specification = ToolService.findTools(tools).stream()
                .filter(tool -> tool.name().equals("refund")).findFirst().orElseThrow().toolSpecification();
        return new ReliableToolExecutor(delegate, specification, actions(policy).runner(), OutcomeRenderer.standard(),
                new ArgumentsParser(), ActionTelemetry.none());
    }

    private static ToolExecutionRequest refundRequest(String id) {
        return ToolExecutionRequest.builder().id(id).name("refund").arguments(REFUND_ARGS).build();
    }

    private Assistant assistant(ScriptedChatModel model, ReliableActions actions) {
        return AiServices.builder(Assistant.class).chatModel(model)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
                .tools(ReliableTools.wrap(tools, actions)).build();
    }

    private ReliableActions actions(JournaledPolicy<?> refundPolicy) {
        return ReliableActions.builder(journal).tool("refund", policy(refundPolicy))
                .tool("lookUpOrder", ActionPolicy.none()).lease(LEASE).build();
    }

    private static JournaledPolicy<?> policy(JournaledPolicy<?> policy) {
        return policy.key((call, args) -> ActionKey.of(call.scope(), call.toolName(), args.string("orderId")))
                .maxEffectDuration(Duration.ofSeconds(60));
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** A clock that moves only when a test advances it. */
    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
