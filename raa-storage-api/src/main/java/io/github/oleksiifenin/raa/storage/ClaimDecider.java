package io.github.oleksiifenin.raa.storage;

import java.time.Instant;
import java.util.Optional;

/**
 * The decision table of docs/DESIGN.md §6, supplied by the core to {@link ActionJournal#claim}. Storage owns
 * atomicity, the decider owns the policy: a storage implementation never decides anything itself.
 *
 * <p>Implementations are pure functions: no I/O, no clock, no side effects. They may be called more than once per
 * claim (for example when the row disappeared between the insert and the lock and the journal retries).
 */
@FunctionalInterface
public interface ClaimDecider {

    /**
     * Decides what a call means for the action.
     *
     * @param request      the call
     * @param current      the row for the key, locked by the claim transaction, or empty if there is none
     * @param databaseNow  {@code clock_timestamp()} of the journal database, read inside the claim transaction after
     *                     the row was locked; the only clock a decision may use
     * @return the verdict and the write to apply in the same transaction
     */
    ClaimDecision decide(ClaimRequest request, Optional<ActionRecord> current, Instant databaseNow);
}
