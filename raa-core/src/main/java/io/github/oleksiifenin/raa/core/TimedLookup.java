package io.github.oleksiifenin.raa.core;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs an {@link OutcomeLookup} on its own daemon thread, bounded by a timeout, so a hanging downstream cannot block
 * the runner or the reconciler. Used by both; never inside a journal transaction.
 */
final class TimedLookup {

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "raa-outcome-lookup");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Asks the downstream. A timeout, an exception or a {@code null} answer counts as unreachable.
     *
     * @return the result, or {@code null} if the calling thread was interrupted meanwhile (its flag is then set)
     */
    LookupResult lookUp(OutcomeLookup lookup, ActionKey key, String idempotencyKey, Duration timeout) {
        Future<LookupResult> pending = executor.submit(() -> lookup.lookup(key, idempotencyKey));
        try {
            LookupResult result = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return result == null ? new LookupResult.Unreachable("the lookup returned no result") : result;
        } catch (TimeoutException e) {
            pending.cancel(true);
            return new LookupResult.Unreachable("the lookup did not answer within " + timeout);
        } catch (ExecutionException e) {
            return new LookupResult.Unreachable("the lookup failed: " + describe(e.getCause()));
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null ? error.getClass().getName() : error.getMessage();
    }
}
