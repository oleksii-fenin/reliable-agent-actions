package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the leases of this process's running attempts alive (docs/DESIGN.md §6, "Lease renewer"). The runner
 * registers an attempt when its claim starts it and removes it when the attempt's outcome is recorded; every
 * {@code lease / 2} the renewer extends all registered leases with one journal call. The journal renews only attempts
 * that are still executing, owned by this process and within their maximum effect duration, so an effect that
 * overruns loses its lease and its outcome becomes unknown.
 *
 * <p>One renewer per {@link ReliableActions}; the application calls {@link #start()} once and {@link #close()} on
 * shutdown. Without {@code start()}, leases are not renewed.
 */
public final class LeaseRenewer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(LeaseRenewer.class.getName());

    private final ActionJournal journal;
    private final String owner;
    private final Duration lease;
    private final Set<KeyAttempt> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean warnedNotStarted = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;

    LeaseRenewer(ActionJournal journal, String owner, Duration lease) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.lease = Objects.requireNonNull(lease, "lease");
    }

    /**
     * Starts renewing every {@code lease / 2} on a daemon thread.
     *
     * @throws IllegalStateException if already started or closed
     */
    public synchronized void start() {
        if (scheduler != null) {
            throw new IllegalStateException("lease renewer already started");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "raa-lease-renewer");
            thread.setDaemon(true);
            return thread;
        });
        long periodMillis = Math.max(1, lease.toMillis() / 2);
        scheduler.scheduleAtFixedRate(this::renewQuietly, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Renews the leases of all registered attempts now.
     *
     * @return number of leases the journal renewed
     * @throws io.github.oleksiifenin.raa.storage.JournalException if the journal fails
     */
    public int renewNow() {
        List<KeyAttempt> attempts = List.copyOf(inFlight);
        if (attempts.isEmpty()) {
            return 0;
        }
        return journal.renewLeases(owner, attempts, lease);
    }

    /** Stops renewing. Registered attempts keep running; their leases expire. */
    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    void register(KeyAttempt attempt) {
        inFlight.add(attempt);
    }

    void unregister(KeyAttempt attempt) {
        inFlight.remove(attempt);
    }

    /** Logs once if attempts run while renewal was never started: their leases then expire after one lease. */
    void warnIfNotStarted() {
        if (scheduler == null && warnedNotStarted.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING, "an attempt started but LeaseRenewer.start() was never called;"
                    + " effects longer than the lease lose it and may be repeated by another call");
        }
    }

    int inFlightCount() {
        return inFlight.size();
    }

    private void renewQuietly() {
        try {
            renewNow();
        } catch (RuntimeException e) {
            // The next period tries again; leases that expire meanwhile turn into UNKNOWN, as designed.
            LOG.log(System.Logger.Level.WARNING, "lease renewal failed", e);
        }
    }
}
