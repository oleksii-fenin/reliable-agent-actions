package io.github.oleksiifenin.raa.storage;

/**
 * The durable action journal (docs/DESIGN.md §5, §7). The remaining operations of the contract (record, renew,
 * reconcile, resolve, retention) are added together with the conformance suite in roadmap week 2.
 */
public interface ActionJournal {

    /**
     * Claims the action of {@code request.keyHash()} in one short READ COMMITTED transaction: creates the row or
     * locks the existing one ({@code SELECT … FOR UPDATE}), reads {@code clock_timestamp()}, calls {@code decider}
     * and applies the returned {@link JournalWrite} before committing. No other decision logic exists in storage.
     *
     * @param request the call
     * @param decider the decision table
     * @return the applied decision and the committed row
     */
    ClaimResult claim(ClaimRequest request, ClaimDecider decider);
}
