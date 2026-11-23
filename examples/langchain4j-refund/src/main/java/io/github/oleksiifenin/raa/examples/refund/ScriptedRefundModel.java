package io.github.oleksiifenin.raa.examples.refund;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for an LLM that follows the scripted-model rules of docs/DESIGN.md §9.1, so the demo and the kill matrix
 * run without a provider. A user message asks for the refund; after a tool result the model calls the tool again (new
 * tool-call id, same arguments) when told it is safe, may duplicate, or was not performed — at most three times — and
 * otherwise answers with the tool's text. "continue" (after a restart) applies the rules to the last tool result in the
 * history. Like a real provider, it rejects a history in which a tool call has no result.
 */
public final class ScriptedRefundModel implements ChatModel {

    /** Most re-calls per model instance. */
    public static final int MAX_RECALLS = 3;

    private final String callIdPrefix;
    private final String refundArguments;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger recalls = new AtomicInteger();

    /**
     * Creates the model.
     *
     * @param callIdPrefix    prefix of the tool-call ids it emits, unique per process (the provider's ids are unique)
     * @param refundArguments the JSON arguments of the refund call
     */
    public ScriptedRefundModel(String callIdPrefix, String refundArguments) {
        this.callIdPrefix = callIdPrefix;
        this.refundArguments = refundArguments;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        requireEveryToolCallAnswered(messages);
        ChatMessage last = messages.get(messages.size() - 1);
        String toolResult = null;
        if (last instanceof ToolExecutionResultMessage) {
            toolResult = ((ToolExecutionResultMessage) last).text();
        } else if (last instanceof UserMessage && ((UserMessage) last).hasSingleText()
                && "continue".equals(((UserMessage) last).singleText())) {
            toolResult = lastToolResult(messages);
        }
        if (toolResult == null) {
            return respond(AiMessage.from(refundCall()));
        }
        if (saysCallAgain(toolResult) && recalls.getAndIncrement() < MAX_RECALLS) {
            return respond(AiMessage.from(refundCall()));
        }
        return respond(AiMessage.from(toolResult));
    }

    /**
     * Returns how many times the model called the tool again after a result.
     *
     * @return the number of repeated calls, at most {@link #MAX_RECALLS}
     */
    public int recalls() {
        return Math.min(recalls.get(), MAX_RECALLS);
    }

    private ToolExecutionRequest refundCall() {
        return ToolExecutionRequest.builder().id(callIdPrefix + calls.incrementAndGet()).name("refund")
                .arguments(refundArguments).build();
    }

    private static boolean saysCallAgain(String text) {
        return text.contains("it is safe to call this tool again") || text.contains("calling again may perform it twice")
                || text.contains("you may call again");
    }

    private static String lastToolResult(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolExecutionResultMessage) {
                return ((ToolExecutionResultMessage) messages.get(i)).text();
            }
        }
        return null;
    }

    private static ChatResponse respond(AiMessage message) {
        return ChatResponse.builder().aiMessage(message).build();
    }

    private static void requireEveryToolCallAnswered(List<ChatMessage> messages) {
        List<String> open = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage) {
                open.remove(((ToolExecutionResultMessage) message).id());
                continue;
            }
            if (!open.isEmpty()) {
                throw new IllegalStateException("invalid request: tool calls without results " + open);
            }
            if (message instanceof AiMessage && ((AiMessage) message).hasToolExecutionRequests()) {
                ((AiMessage) message).toolExecutionRequests().forEach(call -> open.add(call.id()));
            }
        }
        if (!open.isEmpty()) {
            throw new IllegalStateException("invalid request: tool calls without results " + open);
        }
    }
}
