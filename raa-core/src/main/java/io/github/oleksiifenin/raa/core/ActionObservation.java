package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.ClaimVerdict;
import io.github.oleksiifenin.raa.storage.RecordOutcome;

/**
 * One action call in progress ({@link ActionTelemetry#startAction}). The runner reports the state transitions it
 * sees and ends the observation exactly once, on the thread that started it.
 */
public interface ActionObservation {

    /** The observation that records nothing. */
    ActionObservation NONE = new ActionObservation() {
    };

    /**
     * A claim was decided and applied.
     *
     * @param verdict the decision
     * @param state   the row's state after the claim
     * @param attempt the row's attempt after the claim
     */
    default void claimed(ClaimVerdict verdict, ActionState state, int attempt) {
    }

    /**
     * An attempt's outcome was written (or found already written); not reported if every write failed.
     *
     * @param outcome what the write did
     * @param attempt the attempt
     */
    default void recorded(RecordOutcome outcome, int attempt) {
    }

    /**
     * An outcome lookup answered.
     *
     * @param result {@code found}, {@code not_found} or {@code unreachable}
     */
    default void lookedUp(String result) {
    }

    /**
     * The call returned.
     *
     * @param outcome what the caller got
     */
    default void ended(ActionOutcome outcome) {
    }

    /**
     * The call threw instead of returning an outcome.
     *
     * @param error the exception
     */
    default void failed(Throwable error) {
    }
}
