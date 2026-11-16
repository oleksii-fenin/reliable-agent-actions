package io.github.oleksiifenin.raa.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * A chat model that answers from a script: each request takes the next scripted {@link AiMessage}. It keeps every
 * request and checks the provider rule the journal must keep true: every tool call in the history has a result.
 */
final class ScriptedChatModel implements ChatModel {

    private final ConcurrentLinkedQueue<AiMessage> script = new ConcurrentLinkedQueue<>();
    private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());

    /** Scripts a response that calls tools. */
    ScriptedChatModel callsTools(ToolExecutionRequest... calls) {
        script.add(AiMessage.from(calls));
        return this;
    }

    /** Scripts a final text response. */
    ScriptedChatModel answers(String text) {
        script.add(AiMessage.from(text));
        return this;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        requireEveryToolCallAnswered(request.messages());
        AiMessage next = script.poll();
        if (next == null) {
            throw new IllegalStateException("the script has no response left for " + request.messages());
        }
        return ChatResponse.builder().aiMessage(next).build();
    }

    /** Returns the texts of the tool results the model received in its last request, in order. */
    List<String> lastToolResults() {
        ChatRequest last = requests.get(requests.size() - 1);
        return last.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(message -> ((ToolExecutionResultMessage) message).text())
                .collect(Collectors.toList());
    }

    static ToolExecutionRequest call(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    private static void requireEveryToolCallAnswered(List<ChatMessage> messages) {
        List<String> open = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage && ((AiMessage) message).hasToolExecutionRequests()) {
                if (!open.isEmpty()) {
                    throw new IllegalStateException("tool calls without results: " + open);
                }
                ((AiMessage) message).toolExecutionRequests().forEach(request -> open.add(request.id()));
            } else if (message instanceof ToolExecutionResultMessage) {
                open.remove(((ToolExecutionResultMessage) message).id());
            } else if (!open.isEmpty()) {
                throw new IllegalStateException("tool calls without results: " + open);
            }
        }
        if (!open.isEmpty()) {
            throw new IllegalStateException("tool calls without results: " + open);
        }
    }
}
