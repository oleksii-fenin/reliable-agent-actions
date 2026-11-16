package io.github.oleksiifenin.raa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oleksiifenin.raa.core.ArgumentsHasher.ArgumentHashes;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArgumentsHasherTest {

    private final ArgumentsHasher hasher = new ArgumentsHasher();

    @Test
    void hash_isSha256OverTheDocumentedVersionOneEncoding() throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("orderId", "order-42");
        arguments.put("amount", new BigDecimal("10.50"));

        ByteBuffer expected = ByteBuffer.allocate(256);
        putString(expected, "raa-arguments/v1");
        expected.put((byte) 6).putInt(2);
        putString(expected, "amount"); // keys sorted by UTF-8 bytes
        expected.put((byte) 3);
        putString(expected, "105");
        expected.putInt(1);
        putString(expected, "orderId");
        expected.put((byte) 4);
        putString(expected, "order-42");
        byte[] encoded = Arrays.copyOf(expected.array(), expected.position());

        assertThat(hasher.hash(arguments, Set.of()).all())
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(encoded));
    }

    @Test
    void hash_ignoresKeyOrderAtEveryLevel() {
        Map<String, Object> innerA = new LinkedHashMap<>();
        innerA.put("x", 1);
        innerA.put("y", 2);
        Map<String, Object> innerB = new LinkedHashMap<>();
        innerB.put("y", 2);
        innerB.put("x", 1);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("first", "1");
        a.put("nested", innerA);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("nested", innerB);
        b.put("first", "1");

        assertThat(all(a)).isEqualTo(all(b));
    }

    @Test
    void hash_treatsNumbersByValue_notByScaleOrJavaType() {
        assertThat(all(Map.of("n", new BigDecimal("10.0"))))
                .isEqualTo(all(Map.of("n", 10)))
                .isEqualTo(all(Map.of("n", 10L)))
                .isEqualTo(all(Map.of("n", BigInteger.TEN)))
                .isEqualTo(all(Map.of("n", new BigDecimal("1E+1"))));
        assertThat(all(Map.of("n", new BigDecimal("0.00")))).isEqualTo(all(Map.of("n", 0)));
        assertThat(all(Map.of("n", new BigDecimal("10.01")))).isNotEqualTo(all(Map.of("n", 10)));
    }

    @Test
    void hash_distinguishesTypesThatTheAdapterAlreadyCoerced() {
        assertThat(all(Map.of("v", "10"))).isNotEqualTo(all(Map.of("v", 10)));
        assertThat(all(Map.of("v", "true"))).isNotEqualTo(all(Map.of("v", true)));
        assertThat(all(Map.of("v", true))).isNotEqualTo(all(Map.of("v", false)));
        assertThat(all(mapOfNull("v"))).isNotEqualTo(all(Map.of("v", "")));
        assertThat(all(mapOfNull("v"))).isNotEqualTo(all(Map.of()));
        assertThat(all(Map.of("v", List.of()))).isNotEqualTo(all(Map.of("v", Map.of())));
    }

    @Test
    void hash_keepsListOrder_andHasNoDelimiterAmbiguity() {
        assertThat(all(Map.of("l", List.of("a", "b")))).isNotEqualTo(all(Map.of("l", List.of("b", "a"))));
        assertThat(all(Map.of("l", List.of("ab", "c")))).isNotEqualTo(all(Map.of("l", List.of("a", "bc"))));
        assertThat(all(Map.of("ab", "c"))).isNotEqualTo(all(Map.of("a", "bc")));
    }

    @Test
    void materialHash_leavesOutFreeTextArguments_allArgumentsHashKeepsThem() {
        Map<String, Object> first = Map.of("to", "a@example.org", "body", "Hello");
        Map<String, Object> edited = Map.of("to", "a@example.org", "body", "Hello again");

        ArgumentHashes firstHashes = hasher.hash(first, Set.of("body"));
        ArgumentHashes editedHashes = hasher.hash(edited, Set.of("body"));

        assertThat(firstHashes.material()).isEqualTo(editedHashes.material());
        assertThat(firstHashes.all()).isNotEqualTo(editedHashes.all());
        assertThat(firstHashes.material()).isEqualTo(all(Map.of("to", "a@example.org")));
    }

    @Test
    void materialHash_equalsAllArgumentsHash_withoutFreeText() {
        ArgumentHashes hashes = hasher.hash(Map.of("a", 1), Set.of());

        assertThat(hashes.material()).isEqualTo(hashes.all());
    }

    @Test
    void hash_rejectsFloatingPointAndOtherTypes() {
        assertThatThrownBy(() -> all(Map.of("v", 1.5d))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.lang.Double");
        assertThatThrownBy(() -> all(Map.of("v", 1.5f))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> all(Map.of("v", new Object()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hash_rejectsNonStringKeysInNestedMaps_andUnpairedSurrogates() {
        Map<Object, Object> nested = new HashMap<>();
        nested.put(1, "x");

        assertThatThrownBy(() -> all(Map.of("n", nested))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> all(Map.of("v", "\uD800"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> all(Map.of("\uDC00", "v"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hash_rejectsCyclesAndDeepNesting_insteadOfOverflowingTheStack() {
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);

        assertThatThrownBy(() -> all(Map.of("c", cycle))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deeper than 64");
    }

    private byte[] all(Map<String, ?> arguments) {
        return hasher.hash(arguments, Set.of()).all();
    }

    private static Map<String, Object> mapOfNull(String key) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, null);
        return map;
    }

    private static void putString(ByteBuffer buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(bytes.length).put(bytes);
    }
}
