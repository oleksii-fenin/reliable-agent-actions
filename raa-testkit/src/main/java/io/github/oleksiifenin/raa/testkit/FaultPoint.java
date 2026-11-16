package io.github.oleksiifenin.raa.testkit;

/**
 * Where the kill matrix stops a process (docs/DESIGN.md §9): each point is a moment of one tool call at which the
 * process is killed with SIGKILL.
 */
public enum FaultPoint {

    /** The action is claimed and the effect has started, but nothing was sent downstream. */
    AFTER_CLAIM,

    /**
     * The downstream applied the effect and holds its response; reached in the {@link FakeDownstream}, not in the
     * calling process.
     */
    EFFECT_APPLIED_RESPONSE_HELD,

    /** The effect got the downstream's response; the journal has not recorded it. */
    AFTER_RESPONSE_BEFORE_RECORD,

    /** The journal recorded the result; the caller (for example chat memory) has not stored it. */
    AFTER_RECORD_BEFORE_MEMORY
}
