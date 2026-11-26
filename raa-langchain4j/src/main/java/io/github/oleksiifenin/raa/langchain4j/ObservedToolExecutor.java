package io.github.oleksiifenin.raa.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.github.oleksiifenin.raa.core.ActionTelemetry;
import io.github.oleksiifenin.raa.core.ToolObservation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Reports the execution of a tool that is not journaled ({@code ActionPolicy.none()}) to the telemetry, as the
 * {@code execute_tool} span of the GenAI semantic conventions; the tool itself runs unchanged. Journaled tools report
 * the same through {@link ReliableToolExecutor}.
 *
 * <p>An asynchronous execution ({@code executeAsync}, LangChain4j 1.20 and later) goes to the tool unchanged and is
 * not observed: an observation belongs to the thread that starts it.
 */
final class ObservedToolExecutor implements ToolExecutor {

    private final ToolExecutor delegate;
    private final ToolSpecification specification;
    private final ActionTelemetry telemetry;

    ObservedToolExecutor(ToolExecutor delegate, ToolSpecification specification, ActionTelemetry telemetry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.specification = Objects.requireNonNull(specification, "specification");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        return observe(telemetry, specification, request, () -> delegate.executeWithContext(request, context));
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return observe(telemetry, specification, request, () -> ToolExecutionResult.builder()
                .resultText(delegate.execute(request, memoryId)).build()).resultText();
    }

    /**
     * Passes an asynchronous execution to the tool unchanged. Not an {@code @Override}: the supported floor, LangChain4j
     * 1.14.0, has no {@code executeAsync}; on versions that have it, this overrides it, so a read-only tool keeps its
     * asynchronous path when it is wrapped.
     *
     * @param request the request
     * @param context the invocation context
     * @return the tool's own future
     */
    public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest request,
            InvocationContext context) {
        Method executeAsync;
        try {
            executeAsync = delegate.getClass().getMethod("executeAsync", ToolExecutionRequest.class,
                    InvocationContext.class);
        } catch (NoSuchMethodException e) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "this LangChain4j version has no asynchronous tool execution", e));
        }
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<ToolExecutionResult> future =
                    (CompletableFuture<ToolExecutionResult>) executeAsync.invoke(delegate, request, context);
            return future;
        } catch (IllegalAccessException e) {
            return CompletableFuture.failedFuture(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            return CompletableFuture.failedFuture(cause);
        }
    }

    /** Runs one tool execution inside its observation, which ends exactly once. */
    static ToolExecutionResult observe(ActionTelemetry telemetry, ToolSpecification specification,
            ToolExecutionRequest request, Supplier<ToolExecutionResult> execution) {
        ToolObservation observation = telemetry.startTool(request.name(), specification.description(), request.id());
        ToolExecutionResult result;
        try {
            result = execution.get();
        } catch (RuntimeException | Error e) {
            observation.failed(e);
            throw e;
        }
        observation.ended(result != null && result.isError());
        return result;
    }
}
