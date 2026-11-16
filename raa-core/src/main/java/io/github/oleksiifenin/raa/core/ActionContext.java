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
    private volatile boolean sideEffectStarted;

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
     * Marks the point after which the effect may have reached the downstream. Call it on the effect's own control
     * path, before the <em>first</em> call that may change anything downstream and before handing any work to
     * another thread or an asynchronous client; later calls change nothing.
     *
     * <p>It counts only if the tool's policy declares {@link JournaledPolicy#usesSideEffectMarker()}. Then an
     * exception thrown before the first call is classified with {@code effectStarted = false} (by default "definitely
     * not applied"), and a new attempt may start, even for an at-most-once tool. The runner reads the marker once,
     * when the effect throws: work that the tool started in the background and that marks or sends later is not seen,
     * so such a tool must mark before the hand-off. Without the declaration every exception counts as thrown after
     * the effect started: the runner cannot tell a tool that threw before this marker from one that never calls it.
     */
    public void beforeSideEffect() {
        sideEffectStarted = true;
    }

    /** Returns whether {@link #beforeSideEffect()} was called during this attempt. */
    boolean sideEffectStarted() {
        return sideEffectStarted;
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
