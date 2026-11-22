package io.github.oleksiifenin.raa.core;

import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.ClaimVerdict;
import io.github.oleksiifenin.raa.storage.RecordOutcome;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps the application's telemetry so that a failing implementation is logged and never changes what an action does
 * (the contract of {@link ActionTelemetry}): runtime exceptions and linkage errors (for example a telemetry library
 * missing from the class path) are caught; other errors pass through. An observation is ended at most once.
 */
final class GuardedTelemetry implements ActionTelemetry {

    private static final System.Logger LOG = System.getLogger(GuardedTelemetry.class.getName());

    private final ActionTelemetry delegate;

    private GuardedTelemetry(ActionTelemetry delegate) {
        this.delegate = delegate;
    }

    /** The guarded telemetry, or {@link ActionTelemetry#none()} itself, which cannot fail. */
    static ActionTelemetry guard(ActionTelemetry telemetry) {
        Objects.requireNonNull(telemetry, "telemetry");
        return telemetry == ActionTelemetry.none() || telemetry instanceof GuardedTelemetry
                ? telemetry : new GuardedTelemetry(telemetry);
    }

    @Override
    public ActionObservation startAction(ActionStart start) {
        try {
            ActionObservation observation = delegate.startAction(start);
            return observation == null ? ActionObservation.NONE : new GuardedAction(observation);
        } catch (RuntimeException | LinkageError e) {
            failed("startAction", e);
            return ActionObservation.NONE;
        }
    }

    @Override
    public ToolObservation startTool(String toolName, String description, String providerCallId) {
        try {
            ToolObservation observation = delegate.startTool(toolName, description, providerCallId);
            return observation == null ? ToolObservation.NONE : new GuardedTool(observation);
        } catch (RuntimeException | LinkageError e) {
            failed("startTool", e);
            return ToolObservation.NONE;
        }
    }

    @Override
    public void journalOperation(String operation, long durationNanos, boolean failed) {
        run("journalOperation", () -> delegate.journalOperation(operation, durationNanos, failed));
    }

    @Override
    public void signal(ActionSignal signal, String toolName) {
        run("signal", () -> delegate.signal(signal, toolName));
    }

    @Override
    public void reconcilerResolution(ReconcilerResult result, String toolName) {
        run("reconcilerResolution", () -> delegate.reconcilerResolution(result, toolName));
    }

    @Override
    public void unresolvedActions(long unknown, long escalated) {
        run("unresolvedActions", () -> delegate.unresolvedActions(unknown, escalated));
    }

    private static void run(String hook, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException | LinkageError e) {
            failed(hook, e);
        }
    }

    private static void failed(String hook, Throwable e) {
        LOG.log(System.Logger.Level.WARNING, "telemetry hook " + hook + " failed; the action is not affected", e);
    }

    private static final class GuardedAction implements ActionObservation {

        private final ActionObservation delegate;
        private final AtomicBoolean ended = new AtomicBoolean();

        GuardedAction(ActionObservation delegate) {
            this.delegate = delegate;
        }

        @Override
        public void claimed(ClaimVerdict verdict, ActionState state, int attempt) {
            run("claimed", () -> delegate.claimed(verdict, state, attempt));
        }

        @Override
        public void recorded(RecordOutcome outcome, int attempt) {
            run("recorded", () -> delegate.recorded(outcome, attempt));
        }

        @Override
        public void lookedUp(String result) {
            run("lookedUp", () -> delegate.lookedUp(result));
        }

        @Override
        public void ended(ActionOutcome outcome) {
            if (ended.compareAndSet(false, true)) {
                run("ended", () -> delegate.ended(outcome));
            }
        }

        @Override
        public void failed(Throwable error) {
            if (ended.compareAndSet(false, true)) {
                run("failed", () -> delegate.failed(error));
            }
        }
    }

    private static final class GuardedTool implements ToolObservation {

        private final ToolObservation delegate;
        private final AtomicBoolean ended = new AtomicBoolean();

        GuardedTool(ToolObservation delegate) {
            this.delegate = delegate;
        }

        @Override
        public void ended(boolean error) {
            if (ended.compareAndSet(false, true)) {
                run("ended", () -> delegate.ended(error));
            }
        }

        @Override
        public void failed(Throwable error) {
            if (ended.compareAndSet(false, true)) {
                run("failed", () -> delegate.failed(error));
            }
        }
    }
}
