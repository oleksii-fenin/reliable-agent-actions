package io.github.oleksiifenin.raa.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolService;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionObservation;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.ActionStart;
import io.github.oleksiifenin.raa.core.ActionTelemetry;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.ToolObservation;
import io.github.oleksiifenin.raa.testkit.InMemoryActionJournal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The {@code execute_tool} observations of the adapter (docs/DESIGN.md §10), around the action's own observation. */
class ToolSpansTest {

    private final LangChain4jContractTest.RefundTools tools = new LangChain4jContractTest.RefundTools();
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @Test
    void journaledTool_isObservedAsAToolExecution_aroundItsActionObservation() {
        AiServiceTool refund = tool(ReliableTools.wrap(tools, actions(recording())), "refund");

        refund.toolExecutor().execute(request("t1", "refund", "{\"orderId\":\"order-42\",\"amount\":30}"),
                "conversation-1");

        assertThat(events).containsExactly("tool start refund Refunds an order t1", "action start refund t1",
                "action ended Succeeded", "tool ended false");
    }

    @Test
    void readOnlyTool_isObservedToo_andRunsUnchanged() {
        AiServiceTool lookUp = tool(ReliableTools.wrap(tools, actions(recording())), "lookUpOrder");

        String result = lookUp.toolExecutor().execute(request("t2", "lookUpOrder", "{\"orderId\":\"order-42\"}"),
                "conversation-1");

        assertThat(result).isEqualTo("order order-42 is paid");
        assertThat(events).containsExactly("tool start lookUpOrder Looks an order up t2", "tool ended false");
    }

    @Test
    void notPerformedResult_isAToolExecutionEndingInAnError() {
        AiServiceTool refund = tool(ReliableTools.wrap(tools, actions(recording())), "refund");

        // No invocation context, so no scope: the key cannot be built and the call is not performed.
        ToolExecutionResult result = refund.toolExecutor().executeWithContext(
                request("t3", "refund", "{\"orderId\":\"order-42\",\"amount\":30}"), null);

        assertThat(result.isError()).isTrue();
        assertThat(events).endsWith("action ended Failed", "tool ended true");
    }

    /** The wrapped read-only tool keeps the tool's asynchronous path (LangChain4j 1.20+), unobserved. */
    @Test
    void readOnlyTool_withAnAsynchronousPath_keepsIt() {
        ToolExecutionResult asyncResult = ToolExecutionResult.builder().resultText("async").build();
        ToolExecutor asyncCapable = new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                return "sync";
            }

            // Not an @Override: the 1.14.0 floor has no executeAsync; from 1.20 it overrides the default.
            public CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest request,
                    InvocationContext context) {
                return CompletableFuture.completedFuture(asyncResult);
            }
        };
        ObservedToolExecutor observed = new ObservedToolExecutor(asyncCapable,
                tool(ToolService.findTools(tools), "lookUpOrder").toolSpecification(), recording());

        assertThat(observed.executeAsync(request("t5", "lookUpOrder", "{}"), null).join()).isSameAs(asyncResult);
        assertThat(events).isEmpty();
    }

    @Test
    void toolSpansOff_reportsNoToolExecutions_andLeavesReadOnlyToolsAsTheyAre() {
        ReliableActions actions = actions(recording());
        List<AiServiceTool> wrapped = ReliableTools.builder(actions).toolSpans(false).build().wrap(tools);
        AiServiceTool original = tool(ToolService.findTools(tools), "lookUpOrder");

        tool(wrapped, "refund").toolExecutor().execute(
                request("t4", "refund", "{\"orderId\":\"order-42\",\"amount\":30}"), "conversation-1");

        assertThat(tool(wrapped, "lookUpOrder").toolExecutor()).isNotInstanceOf(ObservedToolExecutor.class);
        assertThat(tool(wrapped, "lookUpOrder").toolSpecification()).isEqualTo(original.toolSpecification());
        assertThat(events).containsExactly("action start refund t4", "action ended Succeeded");
    }

    @Test
    void withoutTelemetry_readOnlyToolsAreReturnedUnchanged() {
        List<AiServiceTool> original = ToolService.findTools(tools);
        List<AiServiceTool> wrapped = ReliableTools.wrap(tools, actions(ActionTelemetry.none()));

        assertThat(tool(wrapped, "lookUpOrder").toolExecutor())
                .isNotInstanceOf(ObservedToolExecutor.class).isNotInstanceOf(ReliableToolExecutor.class);
        assertThat(tool(wrapped, "lookUpOrder").toolSpecification())
                .isEqualTo(tool(original, "lookUpOrder").toolSpecification());
    }

    private ActionTelemetry recording() {
        return new ActionTelemetry() {
            @Override
            public ToolObservation startTool(String toolName, String description, String providerCallId) {
                events.add("tool start " + toolName + " " + description + " " + providerCallId);
                return new ToolObservation() {
                    @Override
                    public void ended(boolean error) {
                        events.add("tool ended " + error);
                    }

                    @Override
                    public void failed(Throwable error) {
                        events.add("tool failed " + error.getClass().getSimpleName());
                    }
                };
            }

            @Override
            public ActionObservation startAction(ActionStart start) {
                events.add("action start " + start.toolName() + " " + start.providerCallId());
                return new ActionObservation() {
                    @Override
                    public void ended(ActionOutcome outcome) {
                        events.add("action ended " + outcome.getClass().getSimpleName());
                    }
                };
            }
        };
    }

    private static ReliableActions actions(ActionTelemetry telemetry) {
        return ReliableActions.builder(new InMemoryActionJournal(Clock.systemUTC()))
                .tool("refund", ActionPolicy.atLeastOnce()
                        .key((call, args) -> ActionKey.of(call.scope(), call.toolName(), args.string("orderId")))
                        .maxEffectDuration(Duration.ofSeconds(60)))
                .tool("lookUpOrder", ActionPolicy.none())
                .telemetry(telemetry).build();
    }

    private static AiServiceTool tool(List<AiServiceTool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static ToolExecutionRequest request(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }
}
