package io.github.oleksiifenin.raa.storage;

import java.time.Instant;
import java.util.Objects;

/**
 * One attempt of one action: the fencing token of every write after the claim, and the unit leases are renewed for.
 *
 * <p>The attempt number alone is not enough: once retention deleted a finished row, the same key starts again at
 * attempt 1, and a write of the old attempt 1 (from a process frozen for longer than the retention) must not land on
 * the new action. {@code createdAt} is the row's creation time, which differs for every row created for the key, so a
 * write whose {@code createdAt} does not match the row is treated as a write for a row that no longer exists.
 *
 * <p>The hash array is not copied; callers must not modify it. {@code equals} uses array identity.
 *
 * @param keyHash   SHA-256 of the canonical action key
 * @param createdAt creation time of the row the attempt belongs to ({@link ActionRecord#createdAt()})
 * @param attempt   attempt number
 */
public record KeyAttempt(byte[] keyHash, Instant createdAt, int attempt) {

    /** Validates the fields. */
    public KeyAttempt {
        Objects.requireNonNull(keyHash, "keyHash");
        Objects.requireNonNull(createdAt, "createdAt");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1: " + attempt);
        }
    }

    /**
     * The current attempt of a row.
     *
     * @param row the row, for example as a claim returned it
     * @return its key hash, creation time and attempt
     */
    public static KeyAttempt of(ActionRecord row) {
        return new KeyAttempt(row.keyHash(), row.createdAt(), row.attempt());
    }

    /**
     * Whether this is the current attempt of {@code row}: same row (creation time) and same attempt number.
     *
     * @param row the row as stored now
     * @return whether the attempt is the row's current one
     */
    public boolean isCurrentAttemptOf(ActionRecord row) {
        return isOf(row) && row.attempt() == attempt;
    }

    /**
     * Whether the attempt belongs to {@code row}: the same row, not one created again for the key after a deletion.
     *
     * @param row the row as stored now
     * @return whether the creation times are equal
     */
    public boolean isOf(ActionRecord row) {
        return row.createdAt().equals(createdAt);
    }
}
