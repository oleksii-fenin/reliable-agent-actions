package io.github.oleksiifenin.raa.storage;

/** What happened to a {@link ActionJournal#recordSuccess} or {@link ActionJournal#recordFailure} write. */
public enum RecordOutcome {
    /** The write landed. */
    APPLIED,
    /** A newer attempt exists; the write did not change the row (fenced). */
    SUPERSEDED,
    /** The same attempt is already {@link ActionState#SUCCEEDED}; the first recorded result is kept. */
    ALREADY_SUCCEEDED,
    /**
     * A failure of the same attempt arrived after the row left {@code EXECUTING}/{@code UNKNOWN} (for example it was
     * resolved by a lookup or escalated); the row is unchanged.
     */
    ALREADY_RESOLVED,
    /**
     * The row the attempt belongs to does not exist: there is no row for the key, or the row was created again after
     * retention deleted the attempt's row (its creation time differs, {@link KeyAttempt}); nothing was changed.
     */
    MISSING
}
