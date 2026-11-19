package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.DecisionTable;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ClaimRequest;
import io.github.oleksiifenin.raa.storage.ClaimVerdict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Quality scenario Q2 (docs/DESIGN.md §2) at the storage level: 32 threads in each of two "processes" (two journals
 * with their own data source and owner id) claim one key at the same moment; exactly one attempt starts.
 * The two-JVM variant through the runner is {@link TwoJvmConcurrencyTest}.
 */
class ConcurrentClaimTest {

    private static final int THREADS_PER_PROCESS = 32;

    private final PostgresTestDatabase database = PostgresTestDatabase.get();

    @RepeatedTest(5)
    void sixtyFourConcurrentClaimsOfOneKey_startExactlyOneAttempt() throws Exception {
        ActionKey key = ActionKey.of("conversation-q2-" + System.nanoTime(), "refund", "order-42");
        List<ActionJournal> processes = List.of(PostgresActionJournal.create(database.newDataSource()),
                PostgresActionJournal.create(database.newDataSource()));
        DecisionTable table = new DecisionTable(ActionPolicy.atLeastOnce()
                .key((call, args) -> key).maxEffectDuration(Duration.ofSeconds(30)));
        ExecutorService executor = Executors.newFixedThreadPool(2 * THREADS_PER_PROCESS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClaimVerdict>> claims = new ArrayList<>();
        try {
            for (int process = 0; process < processes.size(); process++) {
                ActionJournal journal = processes.get(process);
                for (int thread = 0; thread < THREADS_PER_PROCESS; thread++) {
                    String owner = "process-" + process;
                    String callId = "call-" + process + "-" + thread;
                    claims.add(executor.submit(() -> {
                        start.await();
                        return journal.claim(request(key, owner, callId), table).decision().verdict();
                    }));
                }
            }
            start.countDown();
            List<ClaimVerdict> verdicts = new ArrayList<>();
            for (Future<ClaimVerdict> claim : claims) {
                verdicts.add(claim.get(60, TimeUnit.SECONDS));
            }

            Map<ClaimVerdict, Long> counts = verdicts.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            assertThat(counts).containsOnlyKeys(ClaimVerdict.START, ClaimVerdict.IN_PROGRESS)
                    .containsEntry(ClaimVerdict.START, 1L)
                    .containsEntry(ClaimVerdict.IN_PROGRESS, 2L * THREADS_PER_PROCESS - 1);
            assertThat(callIdsMappedTo(key)).isEqualTo(2 * THREADS_PER_PROCESS);
        } finally {
            executor.shutdownNow();
        }
    }

    private int callIdsMappedTo(ActionKey key) {
        int mapped = 0;
        ActionJournal journal = PostgresActionJournal.create(database.newDataSource());
        for (int process = 0; process < 2; process++) {
            for (int thread = 0; thread < THREADS_PER_PROCESS; thread++) {
                if (journal.findByProviderCallId(key.scopeHash(), "call-" + process + "-" + thread).isPresent()) {
                    mapped++;
                }
            }
        }
        return mapped;
    }

    private static ClaimRequest request(ActionKey key, String owner, String callId) {
        byte[] arguments = ActionKey.of("args", "refund", "order-42", "10.00").hash();
        return new ClaimRequest(key.hash(), key.scopeHash(), null, "refund", "AT_LEAST_ONCE", arguments, arguments,
                null, callId, owner, Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(15));
    }
}
