package io.github.oleksiifenin.raa.storage;

import java.util.Objects;

/**
 * The change a {@link ClaimDecision} asks the journal to apply to the row, in the claim transaction. The journal
 * applies it to the row it locked and passed to the decider, without re-checking states or times: the decider already
 * decided on that locked row and that database time. A write may therefore apply to any source state the table allows,
 * including an {@link ActionState#EXECUTING} row whose lease expired (row 6, folded into the same decision).
 */
public sealed interface JournalWrite {

    /** Leave the row as it is. */
    record None() implements JournalWrite {}

    /**
     * Start attempt {@code attempt}: insert the row if there is none (attempt 1), otherwise update it. The journal sets
     * state {@link ActionState#EXECUTING}, the lease owner and {@code lease_until = clock_timestamp() + lease},
     * {@code started_at = clock_timestamp()}, the request's argument hashes, policy and maximum effect duration, and
     * clears result, error, failure basis, lookup failures and {@code resolve_after}. {@code deadline_at} is set only
     * on insert ({@code clock_timestamp() + deadline}) and never moved by a later attempt.
     *
     * @param attempt the new attempt number, 1 for a new row, otherwise the current attempt + 1
     */
    record StartAttempt(int attempt) implements JournalWrite {
        /** Validates the attempt number. */
        public StartAttempt {
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be at least 1: " + attempt);
            }
        }
    }

    /**
     * Move the row to {@code state} without starting an attempt; the attempt number is kept, the lease (owner and
     * end) is cleared and {@code updated_at = clock_timestamp()}. A move to {@link ActionState#UNKNOWN} also sets
     * {@code resolve_after = clock_timestamp()} (every transition into {@code UNKNOWN} does, docs/DESIGN.md §6); a
     * move to {@link ActionState#ESCALATED} or {@link ActionState#ABANDONED} clears {@code resolve_after}.
     *
     * @param state {@link ActionState#UNKNOWN}, {@link ActionState#ESCALATED} or {@link ActionState#ABANDONED}
     */
    record ChangeState(ActionState state) implements JournalWrite {
        /** Rejects states a claim never moves a row to. */
        public ChangeState {
            Objects.requireNonNull(state, "state");
            if (state != ActionState.UNKNOWN && state != ActionState.ESCALATED && state != ActionState.ABANDONED) {
                throw new IllegalArgumentException("a claim does not move a row to " + state);
            }
        }
    }
}
