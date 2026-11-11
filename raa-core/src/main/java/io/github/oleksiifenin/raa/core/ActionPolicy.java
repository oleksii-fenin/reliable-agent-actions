package io.github.oleksiifenin.raa.core;

import java.time.Duration;

/**
 * The guarantee a tool declares (ADR-0001 §2). Every side-effecting tool needs one of the four journaled policies;
 * a read-only tool is declared {@link #none()}. Policies are immutable: every setter returns a new instance.
 */
public sealed interface ActionPolicy permits JournaledPolicy, NoJournal {

    /**
     * The downstream deduplicates by the idempotency key within {@code downstreamDedupWindow}; unknown outcomes are
     * retried with the same key.
     *
     * @param downstreamDedupWindow how long the downstream remembers an idempotency key; the deadline must not
     *                              exceed it
     * @return the policy
     */
    static IdempotentDownstream idempotentDownstream(Duration downstreamDedupWindow) {
        return new IdempotentDownstream(downstreamDedupWindow);
    }

    /**
     * The downstream can tell whether an action was applied; unknown outcomes are resolved by {@code lookup}.
     *
     * @param lookup how to ask the downstream
     * @return the policy
     */
    static Reconcilable reconcilable(OutcomeLookup lookup) {
        return new Reconcilable(lookup);
    }

    /**
     * No downstream cooperation: an unknown outcome is escalated, never retried.
     *
     * @return the policy
     */
    static AtMostOnce atMostOnce() {
        return new AtMostOnce();
    }

    /**
     * No downstream cooperation, duplicates acceptable: an unknown outcome is retried.
     *
     * @return the policy
     */
    static AtLeastOnce atLeastOnce() {
        return new AtLeastOnce();
    }

    /**
     * Not journaled; for read-only tools only.
     *
     * @return the policy
     */
    static NoJournal none() {
        return NoJournal.INSTANCE;
    }
}
