package io.github.oleksiifenin.raa.langchain4j;

import static io.github.oleksiifenin.raa.langchain4j.ScriptedChatModel.call;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.tool.AiServiceTool;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.testkit.InMemoryActionJournal;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Memory repair (ADR-0002 §3, docs/DESIGN.md §6 "Inspect mode", §8 rows on repair): a memory left with tool calls
 * without results is answered from the journal, and the model then accepts the history ({@link ScriptedChatModel}
 * rejects a call without a result, as providers do).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JournalChatMemoryRepairTest {

    private static final String CONVERSATION = "conversation-1";
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final String REFUND_ARGS = "{\"orderId\":\"order-42\",\"amount\":30}";

    interface Assistant {
        String chat(@MemoryId String conversation, @dev.langchain4j.service.UserMessage String message);
    }

    /** A refund tool; {@code failure} makes the next run throw. */
    public static final class Tools {
        final AtomicInteger runs = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Tool("Refunds an order")
        public String refund(@P("the order id") String orderId, @P("the amount") BigDecimal amount) throws Exception {
            runs.incrementAndGet();
            Throwable toThrow = failure.getAndSet(null);
            if (toThrow instanceof Exception) {
                throw (Exception) toThrow;
            }
            if (toThrow instanceof Error) {
                throw (Error) toThrow;
            }
            return "refunded " + amount.stripTrailingZeros().toPlainString() + " for " + orderId;
        }

        @Tool("Looks an order up")
        public String lookUpOrder(@P("the order id") String orderId) {
            return "order " + orderId + " is paid";
        }
    }

    private final TestClock clock = new TestClock();
    private final AtomicBoolean journalDown = new AtomicBoolean();
    private final InMemoryActionJournal memoryJournal = new InMemoryActionJournal(clock);
    /** The in-memory journal, failing every call while {@link #journalDown} is set. */
    private final io.github.oleksiifenin.raa.storage.ActionJournal journal =
            (io.github.oleksiifenin.raa.storage.ActionJournal) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {io.github.oleksiifenin.raa.storage.ActionJournal.class},
                    (proxy, method, args) -> {
                        if (journalDown.get()) {
                            throw new io.github.oleksiifenin.raa.storage.JournalException("connection refused", null);
                        }
                        try {
                            return method.invoke(memoryJournal, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
    private final Tools tools = new Tools();

    @Test
    void crashAfterTheRecord_theRecordedResultIsInserted_andTheConversationContinues() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "refund", REFUND_ARGS));
        setup.execute(call("t1", "refund", REFUND_ARGS)); // recorded; the result never reached the memory

        RepairReport report = setup.repair.repair(memory);
        ScriptedChatModel model = new ScriptedChatModel().answers("your refund is done");
        String answer = AiServices.builder(Assistant.class).chatModel(model).chatMemoryProvider(id -> memory)
                .tools(setup.tools).build().chat(CONVERSATION, "did it work?");

        assertThat(report.complete()).isTrue();
        assertThat(report.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(RepairReport.Status.ANSWERED);
            assertThat(entry.text()).isEqualTo("refunded 30 for order-42");
        });
        assertThat(answer).isEqualTo("your refund is done");
        assertThat(model.lastToolResults()).containsExactly("refunded 30 for order-42");
        assertThat(tools.runs).hasValue(1);
    }

    @Test
    void withoutRepair_theModelRejectsTheHistory() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "refund", REFUND_ARGS));
        ScriptedChatModel model = new ScriptedChatModel().answers("unused");

        assertThatThrownBy(() -> AiServices.builder(Assistant.class).chatModel(model).chatMemoryProvider(id -> memory)
                .tools(setup.tools).build().chat(CONVERSATION, "did it work?"))
                .hasMessageContaining("tool calls without results");
    }

    @Test
    void crashBeforeTheClaim_isNotPerformed_youMayCallAgain() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "refund", REFUND_ARGS));

        RepairReport report = setup.repair.repair(memory);

        assertThat(report.entries()).singleElement().satisfies(entry ->
                assertThat(entry.text()).isEqualTo("not performed: you may call again with the same arguments"));
        assertThat(lastResult(memory)).isEqualTo("not performed: you may call again with the same arguments");
        assertThat(tools.runs).hasValue(0);
    }

    @Test
    void crashDuringTheTool_isPendingWhileTheLeaseLives_thenSafeToCallAgain() {
        Setup setup = setup(ActionPolicy.idempotentDownstream(Duration.ofHours(1)));
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "refund", REFUND_ARGS));
        tools.failure.set(new SimulatedCrash());
        assertThatThrownBy(() -> setup.execute(call("t1", "refund", REFUND_ARGS))).isInstanceOf(SimulatedCrash.class);
        int messagesBefore = memory.messages().size();

        RepairReport whileLive = setup.repair.repair(memory);
        int messagesWhileLive = memory.messages().size();
        clock.advance(LEASE.plusMillis(1));
        RepairReport afterExpiry = setup.repair.repair(memory);

        assertThat(whileLive.complete()).isFalse();
        assertThat(whileLive.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.status()).isEqualTo(RepairReport.Status.PENDING));
        assertThat(messagesWhileLive).isEqualTo(messagesBefore);
        assertThat(afterExpiry.complete()).isTrue();
        assertThat(lastResult(memory))
                .isEqualTo("outcome unknown; it is safe to call this tool again with the same arguments");
        assertThat(row("t1").state()).isEqualTo(ActionState.UNKNOWN);
        assertThat(row("t1").attempt()).isEqualTo(1); // nothing was started
        assertThat(tools.runs).hasValue(1);
    }

    @Test
    void anUnknownOutcomeOfAnAtLeastOnceTool_warnsAboutDuplicates() {
        Setup setup = setup(ActionPolicy.atLeastOnce());
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "refund", REFUND_ARGS));
        tools.failure.set(new IOException("connection reset"));
        setup.execute(call("t1", "refund", REFUND_ARGS));

        setup.repair.repair(memory);

        assertThat(lastResult(memory)).isEqualTo("outcome unknown; calling again may perform it twice — repeat only if a"
                + " duplicate is acceptable");
    }

    @Test
    void aCallIdSupersededByARegeneratedCall_getsTheActionsResult() {
        Setup setup = setup(ActionPolicy.atLeastOnce());
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "refund", REFUND_ARGS));
        tools.failure.set(new IOException("connection reset"));
        setup.execute(call("t1", "refund", REFUND_ARGS));
        setup.execute(call("t2", "refund", REFUND_ARGS)); // a regenerated call retried and succeeded

        setup.repair.repair(memory);

        assertThat(lastResult(memory)).isEqualTo("refunded 30 for order-42");
    }

    @Test
    void callsWithoutAnId_orOfUnknownTools_areUnmapped_andNothingIsAdded() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = memoryWithUnansweredCall(call(null, "refund", REFUND_ARGS),
                call("t2", "cancelOrder", "{\"orderId\":\"order-42\"}"));
        int messagesBefore = memory.messages().size();

        RepairReport report = setup.repair.repair(memory);

        assertThat(report.complete()).isFalse();
        assertThat(report.entries()).extracting(RepairReport.Entry::status)
                .containsExactly(RepairReport.Status.UNMAPPED, RepairReport.Status.UNMAPPED);
        assertThat(memory.messages()).hasSize(messagesBefore);
    }

    @Test
    void aReadOnlyToolsLostResult_mayBeCalledAgain() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "lookUpOrder", "{\"orderId\":\"order-42\"}"));

        setup.repair.repair(memory);

        assertThat(lastResult(memory))
                .isEqualTo("the result of this call was lost; the tool only reads, so you may call it again");
    }

    @Test
    void anUnansweredCallInsideTheHistory_getsItsResultRightAfterItsCall() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "refund", REFUND_ARGS),
                call("t2", "lookUpOrder", "{\"orderId\":\"order-42\"}"));
        memory.add(ToolExecutionResultMessage.from(call("t2", "lookUpOrder", "{}"), "order order-42 is paid"));
        memory.add(UserMessage.from("hello again"));
        setup.execute(call("t1", "refund", REFUND_ARGS));

        RepairReport report = setup.repair.repair(memory);

        List<ChatMessage> messages = memory.messages();
        assertThat(report.complete()).isTrue();
        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
        assertThat(messages.get(2)).isInstanceOf(ToolExecutionResultMessage.class); // the existing t2 result
        assertThat(((ToolExecutionResultMessage) messages.get(3)).id()).isEqualTo("t1");
        assertThat(((ToolExecutionResultMessage) messages.get(3)).text()).isEqualTo("refunded 30 for order-42");
        assertThat(messages.get(4)).isInstanceOf(UserMessage.class);
    }

    @Test
    void aWholeMemory_isLeftAsItIs() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "refund", REFUND_ARGS));
        memory.add(ToolExecutionResultMessage.from(call("t1", "refund", REFUND_ARGS), "refunded"));
        List<ChatMessage> before = memory.messages();

        RepairReport report = setup.repair.repair(memory);

        assertThat(report.entries()).isEmpty();
        assertThat(report.complete()).isTrue();
        assertThat(memory.messages()).isEqualTo(before);
    }

    @Test
    void aMemoryWithoutMemoryId_isAnsweredLikeTheExecutor_notPerformed() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20); // id "default"
        memory.add(UserMessage.from("refund order 42"));
        memory.add(AiMessage.from(call("t1", "refund", REFUND_ARGS)));

        setup.repair.repair(memory);

        assertThat(lastResult(memory)).startsWith("not performed: invalid arguments")
                .contains("this conversation has no scope: add @MemoryId");
    }

    @Test
    void anUnavailableJournal_isPending_neverNotPerformed() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "refund", REFUND_ARGS));
        setup.execute(call("t1", "refund", REFUND_ARGS)); // the refund happened
        int messagesBefore = memory.messages().size();
        journalDown.set(true);

        RepairReport report = setup.repair.repair(memory);

        assertThat(report.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(RepairReport.Status.PENDING);
            assertThat(entry.text()).contains("journal is unavailable");
        });
        assertThat(memory.messages()).hasSize(messagesBefore);
        journalDown.set(false);
        assertThat(setup.repair.repair(memory).complete()).isTrue();
        assertThat(lastResult(memory)).isEqualTo("refunded 30 for order-42");
    }

    @Test
    void aMemoryWhoseIdIsNotTheMemoryId_isRepairedWithTheMemoryIdGiven() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = MessageWindowChatMemory.builder().id("store:" + CONVERSATION).maxMessages(20).build();
        memory.add(UserMessage.from("refund order 42"));
        memory.add(AiMessage.from(call("t1", "refund", REFUND_ARGS)));
        setup.execute(call("t1", "refund", REFUND_ARGS)); // executed with @MemoryId = CONVERSATION

        setup.repair.repair(CONVERSATION, memory);

        assertThat(lastResult(memory)).isEqualTo("refunded 30 for order-42");
    }

    @Test
    void aSmallWindow_evictsACallTogetherWithItsResults_neverLeavingOrphans() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = MessageWindowChatMemory.builder().id(CONVERSATION).maxMessages(3).build();
        memory.add(UserMessage.from("refund three orders"));
        memory.add(AiMessage.from(call("t1", "refund", "{\"orderId\":\"o-1\",\"amount\":1}"),
                call("t2", "refund", "{\"orderId\":\"o-2\",\"amount\":2}"),
                call("t3", "refund", "{\"orderId\":\"o-3\",\"amount\":3}")));

        setup.repair.repair(memory);

        List<ChatMessage> messages = memory.messages();
        assertThat(messages).noneMatch(ToolExecutionResultMessage.class::isInstance); // the group went as a whole
        assertThat(messages).noneMatch(m -> m instanceof AiMessage && ((AiMessage) m).hasToolExecutionRequests());
    }

    @Test
    void callsWithoutAnIdThatAreAnswered_areLeftAlone() {
        Setup setup = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = memoryWithUnansweredCall(call(null, "refund", REFUND_ARGS));
        memory.add(ToolExecutionResultMessage.from(null, "refund", "refunded"));

        RepairReport report = setup.repair.repair(memory);

        assertThat(report.entries()).isEmpty();
        assertThat(report.complete()).isTrue();
    }

    @Test
    void aCallOfAnActionWhoseKeyStrategyChanged_isUnmapped_notAnsweredForAnotherAction() {
        Setup before = setup(ActionPolicy.atMostOnce());
        ChatMemory memory = memoryWithUnansweredCall(call("t1", "refund", REFUND_ARGS));
        before.execute(call("t1", "refund", REFUND_ARGS));
        ReliableActions changedKey = ReliableActions.builder(journal)
                .tool("refund", ActionPolicy.atMostOnce()
                        .key((call, args) -> ActionKey.of(call.scope(), "refund-v2", args.string("orderId")))
                        .maxEffectDuration(Duration.ofSeconds(60)))
                .tool("lookUpOrder", ActionPolicy.none()).lease(LEASE).build();
        ReliableTools tools = ReliableTools.builder(changedKey).build();

        RepairReport report = tools.memoryRepair(tools.wrap(this.tools)).repair(memory);

        assertThat(report.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(RepairReport.Status.UNMAPPED);
            assertThat(entry.text()).contains("key strategy");
        });
    }

    // ---- helpers

    private Setup setup(JournaledPolicy<?> refundPolicy) {
        ReliableActions actions = ReliableActions.builder(journal)
                .tool("refund", refundPolicy
                        .key((call, args) -> ActionKey.of(call.scope(), call.toolName(), args.string("orderId")))
                        .maxEffectDuration(Duration.ofSeconds(60)))
                .tool("lookUpOrder", ActionPolicy.none()).lease(LEASE).build();
        ReliableTools reliableTools = ReliableTools.builder(actions).build();
        List<AiServiceTool> wrapped = reliableTools.wrap(tools);
        return new Setup(wrapped, reliableTools.memoryRepair(wrapped));
    }

    private static ChatMemory memoryWithUnansweredCall(ToolExecutionRequest... calls) {
        ChatMemory memory = MessageWindowChatMemory.builder().id(CONVERSATION).maxMessages(20).build();
        memory.add(UserMessage.from("refund order 42"));
        memory.add(AiMessage.from(calls));
        return memory;
    }

    private static String lastResult(ChatMemory memory) {
        List<ChatMessage> messages = memory.messages();
        return ((ToolExecutionResultMessage) messages.get(messages.size() - 1)).text();
    }

    private io.github.oleksiifenin.raa.storage.ActionRecord row(String callId) {
        return journal.findByProviderCallId(ActionKey.of(CONVERSATION, "refund").scopeHash(), callId).orElseThrow()
                .action();
    }

    /** The wrapped tools and their repair. */
    private static final class Setup {
        final List<AiServiceTool> tools;
        final JournalChatMemoryRepair repair;

        Setup(List<AiServiceTool> tools, JournalChatMemoryRepair repair) {
            this.tools = tools;
            this.repair = repair;
        }

        /** Runs the wrapped refund executor, as the AI service would for this call. */
        void execute(ToolExecutionRequest request) {
            tools.stream().filter(tool -> tool.name().equals(request.name())).findFirst().orElseThrow().toolExecutor()
                    .executeWithContext(request, InvocationContext.builder().chatMemoryId(CONVERSATION).build());
        }
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** A clock that moves only when a test advances it. */
    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-17T10:00:00Z"));

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
