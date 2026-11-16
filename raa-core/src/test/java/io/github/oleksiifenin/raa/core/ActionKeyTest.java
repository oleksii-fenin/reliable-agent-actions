package io.github.oleksiifenin.raa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionKeyTest {

    private enum Region { EU }

    // ---- key text (ADR-0004)

    @Test
    void toText_isTheDocumentedLengthPrefixedForm() {
        assertThat(ActionKey.of("conversation-1", "refund", "order-42").toText())
                .isEqualTo("raa-key/v1;14:conversation-1;6:refund;8:order-42");
        assertThat(ActionKey.of("c", "send").toText()).isEqualTo("raa-key/v1;1:c;4:send");
    }

    @Test
    void parse_readsBackEveryKey_whateverItsComponentsContain() {
        List<ActionKey> keys = List.of(
                ActionKey.of("c", "send"),
                ActionKey.of("c;1:x", "a:b;c", ";", ":", "", "12:", "raa-key/v1;"),
                ActionKey.of("Zürich", "refund", "😀 emoji", "line\nbreak", "tab\t"),
                ActionKey.of("conversation-1", "refund", 42, new BigDecimal("10.50"), Region.EU));

        for (ActionKey key : keys) {
            ActionKey parsed = ActionKey.parse(key.toText());
            assertThat(parsed).isEqualTo(key);
            assertThat(parsed.hash()).isEqualTo(key.hash());
        }
    }

    @Test
    void parse_rejectsTextThatIsNotAKeyTextOfVersion1() {
        List<String> malformed = List.of(
                "", "raa-key/v2;1:c;4:send", "raa-key/v1", "raa-key/v1;1:c", "raa-key/v1;1:c;4:sen",
                "raa-key/v1;1:c;4:sendX", "raa-key/v1;1:c;04:send", "raa-key/v1;1:c;-4:send", "raa-key/v1;1:c;x:send",
                "raa-key/v1;1:c;4send", "raa-key/v1;1: ;4:send", "raa-key/v1;1:c;9999999999:send",
                "raa-key/v1;1:c;4:send;1:\uD800");

        for (String text : malformed) {
            assertThatThrownBy(() -> ActionKey.parse(text)).as(text).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void hash_isSha256OverTheDocumentedLengthPrefixedEncoding() throws Exception {
        ActionKey key = ActionKey.of("conversation-1", "refund", "order-42", "Zürich");

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (String field : List.of("raa-action-key/v1", "conversation-1", "refund")) {
            writeString(expected, field);
        }
        expected.writeBytes(ByteBuffer.allocate(4).putInt(2).array());
        writeString(expected, "order-42");
        writeString(expected, "Zürich");

        assertThat(key.hash()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()));
    }

    @Test
    void hash_hasNoDelimiterCollisions() {
        List<ActionKey> keys = List.of(
                ActionKey.of("a", "b", "c|d"),
                ActionKey.of("a", "b", "c", "d"),
                ActionKey.of("a", "b", "cd"),
                ActionKey.of("a", "b", "c", ""),
                ActionKey.of("a", "b", "", "c"),
                ActionKey.of("a", "b"),
                ActionKey.of("a", "b", ""),
                ActionKey.of("a", "bc"),
                ActionKey.of("ab", "c"),
                ActionKey.of("a", "b\u0000c"),
                ActionKey.of("a\u0000b", "c"));

        List<String> hashes = new ArrayList<>();
        keys.forEach(key -> hashes.add(Arrays.toString(key.hash())));

        assertThat(hashes).doesNotHaveDuplicates();
    }

    @Test
    void hash_isStableAndEqualForEqualKeys() {
        assertThat(ActionKey.of("s", "n", "x").hash()).isEqualTo(ActionKey.of("s", "n", "x").hash());
        assertThat(ActionKey.of("s", "n", "x").hash()).isEqualTo(new ActionKey("s", "n", List.of("x")).hash());
    }

    @Test
    void of_canonicalizesNumbersAndIdentifiers() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        ActionKey key = ActionKey.of("s", "n", 42, 42L, (short) 7, (byte) 1, new BigInteger("12345678901234567890"),
                new BigDecimal("10.500"), new BigDecimal("1E+3"), new BigDecimal("0.00"), id, Region.EU,
                new StringBuilder("text"));

        assertThat(key.businessParts()).containsExactly("42", "42", "7", "1", "12345678901234567890", "10.5", "1000",
                "0", "123e4567-e89b-12d3-a456-426614174000", "EU", "text");
    }

    @Test
    void of_givesTheSameKeyForEqualValuesOfDifferentTypes() {
        assertThat(ActionKey.of("s", "n", 42).hash())
                .isEqualTo(ActionKey.of("s", "n", "42").hash())
                .isEqualTo(ActionKey.of("s", "n", new BigDecimal("42.0")).hash());
    }

    @Test
    void of_rejectsUnsupportedTypesAndNull() {
        assertThatThrownBy(() -> ActionKey.of("s", "n", 1.5d)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.lang.Double");
        assertThatThrownBy(() -> ActionKey.of("s", "n", 1.5f)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionKey.of("s", "n", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionKey.of("s", "n", new Object())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionKey.of("s", "n", (Object) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ActionKey.of("s", "n", (Object[]) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsBlankScopeOrName_andNullParts() {
        assertThatThrownBy(() -> ActionKey.of(" ", "n")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(() -> ActionKey.of("s", "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> ActionKey.of(null, "n")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ActionKey("s", "n", Arrays.asList("a", null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_rejectsTextThatIsNotValidUtf16_insteadOfReplacingIt() {
        assertThatThrownBy(() -> ActionKey.of("s", "n", "a\uD800")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surrogate");
        assertThatThrownBy(() -> ActionKey.of("s\uDC00", "n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void businessParts_areImmutableCopies() {
        List<String> parts = new ArrayList<>(List.of("a"));
        ActionKey key = new ActionKey("s", "n", parts);
        parts.add("b");

        assertThat(key.businessParts()).containsExactly("a");
        assertThatThrownBy(() -> key.businessParts().add("c")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scopeHash_dependsOnlyOnTheScope_andDiffersFromTheKeyHash() {
        ActionKey key = ActionKey.of("conversation-1", "refund", "order-42");

        assertThat(key.scopeHash()).hasSize(32)
                .isEqualTo(ActionKey.of("conversation-1", "other", "x").scopeHash())
                .isNotEqualTo(ActionKey.of("conversation-2", "refund", "order-42").scopeHash())
                .isNotEqualTo(key.hash());
    }

    @Test
    void idempotencyKey_isRaaPrefixedBase64UrlOfSha256OverKeyHashAndMaterialHash() throws Exception {
        ActionKey key = ActionKey.of("conversation-1", "refund", "order-42");
        byte[] material = DecisionTableTest.filled(1);

        String idempotencyKey = key.idempotencyKey(material);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(key.hash());
        digest.update(material);
        String expected = "raa-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        assertThat(idempotencyKey).isEqualTo(expected).hasSize(47).matches("raa-[A-Za-z0-9_-]{43}");
    }

    @Test
    void idempotencyKey_isStableForSameArgs_andChangesWithMaterialArgsOrKey() {
        ActionKey key = ActionKey.of("s", "n", "x");
        byte[] material = DecisionTableTest.filled(1);

        assertThat(key.idempotencyKey(material))
                .isEqualTo(ActionKey.of("s", "n", "x").idempotencyKey(DecisionTableTest.filled(1)))
                .isNotEqualTo(key.idempotencyKey(DecisionTableTest.filled(2)))
                .isNotEqualTo(ActionKey.of("s", "n", "y").idempotencyKey(material));
    }

    @Test
    void idempotencyKey_rejectsAHashOfTheWrongLength() {
        ActionKey key = ActionKey.of("s", "n");

        assertThatThrownBy(() -> key.idempotencyKey(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> key.idempotencyKey(null)).isInstanceOf(NullPointerException.class);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }
}
