package io.github.oleksiifenin.raa.core;

import java.util.Objects;

/** Answer of an {@link OutcomeLookup}. */
public sealed interface LookupResult {

    /**
     * The action was applied.
     *
     * @param resultText result text to record and return to the model
     */
    record Found(String resultText) implements LookupResult {
        /** Validates the result text. */
        public Found {
            Objects.requireNonNull(resultText, "resultText");
        }
    }

    /** The downstream does not know the action. Evidence, not proof: a paused attempt may still land. */
    record NotFound() implements LookupResult {}

    /**
     * The downstream could not be asked.
     *
     * @param reason why
     */
    record Unreachable(String reason) implements LookupResult {
        /** Validates the reason. */
        public Unreachable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
