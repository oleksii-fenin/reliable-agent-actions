package io.github.oleksiifenin.raa.langchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.ToolCall;
import io.github.oleksiifenin.raa.testkit.InMemoryActionJournal;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The adapter's half of ADR-0003: the shared canonicalization vectors (key order, number scale, nested objects) and the
 * coercion to the schema type, checked through the core's hashes, so the adapter and the core cannot disagree silently.
 */
class ArgumentsParserTest {

    private static final JsonObjectSchema REFUND = JsonObjectSchema.builder()
            .addProperty("orderId", JsonStringSchema.builder().build())
            .addProperty("amount", JsonNumberSchema.builder().build())
            .addProperty("quantity", JsonIntegerSchema.builder().build())
            .addProperty("express", JsonBooleanSchema.builder().build())
            .addProperty("address", JsonObjectSchema.builder()
                    .addProperty("zip", JsonStringSchema.builder().build())
                    .addProperty("lines", JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build())
                    .build())
            .build();

    private final ArgumentsParser parser = new ArgumentsParser();

    @Test
    void sharedVectors_keyOrderNumberScaleAndNesting_doNotChangeTheArguments() {
        String reference = "{\"orderId\":\"o-1\",\"amount\":10,\"address\":{\"zip\":\"1000\",\"lines\":[\"a\",\"b\"]}}";
        List<String> equivalent = List.of(
                "{\"address\":{\"lines\":[\"a\",\"b\"],\"zip\":\"1000\"},\"amount\":10,\"orderId\":\"o-1\"}",
                "{\"orderId\":\"o-1\",\"amount\":10.00,\"address\":{\"zip\":\"1000\",\"lines\":[\"a\",\"b\"]}}",
                "{\"orderId\":\"o-1\",\"amount\":1E+1,\"address\":{\"zip\":\"1000\",\"lines\":[\"a\",\"b\"]}}",
                " { \"orderId\" : \"o-1\" , \"amount\" : \"10.0\" , \"address\" : { \"zip\" : 1000 , \"lines\" : [\"a\",\"b\"] } } ");
        List<String> different = List.of(
                "{\"orderId\":\"o-1\",\"amount\":11,\"address\":{\"zip\":\"1000\",\"lines\":[\"a\",\"b\"]}}",
                "{\"orderId\":\"o-1\",\"amount\":10,\"address\":{\"zip\":\"1000\",\"lines\":[\"b\",\"a\"]}}",
                "{\"orderId\":\"o-1\",\"amount\":10,\"address\":{\"zip\":\"1000\"}}");

        byte[] expected = materialHash(reference);
        for (String json : equivalent) {
            assertThat(materialHash(json)).as(json).isEqualTo(expected);
        }
        for (String json : different) {
            assertThat(materialHash(json)).as(json).isNotEqualTo(expected);
        }
    }

    @Test
    void values_areExact_andCoercedToTheDeclaredType() {
        Map<String, Object> arguments = parser.parse("{\"orderId\":42,\"amount\":\"19.99\",\"quantity\":\"3\","
                + "\"express\":\"true\",\"extra\":12345678901234567890123,\"note\":1.5}", REFUND);

        assertThat(arguments.get("orderId")).isEqualTo("42");
        assertThat(arguments.get("amount")).isEqualTo(new BigDecimal("19.99"));
        assertThat(arguments.get("quantity")).isEqualTo(3L);
        assertThat(arguments.get("express")).isEqualTo(true);
        assertThat(arguments.get("extra")).isEqualTo(new BigInteger("12345678901234567890123"));
        assertThat(arguments.get("note")).isEqualTo(new BigDecimal("1.5")); // undeclared: exact, never a double
    }

    @Test
    void integerParameters_acceptIntegralValues_inAnyNotation() {
        for (String json : List.of("{\"quantity\":2}", "{\"quantity\":2.0}", "{\"quantity\":\"2\"}",
                "{\"quantity\":\"2.00\"}", "{\"quantity\":2E0}")) {
            assertThat(parser.parse(json, REFUND).get("quantity")).as(json).isEqualTo(2L);
        }
    }

    @Test
    void hugeExponents_areRejectedBeforeAnyExpansion() {
        for (String json : List.of("{\"quantity\":\"1e10000000\"}", "{\"quantity\":1e10000000}",
                "{\"quantity\":\"0e10000000\"}")) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> parser.parse(json, REFUND)).as(json).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("in range");
            assertThat(System.nanoTime() - started).as("rejected at once").isLessThan(1_000_000_000L);
        }
    }

    @Test
    void blankArguments_areNoArguments() {
        assertThat(parser.parse("", REFUND)).isEmpty();
        assertThat(parser.parse(null, null)).isEmpty();
        assertThat(parser.parse("{}", null)).isEmpty();
    }

    @Test
    void malformedOrMistypedArguments_areRejected() {
        List<String> rejected = List.of("[]", "\"x\"", "{", "{\"orderId\":\"a\",\"orderId\":\"b\"}", "{} {}",
                "{\"quantity\":\"2.5\"}", "{\"quantity\":2.5}", "{\"quantity\":\"many\"}", "{\"amount\":\"ten\"}",
                "{\"express\":\"yes\"}", "{\"express\":1}", "{\"amount\":true}", "{\"amount\":[1]}",
                "{\"orderId\":{\"id\":1}}");

        for (String json : rejected) {
            assertThatThrownBy(() -> parser.parse(json, REFUND)).as(json).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private byte[] materialHash(String json) {
        // Hash through the runner, as the adapter does: the recorded row carries the core's material hash.
        InMemoryActionJournal journal = new InMemoryActionJournal();
        ReliableActions actions = ReliableActions.builder(journal)
                .tool("refund", ActionPolicy.atLeastOnce()
                        .key((call, args) -> ActionKey.of("c", "refund", args.string("orderId")))
                        .maxEffectDuration(Duration.ofSeconds(10)))
                .build();
        ToolCall call = new ToolCall("refund", json, parser.parse(json, REFUND), "id", "c");
        assertThat(actions.runner().run(call, context -> "ok")).isInstanceOf(ActionOutcome.Succeeded.class);
        return journal.findByProviderCallId(ActionKey.of("c", "refund").scopeHash(), "id").orElseThrow().action()
                .materialArgsHash();
    }
}
