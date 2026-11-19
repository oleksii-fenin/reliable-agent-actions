package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.testkit.FakeDownstream;
import io.github.oleksiifenin.raa.testkit.JvmProcess;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The randomized kill of docs/DESIGN.md §9 (nightly): a child JVM is killed with SIGKILL at a random moment of a tool
 * call, between just before the call and a little after it would have finished, so it also hits the windows between
 * the fixed fault points of {@link KillMatrixTest}; a second child resumes with a new tool-call id while the reconciler
 * runs. Every run must end in one of the outcomes the expected-outcome table of §9.1 allows for its policy, plus "killed
 * before the claim committed" (nothing happened yet, so the call runs normally).
 *
 * <p>Off by default. System properties (Gradle properties in parentheses): {@code raa.randomizedKill.runs}
 * ({@code randomizedKillRuns}) runs per policy; {@code raa.randomizedKill.policies} ({@code randomizedKillPolicies})
 * comma-separated, default all four; {@code raa.randomizedKill.seed} ({@code randomizedKillSeed}), default the current
 * time, printed in every run's name for reproduction; {@code raa.randomizedKill.maxDelayMillis}
 * ({@code randomizedKillMaxDelayMillis}), default 1.5 times the median duration of three calls measured per policy
 * before its runs (the window from just before the call to after its end). Each run's name gives the seed and the
 * window that repeat it. A last check per policy, from 20 runs on, requires a fifth of the runs to have been killed
 * after the claim committed, so that a window that is too short fails instead of passing on "nothing happened yet".
 */
@EnabledIfSystemProperty(named = "raa.randomizedKill.runs", matches = "[1-9][0-9]*")
class RandomizedKillTest {

    private static final PostgresTestDatabase DATABASE = PostgresTestDatabase.get();
    private static final ActionJournal JOURNAL = PostgresActionJournal.create(DATABASE.newDataSource());
    private static final Duration CHILD_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern RESPONSE = Pattern.compile("effect (\\d+) for (.+)");
    /** How many runs ended in each allowed outcome, per policy: shows which windows the random kills hit. */
    private static final Map<String, AtomicInteger> SEEN = new ConcurrentHashMap<>();

    /** One allowed end of a run: effects applied, final state, final outcome type, whether from the lookup. */
    private record End(int effects, ActionState state, String outcome, boolean fromLookup) {
        @Override
        public String toString() {
            return effects + "/" + state + "/" + outcome + (fromLookup ? "(lookup)" : "");
        }
    }

    /**
     * The ends a run may have. If the first call's claim never committed (no row for its tool-call id), nothing
     * happened yet and the resumed call runs normally: 1 / SUCCEEDED / Succeeded only. Otherwise the cells of §9.1 for
     * the policy over all four fault points (a call that finished before the kill is {@code AFTER_RECORD_BEFORE_MEMORY}).
     */
    static Set<End> allowed(KillMatrixTest.Policy policy, boolean firstClaimCommitted) {
        End succeeded = new End(1, ActionState.SUCCEEDED, "Succeeded", false);
        if (!firstClaimCommitted) {
            return Set.of(succeeded);
        }
        End recorded = new End(1, ActionState.SUCCEEDED, "Replayed", false);
        switch (policy) {
            case IDEMPOTENT_DOWNSTREAM:
                return Set.of(succeeded, recorded); // the retry is deduplicated downstream
            case RECONCILABLE:
                return Set.of(succeeded, recorded, new End(1, ActionState.SUCCEEDED, "Replayed", true));
            case AT_MOST_ONCE: // never retried: a committed claim is escalated unless its success was recorded
                return Set.of(recorded, new End(0, ActionState.ESCALATED, "Escalated", false),
                        new End(1, ActionState.ESCALATED, "Escalated", false));
            case AT_LEAST_ONCE:
                return Set.of(succeeded, recorded, new End(2, ActionState.SUCCEEDED, "Succeeded", false));
            default:
                throw new IllegalArgumentException(policy.name());
        }
    }

    @TestFactory
    Stream<DynamicTest> killedAtARandomMoment_endsAsTheTableAllowsForItsPolicy() {
        int runs = Integer.parseInt(System.getProperty("raa.randomizedKill.runs"));
        long seed = Long.parseLong(System.getProperty("raa.randomizedKill.seed",
                Long.toString(System.currentTimeMillis())));
        String maxDelay = System.getProperty("raa.randomizedKill.maxDelayMillis");
        List<KillMatrixTest.Policy> policies = Arrays.stream(System.getProperty("raa.randomizedKill.policies",
                        "IDEMPOTENT_DOWNSTREAM,RECONCILABLE,AT_MOST_ONCE,AT_LEAST_ONCE").split(","))
                .map(String::trim).map(KillMatrixTest.Policy::valueOf).collect(Collectors.toList());
        return policies.stream().flatMap(policy -> {
            // Whole milliseconds, so that the window printed in each name reproduces the run exactly.
            long windowMillis = maxDelay != null ? Long.parseLong(maxDelay)
                    : Math.max(20, TimeUnit.NANOSECONDS.toMillis(callDuration(policy) * 3 / 2));
            AtomicInteger committed = new AtomicInteger();
            Stream<DynamicTest> kills = IntStream.range(0, runs).mapToObj(run -> {
                long runSeed = seed + 31L * run + policy.ordinal();
                // SplittableRandom mixes the seed; java.util.Random gives nearly equal first values for nearby seeds.
                long delayNanos = (long) (new SplittableRandom(runSeed).nextDouble()
                        * TimeUnit.MILLISECONDS.toNanos(windowMillis));
                // Repeat with -PrandomizedKillSeed=<seed> -PrandomizedKillMaxDelayMillis=<window>, policy and runs > #.
                String name = policy + " #" + run + " (seed " + seed + ", window " + windowMillis + " ms, kill after "
                        + TimeUnit.NANOSECONDS.toMicros(delayNanos) / 1000.0 + " ms)";
                return DynamicTest.dynamicTest(name, () -> {
                    if (killAndResume(policy, delayNanos)) {
                        committed.incrementAndGet();
                    }
                });
            });
            DynamicTest coverage = DynamicTest.dynamicTest(policy + " coverage: kills after the claim", () -> {
                assumeTrue(runs >= 20, "coverage is checked from 20 runs on");
                assertThat(committed.get()).as("runs whose first claim had committed (window " + windowMillis
                        + " ms): if few, the kills landed before the call and proved little").isGreaterThanOrEqualTo(
                        runs / 5);
            });
            return Stream.concat(kills, Stream.of(coverage));
        });
    }

    /**
     * The median of three calls of the policy that are not killed, from the announcement to the end, in nanoseconds.
     */
    private static long callDuration(KillMatrixTest.Policy policy) {
        long[] durations = new long[3];
        for (int i = 0; i < durations.length; i++) {
            String scope = "calibrate-" + policy + "-" + UUID.randomUUID();
            try (FakeDownstream downstream = FakeDownstream.start(
                    policy == KillMatrixTest.Policy.IDEMPOTENT_DOWNSTREAM, policy == KillMatrixTest.Policy.RECONCILABLE,
                    false);
                 JvmProcess first = JvmProcess.start(KillMatrixChild.class,
                         arguments("random-first", policy, downstream, scope))) {
                first.awaitLine(KillMatrixChild.CALLING, CHILD_TIMEOUT);
                long started = System.nanoTime();
                first.awaitLine(KillMatrixChild.FINAL, CHILD_TIMEOUT);
                durations[i] = System.nanoTime() - started;
                assertThat(first.awaitExit(CHILD_TIMEOUT)).isZero();
            }
        }
        Arrays.sort(durations);
        return durations[1];
    }

    @AfterAll
    static void reportWhatTheKillsHit() {
        if (!SEEN.isEmpty()) {
            Map<String, Integer> sorted = new TreeMap<>();
            SEEN.forEach((cell, count) -> sorted.put(cell, count.get()));
            System.out.println("randomized kill: ends seen " + sorted);
        }
    }

    /** One run; returns whether the first call's claim had committed. */
    private static boolean killAndResume(KillMatrixTest.Policy policy, long delayNanos) {
        String scope = "random-" + policy + "-" + UUID.randomUUID();
        try (FakeDownstream downstream = FakeDownstream.start(policy == KillMatrixTest.Policy.IDEMPOTENT_DOWNSTREAM,
                policy == KillMatrixTest.Policy.RECONCILABLE, false)) {
            boolean finishedFirst;
            try (JvmProcess first = JvmProcess.start(KillMatrixChild.class,
                    arguments("random-first", policy, downstream, scope))) {
                first.awaitLine(KillMatrixChild.CALLING, CHILD_TIMEOUT);
                long end = System.nanoTime() + delayNanos;
                while (System.nanoTime() - end < 0) {
                    LockSupport.parkNanos(end - System.nanoTime()); // the random kill moment is the fault itself
                }
                int exit = first.kill();
                finishedFirst = exit != JvmProcess.KILLED_BY_SIGKILL;
                if (finishedFirst) {
                    assertThat(exit).as("the first child finished on its own; output:\n" + first.output()).isZero();
                }
            }
            KillMatrixTest.Final result;
            try (JvmProcess resumed = JvmProcess.start(KillMatrixChild.class,
                    arguments("random-resume", policy, downstream, scope))) {
                String line = resumed.awaitLine(KillMatrixChild.FINAL, CHILD_TIMEOUT);
                assertThat(resumed.awaitExit(CHILD_TIMEOUT)).as("exit code; output:\n" + resumed.output()).isZero();
                result = KillMatrixTest.Final.parse(line);
            }

            ActionKey key = ActionKey.of(scope, KillMatrixChild.TOOL, "order-42");
            // Read after the resume: a claim whose COMMIT was in flight at the kill has landed by now.
            boolean firstClaimCommitted = JOURNAL.findByProviderCallId(key.scopeHash(), KillMatrixChild.FIRST_CALL_ID)
                    .isPresent();
            ActionRecord row = row(scope);
            String idempotencyKey = key.idempotencyKey(row.materialArgsHash());
            int effects = downstream.appliedEffects(idempotencyKey);
            End end = new End(effects, row.state(), result.outcome, result.fromLookup);
            SEEN.computeIfAbsent(policy + " " + end + (firstClaimCommitted ? "" : " (no claim)")
                    + (finishedFirst ? " (finished before the kill)" : ""), cell -> new AtomicInteger())
                    .incrementAndGet();

            assertThat(downstream.totalEffects()).as("effects under any key").isEqualTo(effects);
            assertThat(allowed(policy, firstClaimCommitted)).as("allowed ends for " + policy
                    + (firstClaimCommitted ? " after a committed claim" : " without a claim") + "; final " + result)
                    .contains(end);
            if (effects > 0) {
                assertThat(result.outcome).as("never 'not performed' after an applied effect")
                        .isNotIn("Failed", "Abandoned", "JournalUnavailable");
            }
            if (result.outcome.equals("Succeeded") || result.outcome.equals("Replayed")) {
                Matcher response = RESPONSE.matcher(result.text);
                assertThat(response.matches()).as("a response of the downstream: " + result.text).isTrue();
                assertThat(response.group(2)).as("for this action's key").isEqualTo(idempotencyKey);
                assertThat(Integer.parseInt(response.group(1))).as("one of the applied effects")
                        .isBetween(1, effects);
            }
            return firstClaimCommitted;
        }
    }

    private static List<String> arguments(String role, KillMatrixTest.Policy policy, FakeDownstream downstream,
            String scope) {
        List<String> arguments = new ArrayList<>(List.of(role, policy.name(), "NONE"));
        arguments.addAll(DATABASE.connectionArguments());
        arguments.add(downstream.uri().toString());
        arguments.add(scope);
        return arguments;
    }

    /** The action row, found through the resumed call (the first call may have claimed nothing). */
    private static ActionRecord row(String scope) {
        byte[] scopeHash = ActionKey.of(scope, KillMatrixChild.TOOL).scopeHash();
        return JOURNAL.findByProviderCallId(scopeHash, KillMatrixChild.FIRST_CALL_ID)
                .or(() -> JOURNAL.findByProviderCallId(scopeHash, "call-2"))
                .orElseThrow(() -> new AssertionError("no action row for " + scope)).action();
    }
}
