package io.github.oleksiifenin.raa.core;

import java.math.BigDecimal;

/** Typed access to the arguments of a {@link ToolCall}, used by a {@link KeyStrategy}. */
public interface Arguments {

    /**
     * Returns a string argument.
     *
     * @param name argument name
     * @return the value
     */
    String string(String name);

    /**
     * Returns an integral argument.
     *
     * @param name argument name
     * @return the value
     */
    long longValue(String name);

    /**
     * Returns a decimal argument.
     *
     * @param name argument name
     * @return the value
     */
    BigDecimal decimal(String name);
}
