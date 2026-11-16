package io.github.oleksiifenin.raa.core;

/** What the model may do after an {@link ActionOutcome.Unknown} outcome (docs/DESIGN.md §6, "What the model is told"). */
public enum RepeatAdvice {
    /** Calling again with the same arguments is safe: the downstream deduplicates ({@code IDEMPOTENT_DOWNSTREAM}). */
    SAFE,
    /** Calling again may perform the action twice ({@code AT_LEAST_ONCE}). */
    MAY_DUPLICATE,
    /** Do not repeat now; the outcome is being verified, call again later to get it. */
    ASK_LATER
}
