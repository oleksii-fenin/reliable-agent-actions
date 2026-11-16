package io.github.oleksiifenin.raa.core;

/** The side effect of one tool call, run by {@link ActionRunner} at most once per started attempt. */
@FunctionalInterface
public interface Effect {

    /**
     * Performs the action.
     *
     * @param context the attempt being executed; also available as {@link ActionContext#current()} on this thread
     * @return the result text for the model; {@code null} is recorded as the empty text
     * @throws Exception if the action failed; classified by the policy's {@link FailureClassifier}
     */
    String execute(ActionContext context) throws Exception;
}
