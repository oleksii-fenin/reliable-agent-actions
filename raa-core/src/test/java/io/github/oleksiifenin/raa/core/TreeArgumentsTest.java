package io.github.oleksiifenin.raa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TreeArgumentsTest {

    private final Arguments arguments = new TreeArguments(Map.of(
            "orderId", "order-42", "count", 3, "big", BigInteger.valueOf(7), "amount", new BigDecimal("10.50"),
            "whole", new BigDecimal("4.0")));

    @Test
    void readsTypedValues() {
        assertThat(arguments.string("orderId")).isEqualTo("order-42");
        assertThat(arguments.longValue("count")).isEqualTo(3L);
        assertThat(arguments.longValue("big")).isEqualTo(7L);
        assertThat(arguments.longValue("whole")).isEqualTo(4L);
        assertThat(arguments.decimal("amount")).isEqualByComparingTo("10.5");
        assertThat(arguments.decimal("count")).isEqualByComparingTo("3");
    }

    @Test
    void rejectsMissingArgumentsAndWrongTypes() {
        assertThatThrownBy(() -> arguments.string("missing")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> arguments.string("count")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arguments.decimal("orderId")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arguments.longValue("amount")).isInstanceOf(IllegalArgumentException.class);
    }
}
