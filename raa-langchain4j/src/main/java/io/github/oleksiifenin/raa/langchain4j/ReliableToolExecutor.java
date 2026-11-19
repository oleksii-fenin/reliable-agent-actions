package io.github.oleksiifenin.raa.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.memory.ChatMemoryService;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionRejectedException;
import io.github.oleksiifenin.raa.core.ActionRunner;
import io.github.oleksiifenin.raa.core.ActionTelemetry;
import io.github.oleksiifenin.raa.core.Effect;
import io.github.oleksiifenin.raa.core.ToolCall;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A LangChain4j {@link ToolExecutor} that runs the tool it decorates through the action journal (ADR-0002): the
 * journal row is claimed before the tool runs, a repeated call gets the recorded result instead of a second run, and
 * an unknown outcome is reported as such. Created by {@link ReliableTools}.
 *
 * <p><b>Scope.</b> The chat memory id of the invocation is the {@link ToolCall#scope()}. LangChain4j passes
 * {@code "default"} for every conversation of an AI service method without {@code @MemoryId}; that is treated as no
 * scope, so actions of different users never share a key by accident. Such a call is not performed unless the tool's
 * key strategy sets the scope itself.
 *
 * <p><b>Errors.</b> Invalid arguments are "not performed" (nothing is claimed, or the tool was never invoked). An
 * exception of the tool reaches the policy's failure classifier as the tool threw it. A tool result marked as an error
 * counts as an exception after the effect may have started.
 *
 * <p><b>Telemetry.</b> Each execution is reported as an {@code execute_tool} observation
 * ({@link ActionTelemetry#startTool}) around the action's own observation, unless
 * {@link ReliableTools.Builder#toolSpans(boolean)} turned it off.
 *
 * <p><b>Asynchronous execution</b> is not supported in v0.1: {@code executeAsync} is inherited, which on LangChain4j
 * versions that have it completes exceptionally without running the tool.
 */
public final class ReliableToolExecutor implements ToolExecutor {

    /** The runner's error when the key strategy passed a null scope to {@code ActionKey}. */
    private static final String MISSING_SCOPE = "invalid arguments: scope";

    private final ToolExecutor delegate;
    private final ToolSpecification specification;
    private final ActionRunner runner;
    private final OutcomeRenderer renderer;
    private final ArgumentsParser parser;
    private final ActionTelemetry telemetry;

    ReliableToolExecutor(ToolExecutor delegate, ToolSpecification specification, ActionRunner runner,
            OutcomeRenderer renderer, ArgumentsParser parser, ActionTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.specification = Objects.requireNonNull(specification, "specification");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        Object memoryId = context == null ? null : context.chatMemoryId();
        return ObservedToolExecutor.observe(telemetry, specification, request,
                () -> run(request, memoryId, () -> delegate.executeWithContext(request, context)));
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return ObservedToolExecutor.observe(telemetry, specification, request,
                () -> run(request, memoryId, () -> ToolExecutionResult.builder()
                        .resultText(delegate.execute(request, memoryId)).build())).resultText();
    }

    private ToolExecutionResult run(ToolExecutionRequest request, Object memoryId,
            Supplier<ToolExecutionResult> invocation) {
        String argumentsJson = request.arguments() == null ? "" : request.arguments();
        String scope = scopeOf(memoryId);
        Map<String, Object> arguments;
        try {
            arguments = parser.parse(argumentsJson, specification.parameters());
        } catch (IllegalArgumentException e) {
            return notPerformed("not performed: invalid arguments: " + e.getMessage());
        }
        ToolCall call = new ToolCall(request.name(), argumentsJson, arguments, request.id(), scope);
        AtomicReference<ToolExecutionResult> freshResult = new AtomicReference<>();
        Effect effect = context -> {
            ToolExecutionResult result = invoke(invocation);
            if (result.isError()) {
                throw new ToolReportedError(result.resultText());
            }
            freshResult.set(result);
            return result.resultText();
        };
        ActionOutcome outcome = runner.run(call, effect);
        if (outcome instanceof ActionOutcome.Succeeded && freshResult.get() != null) {
            return freshResult.get(); // this call ran the tool: the model gets its own result, contents included
        }
        return ToolExecutionResult.builder().isError(isNotPerformed(outcome))
                .resultText(render(renderer, outcome, call)).build();
    }

    /** Runs the decorated tool, unwrapping LangChain4j's exception wrappers for the failure classifier. */
    private static ToolExecutionResult invoke(Supplier<ToolExecutionResult> invocation) throws Exception {
        try {
            return Objects.requireNonNull(invocation.get(), "the tool executor returned no result");
        } catch (ToolArgumentsException e) {
            // Thrown while preparing the method arguments, before the tool method runs: definitely not applied.
            throw new ActionRejectedException("invalid arguments: " + e.getMessage(), e);
        } catch (ToolExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    /** The model's text for an outcome; shared with memory repair so that both say the same. */
    static String render(OutcomeRenderer renderer, ActionOutcome outcome, ToolCall call) {
        String text = Objects.requireNonNull(renderer.render(outcome, call), "the outcome renderer returned null");
        if (call.scope() == null && outcome instanceof ActionOutcome.Failed
                && MISSING_SCOPE.equals(((ActionOutcome.Failed) outcome).error())) {
            // ActionKey rejects a null scope with exactly this message; any other error keeps its own text.
            text += "; this conversation has no scope: add @MemoryId to the AI service method or set the scope in the"
                    + " tool's key strategy";
        }
        return text;
    }

    static String scopeOf(Object memoryId) {
        if (memoryId == null || ChatMemoryService.DEFAULT.equals(memoryId)) {
            return null;
        }
        return memoryId.toString();
    }

    private static boolean isNotPerformed(ActionOutcome outcome) {
        return outcome instanceof ActionOutcome.Failed || outcome instanceof ActionOutcome.Abandoned
                || outcome instanceof ActionOutcome.JournalUnavailable;
    }

    private static ToolExecutionResult notPerformed(String text) {
        return ToolExecutionResult.builder().isError(true).resultText(text).build();
    }

    /** A tool result marked as an error: the tool ran, so its effect may have started. */
    private static final class ToolReportedError extends Exception {
        private static final long serialVersionUID = 1L;

        ToolReportedError(String message) {
            super(message == null ? "the tool reported an error" : message);
        }
    }
}
