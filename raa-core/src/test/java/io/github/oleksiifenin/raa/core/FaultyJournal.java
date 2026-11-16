package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.CallRecord;
import io.github.oleksiifenin.raa.storage.ClaimDecider;
import io.github.oleksiifenin.raa.storage.ClaimRequest;
import io.github.oleksiifenin.raa.storage.ClaimResult;
import io.github.oleksiifenin.raa.storage.FailureKind;
import io.github.oleksiifenin.raa.storage.JournalException;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import io.github.oleksiifenin.raa.storage.RecordResult;
import io.github.oleksiifenin.raa.storage.Resolution;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Wraps a journal and injects storage failures: a record write can fail before it reaches the journal or after it
 * committed (the ambiguous case), and claims can fail outright. Also counts claims.
 */
final class FaultyJournal implements ActionJournal {

    private final ActionJournal delegate;
    final AtomicInteger claims = new AtomicInteger();
    final AtomicInteger recordCalls = new AtomicInteger();
    final AtomicBoolean failClaims = new AtomicBoolean();
    final AtomicBoolean failResolves = new AtomicBoolean();
    final AtomicInteger renewCalls = new AtomicInteger();
    /** Record writes that fail without reaching the journal. */
    final AtomicInteger recordFailuresBeforeWrite = new AtomicInteger();
    /** Record writes that reach the journal and then report a failure. */
    final AtomicInteger recordFailuresAfterWrite = new AtomicInteger();
    /** Runs on the calling thread before every record write. */
    volatile Consumer<Thread> beforeRecord = thread -> { };

    FaultyJournal(ActionJournal delegate) {
        this.delegate = delegate;
    }

    @Override
    public ClaimResult claim(ClaimRequest request, ClaimDecider decider) {
        claims.incrementAndGet();
        if (failClaims.get()) {
            throw storageDown();
        }
        return delegate.claim(request, decider);
    }

    @Override
    public RecordResult recordSuccess(byte[] keyHash, int attempt, String resultText) {
        return record(() -> delegate.recordSuccess(keyHash, attempt, resultText));
    }

    @Override
    public RecordResult recordFailure(byte[] keyHash, int attempt, FailureKind kind, String error) {
        return record(() -> delegate.recordFailure(keyHash, attempt, kind, error));
    }

    private RecordResult record(Supplier<RecordResult> write) {
        recordCalls.incrementAndGet();
        beforeRecord.accept(Thread.currentThread());
        if (takeOne(recordFailuresBeforeWrite)) {
            throw storageDown();
        }
        RecordResult result = write.get();
        if (takeOne(recordFailuresAfterWrite)) {
            throw new JournalException("connection lost after commit", new SQLException("08006"));
        }
        return result;
    }

    @Override
    public boolean markUnknownIfLeaseExpired(byte[] keyHash, int attempt, String reason) {
        return delegate.markUnknownIfLeaseExpired(keyHash, attempt, reason);
    }

    @Override
    public int renewLeases(String owner, Collection<KeyAttempt> inFlight, Duration lease) {
        renewCalls.incrementAndGet();
        return delegate.renewLeases(owner, inFlight, lease);
    }

    @Override
    public List<ActionRecord> takeReconcileBatch(Duration hold, int limit) {
        return delegate.takeReconcileBatch(hold, limit);
    }

    @Override
    public boolean resolve(byte[] keyHash, int attempt, Resolution resolution) {
        if (failResolves.get()) {
            throw storageDown();
        }
        return delegate.resolve(keyHash, attempt, resolution);
    }

    @Override
    public Optional<CallRecord> findByProviderCallId(byte[] scopeHash, String providerCallId) {
        return delegate.findByProviderCallId(scopeHash, providerCallId);
    }

    private static boolean takeOne(AtomicInteger counter) {
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    private static JournalException storageDown() {
        return new JournalException("database unavailable", new SQLException("08001"));
    }
}
