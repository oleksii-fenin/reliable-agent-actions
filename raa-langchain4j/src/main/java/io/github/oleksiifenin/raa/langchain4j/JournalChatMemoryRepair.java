package io.github.oleksiifenin.raa.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Answers tool calls left without a result in a chat memory, from the journal (ADR-0002 §3, docs/DESIGN.md §6 "Inspect
 * mode"). LangChain4j stores the assistant's tool calls before running them and the results after the whole round, so
 * a process that dies in between leaves calls without results; providers reject such a history, and nobody knows
 * whether the tools ran. Call {@link #repair} before the next model call of a conversation, for example after a
 * restart.
 *
 * <p>Each call gets the text a repeated call would get, computed with effects disabled: the recorded result, "safe to
 * call again with the same arguments", "calling again may perform it twice", "being verified", "not performed; you may
 * call again", or "could not be verified". Repair performs no tool and starts no attempt; it applies only transitions
 * that need no effect (an expired attempt becomes {@code UNKNOWN}, a due outcome lookup resolves it, an unresolvable
 * outcome is escalated). Created by {@link ReliableTools#memoryRepair(List)}.
 */
public final class JournalChatMemoryRepair {

    private static final String LOST_READ_ONLY_RESULT =
            "the result of this call was lost; the tool only reads, so you may call it again";

    private final ReliableActions actions;
    private final OutcomeRenderer renderer;
    private final ArgumentsParser parser;
    private final Map<String, ToolSpecification> specifications;

    JournalChatMemoryRepair(ReliableActions actions, OutcomeRenderer renderer, ArgumentsParser parser,
            Map<String, ToolSpecification> specifications) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.specifications = Map.copyOf(specifications);
    }

    /**
     * Repairs a memory whose {@link ChatMemory#id()} is the AI service's {@code @MemoryId} value (as for a
     * {@code MessageWindowChatMemory} built with {@code id(memoryId)}); otherwise use {@link #repair(Object, ChatMemory)}.
     *
     * @param memory the conversation's memory
     * @return what was done per unanswered call
     */
    public RepairReport repair(ChatMemory memory) {
        return repair(Objects.requireNonNull(memory, "memory").id(), memory);
    }

    /**
     * Adds a result message for every tool call of the memory that has none, right after the results the call's
     * assistant message already has. The memory is changed only if a result is added, and then in one
     * {@link ChatMemory#set(Iterable)}: atomic for {@code MessageWindowChatMemory} (one store write, the window applied
     * once, so a call and its results are evicted together); a memory whose {@code set} is not atomic can lose messages
     * if the process dies during it.
     *
     * @param memoryId the AI service's {@code @MemoryId} value of the conversation: the scope of the action keys, as in
     *                 {@link ReliableToolExecutor} (ADR-0005)
     * @param memory   the conversation's memory
     * @return what was done per unanswered call; {@link RepairReport#complete()} tells whether the memory is whole
     */
    public RepairReport repair(Object memoryId, ChatMemory memory) {
        Objects.requireNonNull(memory, "memory");
        String scope = ReliableToolExecutor.scopeOf(memoryId);
        List<ChatMessage> messages = memory.messages();
        List<ChatMessage> repaired = new ArrayList<>(messages.size());
        List<RepairReport.Entry> entries = new ArrayList<>();
        boolean changed = false;
        int index = 0;
        while (index < messages.size()) {
            ChatMessage message = messages.get(index++);
            repaired.add(message);
            if (!(message instanceof AiMessage) || !((AiMessage) message).hasToolExecutionRequests()) {
                continue;
            }
            Set<String> answered = new HashSet<>();
            int resultsWithoutId = 0;
            while (index < messages.size() && messages.get(index) instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage result = (ToolExecutionResultMessage) messages.get(index++);
                if (result.id() == null) {
                    resultsWithoutId++;
                } else {
                    answered.add(result.id());
                }
                repaired.add(result);
            }
            for (ToolExecutionRequest request : ((AiMessage) message).toolExecutionRequests()) {
                if (request.id() != null ? answered.contains(request.id()) : resultsWithoutId-- > 0) {
                    continue; // already answered (calls without an id are matched by count, as providers pair them)
                }
                RepairReport.Entry entry = answer(request, scope);
                entries.add(entry);
                if (entry.status() == RepairReport.Status.ANSWERED) {
                    repaired.add(ToolExecutionResultMessage.from(request, entry.text()));
                    changed = true;
                }
            }
        }
        if (changed) {
            memory.set(repaired);
        }
        return new RepairReport(entries);
    }

    private RepairReport.Entry answer(ToolExecutionRequest request, String scope) {
        String name = request.name();
        if (request.id() == null) {
            return unmapped(request, "the provider sent no tool-call id, so the call cannot be mapped to an action");
        }
        Optional<ActionPolicy> policy = actions.policy(name);
        if (policy.isEmpty()) {
            return unmapped(request, "tool " + name + " has no policy");
        }
        if (!(policy.get() instanceof JournaledPolicy<?>)) {
            return answered(request, LOST_READ_ONLY_RESULT);
        }
        ToolSpecification specification = specifications.get(name);
        if (specification == null) {
            return unmapped(request, "tool " + name + " is not among the tools given to this repair");
        }
        String argumentsJson = request.arguments() == null ? "" : request.arguments();
        Map<String, Object> arguments;
        try {
            arguments = parser.parse(argumentsJson, specification.parameters());
        } catch (IllegalArgumentException e) {
            // The executor rejects such a call before claiming anything, and so does repair.
            return answered(request, "not performed: invalid arguments: " + e.getMessage());
        }
        ToolCall call = new ToolCall(name, argumentsJson, arguments, request.id(), scope);
        ActionOutcome outcome;
        try {
            outcome = actions.runner().inspect(call);
        } catch (IllegalStateException e) {
            return unmapped(request, e.getMessage()); // the call history maps it to another action
        }
        if (outcome instanceof ActionOutcome.InProgressElsewhere) {
            return new RepairReport.Entry(request.id(), name, RepairReport.Status.PENDING,
                    "an attempt of this action is still running; repair again later");
        }
        if (outcome instanceof ActionOutcome.JournalUnavailable) {
            // Unknown is not "not performed": the effect may have been applied. Nothing is written; repair again.
            return new RepairReport.Entry(request.id(), name, RepairReport.Status.PENDING,
                    "the journal is unavailable (" + ((ActionOutcome.JournalUnavailable) outcome).reason()
                            + "); repair again later");
        }
        return answered(request, ReliableToolExecutor.render(renderer, outcome, call));
    }

    private static RepairReport.Entry answered(ToolExecutionRequest request, String text) {
        return new RepairReport.Entry(request.id(), request.name(), RepairReport.Status.ANSWERED, text);
    }

    private static RepairReport.Entry unmapped(ToolExecutionRequest request, String reason) {
        return new RepairReport.Entry(request.id(), request.name(), RepairReport.Status.UNMAPPED, reason);
    }
}
