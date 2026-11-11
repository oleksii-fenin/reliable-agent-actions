package io.github.oleksiifenin.raa.core;

/** Asks the downstream whether an action with an unknown outcome was applied. */
@FunctionalInterface
public interface OutcomeLookup {

    /**
     * Looks the action up.
     *
     * @param key            the action key
     * @param idempotencyKey the idempotency key the attempt sent downstream
     * @return what the downstream knows
     */
    LookupResult lookup(ActionKey key, String idempotencyKey);
}
