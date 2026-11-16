package io.github.oleksiifenin.raa.core;

import java.util.Map;
import java.util.Objects;

/**
 * One tool call as the model emitted it, with its arguments already parsed by the framework adapter (ADR-0003).
 *
 * <p>{@code arguments} is the canonical value tree of the JSON arguments object, built by the adapter with the tool's
 * schema: values are {@code null}, {@link Boolean}, {@link String}, an integral number ({@link Integer},
 * {@link Long}, {@link Short}, {@link Byte}, {@link java.math.BigInteger}) or {@link java.math.BigDecimal}, a
 * {@link java.util.List} of values, or a {@link Map} with string keys. Floating-point numbers are rejected: the
 * adapter converts JSON numbers to {@code BigDecimal} from their text. The core hashes this tree; the map is not
 * copied, so the adapter must not change it afterwards.
 *
 * @param toolName       name of the tool
 * @param argumentsJson  arguments as JSON text, as the model sent them (for display; never hashed)
 * @param arguments      the parsed arguments object
 * @param providerCallId tool-call id given by the provider, or {@code null}; changes on regeneration, never a key
 * @param scope          memory or conversation id, or {@code null}; the {@link KeyStrategy} must still yield a scope
 */
public record ToolCall(
        String toolName, String argumentsJson, Map<String, ?> arguments, String providerCallId, String scope) {

    /** Validates mandatory fields. */
    public ToolCall {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        Objects.requireNonNull(arguments, "arguments");
    }
}
