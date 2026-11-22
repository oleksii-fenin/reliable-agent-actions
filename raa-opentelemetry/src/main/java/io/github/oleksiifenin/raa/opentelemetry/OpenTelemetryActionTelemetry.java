package io.github.oleksiifenin.raa.opentelemetry;

import io.github.oleksiifenin.raa.core.ActionObservation;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionSignal;
import io.github.oleksiifenin.raa.core.ActionStart;
import io.github.oleksiifenin.raa.core.ActionTelemetry;
import io.github.oleksiifenin.raa.core.ReconcilerResult;
import io.github.oleksiifenin.raa.core.ToolObservation;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.ClaimVerdict;
import io.github.oleksiifenin.raa.storage.RecordOutcome;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ActionTelemetry} on the OpenTelemetry API (docs/DESIGN.md §10). Pass it to
 * {@code ReliableActions.builder(journal).telemetry(OpenTelemetryActionTelemetry.create(openTelemetry))}.
 *
 * <p><b>Spans.</b> {@code reliable_action {tool}} (INTERNAL) per action call, a child of the current span and current
 * itself while the call runs, so the tool's own client spans nest under it. Attributes: {@code gen_ai.tool.name},
 * {@code gen_ai.tool.call.id} (if known), {@code raa.policy}, {@code raa.key.hash} (never the key itself),
 * {@code raa.mode} ({@code run} or {@code inspect}), and at the end {@code raa.attempt} (the last attempt the call saw)
 * and {@code raa.outcome}. Events {@code raa.claim}, {@code raa.record}, {@code raa.lookup} mark the state transitions.
 * The outcomes {@code failed}, {@code abandoned}, {@code escalated} and {@code journal_unavailable} set the status to
 * ERROR with {@code error.type} = the outcome. {@code execute_tool {tool}} (INTERNAL) per tool execution of a framework
 * adapter, after the GenAI semantic conventions (status Development, checked 2026-11-19 in
 * open-telemetry/semantic-conventions-genai, {@code docs/gen-ai/execute-tool.md}): {@code gen_ai.operation.name},
 * {@code gen_ai.tool.name}, {@code gen_ai.tool.type = function}, {@code gen_ai.tool.call.id},
 * {@code gen_ai.tool.description}; a result marked as an error sets {@code error.type = tool_error}, an exception its
 * class name. Arguments, results and the conversation id are not recorded (they may contain personal data).
 *
 * <p>A call that throws records only the exception's class in {@code error.type}, never its message or stack trace
 * (they may contain arguments). Client spans of an outcome lookup are not children of the action span: lookups run on
 * their own thread with a timeout.
 *
 * <p><b>Metrics.</b> {@code raa.actions} (counter of run calls, not inspections: {@code raa.policy},
 * {@code raa.outcome}, {@code exception} for a call that threw); {@code raa.journal.duration} (histogram, seconds: {@code raa.journal.operation},
 * {@code error.type = journal_error} on failure); {@code raa.reconciler.resolutions} (counter: {@code raa.result},
 * {@code gen_ai.tool.name}); {@code raa.stale_success}, {@code raa.late_result_corrected}, {@code raa.lease.expired}
 * (counters: {@code gen_ai.tool.name}); {@code raa.actions.unresolved} (gauge, set at the end of every reconciler run:
 * {@code raa.state = UNKNOWN} or {@code ESCALATED}). The gauge is the count of the whole journal, so every process that
 * runs the reconciler reports the same value: aggregate it across processes with the maximum, never the sum. While the
 * journal cannot be counted the gauge keeps its last value; alert on it together with {@code raa.journal.duration}
 * errors.
 */
public final class OpenTelemetryActionTelemetry implements ActionTelemetry {

    /** The instrumentation scope of the tracer and the meter. */
    public static final String INSTRUMENTATION_SCOPE = "io.github.oleksiifenin.raa";

    static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");
    static final AttributeKey<String> TOOL_CALL_ID = AttributeKey.stringKey("gen_ai.tool.call.id");
    static final AttributeKey<String> TOOL_TYPE = AttributeKey.stringKey("gen_ai.tool.type");
    static final AttributeKey<String> TOOL_DESCRIPTION = AttributeKey.stringKey("gen_ai.tool.description");
    static final AttributeKey<String> OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name");
    static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    static final AttributeKey<String> POLICY = AttributeKey.stringKey("raa.policy");
    static final AttributeKey<String> KEY_HASH = AttributeKey.stringKey("raa.key.hash");
    static final AttributeKey<String> MODE = AttributeKey.stringKey("raa.mode");
    static final AttributeKey<Long> ATTEMPT = AttributeKey.longKey("raa.attempt");
    static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("raa.outcome");
    static final AttributeKey<String> VERDICT = AttributeKey.stringKey("raa.verdict");
    static final AttributeKey<String> STATE = AttributeKey.stringKey("raa.state");
    static final AttributeKey<String> RECORD_OUTCOME = AttributeKey.stringKey("raa.record.outcome");
    static final AttributeKey<String> LOOKUP_RESULT = AttributeKey.stringKey("raa.lookup.result");
    static final AttributeKey<String> JOURNAL_OPERATION = AttributeKey.stringKey("raa.journal.operation");
    static final AttributeKey<String> RESULT = AttributeKey.stringKey("raa.result");

    private static final Set<String> ERROR_OUTCOMES = Set.of("failed", "abandoned", "escalated",
            "journal_unavailable");
    /** Journal operations take milliseconds; the default buckets are meant for milliseconds, not seconds. */
    private static final List<Double> JOURNAL_BUCKETS = List.of(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25,
            0.5, 1.0, 2.5, 5.0);

    private final Tracer tracer;
    private final LongCounter actions;
    private final DoubleHistogram journalDuration;
    private final LongCounter resolutions;
    private final LongCounter staleSuccess;
    private final LongCounter lateResultCorrected;
    private final LongCounter leaseExpired;
    private final LongGauge unresolved;

    private OpenTelemetryActionTelemetry(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_SCOPE);
        this.actions = meter.counterBuilder("raa.actions").setUnit("{action}")
                .setDescription("Action calls by policy and outcome").build();
        this.journalDuration = meter.histogramBuilder("raa.journal.duration").setUnit("s")
                .setDescription("Duration of journal operations").setExplicitBucketBoundariesAdvice(JOURNAL_BUCKETS)
                .build();
        this.resolutions = meter.counterBuilder("raa.reconciler.resolutions").setUnit("{action}")
                .setDescription("Rows the reconciler changed, by result").build();
        this.staleSuccess = meter.counterBuilder("raa.stale_success").setUnit("{attempt}")
                .setDescription("Attempts that finished after a newer attempt had started").build();
        this.lateResultCorrected = meter.counterBuilder("raa.late_result_corrected").setUnit("{attempt}")
                .setDescription("Late successes that landed after a lookup had said not performed").build();
        this.leaseExpired = meter.counterBuilder("raa.lease.expired").setUnit("{attempt}")
                .setDescription("Attempts whose lease ran out while executing").build();
        this.unresolved = meter.gaugeBuilder("raa.actions.unresolved").ofLongs().setUnit("{action}")
                .setDescription("Actions of the journal waiting for an outcome lookup or an operator, by state")
                .build();
    }

    /**
     * Creates the telemetry on an OpenTelemetry instance, for example {@code GlobalOpenTelemetry.get()} or the SDK
     * the application built.
     *
     * @param openTelemetry the OpenTelemetry instance
     * @return the telemetry
     */
    public static OpenTelemetryActionTelemetry create(OpenTelemetry openTelemetry) {
        return new OpenTelemetryActionTelemetry(Objects.requireNonNull(openTelemetry, "openTelemetry"));
    }

    @Override
    public ActionObservation startAction(ActionStart start) {
        SpanBuilder builder = tracer.spanBuilder("reliable_action " + start.toolName()).setSpanKind(SpanKind.INTERNAL)
                .setAttribute(TOOL_NAME, start.toolName())
                .setAttribute(POLICY, start.policy())
                .setAttribute(MODE, start.inspect() ? "inspect" : "run");
        if (start.providerCallId() != null) {
            builder.setAttribute(TOOL_CALL_ID, start.providerCallId());
        }
        if (start.keyHash() != null) {
            builder.setAttribute(KEY_HASH, start.keyHash());
        }
        Span span = builder.startSpan();
        return new ActionSpan(span, span.makeCurrent(), start);
    }

    @Override
    public ToolObservation startTool(String toolName, String description, String providerCallId) {
        SpanBuilder builder = tracer.spanBuilder("execute_tool " + toolName).setSpanKind(SpanKind.INTERNAL)
                .setAttribute(OPERATION_NAME, "execute_tool")
                .setAttribute(TOOL_NAME, toolName)
                .setAttribute(TOOL_TYPE, "function");
        if (providerCallId != null) {
            builder.setAttribute(TOOL_CALL_ID, providerCallId);
        }
        if (description != null && !description.isEmpty()) {
            builder.setAttribute(TOOL_DESCRIPTION, description);
        }
        Span span = builder.startSpan();
        return new ToolSpan(span, span.makeCurrent());
    }

    @Override
    public void journalOperation(String operation, long durationNanos, boolean failed) {
        Attributes attributes = failed
                ? Attributes.of(JOURNAL_OPERATION, operation, ERROR_TYPE, "journal_error")
                : Attributes.of(JOURNAL_OPERATION, operation);
        journalDuration.record(durationNanos / 1e9, attributes);
    }

    @Override
    public void signal(ActionSignal signal, String toolName) {
        Attributes attributes = Attributes.of(TOOL_NAME, toolName);
        switch (signal) {
            case STALE_SUCCESS:
                staleSuccess.add(1, attributes);
                break;
            case LATE_RESULT_CORRECTED:
                lateResultCorrected.add(1, attributes);
                break;
            case LEASE_EXPIRED:
                leaseExpired.add(1, attributes);
                break;
            default:
                throw new IllegalArgumentException("unknown signal " + signal);
        }
    }

    @Override
    public void reconcilerResolution(ReconcilerResult result, String toolName) {
        resolutions.add(1, Attributes.of(RESULT, lowerCase(result.name()), TOOL_NAME, toolName));
    }

    @Override
    public void unresolvedActions(long unknown, long escalated) {
        unresolved.set(unknown, Attributes.of(STATE, ActionState.UNKNOWN.name()));
        unresolved.set(escalated, Attributes.of(STATE, ActionState.ESCALATED.name()));
    }

    /** {@code JournalUnavailable} → {@code journal_unavailable}. */
    static String outcomeName(ActionOutcome outcome) {
        String simpleName = outcome.getClass().getSimpleName();
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                name.append('_');
            }
            name.append(Character.toLowerCase(c));
        }
        return name.toString();
    }

    private static String lowerCase(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** The span of one action call. */
    private final class ActionSpan implements ActionObservation {

        private final Span span;
        private final Scope scope;
        private final ActionStart start;
        private int attempt;

        ActionSpan(Span span, Scope scope, ActionStart start) {
            this.span = span;
            this.scope = scope;
            this.start = start;
        }

        @Override
        public void claimed(ClaimVerdict verdict, ActionState state, int attempt) {
            this.attempt = attempt;
            span.addEvent("raa.claim", Attributes.of(VERDICT, lowerCase(verdict.name()), STATE, state.name(),
                    ATTEMPT, (long) attempt));
        }

        @Override
        public void recorded(RecordOutcome outcome, int attempt) {
            span.addEvent("raa.record", Attributes.of(RECORD_OUTCOME, lowerCase(outcome.name()), ATTEMPT,
                    (long) attempt));
        }

        @Override
        public void lookedUp(String result) {
            span.addEvent("raa.lookup", Attributes.of(LOOKUP_RESULT, result));
        }

        @Override
        public void ended(ActionOutcome outcome) {
            try {
                String name = outcomeName(outcome);
                span.setAttribute(OUTCOME, name);
                if (attempt > 0) {
                    span.setAttribute(ATTEMPT, (long) attempt);
                }
                if (ERROR_OUTCOMES.contains(name)) {
                    span.setStatus(StatusCode.ERROR);
                    span.setAttribute(ERROR_TYPE, name);
                }
                count(name);
            } finally {
                end();
            }
        }

        @Override
        public void failed(Throwable error) {
            try {
                // The type only: an exception message may contain arguments (personal data).
                span.setStatus(StatusCode.ERROR);
                span.setAttribute(ERROR_TYPE, error.getClass().getName());
                count("exception");
            } finally {
                end();
            }
        }

        private void count(String outcome) {
            if (!start.inspect()) {
                actions.add(1, Attributes.of(POLICY, start.policy(), OUTCOME, outcome));
            }
        }

        private void end() {
            scope.close();
            span.end();
        }
    }

    /** The span of one tool execution of a framework adapter. */
    private static final class ToolSpan implements ToolObservation {

        private final Span span;
        private final Scope scope;

        ToolSpan(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        @Override
        public void ended(boolean error) {
            try {
                if (error) {
                    span.setStatus(StatusCode.ERROR);
                    span.setAttribute(ERROR_TYPE, "tool_error");
                }
            } finally {
                end();
            }
        }

        @Override
        public void failed(Throwable error) {
            try {
                span.setStatus(StatusCode.ERROR);
                span.setAttribute(ERROR_TYPE, error.getClass().getName()); // no message: personal data
            } finally {
                end();
            }
        }

        private void end() {
            scope.close();
            span.end();
        }
    }
}
