package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.JournaledPolicy;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.ToolCall;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The runner end to end on PostgreSQL: claim, effect and record with database time. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PostgresActionRunnerTest {

    private static final System.Logger LOG = System.getLogger(PostgresActionRunnerTest.class.getName());
    private static final DataSource DATA_SOURCE = PostgresTestDatabase.get().newDataSource();
    private static final ActionJournal JOURNAL = PostgresActionJournal.create(DATA_SOURCE);

    private final String scope = "conversation-" + UUID.randomUUID();
    private final AtomicInteger effects = new AtomicInteger();

    @Test
    void firstCallSucceeds_aRegeneratedCallIsReplayed_andChangedArgumentsAreAConflict() {
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), Duration.ofSeconds(30), Duration.ofSeconds(10));

        long started = System.nanoTime();
        ActionOutcome first = actions.runner().run(call("c1", "10"), context -> {
            effects.incrementAndGet();
            return "refunded 10 with " + context.idempotencyKey();
        });
        LOG.log(System.Logger.Level.INFO, "Q3 (reference only): one journaled action took {0} ms",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        ActionOutcome regenerated = actions.runner().run(call("c2", "10.0"), context -> {
            effects.incrementAndGet();
            return "again";
        });
        ActionOutcome changed = actions.runner().run(call("c3", "20"), context -> {
            effects.incrementAndGet();
            return "refunded 20";
        });

        assertThat(first).isInstanceOfSatisfying(ActionOutcome.Succeeded.class,
                succeeded -> assertThat(succeeded.attempt()).isEqualTo(1));
        String recorded = ((ActionOutcome.Succeeded) first).resultText();
        assertThat(regenerated).isInstanceOfSatisfying(ActionOutcome.Replayed.class,
                replayed -> assertThat(replayed.resultText()).isEqualTo(recorded));
        assertThat(changed).isEqualTo(new ActionOutcome.Conflict(recorded, false));
        assertThat(effects).hasValue(1);
        ActionRecord row = row("c2");
        assertThat(row.state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(row.resultText()).isEqualTo(recorded);
        assertThat(row("c1").keyHash()).isEqualTo(row.keyHash());
    }

    @Test
    void renewedLease_outlivesItsFirstExpiry_whileTheEffectRuns() {
        Duration lease = Duration.ofSeconds(1);
        ReliableActions actions = actions(ActionPolicy.atMostOnce(), lease, Duration.ofSeconds(30));

        ActionOutcome outcome = actions.runner().run(call("c1", "10"), context -> {
            Instant firstLeaseEnd = row("c1").leaseUntil();
            // Renew as the scheduled renewer would, until the first lease is half a lease in the past.
            eventually(() -> actions.leaseRenewer().renewNow() == 1
                    && databaseNow().isAfter(firstLeaseEnd.plus(lease.dividedBy(2))));

            assertThat(row("c1").leaseUntil()).isAfter(firstLeaseEnd.plus(lease.dividedBy(2)));
            assertThat(JOURNAL.markUnknownIfLeaseExpired(KeyAttempt.of(row("c1")), "test")).isFalse();
            assertThat(row("c1").state()).isEqualTo(ActionState.EXECUTING);
            return "refunded";
        });

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded", 1));
    }

    @Test
    void effectOverrunningItsMaximumDuration_losesItsLease_andItsLateSuccessStillLands() {
        Duration maxEffect = Duration.ofSeconds(1);
        ReliableActions actions = actions(ActionPolicy.atLeastOnce(), Duration.ofSeconds(1), maxEffect);

        ActionOutcome outcome = actions.runner().run(call("c1", "10"), context -> {
            ActionRecord row = row("c1");
            eventually(() -> databaseNow().isAfter(row.startedAt().plus(maxEffect)));
            assertThat(actions.leaseRenewer().renewNow()).isZero();
            eventually(() -> JOURNAL.markUnknownIfLeaseExpired(KeyAttempt.of(row), "lease expired"));
            return "refunded late";
        });

        assertThat(outcome).isEqualTo(new ActionOutcome.Succeeded("refunded late", 1));
        assertThat(row("c1").state()).isEqualTo(ActionState.SUCCEEDED);
    }

    private ReliableActions actions(JournaledPolicy<?> policy, Duration lease, Duration maxEffect) {
        JournaledPolicy<?> complete = policy
                .key((call, args) -> ActionKey.of(scope, call.toolName(), args.string("orderId")))
                .maxEffectDuration(maxEffect);
        return ReliableActions.builder(JOURNAL).tool("refund", complete).lease(lease).build();
    }

    private ToolCall call(String callId, String amount) {
        Map<String, Object> arguments = Map.of("orderId", "order-42", "amount", new BigDecimal(amount));
        return new ToolCall("refund", "{\"orderId\":\"order-42\",\"amount\":" + amount + "}", arguments, callId,
                scope);
    }

    private ActionRecord row(String callId) {
        return JOURNAL.findByProviderCallId(ActionKey.of(scope, "refund").scopeHash(), callId).orElseThrow()
                .action();
    }

    private static Instant databaseNow() {
        try (Connection connection = DATA_SOURCE.getConnection();
             PreparedStatement statement = connection.prepareStatement("select clock_timestamp()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void eventually(BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition reached in time").isLessThan(end);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5)); // polling interval, not synchronization
        }
    }
}
