package io.github.oleksiifenin.raa.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Hashes the argument tree of a {@link ToolCall} (ADR-0003). The encoding is persisted through the journal's
 * argument hashes, so changing it needs a new version tag.
 *
 * <p><b>Canonical form (version 1).</b> SHA-256 over the tag {@code raa-arguments/v1} followed by the arguments
 * object, every value written as a one-byte type tag and its content: null; false; true; a number as
 * {@code stripTrailingZeros()} unscaled value (decimal text) and scale, so {@code 10}, {@code 10.0} and {@code 1E+1}
 * are equal; a string; a list as its size and its elements in order; a map as its size and its entries sorted by the
 * UTF-8 bytes of the key. Strings and integers are length-prefixed as in {@link ActionKey}. Values are type-tagged:
 * the string {@code "10"} and the number {@code 10} differ, because the adapter has already coerced each value to the
 * tool schema's type.
 *
 * <p>The material hash leaves out the top-level arguments the policy declares as free text; the all-arguments hash
 * covers everything.
 */
final class ArgumentsHasher {

    private static final String TAG = "raa-arguments/v1";
    private static final int MAX_DEPTH = 64;

    private static final int NULL = 0;
    private static final int FALSE = 1;
    private static final int TRUE = 2;
    private static final int NUMBER = 3;
    private static final int STRING = 4;
    private static final int LIST = 5;
    private static final int MAP = 6;

    /**
     * The two hashes of one call's arguments.
     *
     * @param material hash without the free-text arguments
     * @param all      hash over all arguments
     */
    record ArgumentHashes(byte[] material, byte[] all) {}

    /**
     * Hashes an arguments object.
     *
     * @param arguments         the parsed arguments
     * @param freeTextArguments top-level argument names left out of the material hash
     * @return both hashes
     * @throws IllegalArgumentException if the tree holds an unsupported value, a non-string key, an unpaired
     *                                  surrogate or nests deeper than 64 levels
     */
    ArgumentHashes hash(Map<String, ?> arguments, Set<String> freeTextArguments) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(freeTextArguments, "freeTextArguments");
        byte[] all = hashOf(arguments, Set.of());
        byte[] material = freeTextArguments.isEmpty() ? all : hashOf(arguments, freeTextArguments);
        return new ArgumentHashes(material, all);
    }

    private static byte[] hashOf(Map<String, ?> arguments, Set<String> leftOut) {
        CanonicalEncoder encoder = new CanonicalEncoder().writeString(TAG);
        writeMap(encoder, arguments, leftOut, 1);
        return encoder.sha256();
    }

    private static void writeValue(CanonicalEncoder encoder, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("arguments nest deeper than " + MAX_DEPTH + " levels");
        }
        if (value == null) {
            encoder.writeByte(NULL);
        } else if (value instanceof Boolean) {
            encoder.writeByte((Boolean) value ? TRUE : FALSE);
        } else if (value instanceof String) {
            encoder.writeByte(STRING).writeString((String) value);
        } else if (value instanceof Map<?, ?>) {
            writeMap(encoder, (Map<?, ?>) value, Set.of(), depth);
        } else if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            encoder.writeByte(LIST).writeInt(list.size());
            for (Object element : list) {
                writeValue(encoder, element, depth + 1);
            }
        } else {
            BigDecimal number;
            try {
                number = toDecimal(value).stripTrailingZeros();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("number out of range: scale overflow", e);
            }
            encoder.writeByte(NUMBER).writeString(number.unscaledValue().toString()).writeInt(number.scale());
        }
    }

    private static void writeMap(CanonicalEncoder encoder, Map<?, ?> map, Set<String> leftOut, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("arguments nest deeper than " + MAX_DEPTH + " levels");
        }
        List<Entry> entries = new ArrayList<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("argument keys must be strings, got " + entry.getKey());
            }
            String key = (String) entry.getKey();
            if (!leftOut.contains(key)) {
                entries.add(new Entry(CanonicalEncoder.utf8(key), entry.getValue()));
            }
        }
        entries.sort((a, b) -> Arrays.compareUnsigned(a.keyBytes, b.keyBytes));
        encoder.writeByte(MAP).writeInt(entries.size());
        for (Entry entry : entries) {
            encoder.writeInt(entry.keyBytes.length).writeBytes(entry.keyBytes);
            writeValue(encoder, entry.value, depth + 1);
        }
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        throw new IllegalArgumentException("unsupported argument value type " + value.getClass().getName()
                + "; the adapter must supply null, Boolean, String, an integral number, BigDecimal, List or Map");
    }

    private static final class Entry {
        final byte[] keyBytes;
        final Object value;

        Entry(byte[] keyBytes, Object value) {
            this.keyBytes = keyBytes;
            this.value = value;
        }
    }
}
