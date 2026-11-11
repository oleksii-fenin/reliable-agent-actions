package io.github.oleksiifenin.raa.storage;

import java.time.Instant;
import java.util.Objects;

/**
 * A provider tool-call id mapped to the action it asked for (call history, docs/DESIGN.md §7).
 *
 * @param providerCallId the provider's tool-call id
 * @param attempt        latest attempt started by this call, or the attempt current when the call was first seen
 * @param seenAt         when the mapping was last written (database time)
 * @param action         the current row of the action
 */
public record CallRecord(String providerCallId, int attempt, Instant seenAt, ActionRecord action) {

    /** Validates the fields. */
    public CallRecord {
        Objects.requireNonNull(providerCallId, "providerCallId");
        Objects.requireNonNull(seenAt, "seenAt");
        Objects.requireNonNull(action, "action");
    }
}
