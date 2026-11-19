package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.testkit.FakeDownstream;
import io.github.oleksiifenin.raa.testkit.JvmProcess;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * Quality scenario Q2 (docs/DESIGN.md §2, §9 "Concurrency"): 32 threads in each of two JVMs call the same action at
 * once; the downstream deduplicates nothing, so it counts every request that reaches it. The response of the first
 * effect is held until every other call has given up waiting, which proves that all 64 calls overlapped with one live
 * attempt: exactly one effect, one {@code Succeeded}, 63 {@code InProgressElsewhere} — in both processes — and, once
 * the attempt is recorded, {@code Replayed} for a new call in each process.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TwoJvmConcurrencyTest {

    private static final PostgresTestDatabase DATABASE = PostgresTestDatabase.get();
    private static final ActionJournal JOURNAL = PostgresActionJournal.create(DATABASE.newDataSource());
    private static final int THREADS_PER_PROCESS = 32;
    private static final int CALLS = 2 * THREADS_PER_PROCESS;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @RepeatedTest(3)
    void thirtyTwoThreadsInEachOfTwoJvms_startOneEffect_andTheOthersSeeItInProgressThenReplayed() throws Exception {
        String scope = "q2-" + UUID.randomUUID();
        try (FakeDownstream downstream = FakeDownstream.start(false, false, true);
             JvmProcess first = JvmProcess.start(ConcurrencyChild.class, arguments(downstream, scope, "a"));
             JvmProcess second = JvmProcess.start(ConcurrencyChild.class, arguments(downstream, scope, "b"))) {
            Lines lines = new Lines(List.of(first, second));
            lines.await(seen -> seen.count(ConcurrencyChild.READY) == 2, "both processes ready");
            first.send("go");
            second.send("go");

            assertThat(downstream.awaitHeldResponse(TIMEOUT)).as("one effect applied, its response held").isTrue();
            lines.await(seen -> seen.count(ConcurrencyChild.OUTCOME) == CALLS - 1, "every other call gave up");
            assertThat(lines.outcomes(null)).as("while the effect is held; journal failures: "
                            + lines.texts("JournalUnavailable"))
                    .containsOnlyKeys("InProgressElsewhere").containsEntry("InProgressElsewhere", (long) CALLS - 1);
            assertThat(lines.outcomes(first)).as("calls in the first JVM").isNotEmpty();
            assertThat(lines.outcomes(second)).as("calls in the second JVM").isNotEmpty();
            assertThat(downstream.totalEffects()).as("effects while the others waited").isEqualTo(1);

            downstream.releaseHeldResponse();
            lines.await(seen -> seen.count(ConcurrencyChild.DONE) == 2, "all calls finished");
            String idempotencyKey = idempotencyKey(scope);
            assertThat(lines.outcomes(null)).containsEntry("Succeeded", 1L)
                    .containsEntry("InProgressElsewhere", (long) CALLS - 1).hasSize(2);
            assertThat(lines.texts("Succeeded")).containsExactly("effect 1 for " + idempotencyKey);

            first.send("after");
            second.send("after");
            lines.await(seen -> seen.count(ConcurrencyChild.AFTER) == 2, "a new call in each process");
            assertThat(lines.of(ConcurrencyChild.AFTER)).as("after the attempt was recorded")
                    .containsOnly("Replayed\teffect 1 for " + idempotencyKey);
            assertThat(first.awaitExit(TIMEOUT)).as("exit code; output:\n" + first.output()).isZero();
            assertThat(second.awaitExit(TIMEOUT)).as("exit code; output:\n" + second.output()).isZero();
            assertThat(downstream.totalEffects()).as("effects in total").isEqualTo(1);
            assertThat(downstream.appliedEffects(idempotencyKey)).isEqualTo(1);
        }
    }

    /** The idempotency key every process derives for the action and these arguments, from the journal row. */
    private static String idempotencyKey(String scope) {
        ActionKey key = ActionKey.of(scope, ConcurrencyChild.TOOL, "order-42");
        return key.idempotencyKey(JOURNAL.findByProviderCallId(key.scopeHash(), "call-a-0").orElseThrow().action()
                .materialArgsHash());
    }

    private static List<String> arguments(FakeDownstream downstream, String scope, String process) {
        List<String> arguments = new ArrayList<>(DATABASE.connectionArguments());
        arguments.add(downstream.uri().toString());
        arguments.add(scope);
        arguments.add(process);
        arguments.add(Integer.toString(THREADS_PER_PROCESS));
        return arguments;
    }

    /** The protocol lines of both processes, read as they come (a line of one never blocks reading the other). */
    private static final class Lines {
        private final List<JvmProcess> processes;
        private final Map<JvmProcess, List<String>> seen = new LinkedHashMap<>();

        Lines(List<JvmProcess> processes) {
            this.processes = processes;
            processes.forEach(process -> seen.put(process, new ArrayList<>()));
        }

        void await(Predicate<Lines> condition, String what) {
            long end = System.nanoTime() + TIMEOUT.toNanos();
            while (!condition.test(this)) {
                if (System.nanoTime() > end) {
                    throw new AssertionError("not reached within " + TIMEOUT + ": " + what + "; lines: " + seen);
                }
                for (JvmProcess process : processes) {
                    Optional<String> line = process.pollLine("RAA-", Duration.ofMillis(20));
                    line.ifPresent(seen.get(process)::add);
                }
            }
        }

        long count(String prefix) {
            return seen.values().stream().flatMap(List::stream).filter(line -> line.startsWith(prefix + "\t")
                    || line.equals(prefix)).count();
        }

        /** Outcome types of the {@code RAA-OUTCOME} lines, of one process or (null) of both. */
        Map<String, Long> outcomes(JvmProcess process) {
            return (process == null ? seen.values().stream().flatMap(List::stream) : seen.get(process).stream())
                    .filter(line -> line.startsWith(ConcurrencyChild.OUTCOME + "\t"))
                    .map(line -> line.split("\t", -1)[1])
                    .collect(Collectors.groupingBy(type -> type, Collectors.counting()));
        }

        List<String> texts(String type) {
            return of(ConcurrencyChild.OUTCOME).stream().filter(line -> line.startsWith(type + "\t"))
                    .map(line -> line.substring(type.length() + 1)).collect(Collectors.toList());
        }

        /** {@code type<TAB>text} of every line with this prefix. */
        List<String> of(String prefix) {
            return seen.values().stream().flatMap(List::stream).filter(line -> line.startsWith(prefix + "\t"))
                    .map(line -> line.substring(prefix.length() + 1)).collect(Collectors.toList());
        }
    }
}
