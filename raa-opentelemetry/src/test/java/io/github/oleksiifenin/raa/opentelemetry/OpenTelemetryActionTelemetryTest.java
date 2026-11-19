package io.github.oleksiifenin.raa.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.ToolCall;
import io.github.oleksiifenin.raa.core.ToolObservation;
import io.github.oleksiifenin.raa.testkit.InMemoryActionJournal;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Spans and metrics of docs/DESIGN.md §10, through the OpenTelemetry SDK with in-memory exporters (R8). */
class OpenTelemetryActionTelemetryTest {

    private static final String SCOPE = "conversation-1";
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("raa.outcome");
    private static final AttributeKey<String> POLICY = AttributeKey.stringKey("raa.policy");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .build();
    private final Tracer tracer = sdk.getTracer("test");
    private final TestClock clock = new TestClock();
    private final InMemoryActionJournal journal = new InMemoryActionJournal(clock);

    @AfterEach
    void close() {
        sdk.close();
    }

    @Test
    void actionCall_isOneSpan_childOfTheCurrentSpan_andCurrentWhileTheEffectRuns() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce());
        AtomicReference<String> spanInEffect = new AtomicReference<>();
        Span parent = tracer.spanBuilder("agent turn").startSpan();

        Scope scope = parent.makeCurrent();
        try {
            actions.runner().run(call("c1"), context -> {
                spanInEffect.set(Span.current().getSpanContext().getSpanId());
                return "refunded";
            });
        } finally {
            scope.close();
            parent.end();
        }

        SpanData action = span("reliable_action refund");
        assertThat(action.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(action.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
        assertThat(spanInEffect.get()).isEqualTo(action.getSpanId());
        assertThat(action.getAttributes().asMap()).containsAllEntriesOf(Map.of(
                TOOL_NAME, "refund",
                AttributeKey.stringKey("gen_ai.tool.call.id"), "c1",
                POLICY, ActionPolicy.atMostOnce().journalName(),
                AttributeKey.stringKey("raa.key.hash"),
                HexFormat.of().formatHex(ActionKey.of(SCOPE, "refund", "order-42").hash()),
                AttributeKey.stringKey("raa.mode"), "run",
                AttributeKey.longKey("raa.attempt"), 1L,
                OUTCOME, "succeeded"));
        assertThat(action.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(action.getEvents()).extracting(EventData::getName).containsExactly("raa.claim", "raa.record");
        assertThat(action.getEvents().get(0).getAttributes().get(AttributeKey.stringKey("raa.verdict")))
                .isEqualTo("start");
        assertThat(action.getEvents().get(1).getAttributes().get(AttributeKey.stringKey("raa.record.outcome")))
                .isEqualTo("applied");
    }

    @Test
    void actionsCounter_countsRunCallsByPolicyAndOutcome_butNotInspections() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce());
        String policy = ActionPolicy.atMostOnce().journalName();

        actions.runner().run(call("c1"), context -> "refunded");
        actions.runner().run(call("c2"), context -> "again");
        actions.runner().inspect(call("c1"));

        assertThat(counts("raa.actions", OUTCOME)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "succeeded", 1L, "replayed", 1L));
        assertThat(points("raa.actions")).allSatisfy(point ->
                assertThat(point.getAttributes().get(POLICY)).isEqualTo(policy));
        assertThat(spans.getFinishedSpanItems()).filteredOn(span -> "inspect".equals(
                span.getAttributes().get(AttributeKey.stringKey("raa.mode")))).hasSize(1);
    }

    @Test
    void notPerformedOutcome_isAnErrorSpan_withoutAKeyHash() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce());

        ActionOutcome outcome = actions.runner().run(new ToolCall("refund", "{}", Map.of(), "c1", SCOPE),
                context -> "refunded");

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        SpanData action = span("reliable_action refund");
        assertThat(action.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(action.getAttributes().get(ERROR_TYPE)).isEqualTo("failed");
        assertThat(action.getAttributes().get(AttributeKey.stringKey("raa.key.hash"))).isNull();
    }

    @Test
    void journalOperations_areRecordedInSeconds_byOperation() {
        actions(ActionPolicy.atMostOnce()).runner().run(call("c1"), context -> "refunded");

        List<HistogramPointData> points = metric("raa.journal.duration").getHistogramData().getPoints().stream()
                .collect(Collectors.toList());
        assertThat(metric("raa.journal.duration").getUnit()).isEqualTo("s");
        assertThat(points).extracting(point -> point.getAttributes().get(
                AttributeKey.stringKey("raa.journal.operation"))).containsExactlyInAnyOrder("claim", "recordSuccess");
        assertThat(points).allSatisfy(point -> {
            assertThat(point.getCount()).isEqualTo(1);
            assertThat(point.getSum()).isBetween(0.0, 5.0);
            assertThat(point.getAttributes().get(ERROR_TYPE)).isNull();
        });
    }

    @Test
    void leaseExpiry_andReconcilerResolution_areCountedPerTool() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce());

        actions.runner().run(call("c1"), context -> {
            clock.advance(LEASE.plusSeconds(1));
            actions.reconciler().runOnce();
            return "refunded";
        });

        assertThat(counts("raa.lease.expired", TOOL_NAME)).containsExactlyEntriesOf(Map.of("refund", 1L));
        assertThat(counts("raa.reconciler.resolutions", AttributeKey.stringKey("raa.result")))
                .containsExactlyEntriesOf(Map.of("marked_unknown", 1L));
    }

    @Test
    void staleSuccess_isCounted() {
        ReliableActions actions = actions(ActionPolicy.atLeastOnce());

        actions.runner().run(call("c1"), context -> {
            clock.advance(LEASE.plusSeconds(1));
            actions.runner().run(call("c2"), second -> "refunded by attempt 2");
            return "refunded by attempt 1";
        });

        assertThat(counts("raa.stale_success", TOOL_NAME)).containsExactlyEntriesOf(Map.of("refund", 1L));
    }

    @Test
    void toolExecution_isAnExecuteToolSpan_afterTheGenAiConventions_withTheActionSpanInside() {
        OpenTelemetryActionTelemetry telemetry = OpenTelemetryActionTelemetry.create(sdk);
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), telemetry);

        ToolObservation tool = telemetry.startTool("refund", "Refunds an order", "c1");
        actions.runner().run(call("c1"), context -> "refunded");
        tool.ended(true);

        SpanData execute = span("execute_tool refund");
        assertThat(execute.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(execute.getAttributes().asMap()).containsAllEntriesOf(Map.of(
                AttributeKey.stringKey("gen_ai.operation.name"), "execute_tool",
                TOOL_NAME, "refund",
                AttributeKey.stringKey("gen_ai.tool.type"), "function",
                AttributeKey.stringKey("gen_ai.tool.call.id"), "c1",
                AttributeKey.stringKey("gen_ai.tool.description"), "Refunds an order",
                ERROR_TYPE, "tool_error"));
        assertThat(execute.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span("reliable_action refund").getParentSpanId()).isEqualTo(execute.getSpanId());
        assertThat(Span.current().getSpanContext().isValid()).as("no span left current").isFalse();
    }

    @Test
    void observationFailingWhileEnding_stillEndsItsSpanAndLeavesNoSpanCurrent() {
        OpenTelemetryActionTelemetry telemetry = OpenTelemetryActionTelemetry.create(sdk);
        io.github.oleksiifenin.raa.core.ActionObservation observation = telemetry.startAction(
                new io.github.oleksiifenin.raa.core.ActionStart("refund", "AT_MOST_ONCE", "c1", null, false));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> observation.ended(null))
                .isInstanceOf(NullPointerException.class);

        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThat(spans.getFinishedSpanItems()).extracting(SpanData::getName).contains("reliable_action refund");
    }

    @Test
    void exception_recordsItsTypeOnly_neverItsMessage() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> actions.runner().run(call("c1"), context -> {
            throw new StackOverflowError("refund for jane@example.com");
        })).isInstanceOf(StackOverflowError.class);

        SpanData action = span("reliable_action refund");
        assertThat(action.getAttributes().get(ERROR_TYPE)).isEqualTo(StackOverflowError.class.getName());
        assertThat(action.getEvents()).noneMatch(event -> event.getAttributes().toString().contains("jane"));
        assertThat(action.getAttributes().toString()).doesNotContain("jane");
        assertThat(counts("raa.actions", OUTCOME)).containsExactlyEntriesOf(Map.of("exception", 1L));
    }

    @Test
    void outcomeNames_areSnakeCase() {
        assertThat(OpenTelemetryActionTelemetry.outcomeName(new ActionOutcome.JournalUnavailable("down")))
                .isEqualTo("journal_unavailable");
        assertThat(OpenTelemetryActionTelemetry.outcomeName(new ActionOutcome.InProgressElsewhere(Instant.EPOCH)))
                .isEqualTo("in_progress_elsewhere");
    }

    private ReliableActions actions(JournaledPolicy<?> policy) {
        return actions(policy, OpenTelemetryActionTelemetry.create(sdk));
    }

    private ReliableActions actions(JournaledPolicy<?> policy, OpenTelemetryActionTelemetry telemetry) {
        return ReliableActions.builder(journal)
                .tool("refund", policy
                        .key((call, args) -> ActionKey.of(SCOPE, call.toolName(), args.string("orderId")))
                        .maxEffectDuration(Duration.ofSeconds(60)))
                .lease(LEASE).inProgressWait(Duration.ofMillis(1)).telemetry(telemetry).build();
    }

    private static ToolCall call(String callId) {
        return new ToolCall("refund", "{\"orderId\":\"order-42\"}", Map.of("orderId", "order-42"), callId, SCOPE);
    }

    private SpanData span(String name) {
        List<SpanData> named = spans.getFinishedSpanItems().stream().filter(span -> span.getName().equals(name))
                .collect(Collectors.toList());
        assertThat(named).as("spans named " + name).hasSize(1);
        return named.get(0);
    }

    private MetricData metric(String name) {
        return metrics.collectAllMetrics().stream().filter(metric -> metric.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no metric " + name));
    }

    private Collection<LongPointData> points(String name) {
        return metric(name).getLongSumData().getPoints();
    }

    private Map<String, Long> counts(String name, AttributeKey<String> key) {
        return points(name).stream().collect(Collectors.toMap(point -> attribute(point.getAttributes(), key),
                LongPointData::getValue));
    }

    private static String attribute(Attributes attributes, AttributeKey<String> key) {
        return attributes.get(key);
    }

    /** A clock that moves only when a test advances it. */
    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-19T10:00:00Z"));

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
}
