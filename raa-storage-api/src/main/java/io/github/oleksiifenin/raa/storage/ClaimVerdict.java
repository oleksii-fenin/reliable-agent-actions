package io.github.oleksiifenin.raa.storage;

/** What a claim means for the caller, as decided by the {@link ClaimDecider}. */
public enum ClaimVerdict {
    /** An attempt was started for this caller; it must run the effect and record its outcome. */
    START,
    /** The action already succeeded with the same material arguments; return the recorded result. */
    REPLAY,
    /** Another outcome blocks this call: different material arguments, or a previous outcome is unresolved. */
    CONFLICT,
    /** Another attempt holds a live lease. */
    IN_PROGRESS,
    /** The outcome is unknown and a lookup is due; the caller performs it outside the claim transaction. */
    NEEDS_LOOKUP,
    /** The outcome is unknown and cannot be checked yet; the caller should ask again later. */
    ASK_LATER,
    /** The outcome is unknown and an operator is needed. */
    ESCALATED,
    /** The effect was not applied and no attempts are left. */
    ABANDONED,
    /**
     * Inspect mode only (docs/DESIGN.md §6, memory repair): the table would start an attempt, but nothing is started;
     * the write is at most the move of an expired attempt to {@code UNKNOWN}.
     */
    WOULD_START
}
