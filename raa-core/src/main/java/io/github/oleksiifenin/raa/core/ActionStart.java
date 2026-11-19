package io.github.oleksiifenin.raa.core;

import java.util.Objects;

/**
 * What is known when an action call starts.
 *
 * @param toolName       the tool name
 * @param policy         the policy's journal name, for example {@code IDEMPOTENT_DOWNSTREAM}
 * @param providerCallId the provider's tool-call id, {@code null} if none
 * @param keyHash        the action key's hash in lower-case hex, {@code null} if the arguments gave no key
 * @param inspect        whether it is an {@link ActionRunner#inspect} call (no effect can start)
 */
public record ActionStart(String toolName, String policy, String providerCallId, String keyHash, boolean inspect) {

    /** Validates the fields. */
    public ActionStart {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(policy, "policy");
    }
}
