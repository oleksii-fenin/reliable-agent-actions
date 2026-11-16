package io.github.oleksiifenin.raa.core;

import java.util.Objects;

/**
 * The attempt an {@link Effect} is executing. Installed on the effect's thread for the duration of
 * {@link Effect#execute}; tool code reads the idempotency key from it to send downstream.
 */
public final class ActionContext {

    private static final ThreadLocal<ActionContext> CURRENT = new ThreadLocal<>();

    private final ActionKey key;
    private final String idempotencyKey;
    private final int attempt;

    ActionContext(ActionKey key, String idempotencyKey, int attempt) {
        this.key = Objects.requireNonNull(key, "key");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.attempt = attempt;
    }

    /**
     * Returns the context of the effect running on this thread.
     *
     * @return the context, never {@code null}
     * @throws IllegalStateException outside an effect, for example when tool code moved to another thread
     */
    public static ActionContext current() {
        ActionContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("no action is executing on thread " + Thread.currentThread().getName()
                    + "; ActionContext is available only on the thread that runs the effect");
        }
        return context;
    }

    /**
     * Returns the idempotency key to send downstream: stable across attempts with the same material arguments.
     *
     * @return {@code "raa-"} followed by 43 base64url characters
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Returns the attempt number, starting at 1.
     *
     * @return the attempt
     */
    public int attempt() {
        return attempt;
    }

    /**
     * Returns the action key.
     *
     * @return the key
     */
    public ActionKey key() {
        return key;
    }

    /**
     * Marks the point after which the effect may have reached the downstream.
     *
     * <p>Until the design fixes how a tool opts in (docs/OPEN-QUESTIONS.md), the runner treats every effect as
     * started, so the default {@link FailureClassifier} classifies every exception except
     * {@link ActionRejectedException} as "may have been applied". A tool that never calls this marker must not have
     * its timeouts classified as "definitely not applied", and the runner cannot tell such a tool from one that threw
     * before reaching the marker.
     */
    public void beforeSideEffect() {
        // Deliberately no effect yet: see the Javadoc.
    }

    /** Runs {@code effect} with this context installed on the current thread. */
    String runOnCurrentThread(Effect effect) throws Exception {
        ActionContext previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return effect.execute(this);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
