package io.github.oleksiifenin.raa.storage;

import java.util.Objects;

/**
 * One attempt of one action: the unit leases are renewed for.
 *
 * <p>The hash array is not copied; callers must not modify it. {@code equals} uses array identity.
 *
 * @param keyHash SHA-256 of the canonical action key
 * @param attempt attempt number
 */
public record KeyAttempt(byte[] keyHash, int attempt) {

    /** Validates the fields. */
    public KeyAttempt {
        Objects.requireNonNull(keyHash, "keyHash");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1: " + attempt);
        }
    }
}
