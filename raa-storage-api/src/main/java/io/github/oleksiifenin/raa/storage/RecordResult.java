package io.github.oleksiifenin.raa.storage;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of a record write.
 *
 * @param outcome     what happened
 * @param record      the row after the write (or as found when the write did not land); empty only for
 *                    {@link RecordOutcome#MISSING}
 * @param stateBefore the state of the row before the write, for example to detect a late success that corrected a
 *                    lookup ({@code FAILED} or {@code ABANDONED}); empty only for {@link RecordOutcome#MISSING}
 */
public record RecordResult(RecordOutcome outcome, Optional<ActionRecord> record, Optional<ActionState> stateBefore) {

    /** Validates that row and previous state are present unless the outcome is {@link RecordOutcome#MISSING}. */
    public RecordResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(stateBefore, "stateBefore");
        boolean missing = outcome == RecordOutcome.MISSING;
        if (record.isPresent() == missing || stateBefore.isPresent() == missing) {
            throw new IllegalArgumentException(outcome + " needs a row and a previous state unless MISSING");
        }
    }
}
