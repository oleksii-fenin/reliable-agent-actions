package io.github.oleksiifenin.raa.langchain4j;

import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ToolCall;

/**
 * Turns the outcome of a journaled tool call into the text the model reads as the tool result (docs/DESIGN.md §6,
 * "What the model is told"). Replaceable, for example to translate the texts; {@link #standard()} is the default.
 */
@FunctionalInterface
public interface OutcomeRenderer {

    /**
     * Renders an outcome.
     *
     * @param outcome what happened
     * @param call    the call it answers
     * @return the tool result text for the model; never {@code null}
     */
    String render(ActionOutcome outcome, ToolCall call);

    /**
     * Returns the default texts of docs/DESIGN.md §6.
     *
     * @return the default renderer
     */
    static OutcomeRenderer standard() {
        return new StandardOutcomeRenderer();
    }
}
