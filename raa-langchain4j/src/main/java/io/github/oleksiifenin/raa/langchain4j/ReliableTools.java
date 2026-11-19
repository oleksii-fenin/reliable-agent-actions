package io.github.oleksiifenin.raa.langchain4j;

import dev.langchain4j.agent.tool.ReturnBehavior;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolService;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.ActionTelemetry;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Wires LangChain4j tools through the action journal (ADR-0002): {@code .tools(ReliableTools.wrap(bean, actions))}
 * instead of {@code .tools(bean)}. Each tool keeps its specification and {@link ReturnBehavior}; tools whose policy is
 * {@link ActionPolicy#none()} run as they are; every other tool gets a {@link ReliableToolExecutor}. With a
 * telemetry in {@link ReliableActions} ({@code ReliableActions.Builder.telemetry}), every tool execution, read-only
 * ones included, is also reported as an {@code execute_tool} observation; {@link Builder#toolSpans(boolean)} turns
 * that off. Without a telemetry, read-only tools are returned unchanged.
 *
 * <p>Wiring fails, before the model can call anything, for a tool without a policy in {@link ReliableActions} and for
 * a journaled tool with {@link ReturnBehavior#IMMEDIATE}: its result would be the final answer to the user, and the
 * library's texts ("being verified", "already in progress") are not meant for users. Allow it per tool with
 * {@link Builder#allowImmediateReturn(String...)}.
 */
public final class ReliableTools {

    private final ReliableActions actions;
    private final OutcomeRenderer renderer;
    private final Set<String> immediateReturnAllowed;
    private final ArgumentsParser parser = new ArgumentsParser();
    private final ActionTelemetry toolTelemetry;

    private ReliableTools(Builder builder) {
        this.actions = builder.actions;
        this.toolTelemetry = builder.toolSpans ? builder.actions.telemetry() : ActionTelemetry.none();
        this.renderer = builder.renderer;
        this.immediateReturnAllowed = Set.copyOf(builder.immediateReturnAllowed);
    }

    /**
     * Wraps the {@code @Tool} methods of a bean with the default settings.
     *
     * @param toolBean an object with {@code @Tool} methods
     * @param actions  the wiring that declares a policy for every tool of the bean
     * @return the tools, to pass to {@code AiServices.builder(...).tools(List<AiServiceTool>)}
     * @throws IllegalStateException if a tool has no policy or is a journaled tool with immediate return
     */
    public static List<AiServiceTool> wrap(Object toolBean, ReliableActions actions) {
        return builder(actions).build().wrap(toolBean);
    }

    /**
     * Starts a configuration.
     *
     * @param actions the wiring that declares the tool policies
     * @return a builder
     */
    public static Builder builder(ReliableActions actions) {
        return new Builder(actions);
    }

    /**
     * Wraps the {@code @Tool} methods of a bean, found with {@link ToolService#findTools(Object)}. Like {@code findTools},
     * this does not register compensating actions that {@code AiServices.tools(Object)} may register for the bean.
     *
     * @param toolBean an object with {@code @Tool} methods
     * @return the tools
     * @throws IllegalStateException if a tool has no policy or is a journaled tool with immediate return
     */
    public List<AiServiceTool> wrap(Object toolBean) {
        return wrap(ToolService.findTools(Objects.requireNonNull(toolBean, "toolBean")));
    }

    /**
     * Wraps tools built another way, for example programmatically. A {@code ToolArgumentsException} from such a tool's
     * executor is taken as "thrown before the tool did anything" (definitely not applied), as LangChain4j's own
     * executor guarantees; an executor that throws it after starting its effect must use another exception.
     *
     * @param tools the tools
     * @return the tools, journaled ones with a {@link ReliableToolExecutor}
     * @throws IllegalStateException if a tool has no policy or is a journaled tool with immediate return
     */
    public List<AiServiceTool> wrap(List<AiServiceTool> tools) {
        List<AiServiceTool> wrapped = new ArrayList<>(tools.size());
        for (AiServiceTool tool : tools) {
            wrapped.add(wrapOne(tool));
        }
        return List.copyOf(wrapped);
    }

    /**
     * Returns the memory repair for these tools, with the same renderer and argument handling as the wrapped tools, so
     * that a repaired answer and a real call never disagree.
     *
     * @param tools the tools of the AI service (wrapped or not; only their specifications are used)
     * @return the repair
     */
    public JournalChatMemoryRepair memoryRepair(List<AiServiceTool> tools) {
        Map<String, ToolSpecification> specifications = new HashMap<>();
        for (AiServiceTool tool : tools) {
            specifications.put(tool.name(), tool.toolSpecification());
        }
        return new JournalChatMemoryRepair(actions, renderer, parser, specifications);
    }

    private AiServiceTool wrapOne(AiServiceTool tool) {
        String name = tool.name();
        ActionPolicy policy = actions.policy(name).orElseThrow(() -> new IllegalStateException("tool " + name
                + " has no policy; declare one with ReliableActions.builder(...).tool(\"" + name + "\", ...), or"
                + " ActionPolicy.none() for a read-only tool"));
        if (!(policy instanceof JournaledPolicy<?>)) {
            if (toolTelemetry == ActionTelemetry.none()) {
                return tool; // read-only: called directly
            }
            return tool.toBuilder()
                    .toolExecutor(new ObservedToolExecutor(tool.toolExecutor(), tool.toolSpecification(), toolTelemetry))
                    .build();
        }
        if (tool.returnBehavior() == ReturnBehavior.IMMEDIATE && !immediateReturnAllowed.contains(name)) {
            throw new IllegalStateException("tool " + name + " returns immediately to the user, so texts such as"
                    + " \"being verified\" would become the answer; use ReturnBehavior.TO_LLM, or allow it with"
                    + " ReliableTools.builder(...).allowImmediateReturn(\"" + name + "\")");
        }
        return tool.toBuilder()
                .toolExecutor(new ReliableToolExecutor(tool.toolExecutor(), tool.toolSpecification(),
                        actions.runner(), renderer, parser, toolTelemetry))
                .build();
    }

    /** Settings of {@link ReliableTools}. */
    public static final class Builder {

        private final ReliableActions actions;
        private OutcomeRenderer renderer = OutcomeRenderer.standard();
        private final Set<String> immediateReturnAllowed = new HashSet<>();
        private boolean toolSpans = true;

        private Builder(ReliableActions actions) {
            this.actions = Objects.requireNonNull(actions, "actions");
        }

        /**
         * Replaces the texts the model reads; default {@link OutcomeRenderer#standard()}.
         *
         * @param renderer the renderer
         * @return this builder
         */
        public Builder renderer(OutcomeRenderer renderer) {
            this.renderer = Objects.requireNonNull(renderer, "renderer");
            return this;
        }

        /**
         * Allows journaled tools with {@link ReturnBehavior#IMMEDIATE}: their outcome text becomes the answer.
         *
         * @param toolNames tool names
         * @return this builder
         */
        public Builder allowImmediateReturn(String... toolNames) {
            immediateReturnAllowed.addAll(Arrays.asList(toolNames)); // rejects null names
            return this;
        }

        /**
         * Reports every tool execution as an {@code execute_tool} observation of the telemetry of
         * {@link ReliableActions} (the GenAI semantic conventions' tool span); default {@code true}. Turn it off when
         * another instrumentation already records tool executions. Without a telemetry it has no effect.
         *
         * @param toolSpans whether to report tool executions
         * @return this builder
         */
        public Builder toolSpans(boolean toolSpans) {
            this.toolSpans = toolSpans;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return the configured tool wrapper
         */
        public ReliableTools build() {
            return new ReliableTools(this);
        }
    }
}
