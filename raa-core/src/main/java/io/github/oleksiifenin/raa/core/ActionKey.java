package io.github.oleksiifenin.raa.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Business identity of one logical action (ADR-0001 §1), for example {@code ("conversation-123", "refund",
 * ["order-42"])}. Produced by the tool's {@link KeyStrategy}; never the provider tool-call id.
 *
 * <p><b>Canonical form (version 1).</b> {@link #hash()} is SHA-256 over a length-prefixed encoding, so no choice of
 * component values can make two different keys encode alike: the tag {@code raa-action-key/v1}, the scope, the name,
 * the number of business parts as a 4-byte big-endian integer, then every part. Each string is written as a 4-byte
 * big-endian length followed by its UTF-8 bytes; strings are not normalized, and strings that are not valid UTF-16
 * (unpaired surrogates) are rejected instead of being replaced. The hash is persisted: changing this encoding needs a
 * new version tag.
 *
 * @param scope         memory or conversation id; non-blank
 * @param name          action name, usually the tool name; non-blank
 * @param businessParts business identifiers of the action; may be empty
 */
public record ActionKey(String scope, String name, List<String> businessParts) {

    private static final String KEY_TAG = "raa-action-key/v1";
    private static final String SCOPE_TAG = "raa-key-scope/v1";
    private static final String IDEMPOTENCY_KEY_PREFIX = "raa-";
    private static final int SHA_256_LENGTH = 32;

    /** Validates the components and copies the part list. */
    public ActionKey {
        requireNonBlank(scope, "scope");
        requireNonBlank(name, "name");
        Objects.requireNonNull(businessParts, "businessParts");
        businessParts = List.copyOf(businessParts); // rejects null elements
        requireEncodable(scope);
        requireEncodable(name);
        businessParts.forEach(ActionKey::requireEncodable);
    }

    /**
     * Creates a key from business values. Accepted values and their canonical text: {@link CharSequence} as is;
     * {@link Integer}, {@link Long}, {@link Short}, {@link Byte} and {@link BigInteger} in decimal; {@link BigDecimal}
     * as {@code stripTrailingZeros().toPlainString()}; {@link UUID} as {@code toString()}; an enum constant as its
     * {@code name()}. Values are not type-tagged: {@code 42}, {@code 42L}, {@code "42"} and {@code 42.0} (as
     * {@code BigDecimal}) give the same key. Floating-point numbers, booleans and other types are rejected.
     *
     * @param scope         memory or conversation id; non-blank
     * @param name          action name; non-blank
     * @param businessParts business identifiers
     * @return the key
     * @throws IllegalArgumentException if a component is blank or a value has an unsupported type
     */
    public static ActionKey of(String scope, String name, Object... businessParts) {
        Objects.requireNonNull(businessParts, "businessParts");
        List<String> parts = new ArrayList<>(businessParts.length);
        for (Object part : businessParts) {
            parts.add(canonicalText(part));
        }
        return new ActionKey(scope, name, parts);
    }

    /**
     * Returns the SHA-256 hash of the canonical form; the journal's primary key.
     *
     * @return a new 32-byte array
     */
    public byte[] hash() {
        CanonicalEncoder encoded = new CanonicalEncoder()
                .writeString(KEY_TAG)
                .writeString(scope)
                .writeString(name)
                .writeInt(businessParts.size());
        businessParts.forEach(encoded::writeString);
        return encoded.sha256();
    }

    /**
     * Returns the SHA-256 hash of the scope alone (tag {@code raa-key-scope/v1}, then the scope, length-prefixed).
     *
     * @return a new 32-byte array
     */
    public byte[] scopeHash() {
        return new CanonicalEncoder().writeString(SCOPE_TAG).writeString(scope).sha256();
    }

    /**
     * Returns the idempotency key a downstream sees: {@code "raa-" + base64url(SHA-256(hash() ‖ materialArgsHash))}
     * without padding, 47 characters. Stable across retries with the same material arguments; it changes only when
     * the material arguments change, so a downstream never sees one key with two parameter sets.
     *
     * @param materialArgsHash hash over the material arguments of the attempt (32 bytes)
     * @return the idempotency key
     */
    public String idempotencyKey(byte[] materialArgsHash) {
        Objects.requireNonNull(materialArgsHash, "materialArgsHash");
        if (materialArgsHash.length != SHA_256_LENGTH) {
            throw new IllegalArgumentException("materialArgsHash must be 32 bytes, got " + materialArgsHash.length);
        }
        byte[] digest = new CanonicalEncoder().writeBytes(hash()).writeBytes(materialArgsHash).sha256();
        return IDEMPOTENCY_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String canonicalText(Object value) {
        Objects.requireNonNull(value, "business part");
        if (value instanceof CharSequence) {
            return value.toString();
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).stripTrailingZeros().toPlainString();
        }
        if (value instanceof UUID) {
            return value.toString();
        }
        if (value instanceof Enum<?>) {
            return ((Enum<?>) value).name();
        }
        throw new IllegalArgumentException("unsupported business part type " + value.getClass().getName()
                + "; use a string, an integral number, BigDecimal, UUID or an enum");
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireEncodable(String value) {
        CanonicalEncoder.utf8(value);
    }
}
