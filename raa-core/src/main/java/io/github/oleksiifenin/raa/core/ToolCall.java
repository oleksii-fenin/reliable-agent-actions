package io.github.oleksiifenin.raa.core;

import java.util.Objects;

/**
 * One tool call as the model emitted it.
 *
 * @param toolName       name of the tool
 * @param argumentsJson  arguments as JSON text
 * @param providerCallId tool-call id given by the provider, or {@code null}; changes on regeneration, never a key
 * @param scope          memory or conversation id, or {@code null}; the {@link KeyStrategy} must still yield a scope
 */
public record ToolCall(String toolName, String argumentsJson, String providerCallId, String scope) {

    /** Validates mandatory fields. */
    public ToolCall {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
