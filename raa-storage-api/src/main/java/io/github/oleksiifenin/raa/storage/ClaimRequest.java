package io.github.oleksiifenin.raa.storage;

import java.time.Duration;
import java.util.Objects;

/**
 * A call that wants to run the effect of one action. Carries what the journal writes when the decision starts an
 * attempt; the decision itself is made by a {@link ClaimDecider}.
 *
 * <p>Hash arrays are not copied; callers must not modify them. Like every record with array components,
 * {@code equals}, {@code hashCode} and {@code toString} use array identity: compare hashes with
 * {@link java.util.Arrays#equals(byte[], byte[])}.
 *
 * @param keyHash           SHA-256 of the canonical action key (32 bytes)
 * @param scopeHash         SHA-256 of the key scope (32 bytes)
 * @param keyText           readable key for operators, or {@code null} (opt-in; may contain personal data)
 * @param toolName          name of the tool
 * @param policy            name of the tool's policy
 * @param materialArgsHash  hash over the material arguments of this call (32 bytes)
 * @param allArgsHash       hash over all arguments of this call, including free text (32 bytes)
 * @param argumentsJson     raw arguments, or {@code null} (opt-in; may contain personal data)
 * @param providerCallId    tool-call id given by the model provider, or {@code null} if the provider sent none
 * @param leaseOwner        owner id of the calling process
 * @param lease             lease length for a started attempt
 * @param maxEffectDuration maximum duration of one attempt's effect
 * @param deadline          time from the creation of the row after which no new attempt starts
 */
public record ClaimRequest(
        byte[] keyHash,
        byte[] scopeHash,
        String keyText,
        String toolName,
        String policy,
        byte[] materialArgsHash,
        byte[] allArgsHash,
        String argumentsJson,
        String providerCallId,
        String leaseOwner,
        Duration lease,
        Duration maxEffectDuration,
        Duration deadline) {

    private static final int SHA_256_LENGTH = 32;

    /** Validates mandatory fields, hash lengths and durations. */
    public ClaimRequest {
        requireSha256(keyHash, "keyHash");
        requireSha256(scopeHash, "scopeHash");
        requireSha256(materialArgsHash, "materialArgsHash");
        requireSha256(allArgsHash, "allArgsHash");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        requirePositive(lease, "lease");
        requirePositive(maxEffectDuration, "maxEffectDuration");
        requirePositive(deadline, "deadline");
    }

    private static void requireSha256(byte[] hash, String name) {
        Objects.requireNonNull(hash, name);
        if (hash.length != SHA_256_LENGTH) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hash (32 bytes), got " + hash.length);
        }
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + duration);
        }
    }
}
