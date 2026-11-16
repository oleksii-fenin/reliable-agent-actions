package io.github.oleksiifenin.raa.core;

import java.time.Instant;
import java.util.Objects;

/**
 * What a call of {@link ActionRunner#run} ended with (docs/DESIGN.md §5, §6). Each variant maps to one text the model
 * is told; a framework adapter renders it.
 */
public sealed interface ActionOutcome {

    /**
     * This call performed the action.
     *
     * @param resultText the effect's result text
     * @param attempt    the attempt that performed it
     */
    record Succeeded(String resultText, int attempt) implements ActionOutcome {
        /** Validates the fields. */
        public Succeeded {
            Objects.requireNonNull(resultText, "resultText");
        }
    }

    /**
     * The action was performed before; its recorded result is returned and nothing was performed again.
     *
     * @param resultText  the recorded result text
     * @param completedAt when the result was recorded (database time)
     * @param fromLookup  whether this call learned the result from an outcome lookup
     */
    record Replayed(String resultText, Instant completedAt, boolean fromLookup) implements ActionOutcome {
        /** Validates the fields. */
        public Replayed {
            Objects.requireNonNull(resultText, "resultText");
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    /**
     * The call has other material arguments than the recorded action; nothing was performed.
     *
     * @param recordedResultText the recorded result if the action succeeded, otherwise {@code null}
     * @param previousUnresolved whether the earlier attempt's outcome is still unknown or in progress
     */
    record Conflict(String recordedResultText, boolean previousUnresolved) implements ActionOutcome {}

    /**
     * Another call is executing the action and did not finish within the in-progress wait.
     *
     * @param leaseUntil end of that call's lease (database time)
     */
    record InProgressElsewhere(Instant leaseUntil) implements ActionOutcome {
        /** Validates the fields. */
        public InProgressElsewhere {
            Objects.requireNonNull(leaseUntil, "leaseUntil");
        }
    }

    /**
     * The action may or may not have been applied.
     *
     * @param advice what the model may do next
     * @param reason why the outcome is unknown, for the model and operators
     */
    record Unknown(RepeatAdvice advice, String reason) implements ActionOutcome {
        /** Validates the fields. */
        public Unknown {
            Objects.requireNonNull(advice, "advice");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The action was definitely not applied.
     *
     * @param error     the error
     * @param retryable whether attempts are left, so a corrected call may start a new one
     */
    record Failed(String error, boolean retryable) implements ActionOutcome {
        /** Validates the fields. */
        public Failed {
            Objects.requireNonNull(error, "error");
        }
    }

    /**
     * The action was not applied and no attempt will start again (attempts exhausted or deadline passed).
     *
     * @param reason why
     */
    record Abandoned(String reason) implements ActionOutcome {
        /** Validates the fields. */
        public Abandoned {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The outcome could not be verified automatically; an operator must resolve it.
     *
     * @param reason why
     */
    record Escalated(String reason) implements ActionOutcome {
        /** Validates the fields. */
        public Escalated {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The journal could not be used, so the effect was not started (fail closed).
     *
     * @param reason the storage error
     */
    record JournalUnavailable(String reason) implements ActionOutcome {
        /** Validates the fields. */
        public JournalUnavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
