package io.github.oleksiifenin.raa.core;

/** Derives the business key of an action from a tool call; mandatory for every journaled policy. */
@FunctionalInterface
public interface KeyStrategy {

    /**
     * Returns the key of the action the call asks for.
     *
     * @param call the tool call
     * @param args typed access to its arguments
     * @return the key; never derived from the provider tool-call id
     */
    ActionKey keyFor(ToolCall call, Arguments args);
}
