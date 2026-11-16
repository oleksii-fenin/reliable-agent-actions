package io.github.oleksiifenin.raa.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** The "database time" of an in-memory journal in runner tests: moves only when a test advances it. */
final class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-12T10:00:00Z"));

    void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
