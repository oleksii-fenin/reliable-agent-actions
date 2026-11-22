package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.CallRecord;
import io.github.oleksiifenin.raa.storage.ClaimDecider;
import io.github.oleksiifenin.raa.storage.ClaimRequest;
import io.github.oleksiifenin.raa.storage.ClaimResult;
import io.github.oleksiifenin.raa.storage.FailureKind;
import io.github.oleksiifenin.raa.storage.JournalException;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import io.github.oleksiifenin.raa.storage.ManualResolution;
import io.github.oleksiifenin.raa.storage.RecordResult;
import io.github.oleksiifenin.raa.storage.Resolution;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reports the duration of every journal operation to the telemetry ({@code raa.journal.duration}). Measurement only:
 * the monotonic clock here never takes part in a decision.
 */
final class ObservedJournal implements ActionJournal {

    private final ActionJournal delegate;
    private final ActionTelemetry telemetry;

    private ObservedJournal(ActionJournal delegate, ActionTelemetry telemetry) {
        this.delegate = delegate;
        this.telemetry = telemetry;
    }

    /** The journal itself when nothing is recorded, otherwise the observed journal. */
    static ActionJournal observe(ActionJournal journal, ActionTelemetry telemetry) {
        return telemetry == ActionTelemetry.none() ? journal : new ObservedJournal(journal, telemetry);
    }

    @Override
    public ClaimResult claim(ClaimRequest request, ClaimDecider decider) {
        return timed("claim", () -> delegate.claim(request, decider));
    }

    @Override
    public RecordResult recordSuccess(KeyAttempt attempt, String resultText) {
        return timed("recordSuccess", () -> delegate.recordSuccess(attempt, resultText));
    }

    @Override
    public RecordResult recordFailure(KeyAttempt attempt, FailureKind kind, String error) {
        return timed("recordFailure", () -> delegate.recordFailure(attempt, kind, error));
    }

    @Override
    public boolean markUnknownIfLeaseExpired(KeyAttempt attempt, String reason) {
        return timed("markUnknownIfLeaseExpired", () -> delegate.markUnknownIfLeaseExpired(attempt, reason));
    }

    @Override
    public int renewLeases(String owner, Collection<KeyAttempt> inFlight, Duration lease) {
        return timed("renewLeases", () -> delegate.renewLeases(owner, inFlight, lease));
    }

    @Override
    public List<ActionRecord> takeReconcileBatch(Duration hold, int limit) {
        return timed("takeReconcileBatch", () -> delegate.takeReconcileBatch(hold, limit));
    }

    @Override
    public boolean resolve(KeyAttempt attempt, Resolution resolution) {
        return timed("resolve", () -> delegate.resolve(attempt, resolution));
    }

    @Override
    public Optional<CallRecord> findByProviderCallId(byte[] scopeHash, String providerCallId) {
        return timed("findByProviderCallId", () -> delegate.findByProviderCallId(scopeHash, providerCallId));
    }

    @Override
    public int deleteTerminalOlderThan(Duration retention, int batchSize) {
        return timed("deleteTerminalOlderThan", () -> delegate.deleteTerminalOlderThan(retention, batchSize));
    }

    @Override
    public List<ActionRecord> find(ActionState state, int limit) {
        return timed("find", () -> delegate.find(state, limit));
    }

    @Override
    public long count(ActionState state) {
        return timed("count", () -> delegate.count(state));
    }

    @Override
    public boolean resolveManually(KeyAttempt attempt, ManualResolution resolution) {
        return timed("resolveManually", () -> delegate.resolveManually(attempt, resolution));
    }

    @Override
    public boolean redact(byte[] keyHash) {
        return timed("redact", () -> delegate.redact(keyHash));
    }

    /** Reports the duration; failed only for a {@link JournalException}, not for an exception of the decider. */
    private <T> T timed(String operation, Supplier<T> call) {
        long started = System.nanoTime();
        boolean failed = false;
        try {
            return call.get();
        } catch (JournalException e) {
            failed = true;
            throw e;
        } finally {
            telemetry.journalOperation(operation, System.nanoTime() - started, failed);
        }
    }
}
