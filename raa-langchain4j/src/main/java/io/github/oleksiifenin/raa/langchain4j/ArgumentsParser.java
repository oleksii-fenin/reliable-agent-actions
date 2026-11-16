package io.github.oleksiifenin.raa.langchain4j;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the JSON arguments of a tool call into the value tree the core hashes (ADR-0003): objects as maps, arrays as
 * lists, numbers exactly ({@link Long}, {@link BigInteger} or {@link BigDecimal}, never floating point), and values
 * coerced to the type the tool's schema declares, so that {@code "10"} and {@code 10} for an integer parameter are the
 * same argument. Strict: the top level must be an object, duplicate keys and trailing content are rejected.
 */
final class ArgumentsParser {

    /**
     * Largest number of integer digits a string may expand to when coerced to an integer. A model-controlled string such
     * as {@code "1e10000000"} would otherwise become a ten-million-digit integer and pin the thread for minutes.
     */
    static final int MAX_INTEGER_DIGITS = 100;

    private final JsonFactory factory = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /**
     * Parses the arguments.
     *
     * @param json   the arguments as the model sent them; blank means no arguments
     * @param schema the tool's parameters, or {@code null} if it declares none
     * @return the value tree
     * @throws IllegalArgumentException if the text is not a JSON object or a value cannot have the declared type
     */
    Map<String, Object> parse(String json, JsonObjectSchema schema) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try (JsonParser parser = factory.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("the arguments are not a JSON object");
            }
            Map<String, Object> arguments = readObject(parser, schema);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("unexpected content after the arguments object");
            }
            return arguments;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("the arguments are not valid JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("the arguments could not be read: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> readObject(JsonParser parser, JsonObjectSchema schema) throws IOException {
        Map<String, Object> object = new LinkedHashMap<>();
        Map<String, JsonSchemaElement> properties = schema == null || schema.properties() == null
                ? Collections.emptyMap()
                : schema.properties();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            parser.nextToken();
            object.put(name, readValue(parser, properties.get(name)));
        }
        return object;
    }

    private Object readValue(JsonParser parser, JsonSchemaElement schema) throws IOException {
        JsonToken token = parser.currentToken();
        switch (token) {
            case START_OBJECT:
                requireNot(isScalar(schema), "an object", "a single value");
                return readObject(parser, schema instanceof JsonObjectSchema ? (JsonObjectSchema) schema : null);
            case START_ARRAY:
                requireNot(isScalar(schema), "an array", "a single value");
                List<Object> array = new ArrayList<>();
                JsonSchemaElement items = schema instanceof JsonArraySchema ? ((JsonArraySchema) schema).items() : null;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    array.add(readValue(parser, items));
                }
                return array;
            case VALUE_STRING:
                return coerceString(parser.getText(), schema);
            case VALUE_NUMBER_INT:
                if (schema instanceof JsonStringSchema) {
                    return parser.getText();
                }
                requireNot(schema instanceof JsonBooleanSchema, parser.getText(), "a boolean");
                return integral(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT:
                if (schema instanceof JsonStringSchema) {
                    return parser.getText();
                }
                requireNot(schema instanceof JsonBooleanSchema, parser.getText(), "a boolean");
                return schema instanceof JsonIntegerSchema
                        ? integer(parser.getDecimalValue(), parser.getText())
                        : parser.getDecimalValue();
            case VALUE_TRUE:
            case VALUE_FALSE:
                if (schema instanceof JsonStringSchema) {
                    return parser.getText();
                }
                requireNot(schema instanceof JsonIntegerSchema || schema instanceof JsonNumberSchema, parser.getText(),
                        "a number");
                return parser.getBooleanValue();
            case VALUE_NULL:
                return null;
            default:
                throw new IllegalArgumentException("unexpected JSON token " + token);
        }
    }

    /** A string where the schema wants a number or a boolean is converted; otherwise it stays a string. */
    private static Object coerceString(String text, JsonSchemaElement schema) {
        if (schema instanceof JsonIntegerSchema || schema instanceof JsonNumberSchema) {
            try {
                BigDecimal number = new BigDecimal(text.trim());
                return schema instanceof JsonIntegerSchema ? integer(number, "'" + text + "'") : number;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + text + "' is not a number");
            }
        }
        if (schema instanceof JsonBooleanSchema) {
            if ("true".equals(text) || "false".equals(text)) {
                return Boolean.valueOf(text);
            }
            throw new IllegalArgumentException("'" + text + "' is not a boolean");
        }
        return text;
    }

    /** An integer parameter: {@code 2}, {@code 2.0} and {@code "2"} are the same; {@code 2.5} is rejected. */
    private static Object integer(BigDecimal number, String shown) {
        // Checked before any expansion, zero included: 0e1000000 would expand to a million-digit power of ten too.
        if (number.precision() - number.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException(shown + " is not an integer in range");
        }
        try {
            return integral(number.toBigIntegerExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(shown + " is not an integer");
        }
    }

    private static boolean isScalar(JsonSchemaElement schema) {
        return schema instanceof JsonStringSchema || schema instanceof JsonIntegerSchema
                || schema instanceof JsonNumberSchema || schema instanceof JsonBooleanSchema;
    }

    /** The value would be rejected by LangChain4j after the claim; rejecting it here claims nothing. */
    private static void requireNot(boolean mismatch, String value, String expected) {
        if (mismatch) {
            throw new IllegalArgumentException(value + " is not " + expected);
        }
    }

    private static Object integral(BigInteger value) {
        return value.bitLength() < Long.SIZE ? (Object) value.longValueExact() : value;
    }
}
