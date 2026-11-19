package io.github.oleksiifenin.raa.core;

/** One tool execution of a framework adapter in progress ({@link ActionTelemetry#startTool}); ended exactly once. */
public interface ToolObservation {

    /** The observation that records nothing. */
    ToolObservation NONE = new ToolObservation() {
    };

    /**
     * The tool returned a result to the model.
     *
     * @param error whether the result is marked as an error
     */
    default void ended(boolean error) {
    }

    /**
     * The tool execution threw.
     *
     * @param error the exception
     */
    default void failed(Throwable error) {
    }
}
