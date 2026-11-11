package io.github.oleksiifenin.raa.storage;

import java.util.Objects;

/**
 * Outcome of {@link ActionJournal#claim}: the decision that was applied and the row as committed.
 *
 * @param decision the applied decision
 * @param record   the row after the write was applied
 */
public record ClaimResult(ClaimDecision decision, ActionRecord record) {

    /** Validates mandatory fields. */
    public ClaimResult {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(record, "record");
    }
}
