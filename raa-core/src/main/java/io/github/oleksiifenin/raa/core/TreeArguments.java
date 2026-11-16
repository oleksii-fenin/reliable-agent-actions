package io.github.oleksiifenin.raa.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;

/** {@link Arguments} over the top level of a call's argument tree (see {@link ToolCall}). */
final class TreeArguments implements Arguments {

    private final Map<String, ?> arguments;

    TreeArguments(Map<String, ?> arguments) {
        this.arguments = Objects.requireNonNull(arguments, "arguments");
    }

    @Override
    public String string(String name) {
        Object value = present(name);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("argument " + name + " is not a string");
        }
        return (String) value;
    }

    @Override
    public long longValue(String name) {
        try {
            return decimal(name).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("argument " + name + " is not an integral number in the long range", e);
        }
    }

    @Override
    public BigDecimal decimal(String name) {
        Object value = present(name);
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        throw new IllegalArgumentException("argument " + name + " is not a number");
    }

    private Object present(String name) {
        Objects.requireNonNull(name, "name");
        Object value = arguments.get(name);
        if (value == null) {
            throw new IllegalArgumentException("argument " + name + " is missing");
        }
        return value;
    }
}
