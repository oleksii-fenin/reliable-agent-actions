package io.github.oleksiifenin.raa.testkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * A child JVM for kill and pause tests: started as {@code java -cp <class path> <main class> <args>} directly, with no
 * shell or build tool in between, so {@link #kill()} delivers SIGKILL and {@link #pause()}/{@link #resume()} deliver
 * SIGSTOP/SIGCONT (Linux and macOS; the latter two run the {@code kill} and {@code ps} commands; Windows is not
 * supported) to the JVM itself. Its standard output
 * is read line by line; standard error is merged into it and kept for failure messages. Lines can be written to its
 * standard input with {@link #send(String)}.
 */
public final class JvmProcess implements AutoCloseable {

    private static final String END_OF_OUTPUT = "\u0000end-of-output";

    private final Process process;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> transcript = Collections.synchronizedList(new ArrayList<>());

    private JvmProcess(Process process) {
        this.process = process;
        Thread reader = new Thread(this::readOutput, "jvm-process-output-" + process.pid());
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Starts {@code mainClass} in a new JVM with the same Java executable and class path as this one.
     *
     * @param mainClass the class whose {@code main} runs
     * @param args      program arguments
     * @return the running process
     */
    public static JvmProcess start(Class<?> mainClass, List<String> args) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass.getName());
        command.addAll(args);
        try {
            return new JvmProcess(new ProcessBuilder(command).redirectErrorStream(true).start());
        } catch (IOException e) {
            throw new UncheckedIOException("could not start " + command, e);
        }
    }

    /**
     * Returns the operating-system process id.
     *
     * @return the pid
     */
    public long pid() {
        return process.pid();
    }

    /**
     * Returns the executable of the running process as the operating system reports it, to check that the pid is the
     * JVM and not a wrapper.
     *
     * @return the command, empty if the platform does not report it
     */
    public Optional<String> command() {
        return process.toHandle().info().command();
    }

    /**
     * Waits for the next line that starts with {@code prefix}, or for the timeout. Like {@link #awaitLine}, it consumes
     * the lines it skips: a skipped line is not returned by a later call.
     *
     * @param prefix  the prefix
     * @param timeout how long to wait
     * @return the line, empty if none came in time
     * @throws IllegalStateException if the process ends first; the message contains the output so far
     */
    public Optional<String> pollLine(String prefix, Duration timeout) {
        return Optional.ofNullable(nextLine(prefix, timeout));
    }

    /**
     * Waits for the first line that starts with {@code prefix}, consuming the lines it skips.
     *
     * @param prefix  the prefix
     * @param timeout how long to wait
     * @return the line
     * @throws IllegalStateException if the line does not come in time or the process ends first; the message contains
     *                               the output so far
     */
    public String awaitLine(String prefix, Duration timeout) {
        String line = nextLine(prefix, timeout);
        if (line == null) {
            throw new IllegalStateException("no line starting with '" + prefix + "' within " + timeout + "; output:\n"
                    + output());
        }
        return line;
    }

    /** The next line with the prefix, {@code null} on timeout; throws if the output ends first. */
    private String nextLine(String prefix, Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long remaining = end - System.nanoTime();
                String line = remaining <= 0 ? null : lines.poll(remaining, TimeUnit.NANOSECONDS);
                if (line == null) {
                    return null;
                }
                if (END_OF_OUTPUT.equals(line)) {
                    lines.add(line); // keep the end visible for later calls
                    throw new IllegalStateException("the process ended before printing '" + prefix + "'; output:\n"
                            + output());
                }
                if (line.startsWith(prefix)) {
                    return line;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for '" + prefix + "'", e);
        }
    }

    /**
     * Writes one line to the process's standard input and flushes it. A paused process reads it once it is resumed.
     *
     * @param line the line, without a line break
     */
    public void send(String line) {
        try {
            OutputStream in = process.getOutputStream();
            in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("could not write to " + this, e);
        }
    }

    /**
     * Pauses the process with SIGSTOP (as a long GC pause or a frozen VM would): none of its threads runs until
     * {@link #resume()}, its lease renewer included.
     *
     * @throws IllegalStateException if the signal cannot be sent or the process does not report itself stopped within
     *                               10 seconds
     */
    public void pause() {
        signal("-STOP");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!isStopped()) {
            if (System.nanoTime() > end) {
                throw new IllegalStateException("the process did not stop: " + this);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); // polling the process state with a timeout
        }
    }

    /**
     * Resumes a paused process with SIGCONT.
     *
     * @throws IllegalStateException if the signal cannot be sent
     */
    public void resume() {
        signal("-CONT");
    }

    /**
     * Returns whether the operating system reports the process as stopped (state {@code T} of {@code ps}).
     *
     * @return {@code true} if stopped
     */
    public boolean isStopped() {
        String state = run("ps", "-o", "stat=", "-p", Long.toString(pid())).trim();
        return state.startsWith("T");
    }

    private void signal(String signal) {
        run("kill", signal, Long.toString(pid()));
    }

    /** Runs a helper command; {@code ps} for a process that is gone (exit 1, no output) returns "". */
    private static String run(String... command) {
        String name = String.join(" ", command);
        try {
            Process helper = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!helper.waitFor(10, TimeUnit.SECONDS)) { // its output is a few bytes and fits in the pipe meanwhile
                helper.destroyForcibly();
                throw new IllegalStateException(name + " did not finish within 10 seconds");
            }
            String output = new String(helper.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean processGone = "ps".equals(command[0]) && helper.exitValue() == 1 && output.isBlank();
            if (helper.exitValue() != 0 && !processGone) {
                throw new IllegalStateException(name + " failed with exit code " + helper.exitValue() + ": " + output);
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException("could not run " + name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + name, e);
        }
    }

    /** Exit code of a JVM ended by SIGKILL (128 + 9), on Linux and macOS. */
    public static final int KILLED_BY_SIGKILL = 137;

    /**
     * Kills the process with SIGKILL and waits until it is gone.
     *
     * @return the exit code: {@link #KILLED_BY_SIGKILL} if the signal ended it, another value if the process had
     *         already ended on its own
     * @throws IllegalStateException if it does not end within 10 seconds
     */
    public int kill() {
        process.destroyForcibly();
        return awaitExit(Duration.ofSeconds(10));
    }

    /**
     * Waits for the process to end on its own.
     *
     * @param timeout how long to wait
     * @return the exit code
     * @throws IllegalStateException if it does not end in time
     */
    public int awaitExit(Duration timeout) {
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("the process did not end within " + timeout + "; output:\n"
                        + output());
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the process", e);
        }
    }

    /**
     * Returns everything the process printed so far.
     *
     * @return the output, one line per line
     */
    public String output() {
        synchronized (transcript) {
            return String.join("\n", transcript);
        }
    }

    /** Kills the process if it still runs. */
    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void readOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                transcript.add(line);
                lines.add(line);
            }
        } catch (IOException e) {
            transcript.add("<output read failed: " + e.getMessage() + ">");
        } finally {
            lines.add(END_OF_OUTPUT);
        }
    }

    @Override
    public String toString() {
        return "JvmProcess[pid=" + pid() + ", command=" + Objects.toString(command().orElse(null)) + "]";
    }
}
