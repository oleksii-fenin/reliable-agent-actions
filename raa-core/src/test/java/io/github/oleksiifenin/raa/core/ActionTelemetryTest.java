package io.github.oleksiifenin.raa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.ClaimVerdict;
import io.github.oleksiifenin.raa.storage.RecordOutcome;
import io.github.oleksiifenin.raa.testkit.InMemoryActionJournal;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** What the runtime reports to {@link ActionTelemetry} (docs/DESIGN.md §10), and that telemetry cannot break actions. */
class ActionTelemetryTest {

    private static final String SCOPE = "conversation-1";
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final ActionKey ORDER_42 = ActionKey.of(SCOPE, "refund", "order-42");

    private final MutableClock clock = new MutableClock();
    private final FaultyJournal journal = new FaultyJournal(new InMemoryActionJournal(clock));
    private final Recording telemetry = new Recording();
    private final AtomicInteger effects = new AtomicInteger();

    @Test
    void firstCall_isObservedFromStartToEnd_withItsClaimRecordAndJournalOperations() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), telemetry);

        ActionOutcome outcome = actions.runner().run(call("c1"), this::refund);

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        String policy = ActionPolicy.atMostOnce().journalName();
        assertThat(telemetry.actionEvents()).containsExactly(
                "start refund " + policy + " c1 " + HexFormat.of().formatHex(ORDER_42.hash()) + " run",
                "claimed START EXECUTING 1",
                "recorded APPLIED 1",
                "ended Succeeded");
        assertThat(telemetry.journalEvents()).containsExactly("journal claim ok", "journal recordSuccess ok");
    }

    @Test
    void repeatedCall_isObservedAsAReplay() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), telemetry);
        actions.runner().run(call("c1"), this::refund);
        telemetry.events.clear();

        actions.runner().run(call("c2"), this::refund);

        assertThat(telemetry.actionEvents()).endsWith("claimed REPLAY SUCCEEDED 1", "ended Replayed");
    }

    @Test
    void invalidArguments_areObservedWithoutAKey_andNothingIsClaimed() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), telemetry);

        actions.runner().run(new ToolCall("refund", "{}", Map.of(), "c1", SCOPE), this::refund);

        assertThat(telemetry.actionEvents()).containsExactly(
                "start refund " + ActionPolicy.atMostOnce().journalName() + " c1 null run", "ended Failed");
        assertThat(telemetry.journalEvents()).isEmpty();
    }

    @Test
    void journalFailure_isAFailedOperation_andTheCallEndsJournalUnavailable() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), telemetry);
        journal.failClaims.set(true);

        actions.runner().run(call("c1"), this::refund);

        assertThat(telemetry.journalEvents()).containsExactly("journal claim failed");
        assertThat(telemetry.actionEvents()).endsWith("ended JournalUnavailable");
    }

    @Test
    void inspect_isObservedAsInspect() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), telemetry);
        actions.runner().run(call("c1"), this::refund);
        telemetry.events.clear();

        actions.runner().inspect(call("c1"));

        assertThat(telemetry.actionEvents().get(0)).endsWith(" inspect");
        assertThat(telemetry.actionEvents()).endsWith("ended Replayed");
    }

    @Test
    void leaseExpiredInTheReconciler_isSignalled_andTheLateSuccessOfTheSameAttemptIsRecorded() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), telemetry);

        actions.runner().run(call("c1"), context -> {
            clock.advance(LEASE.plusSeconds(1));
            actions.reconciler().runOnce();
            return refund(context);
        });

        assertThat(telemetry.events).contains("signal LEASE_EXPIRED refund", "resolution MARKED_UNKNOWN refund",
                "recorded APPLIED 1");
    }

    @Test
    void attemptFinishingAfterANewerOne_isAStaleSuccess_andTheExpiredLeaseIsSignalledByTheClaim() {
        ReliableActions actions = actions(ActionPolicy.atLeastOnce(), telemetry);

        actions.runner().run(call("c1"), context -> {
            clock.advance(LEASE.plusSeconds(1));
            assertThat(actions.runner().run(call("c2"), this::refund)).isEqualTo(new ActionOutcome.Succeeded(
                    "refunded", 2));
            return refund(context);
        });

        assertThat(telemetry.events).contains("signal LEASE_EXPIRED refund", "recorded SUPERSEDED 1",
                "signal STALE_SUCCESS refund");
    }

    /** Row 6 leading to row 7 is a conflict, but the expired lease still moved the row to UNKNOWN. */
    @Test
    void expiredLeaseFollowedByAConflict_isStillSignalled() {
        ReliableActions actions = actions(ActionPolicy.idempotentDownstream(Duration.ofHours(1))
                .freeTextArguments("note"), telemetry);

        actions.runner().run(call("c1", "first"), context -> {
            clock.advance(LEASE.plusSeconds(1));
            ActionOutcome other = actions.runner().run(call("c2", "second"), this::refund);
            assertThat(other).isInstanceOf(ActionOutcome.Conflict.class);
            return refund(context);
        });

        assertThat(telemetry.events).containsOnlyOnce("signal LEASE_EXPIRED refund");
    }

    @Test
    void guardedObservation_isEndedAtMostOnce() {
        AtomicInteger ends = new AtomicInteger();
        ActionTelemetry counting = new ActionTelemetry() {
            @Override
            public ActionObservation startAction(ActionStart start) {
                return new ActionObservation() {
                    @Override
                    public void ended(ActionOutcome outcome) {
                        ends.incrementAndGet();
                    }

                    @Override
                    public void failed(Throwable error) {
                        ends.incrementAndGet();
                    }
                };
            }
        };
        ActionObservation observation = GuardedTelemetry.guard(counting)
                .startAction(new ActionStart("refund", "AT_MOST_ONCE", null, null, false));

        observation.ended(new ActionOutcome.Failed("x", true));
        observation.ended(new ActionOutcome.Failed("x", true));
        observation.failed(new IllegalStateException());

        assertThat(ends).hasValue(1);
    }

    @Test
    void telemetryMissingAClass_isCaughtToo() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), new ActionTelemetry() {
            @Override
            public ActionObservation startAction(ActionStart start) {
                throw new NoClassDefFoundError("io/opentelemetry/api/trace/Span");
            }
        });

        assertThat(actions.runner().run(call("c1"), this::refund)).isEqualTo(new ActionOutcome.Succeeded("refunded",
                1));
    }

    @Test
    void telemetryThatThrowsEverywhere_neverChangesWhatTheActionDoes() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), new Throwing());

        ActionOutcome first = actions.runner().run(call("c1"), this::refund);
        ActionOutcome second = actions.runner().run(call("c2"), this::refund);

        assertThat(first).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
        assertThat(second).isInstanceOf(ActionOutcome.Replayed.class);
        assertThat(effects).hasValue(1);
    }

    @Test
    void reconcilerRun_reportsTheUnresolvedActions_andItsCountsAreJournalOperations() {
        ReliableActions actions = actions(ActionPolicy.atLeastOnce(), telemetry);
        actions.runner().run(call("c1"), context -> {
            throw new IOException("connection reset"); // indeterminate: UNKNOWN
        });
        telemetry.events.clear();

        actions.reconciler().runOnce();

        assertThat(telemetry.events).contains("journal count ok", "unresolved 1 0");
    }

    @Test
    void telemetryThatThrowsFromTheGauge_neverBreaksTheReconcilerRun() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), new Throwing());
        actions.runner().run(call("c1"), context -> {
            throw new IOException("connection reset");
        });

        actions.reconciler().runOnce(); // would throw if the guard let the gauge's exception through

        assertThat(journal.countCalls).hasValue(2);
    }

    @Test
    void errorThrownThroughTheRunner_isReportedAsFailed_andRethrown() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), telemetry);

        assertThatThrownBy(() -> actions.runner().run(call("c1"), context -> {
            throw new StackOverflowError("effect");
        })).isInstanceOf(StackOverflowError.class);
        assertThat(telemetry.actionEvents()).endsWith("failed StackOverflowError");
    }

    @Test
    void noTelemetry_isTheDefault_andTheJournalIsNotWrapped() {
        ReliableActions actions = ReliableActions.builder(journal)
                .tool("refund", complete(ActionPolicy.atMostOnce())).build();

        assertThat(actions.telemetry()).isSameAs(ActionTelemetry.none());
        assertThat(ObservedJournal.observe(journal, ActionTelemetry.none())).isSameAs(journal);
    }

    private String refund(ActionContext context) {
        effects.incrementAndGet();
        return "refunded";
    }

    private ReliableActions actions(JournaledPolicy<?> policy, ActionTelemetry actionTelemetry) {
        return ReliableActions.builder(journal).tool("refund", complete(policy)).lease(LEASE)
                .inProgressWait(Duration.ofMillis(1)).telemetry(actionTelemetry).build();
    }

    private static JournaledPolicy<?> complete(JournaledPolicy<?> policy) {
        JournaledPolicy<?> keyed = policy.keyStrategy().isPresent() ? policy
                : policy.key((call, args) -> ActionKey.of(SCOPE, call.toolName(), args.string("orderId")));
        return keyed.maxEffectDuration(Duration.ofSeconds(60));
    }

    private static ToolCall call(String callId) {
        return new ToolCall("refund", "{\"orderId\":\"order-42\"}", Map.of("orderId", "order-42"), callId, SCOPE);
    }

    private static ToolCall call(String callId, String note) {
        Map<String, Object> arguments = Map.of("orderId", "order-42", "note", note);
        return new ToolCall("refund", arguments.toString(), arguments, callId, SCOPE);
    }

    /** Writes every hook call as a line. */
    private static final class Recording implements ActionTelemetry {

        final List<String> events = Collections.synchronizedList(new ArrayList<>());

        List<String> actionEvents() {
            return events.stream().filter(event -> !event.startsWith("journal ") && !event.startsWith("signal ")
                    && !event.startsWith("resolution ") && !event.startsWith("unresolved ")).toList();
        }

        List<String> journalEvents() {
            return events.stream().filter(event -> event.startsWith("journal ")).toList();
        }

        @Override
        public ActionObservation startAction(ActionStart start) {
            events.add("start " + start.toolName() + " " + start.policy() + " " + start.providerCallId() + " "
                    + start.keyHash() + (start.inspect() ? " inspect" : " run"));
            return new ActionObservation() {
                @Override
                public void claimed(ClaimVerdict verdict, ActionState state, int attempt) {
                    events.add("claimed " + verdict + " " + state + " " + attempt);
                }

                @Override
                public void recorded(RecordOutcome outcome, int attempt) {
                    events.add("recorded " + outcome + " " + attempt);
                }

                @Override
                public void lookedUp(String result) {
                    events.add("lookedUp " + result);
                }

                @Override
                public void ended(ActionOutcome outcome) {
                    events.add("ended " + outcome.getClass().getSimpleName());
                }

                @Override
                public void failed(Throwable error) {
                    events.add("failed " + error.getClass().getSimpleName());
                }
            };
        }

        @Override
        public void journalOperation(String operation, long durationNanos, boolean failed) {
            assertThat(durationNanos).isNotNegative();
            events.add("journal " + operation + (failed ? " failed" : " ok"));
        }

        @Override
        public void signal(ActionSignal signal, String toolName) {
            events.add("signal " + signal + " " + toolName);
        }

        @Override
        public void reconcilerResolution(ReconcilerResult result, String toolName) {
            events.add("resolution " + result + " " + toolName);
        }

        @Override
        public void unresolvedActions(long unknown, long escalated) {
            events.add("unresolved " + unknown + " " + escalated);
        }
    }

    /** Fails in every hook. */
    private static final class Throwing implements ActionTelemetry {

        @Override
        public ActionObservation startAction(ActionStart start) {
            return new ActionObservation() {
                @Override
                public void claimed(ClaimVerdict verdict, ActionState state, int attempt) {
                    throw new IllegalStateException("claimed");
                }

                @Override
                public void recorded(RecordOutcome outcome, int attempt) {
                    throw new IllegalStateException("recorded");
                }

                @Override
                public void ended(ActionOutcome outcome) {
                    throw new IllegalStateException("ended");
                }
            };
        }

        @Override
        public void journalOperation(String operation, long durationNanos, boolean failed) {
            throw new IllegalStateException("journalOperation");
        }

        @Override
        public void signal(ActionSignal signal, String toolName) {
            throw new IllegalStateException("signal");
        }

        @Override
        public void unresolvedActions(long unknown, long escalated) {
            throw new IllegalStateException("unresolvedActions");
        }
    }
}
