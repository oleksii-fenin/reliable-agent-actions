package io.github.oleksiifenin.raa.storage;

import java.time.Duration;
import java.util.Objects;

/**
 * A resolution of an unresolved attempt, applied by {@link ActionJournal#resolve} as a compare-and-set on
 * {@code (key_hash, attempt)} and the source state each variant names. Used by the core after an outcome lookup and
 * by the reconciler (docs/DESIGN.md §6).
 */
public sealed interface Resolution {

    /**
     * {@code UNKNOWN → SUCCEEDED}: a lookup found the effect.
     *
     * @param resultText result text to record
     */
    record Applied(String resultText) implements Resolution {
        /** Validates the text. */
        public Applied {
            Objects.requireNonNull(resultText, "resultText");
        }
    }

    /** {@code UNKNOWN → FAILED} with basis {@link FailureBasis#LOOKUP}: a lookup did not find the effect. */
    record NotApplied() implements Resolution {}

    /**
     * {@code UNKNOWN} stays: the lookup was unreachable. Increments {@code lookup_failures} and sets
     * {@code resolve_after = clock_timestamp() + backoff}.
     *
     * @param backoff positive delay before the next lookup
     */
    record LookupUnreachable(Duration backoff) implements Resolution {
        /** Validates the backoff. */
        public LookupUnreachable {
            Objects.requireNonNull(backoff, "backoff");
            if (backoff.isNegative() || backoff.isZero()) {
                throw new IllegalArgumentException("backoff must be positive: " + backoff);
            }
        }
    }

    /**
     * {@code UNKNOWN → ESCALATED}: the outcome cannot be resolved automatically.
     *
     * @param reason why; stored as the last error
     */
    record Escalate(String reason) implements Resolution {
        /** Validates the reason. */
        public Escalate {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * {@code FAILED → ABANDONED}: no new attempt will start (deadline passed).
     *
     * @param reason why; stored as the last error
     */
    record Abandon(String reason) implements Resolution {
        /** Validates the reason. */
        public Abandon {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
