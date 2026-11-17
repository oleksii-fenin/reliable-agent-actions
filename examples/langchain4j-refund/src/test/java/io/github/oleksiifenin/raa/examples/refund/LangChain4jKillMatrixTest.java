package io.github.oleksiifenin.raa.examples.refund;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.postgres.PostgresActionJournal;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The kill matrix of docs/DESIGN.md §9.1 end to end through LangChain4j (Q1): the {@link RefundAgent} with a persistent
 * chat memory is killed with SIGKILL at a confirmed fault point, and a second process resumes the conversation in mode
 * (a), retried from the start, or mode (b), memory repaired and continued. The scripted model follows the §9.1 rules;
 * effects, the final journal state and the model's final answer are compared with the table. The reconciler is off.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class LangChain4jKillMatrixTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("raa.postgres.image", "postgres:18-alpine"));
    private static final Duration CHILD_TIMEOUT = Duration.ofSeconds(30);
    private static final String ESCALATED_TEXT =
            "could not be verified automatically; an operator has been notified; do not repeat";

    private static ActionJournal journal;

    enum Policy { IDEMPOTENT_DOWNSTREAM, RECONCILABLE, AT_MOST_ONCE, AT_LEAST_ONCE }

    enum Mode {
        A("resume-a"), B("resume-b");

        final String role;

        Mode(String role) {
            this.role = role;
        }
    }

    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            for (FaultPoint point : FaultPoint.values()) {
                for (Policy policy : Policy.values()) {
                    cells.add(Arguments.of(mode, point, policy));
                }
            }
        }
        return cells.stream();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @ParameterizedTest(name = "mode {0}: {1} x {2}")
    @MethodSource("cells")
    void killedAgent_resumedByLangChain4j_endsAsTheTableSays(Mode mode, FaultPoint point, Policy policy) {
        String conversation = "kill-" + mode + "-" + point + "-" + policy + "-" + UUID.randomUUID();
        try (FakeDownstream paymentApi = FakeDownstream.start(policy == Policy.IDEMPOTENT_DOWNSTREAM,
                policy == Policy.RECONCILABLE, point == FaultPoint.EFFECT_APPLIED_RESPONSE_HELD)) {
            killAt(point, paymentApi, arguments("first", policy, point, paymentApi, conversation));
            assertThat(row(conversation).state()).as("state after the kill").isEqualTo(
                    point == FaultPoint.AFTER_RECORD_BEFORE_MEMORY ? ActionState.SUCCEEDED : ActionState.EXECUTING);
            Resumed resumed = resume(arguments(mode.role, policy, null, paymentApi, conversation));
            String answer = resumed.answer;

            ActionRecord row = row(conversation);
            String idempotencyKey = ActionKey.of(conversation, "refund", "order-42")
                    .idempotencyKey(row.materialArgsHash());
            int effects = expectedEffects(point, policy);
            assertThat(paymentApi.appliedEffects(idempotencyKey)).as("effects applied").isEqualTo(effects);
            assertThat(paymentApi.totalEffects()).as("effects under any key").isEqualTo(effects);
            assertThat(row.state()).as("final state").isEqualTo(expectedState(point, policy));
            if (expectedState(point, policy) == ActionState.ESCALATED) {
                assertThat(answer).as("final answer").isEqualTo(ESCALATED_TEXT);
            } else if (effects == 2) {
                assertThat(answer).as("final answer").isEqualTo("effect 2 for " + idempotencyKey);
            } else {
                assertThat(answer).as("final answer: the first effect's confirmation")
                        .isEqualTo(paymentApi.storedResponse(idempotencyKey).orElseThrow());
            }
            if (effects > 0) {
                assertThat(answer).as("never 'not performed' after an applied effect").doesNotStartWith("not performed");
            }
            if (mode == Mode.B) {
                // The repaired answer itself must be truthful; the final answer alone would hide a lying repair,
                // because the model's re-call is protected by the journal anyway.
                String stored = paymentApi.storedResponse(idempotencyKey).orElse(null);
                assertThat(resumed.repair).as("repair of the unanswered call").isEqualTo(
                        "ANSWERED\t" + expectedRepairText(point, policy, stored));
            } else {
                assertThat(resumed.repair).as("mode (a) repairs nothing").isNull();
            }
        }
    }

    /** docs/DESIGN.md §9.1, mode (b): the text memory repair inserts for the call the crash left unanswered. */
    private static String expectedRepairText(FaultPoint point, Policy policy, String storedResponse) {
        if (point == FaultPoint.AFTER_RECORD_BEFORE_MEMORY) {
            return storedResponse; // the recorded result
        }
        switch (policy) {
            case IDEMPOTENT_DOWNSTREAM:
                return "outcome unknown; it is safe to call this tool again with the same arguments";
            case RECONCILABLE:
                return point == FaultPoint.AFTER_CLAIM
                        ? "not performed: you may call again with the same arguments" // lookup: not found
                        : storedResponse; // lookup: found
            case AT_MOST_ONCE:
                return ESCALATED_TEXT;
            default:
                return "outcome unknown; calling again may perform it twice — repeat only if a duplicate is acceptable";
        }
    }

    /**
     * Negative control: the same agent process with the refund tool registered without the library applies the refund
     * twice when the conversation is retried; the harness must see it.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = FaultPoint.class, names = {"EFFECT_APPLIED_RESPONSE_HELD", "AFTER_RESPONSE_BEFORE_RECORD"})
    void withoutTheLibrary_aRetriedConversationRefundsTwice(FaultPoint point) {
        String conversation = "bypass-" + point + "-" + UUID.randomUUID();
        try (FakeDownstream paymentApi = FakeDownstream.start(false, false,
                point == FaultPoint.EFFECT_APPLIED_RESPONSE_HELD)) {
            killAt(point, paymentApi, arguments("bypass-first", Policy.AT_MOST_ONCE, point, paymentApi, conversation));
            String answer = resume(arguments("bypass-resume", Policy.AT_MOST_ONCE, null, paymentApi, conversation))
                    .answer;

            assertThat(paymentApi.appliedEffects("bypass-" + conversation)).as("duplicate seen").isEqualTo(2);
            assertThat(answer).isEqualTo("effect 2 for bypass-" + conversation);
        }
    }

    /** docs/DESIGN.md §9.1 (equal in modes (a) and (b)). */
    private static int expectedEffects(FaultPoint point, Policy policy) {
        if (point == FaultPoint.AFTER_CLAIM) {
            return policy == Policy.AT_MOST_ONCE ? 0 : 1;
        }
        if (point == FaultPoint.AFTER_RECORD_BEFORE_MEMORY) {
            return 1;
        }
        return policy == Policy.AT_LEAST_ONCE ? 2 : 1;
    }

    private static ActionState expectedState(FaultPoint point, Policy policy) {
        return policy == Policy.AT_MOST_ONCE && point != FaultPoint.AFTER_RECORD_BEFORE_MEMORY
                ? ActionState.ESCALATED
                : ActionState.SUCCEEDED;
    }

    private static void killAt(FaultPoint point, FakeDownstream paymentApi, List<String> arguments) {
        try (JvmProcess first = JvmProcess.start(AgentChild.class, arguments)) {
            assertThat(first.command()).as("the pid is the JVM itself")
                    .hasValueSatisfying(command -> assertThat(command).endsWith("/java"));
            if (point == FaultPoint.EFFECT_APPLIED_RESPONSE_HELD) {
                assertThat(awaitHeld(paymentApi)).as("refund applied, response held; output:\n" + first.output())
                        .isTrue();
            } else {
                first.awaitLine(FaultInjector.MARKER + point.name(), CHILD_TIMEOUT);
            }
            assertThat(first.kill()).as("killed by SIGKILL; output:\n" + first.output())
                    .isEqualTo(JvmProcess.KILLED_BY_SIGKILL);
            assertThat(first.output()).doesNotContain(AgentChild.FINAL);
        }
    }

    private static Resumed resume(List<String> arguments) {
        try (JvmProcess resumed = JvmProcess.start(AgentChild.class, arguments)) {
            String line = resumed.awaitLine(AgentChild.FINAL, CHILD_TIMEOUT);
            assertThat(resumed.awaitExit(CHILD_TIMEOUT)).as("exit code; output:\n" + resumed.output()).isZero();
            String repair = resumed.output().lines().filter(l -> l.startsWith(AgentChild.REPAIR + "\t"))
                    .map(l -> l.substring(AgentChild.REPAIR.length() + 1)).reduce((a, b) -> a + " | " + b)
                    .orElse(null);
            return new Resumed(line.substring(AgentChild.FINAL.length() + 1), repair);
        }
    }

    /** The resumed process's final answer and, in mode (b), the repair it printed. */
    private static final class Resumed {
        final String answer;
        final String repair;

        Resumed(String answer, String repair) {
            this.answer = answer;
            this.repair = repair;
        }
    }

    private static boolean awaitHeld(FakeDownstream paymentApi) {
        try {
            return paymentApi.awaitHeldResponse(CHILD_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<String> arguments(String role, Policy policy, FaultPoint point, FakeDownstream paymentApi,
            String conversation) {
        startDatabase();
        return List.of(role, policy.name(), point == null ? "NONE" : point.name(), POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), paymentApi.uri().toString(), conversation);
    }

    private static ActionRecord row(String conversation) {
        return journal.findByProviderCallId(ActionKey.of(conversation, "refund").scopeHash(), AgentChild.FIRST_CALL_ID)
                .orElseThrow().action();
    }

    private static synchronized void startDatabase() {
        if (journal != null) {
            return;
        }
        POSTGRES.start();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        // The agent installs the journal schema; build one here so the parent can read the rows.
        RefundAgent.builder(dataSource, new HttpPayments(java.net.URI.create("http://127.0.0.1:1"),
                Duration.ofSeconds(1)), new ScriptedRefundModel("unused-", "{}")).build();
        journal = PostgresActionJournal.create(dataSource);
    }
}
