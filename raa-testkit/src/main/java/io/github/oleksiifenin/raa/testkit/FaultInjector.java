package io.github.oleksiifenin.raa.testkit;

import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * Stops the current process at one armed {@link FaultPoint}: prints {@link #MARKER} and the point on its own line, then
 * parks the thread for good, so the parent can kill the process at a confirmed point. Points other than the armed one
 * pass through.
 */
public final class FaultInjector {

    /** Prefix of the line a process prints when it reached its armed point. */
    public static final String MARKER = "RAA-FAULT-POINT ";

    private final FaultPoint armed;
    private final PrintStream out;

    /**
     * Creates an injector.
     *
     * @param armed the point to stop at, or {@code null} to never stop
     * @param out   where the marker line is printed (the parent reads it)
     */
    public FaultInjector(FaultPoint armed, PrintStream out) {
        this.armed = armed;
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * Stops here for good if {@code point} is the armed one, otherwise returns at once.
     *
     * @param point the point the caller reached
     */
    public void reach(FaultPoint point) {
        if (point != armed) {
            return;
        }
        out.println(MARKER + point.name());
        out.flush();
        while (true) {
            LockSupport.park(this); // until the parent kills the process; spurious wake-ups park again
            Thread.interrupted(); // a set interrupt flag would make park return at once: clear it, keep waiting
        }
    }
}
