package io.github.oleksiifenin.raa.core;

/** Signals the runtime counts (docs/DESIGN.md §10). */
public enum ActionSignal {
    /** An attempt finished after a newer attempt had started; its result was not recorded. */
    STALE_SUCCESS,
    /** A late success of an attempt landed after a lookup had marked the action not performed. */
    LATE_RESULT_CORRECTED,
    /** An attempt's lease ran out while it was executing; the row became {@code UNKNOWN}. */
    LEASE_EXPIRED
}
