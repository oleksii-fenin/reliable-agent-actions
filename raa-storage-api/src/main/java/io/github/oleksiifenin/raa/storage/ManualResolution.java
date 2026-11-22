package io.github.oleksiifenin.raa.storage;

import java.util.Objects;

/**
 * An operator's resolution of an {@link ActionState#ESCALATED} action, applied by
 * {@link ActionAdmin#resolveManually} (docs/DESIGN.md §6: {@code ESCALATED → SUCCEEDED} or
 * {@code ESCALATED → ABANDONED}).
 */
public sealed interface ManualResolution {

    /** The last error an action gets when an operator resolves it as {@link NotApplied}. */
    String NOT_APPLIED_ERROR = "an operator confirmed that the action was not applied";

    /**
     * The operator verified that the effect was applied: {@code ESCALATED → SUCCEEDED}. Later calls replay the text.
     *
     * @param resultText result text to record, for example what the downstream system shows
     */
    record Applied(String resultText) implements ManualResolution {
        /** Validates the text. */
        public Applied {
            Objects.requireNonNull(resultText, "resultText");
        }
    }

    /**
     * The operator verified that the effect was not applied: {@code ESCALATED → ABANDONED} with
     * {@link #NOT_APPLIED_ERROR} as the last error. No attempt starts again for the key; a later call is told that the
     * action was not performed.
     */
    record NotApplied() implements ManualResolution {}
}
