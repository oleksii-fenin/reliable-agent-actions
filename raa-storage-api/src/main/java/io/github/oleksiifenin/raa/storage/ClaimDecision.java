package io.github.oleksiifenin.raa.storage;

import java.util.Objects;

/**
 * Result of a {@link ClaimDecider}: the verdict for the caller and the write the journal applies in the same
 * transaction.
 *
 * <p>Consistency rules (checked here so that a decider bug cannot reach storage): {@link ClaimVerdict#START} comes with
 * {@link JournalWrite.StartAttempt} and only with it; a move to {@link ActionState#ESCALATED} or
 * {@link ActionState#ABANDONED} comes with the verdict of the same name.
 *
 * @param verdict what the claim means for the caller
 * @param write   the change to apply to the row
 */
public record ClaimDecision(ClaimVerdict verdict, JournalWrite write) {

    /** Validates that the verdict and the write agree. */
    public ClaimDecision {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(write, "write");
        boolean starts = write instanceof JournalWrite.StartAttempt;
        if (starts != (verdict == ClaimVerdict.START)) {
            throw new IllegalArgumentException("verdict " + verdict + " does not match write " + write);
        }
        if (write instanceof JournalWrite.ChangeState) {
            ActionState target = ((JournalWrite.ChangeState) write).state();
            if (target == ActionState.ESCALATED && verdict != ClaimVerdict.ESCALATED
                    || target == ActionState.ABANDONED && verdict != ClaimVerdict.ABANDONED) {
                throw new IllegalArgumentException("verdict " + verdict + " does not match write " + write);
            }
        }
    }
}
