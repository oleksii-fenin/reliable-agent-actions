package io.github.oleksiifenin.raa.core;

import java.time.Duration;

/**
 * The downstream deduplicates by the idempotency key; effectively-once if the deadline does not exceed the
 * downstream's dedup window. Unknown outcomes are retried with the same idempotency key.
 */
public final class IdempotentDownstream extends JournaledPolicy<IdempotentDownstream> {

    private final Duration downstreamDedupWindow;

    IdempotentDownstream(Duration downstreamDedupWindow) {
        this(downstreamDedupWindow, Settings.defaults(null));
    }

    private IdempotentDownstream(Duration downstreamDedupWindow, Settings settings) {
        super(settings);
        requirePositive(downstreamDedupWindow, "downstreamDedupWindow");
        this.downstreamDedupWindow = downstreamDedupWindow;
    }

    @Override
    IdempotentDownstream withSettings(Settings newSettings) {
        return new IdempotentDownstream(downstreamDedupWindow, newSettings);
    }

    @Override
    public String journalName() {
        return "IDEMPOTENT_DOWNSTREAM";
    }

    /**
     * Returns how long the downstream remembers an idempotency key.
     *
     * @return the window
     */
    public Duration downstreamDedupWindow() {
        return downstreamDedupWindow;
    }

    /**
     * {@inheritDoc} Also requires the deadline to be at most the downstream dedup window.
     */
    @Override
    public void validate() {
        super.validate();
        if (deadline().compareTo(downstreamDedupWindow) > 0) {
            throw new IllegalStateException(journalName() + ": deadline " + deadline()
                    + " must not exceed the downstream dedup window " + downstreamDedupWindow);
        }
    }
}
