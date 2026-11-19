package io.github.oleksiifenin.raa.testkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * Stops the current process at one armed {@link FaultPoint}: prints {@link #MARKER} and the point on its own line, then
 * parks the thread for good, so the parent can kill the process at a confirmed point. A {@linkplain #resumable
 * resumable} injector waits for a line on its input instead and then continues, so the parent can pause the process
 * there (SIGSTOP) and let it go on later; it is meant for one stop per process (it reads its input through a fresh
 * buffer). Points other than the armed one pass through.
 */
public final class FaultInjector {

    /** Prefix of the line a process prints when it reached its armed point. */
    public static final String MARKER = "RAA-FAULT-POINT ";

    private final FaultPoint armed;
    private final PrintStream out;
    private final InputStream continueSignal;

    /**
     * Creates an injector that stops for good at its armed point.
     *
     * @param armed the point to stop at, or {@code null} to never stop
     * @param out   where the marker line is printed (the parent reads it)
     */
    public FaultInjector(FaultPoint armed, PrintStream out) {
        this(armed, out, null);
    }

    private FaultInjector(FaultPoint armed, PrintStream out, InputStream continueSignal) {
        this.armed = armed;
        this.out = Objects.requireNonNull(out, "out");
        this.continueSignal = continueSignal;
    }

    /**
     * Creates an injector that, at its armed point, prints the marker and then waits until a line (any text) arrives
     * on {@code in} before it returns.
     *
     * @param armed the point to wait at
     * @param out   where the marker line is printed
     * @param in    where the parent writes the line that lets the process continue, usually {@code System.in}
     * @return the injector
     */
    public static FaultInjector resumable(FaultPoint armed, PrintStream out, InputStream in) {
        return new FaultInjector(Objects.requireNonNull(armed, "armed"), out, Objects.requireNonNull(in, "in"));
    }

    /**
     * Stops here (for good, or until the continue line of a resumable injector) if {@code point} is the armed one,
     * otherwise returns at once.
     *
     * @param point the point the caller reached
     * @throws IllegalStateException if the input of a resumable injector ends before a line arrives
     */
    public void reach(FaultPoint point) {
        if (point != armed) {
            return;
        }
        out.println(MARKER + point.name());
        out.flush();
        if (continueSignal != null) {
            awaitContinueLine();
            return;
        }
        while (true) {
            LockSupport.park(this); // until the parent kills the process; spurious wake-ups park again
            Thread.interrupted(); // a set interrupt flag would make park return at once: clear it, keep waiting
        }
    }

    private void awaitContinueLine() {
        try {
            // Not closed: closing would close the caller's stream (System.in). One injector reads one line.
            BufferedReader reader = new BufferedReader(new InputStreamReader(continueSignal, StandardCharsets.UTF_8));
            if (reader.readLine() == null) {
                throw new IllegalStateException("the input ended before the line to continue at " + armed);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
