package io.github.oleksiifenin.raa.core;

/** What the {@link Reconciler} did to a row. */
public enum ReconcilerResult {
    /** An executing attempt whose lease ran out became {@code UNKNOWN}. */
    MARKED_UNKNOWN,
    /** The outcome lookup found the effect: {@code SUCCEEDED}. */
    APPLIED,
    /** The outcome lookup did not find the effect: {@code FAILED}. */
    NOT_APPLIED,
    /** The outcome lookup was unreachable; it is tried again after a backoff. */
    LOOKUP_UNREACHABLE,
    /** An operator must resolve the action: {@code ESCALATED}. */
    ESCALATED,
    /** A failed action passed its deadline: {@code ABANDONED}. */
    ABANDONED
}
