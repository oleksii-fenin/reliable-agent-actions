package io.github.oleksiifenin.raa.storage;

/**
 * State of a journaled action (docs/DESIGN.md §6).
 *
 * <p>{@link #SUCCEEDED} and {@link #ABANDONED} are terminal. {@link #ESCALATED} is terminal for automation: only an
 * operator resolves it. A late success of the same attempt may still move any non-succeeded state to
 * {@link #SUCCEEDED}.
 */
public enum ActionState {
    /** An attempt holds (or held) a lease and may be running the effect. */
    EXECUTING,
    /** The effect of the current attempt was recorded as applied. */
    SUCCEEDED,
    /** The current attempt did not apply the effect; see {@link FailureBasis} for how sure that is. */
    FAILED,
    /** The outcome of the current attempt is not known. */
    UNKNOWN,
    /** The effect was not applied and no attempts are left. */
    ABANDONED,
    /** The outcome is unknown and cannot be resolved automatically; an operator is needed. */
    ESCALATED
}
