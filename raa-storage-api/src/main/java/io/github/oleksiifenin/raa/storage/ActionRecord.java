package io.github.oleksiifenin.raa.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One journal row: the current state of the action identified by {@code keyHash} (docs/DESIGN.md §7).
 *
 * <p>Hash arrays are not copied; callers must not modify them. Like every record with array components,
 * {@code equals}, {@code hashCode} and {@code toString} use array identity: compare hashes with
 * {@link java.util.Arrays#equals(byte[], byte[])}.
 *
 * @param keyHash           SHA-256 of the canonical action key
 * @param keyText           readable key for operators; {@code null} unless enabled (may contain personal data)
 * @param toolName          name of the tool
 * @param policy            name of the policy the row was created with
 * @param state             current state
 * @param attempt           number of the current attempt; the fencing token
 * @param leaseOwner        owner of the current lease; {@code null} when no attempt holds one
 * @param leaseUntil        end of the current lease (database time); {@code null} when no attempt holds one
 * @param maxEffectDuration maximum duration of one attempt's effect
 * @param createdAt         creation time of the row (database time)
 * @param deadlineAt        no new attempt starts at or after this time (database time)
 * @param startedAt         start of the current attempt (database time)
 * @param updatedAt         time of the last change (database time)
 * @param resolveAfter      earliest time the reconciler looks at the row again; {@code null} means now
 * @param lookupFailures    number of consecutive unreachable outcome lookups
 * @param failureBasis      set when {@code state} is {@link ActionState#FAILED}; otherwise may be {@code null}
 * @param allArgsHash       hash over all arguments of the current attempt, including free text
 * @param scopeHash         SHA-256 of the key scope
 * @param materialArgsHash  hash over the material arguments of the current attempt
 * @param argumentsJson     raw arguments; {@code null} unless enabled (may contain personal data)
 * @param resultText        recorded result text; {@code null} unless an effect was recorded
 * @param lastError         last recorded error; may be {@code null}
 */
public record ActionRecord(
        byte[] keyHash,
        String keyText,
        String toolName,
        String policy,
        ActionState state,
        int attempt,
        String leaseOwner,
        Instant leaseUntil,
        Duration maxEffectDuration,
        Instant createdAt,
        Instant deadlineAt,
        Instant startedAt,
        Instant updatedAt,
        Instant resolveAfter,
        int lookupFailures,
        FailureBasis failureBasis,
        byte[] allArgsHash,
        byte[] scopeHash,
        byte[] materialArgsHash,
        String argumentsJson,
        String resultText,
        String lastError) {

    /** Validates the mandatory columns and the state invariants the decision table relies on. */
    public ActionRecord {
        Objects.requireNonNull(keyHash, "keyHash");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(maxEffectDuration, "maxEffectDuration");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(allArgsHash, "allArgsHash");
        Objects.requireNonNull(scopeHash, "scopeHash");
        Objects.requireNonNull(materialArgsHash, "materialArgsHash");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1: " + attempt);
        }
        if (lookupFailures < 0) {
            throw new IllegalArgumentException("lookupFailures must not be negative: " + lookupFailures);
        }
        if (state == ActionState.FAILED && failureBasis == null) {
            throw new IllegalArgumentException("a FAILED row needs a failureBasis");
        }
    }
}
