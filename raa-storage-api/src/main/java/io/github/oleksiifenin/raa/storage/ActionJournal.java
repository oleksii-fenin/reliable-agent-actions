package io.github.oleksiifenin.raa.storage;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The durable action journal (docs/DESIGN.md §5–§7). Storage owns atomicity; every decision about a call is made by
 * the {@link ClaimDecider} the core passes to {@link #claim}.
 *
 * <p>Rules every implementation must follow (checked by {@code raa-journal-tck}):
 * <ul>
 *   <li>Every write after the claim names its attempt by a {@link KeyAttempt}: key hash, the row's creation time and
 *       the attempt number. A write lands only on that row and that attempt (and the source states the method
 *       names); a write for an older attempt, or for a row that retention deleted and the key created again, changes
 *       nothing.</li>
 *   <li>Every time is the database's {@code clock_timestamp()} (or the storage's equivalent single clock), read when
 *       the statement runs; never a client clock.</li>
 *   <li>Every transition into {@link ActionState#UNKNOWN} or {@link ActionState#FAILED} sets
 *       {@code resolve_after = now}.</li>
 *   <li>Each method is one short transaction; no transaction stays open while the effect or a lookup runs.</li>
 *   <li>Storage failures are reported as {@link JournalException}.</li>
 * </ul>
 * The operator operations of {@link ActionAdmin} follow the same rules.
 */
public interface ActionJournal extends ActionAdmin {

    /**
     * Claims the action of {@code request.keyHash()} in one short transaction: locks the row
     * ({@code SELECT … FOR UPDATE}), reads the database time, calls {@code decider} and applies the returned
     * {@link JournalWrite}. Without a row, the decider is called with an empty row and its
     * {@link JournalWrite.StartAttempt StartAttempt(1)} is inserted; if a concurrent claim inserted the row first, the
     * claim locks that row and asks the decider again (at most 3 rounds, also when the row disappears in between).
     * If the request has a provider call id, the call history row is written in the same transaction: inserted if
     * new, and its attempt updated when the decision starts an attempt.
     *
     * @param request the call
     * @param decider the decision table
     * @return the applied decision and the committed row
     * @throws JournalException if storage fails; the claim may or may not be committed
     */
    ClaimResult claim(ClaimRequest request, ClaimDecider decider);

    /**
     * Records the success of {@code attempt}:
     * {@code UPDATE … WHERE key_hash = ? AND created_at = ? AND attempt = ? AND state <> 'SUCCEEDED'}. A late success
     * of the same attempt therefore lands in any other state; the lease is cleared and {@code resolve_after} too.
     *
     * @param attempt    the attempt that applied the effect
     * @param resultText the result text
     * @return {@link RecordOutcome#APPLIED}, {@code SUPERSEDED}, {@code ALREADY_SUCCEEDED} or {@code MISSING}
     */
    RecordResult recordSuccess(KeyAttempt attempt, String resultText);

    /**
     * Records a failure of {@code attempt}: {@code WHERE created_at = ? AND attempt = ? AND state IN
     * ('EXECUTING','UNKNOWN')};
     * {@link FailureKind#DEFINITE} moves the row to {@code FAILED} with basis {@code DEFINITE},
     * {@link FailureKind#INDETERMINATE} to {@code UNKNOWN}. The lease is cleared.
     *
     * @param attempt the attempt that failed
     * @param kind    how certain the failure is
     * @param error   error text
     * @return {@link RecordOutcome#APPLIED}, {@code SUPERSEDED}, {@code ALREADY_SUCCEEDED}, {@code ALREADY_RESOLVED}
     *         or {@code MISSING}
     */
    RecordResult recordFailure(KeyAttempt attempt, FailureKind kind, String error);

    /**
     * Moves an attempt whose lease expired to {@code UNKNOWN}:
     * {@code WHERE created_at = ? AND attempt = ? AND state = 'EXECUTING' AND lease_until < clock_timestamp()}.
     *
     * @param attempt the attempt
     * @param reason  stored as the last error
     * @return whether the row changed
     */
    boolean markUnknownIfLeaseExpired(KeyAttempt attempt, String reason);

    /**
     * Renews the leases of in-flight attempts of one process in one statement: sets
     * {@code lease_until = clock_timestamp() + lease} for each {@link KeyAttempt} (key hash, row creation time and
     * attempt) that is still {@code EXECUTING}, owned by {@code owner} and within
     * {@code started_at + max_effect_duration}.
     *
     * @param owner    the process owner id
     * @param inFlight attempts running in this process
     * @param lease    lease length
     * @return number of renewed leases
     */
    int renewLeases(String owner, Collection<KeyAttempt> inFlight, Duration lease);

    /**
     * Takes rows the reconciler must look at, in one short transaction that skips rows locked by others: rows
     * {@code EXECUTING} with an expired lease, {@code UNKNOWN}, and {@code FAILED} at or after the deadline, each only
     * when {@code coalesce(resolve_after, -infinity) <= clock_timestamp()}. Sets
     * {@code resolve_after = clock_timestamp() + hold} on the taken rows, so other reconcilers skip them meanwhile.
     *
     * @param hold  how long the taken rows are reserved
     * @param limit maximum number of rows
     * @return the taken rows, as updated
     */
    List<ActionRecord> takeReconcileBatch(Duration hold, int limit);

    /**
     * Applies a resolution as a compare-and-set on the {@link KeyAttempt} and the source state of the resolution
     * ({@code UNKNOWN}, or {@code FAILED} for {@link Resolution.Abandon}).
     *
     * @param attempt    the attempt the resolution is about
     * @param resolution the resolution
     * @return whether the row changed
     */
    boolean resolve(KeyAttempt attempt, Resolution resolution);

    /**
     * Finds the action a provider tool-call id was mapped to.
     *
     * @param scopeHash      SHA-256 of the key scope (call ids are unique per scope only)
     * @param providerCallId the provider's tool-call id
     * @return the mapping with the current row, or empty
     */
    Optional<CallRecord> findByProviderCallId(byte[] scopeHash, String providerCallId);

    /**
     * Retention: deletes at most {@code batchSize} rows in {@link ActionState#SUCCEEDED} or
     * {@link ActionState#ABANDONED} whose {@code updated_at} is older than {@code clock_timestamp() - retention}, oldest first, together with their
     * call history, in one short transaction. Rows locked by another transaction are skipped, not waited for. Rows in
     * other states are never deleted. Once a row is deleted, a call with its key executes again.
     *
     * @param retention how long finished rows are kept; positive
     * @param batchSize maximum number of rows, at least 1
     * @return the number of deleted rows
     * @throws IllegalArgumentException if {@code retention} is not positive or {@code batchSize} is less than 1
     */
    int deleteTerminalOlderThan(Duration retention, int batchSize);
}
