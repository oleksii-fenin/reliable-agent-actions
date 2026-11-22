package io.github.oleksiifenin.raa.storage;

import java.util.List;

/**
 * Operator operations on the journal (docs/DESIGN.md §5): find actions, resolve escalated ones, erase personal data.
 * Every journal implements it ({@link ActionJournal} extends it); applications reach it through
 * {@code ReliableActions.admin()}. Same rules as the journal: database time, short transactions, compare-and-set
 * writes, {@link JournalException} for storage failures.
 */
public interface ActionAdmin {

    /**
     * Lists actions in one state, least recently changed first ({@code updated_at}, then key hash).
     *
     * @param state the state
     * @param limit maximum number of rows, at least 1
     * @return the rows
     * @throws IllegalArgumentException if {@code limit} is less than 1
     */
    List<ActionRecord> find(ActionState state, int limit);

    /**
     * Counts the actions in one state. Cheap for {@link ActionState#UNKNOWN} and {@link ActionState#ESCALATED} (the
     * unresolved actions, which the reconciler reports as a gauge); other states may need a scan of many rows.
     *
     * @param state the state
     * @return the number of rows in that state
     */
    long count(ActionState state);

    /**
     * Applies an operator's resolution as a compare-and-set on the {@link KeyAttempt} and the state:
     * {@code WHERE key_hash = ? AND created_at = ? AND attempt = ? AND state = 'ESCALATED'}. A redacted row records no
     * text.
     *
     * @param attempt    the attempt the operator checked, for example {@code KeyAttempt.of(row)} of a row from
     *                   {@link #find}
     * @param resolution what the operator found
     * @return whether the row changed; {@code false} if it is missing, has another attempt or is no longer escalated
     *         (for example because a late success of the attempt landed)
     */
    boolean resolveManually(KeyAttempt attempt, ManualResolution resolution);

    /**
     * Erases the texts that may contain personal data (key text, arguments, result, last error) and marks the row
     * redacted, in any state. The row itself stays, so the key is still deduplicated: a later call of a succeeded
     * action is told that it was performed and that its result was removed, and is not performed again. Every later
     * write to the row (a late result of a running attempt, a new attempt, a resolution) stores none of these texts.
     * The update time is kept, so retention deletes the row on its usual schedule. The request names the key, not an
     * attempt: it redacts the row the key has now, also one created again after retention deleted an earlier one.
     *
     * <p>The hashes stay (key, scope, arguments): they are needed for deduplication and conflict detection. A hash of
     * a low-entropy value, such as an order id or an e-mail address, can be reversed by trying candidates; keyed
     * hashing is a candidate for a later version (docs/DESIGN.md §10).
     *
     * @param keyHash the action
     * @return whether the row exists (redacting a redacted row again returns {@code true} and changes nothing)
     */
    boolean redact(byte[] keyHash);
}
