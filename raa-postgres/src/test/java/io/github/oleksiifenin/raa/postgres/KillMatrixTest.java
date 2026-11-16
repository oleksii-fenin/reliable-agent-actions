package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.testkit.FakeDownstream;
import io.github.oleksiifenin.raa.testkit.FaultInjector;
import io.github.oleksiifenin.raa.testkit.FaultPoint;
import io.github.oleksiifenin.raa.testkit.JvmProcess;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The kill matrix of docs/DESIGN.md §9.1, mode (a): a child JVM is killed with SIGKILL at a confirmed fault point, a
 * second child resumes by calling again with a new tool-call id and follows the scripted-model rules; effects, final
 * journal state and final outcome are compared with the expected-outcome table. The reconciler is off.
 *
 * <p>Until the {@code OutcomeRenderer} exists (roadmap week 4), the "text the model ends with" is checked as the final
 * {@code ActionOutcome} type and its result text, which the renderer turns into that text one to one.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class KillMatrixTest {

    private static final PostgresTestDatabase DATABASE = PostgresTestDatabase.get();
    private static final ActionJournal JOURNAL = PostgresActionJournal.create(DATABASE.newDataSource());
    private static final Duration CHILD_TIMEOUT = Duration.ofSeconds(30);

    enum Policy { IDEMPOTENT_DOWNSTREAM, RECONCILABLE, AT_MOST_ONCE, AT_LEAST_ONCE }

    /** One expected cell: effects applied, final state, final outcome type, and how its text relates. */
    private static final class Expected {
        final int effects;
        final ActionState state;
        final String outcome;
        final boolean fromLookup;
        /** "stored": the text is the downstream's response to the first effect; "latest": its last response. */
        final String text;

        Expected(int effects, ActionState state, String outcome, boolean fromLookup, String text) {
            this.effects = effects;
            this.state = state;
            this.outcome = outcome;
            this.fromLookup = fromLookup;
            this.text = text;
        }
    }

    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        for (FaultPoint point : FaultPoint.values()) {
            for (Policy policy : Policy.values()) {
                cells.add(Arguments.of(point, policy));
            }
        }
        return cells.stream();
    }

    /** The table of docs/DESIGN.md §9.1, mode (a). */
    private static Expected expected(FaultPoint point, Policy policy) {
        switch (point) {
            case AFTER_CLAIM:
                return policy == Policy.AT_MOST_ONCE
                        ? new Expected(0, ActionState.ESCALATED, "Escalated", false, null) // false escalation, accepted
                        : new Expected(1, ActionState.SUCCEEDED, "Succeeded", false, "stored");
            case EFFECT_APPLIED_RESPONSE_HELD:
            case AFTER_RESPONSE_BEFORE_RECORD:
                switch (policy) {
                    case IDEMPOTENT_DOWNSTREAM:
                        return new Expected(1, ActionState.SUCCEEDED, "Succeeded", false, "stored"); // deduplicated
                    case RECONCILABLE:
                        return new Expected(1, ActionState.SUCCEEDED, "Replayed", true, "stored"); // lookup text
                    case AT_MOST_ONCE:
                        return new Expected(1, ActionState.ESCALATED, "Escalated", false, null);
                    default:
                        return new Expected(2, ActionState.SUCCEEDED, "Succeeded", false, "latest"); // by definition
                }
            case AFTER_RECORD_BEFORE_MEMORY:
                return new Expected(1, ActionState.SUCCEEDED, "Replayed", false, "stored");
            default:
                throw new IllegalStateException(point.name());
        }
    }

    @ParameterizedTest(name = "{0} x {1}")
    @MethodSource("cells")
    void killedAtAFaultPoint_andResumedByANewCall_endsAsTheTableSays(FaultPoint point, Policy policy) {
        String scope = "kill-" + point + "-" + policy + "-" + UUID.randomUUID();
        boolean held = point == FaultPoint.EFFECT_APPLIED_RESPONSE_HELD;
        try (FakeDownstream downstream = FakeDownstream.start(policy == Policy.IDEMPOTENT_DOWNSTREAM,
                policy == Policy.RECONCILABLE, held)) {
            killAt(point, downstream, childArguments("first", policy.name(), point, downstream, scope));
            // The crash left the row where the fault point says, so the resume really starts from a crash.
            assertThat(row(scope).state()).as("state after the kill").isEqualTo(
                    point == FaultPoint.AFTER_RECORD_BEFORE_MEMORY ? ActionState.SUCCEEDED : ActionState.EXECUTING);
            Final result = resume(childArguments("resume", policy.name(), null, downstream, scope));

            Expected expected = expected(point, policy);
            ActionRecord row = row(scope);
            String idempotencyKey = ActionKey.of(scope, KillMatrixChild.TOOL, "order-42")
                    .idempotencyKey(row.materialArgsHash());
            assertThat(downstream.appliedEffects(idempotencyKey)).as("effects applied").isEqualTo(expected.effects);
            assertThat(downstream.totalEffects()).as("effects under any key").isEqualTo(expected.effects);
            assertThat(row.state()).as("final state").isEqualTo(expected.state);
            assertThat(result.outcome).as("final outcome: " + result).isEqualTo(expected.outcome);
            assertThat(result.fromLookup).as("result from the lookup").isEqualTo(expected.fromLookup);
            if ("stored".equals(expected.text)) {
                assertThat(result.text).as("final text").isEqualTo(downstream.storedResponse(idempotencyKey)
                        .orElseThrow());
            } else if ("latest".equals(expected.text)) {
                assertThat(result.text).as("final text").isEqualTo("effect " + expected.effects + " for "
                        + idempotencyKey);
            }
            if (expected.effects > 0) {
                assertThat(result.outcome).as("never 'not performed' after an applied effect")
                        .isNotIn("Failed", "Abandoned", "JournalUnavailable");
            }
        }
    }

    /**
     * Negative control: without the library a killed call that is called again applies its effect twice, and the
     * harness must see it. If this test ever passes with one effect, the matrix above proves nothing.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = FaultPoint.class, names = {"EFFECT_APPLIED_RESPONSE_HELD", "AFTER_RESPONSE_BEFORE_RECORD"})
    void withoutTheLibrary_aKilledCallCalledAgain_appliesItsEffectTwice(FaultPoint point) {
        String scope = "bypass-" + point + "-" + UUID.randomUUID();
        try (FakeDownstream downstream = FakeDownstream.start(false, false,
                point == FaultPoint.EFFECT_APPLIED_RESPONSE_HELD)) {
            killAt(point, downstream, childArguments("bypass-first", "AT_MOST_ONCE", point, downstream, scope));
            Final result = resume(childArguments("bypass-resume", "AT_MOST_ONCE", null, downstream, scope));

            assertThat(downstream.appliedEffects("bypass-" + scope)).as("duplicate seen by the harness").isEqualTo(2);
            assertThat(result.text).isEqualTo("effect 2 for bypass-" + scope);
        }
    }

    private static void killAt(FaultPoint point, FakeDownstream downstream, List<String> arguments) {
        try (JvmProcess first = JvmProcess.start(KillMatrixChild.class, arguments)) {
            assertThat(first.command()).as("the pid is the JVM itself, not a wrapper")
                    .hasValueSatisfying(command -> assertThat(command).endsWith("/java"));
            if (point == FaultPoint.EFFECT_APPLIED_RESPONSE_HELD) {
                assertThat(awaitHeld(downstream)).as("effect applied and response held; output:\n" + first.output())
                        .isTrue();
            } else {
                first.awaitLine(FaultInjector.MARKER + point.name(), CHILD_TIMEOUT);
            }
            // SIGKILL ended it, not its own completion (for example an HTTP timeout while the response was held).
            assertThat(first.kill()).as("exit code of the killed child; output:\n" + first.output())
                    .isEqualTo(JvmProcess.KILLED_BY_SIGKILL);
            assertThat(first.output()).as("the killed child reported no outcome").doesNotContain(KillMatrixChild.FINAL);
        }
    }

    private static Final resume(List<String> arguments) {
        try (JvmProcess resumed = JvmProcess.start(KillMatrixChild.class, arguments)) {
            String line = resumed.awaitLine(KillMatrixChild.FINAL, CHILD_TIMEOUT);
            assertThat(resumed.awaitExit(CHILD_TIMEOUT)).as("exit code; output:\n" + resumed.output()).isZero();
            return Final.parse(line);
        }
    }

    private static boolean awaitHeld(FakeDownstream downstream) {
        try {
            return downstream.awaitHeldResponse(CHILD_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<String> childArguments(String role, String policy, FaultPoint point,
            FakeDownstream downstream, String scope) {
        List<String> arguments = new ArrayList<>(List.of(role, policy, point == null ? "NONE" : point.name()));
        arguments.addAll(DATABASE.connectionArguments());
        arguments.add(downstream.uri().toString());
        arguments.add(scope);
        return arguments;
    }

    private static ActionRecord row(String scope) {
        return JOURNAL.findByProviderCallId(ActionKey.of(scope, KillMatrixChild.TOOL).scopeHash(),
                KillMatrixChild.FIRST_CALL_ID).orElseThrow().action();
    }

    /** The {@code RAA-FINAL} line of the resumed child. */
    private static final class Final {
        final String outcome;
        final boolean fromLookup;
        final String text;
        final int calls;

        private Final(String outcome, boolean fromLookup, String text, int calls) {
            this.outcome = outcome;
            this.fromLookup = fromLookup;
            this.text = text;
            this.calls = calls;
        }

        static Final parse(String line) {
            String[] fields = line.split("\t", -1);
            return new Final(fields[1], Boolean.parseBoolean(fields[2]), fields[3], Integer.parseInt(fields[4]));
        }

        @Override
        public String toString() {
            return outcome + (fromLookup ? " (from lookup)" : "") + ": " + text + " after " + calls + " call(s)";
        }
    }
}
