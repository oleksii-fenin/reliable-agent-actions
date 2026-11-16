package io.github.oleksiifenin.raa.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds the length-prefixed byte encodings that action keys and argument hashes are computed from. Integers are
 * 4-byte big-endian; a string is its UTF-8 length as such an integer followed by the bytes. Strings that are not valid
 * UTF-16 (unpaired surrogates) are rejected instead of being replaced, so two different strings never encode alike.
 */
final class CanonicalEncoder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    CanonicalEncoder writeString(String value) {
        byte[] bytes = utf8(value);
        writeInt(bytes.length);
        out.writeBytes(bytes);
        return this;
    }

    CanonicalEncoder writeInt(int value) {
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
        return this;
    }

    CanonicalEncoder writeByte(int value) {
        out.write(value);
        return this;
    }

    CanonicalEncoder writeBytes(byte[] value) {
        out.writeBytes(value);
        return this;
    }

    /** Returns SHA-256 of everything written so far. */
    byte[] sha256() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    /**
     * Returns the UTF-8 bytes of a string.
     *
     * @throws IllegalArgumentException if the string contains an unpaired surrogate
     */
    static byte[] utf8(String value) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("text is not valid UTF-16 (unpaired surrogate)", e);
        }
    }
}
