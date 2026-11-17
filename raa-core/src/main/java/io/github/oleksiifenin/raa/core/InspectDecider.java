package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.ClaimDecider;
import io.github.oleksiifenin.raa.storage.ClaimDecision;
import io.github.oleksiifenin.raa.storage.ClaimRequest;
import io.github.oleksiifenin.raa.storage.ClaimVerdict;
import io.github.oleksiifenin.raa.storage.JournalWrite;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The decision table with effects disabled (docs/DESIGN.md §6, "Inspect mode"): every decision is the table's own,
 * except that a decision to start an attempt starts none ({@link ClaimVerdict#WOULD_START}); an expired attempt is still
 * moved to {@code UNKNOWN}, as row 6 prescribes. Repair and a real call therefore never disagree.
 */
final class InspectDecider implements ClaimDecider {

    private final ClaimDecider table;

    InspectDecider(ClaimDecider table) {
        this.table = Objects.requireNonNull(table, "table");
    }

    @Override
    public ClaimDecision decide(ClaimRequest request, Optional<ActionRecord> current, Instant databaseNow) {
        if (current.isEmpty()) {
            // Inspect mode looks only at actions it found through the call history; never insert one.
            throw new NoActionRowException();
        }
        ClaimDecision decision = table.decide(request, current, databaseNow);
        if (!(decision.write() instanceof JournalWrite.StartAttempt)) {
            return decision;
        }
        JournalWrite write = current.get().state() == ActionState.EXECUTING
                ? new JournalWrite.ChangeState(ActionState.UNKNOWN)
                : new JournalWrite.None();
        return new ClaimDecision(ClaimVerdict.WOULD_START, write);
    }

    /** The action row disappeared between the call-history lookup and the claim (for example by retention). */
    static final class NoActionRowException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NoActionRowException() {
            super("inspect mode found no action row");
        }
    }
}
