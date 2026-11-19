package io.github.oleksiifenin.raa.core;

/**
 * Observability hooks of the runtime (docs/DESIGN.md §10): one observation per action call, one per tool execution of
 * a framework adapter, the duration of every journal operation, and counted signals. {@code raa-opentelemetry} turns
 * them into OpenTelemetry spans and metrics; {@link #none()} is the default.
 *
 * <p>Every method has a do-nothing default, so an implementation overrides only what it records. Observations start
 * and end on the calling thread (the runner and the adapters are synchronous), so an implementation may make its span
 * current for the time in between. An exception thrown by an implementation is logged and never changes what an
 * action does.
 *
 * <p>Nothing passed here contains arguments, results or key text; the key is passed as its hash.
 */
public interface ActionTelemetry {

    /**
     * Returns the telemetry that records nothing.
     *
     * @return the no-op telemetry
     */
    static ActionTelemetry none() {
        return NoTelemetry.INSTANCE;
    }

    /**
     * Starts the observation of one {@link ActionRunner#run} or {@link ActionRunner#inspect} call of a journaled tool.
     *
     * @param start what is known when the call starts
     * @return the observation; ended exactly once
     */
    default ActionObservation startAction(ActionStart start) {
        return ActionObservation.NONE;
    }

    /**
     * Starts the observation of one tool execution as a framework adapter performs it, journaled or not (the
     * {@code execute_tool} span of the GenAI semantic conventions). The action observation of a journaled tool starts
     * and ends inside it.
     *
     * @param toolName       the tool name
     * @param description    the tool description, {@code null} if none
     * @param providerCallId the provider's tool-call id, {@code null} if none
     * @return the observation; ended exactly once
     */
    default ToolObservation startTool(String toolName, String description, String providerCallId) {
        return ToolObservation.NONE;
    }

    /**
     * Reports one journal operation.
     *
     * @param operation     the {@code ActionJournal} method, for example {@code claim}
     * @param durationNanos how long it took, from a monotonic clock
     * @param failed        whether it threw
     */
    default void journalOperation(String operation, long durationNanos, boolean failed) {
    }

    /**
     * Reports a signal worth counting.
     *
     * @param signal   the signal
     * @param toolName the tool of the action
     */
    default void signal(ActionSignal signal, String toolName) {
    }

    /**
     * Reports a row the {@link Reconciler} changed.
     *
     * @param result   what it did
     * @param toolName the tool of the action
     */
    default void reconcilerResolution(ReconcilerResult result, String toolName) {
    }
}
