package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.core.ActionKey;
import io.github.oleksiifenin.raa.core.ActionOutcome;
import io.github.oleksiifenin.raa.core.ActionPolicy;
import io.github.oleksiifenin.raa.core.LookupResult;
import io.github.oleksiifenin.raa.core.ReliableActions;
import io.github.oleksiifenin.raa.core.ToolCall;
import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The reconciler on PostgreSQL, in a database of its own: a dead attempt is detected and resolved through its lookup,
 * with every time decision taken from {@code clock_timestamp()} (the take time is {@code resolve_after - hold}).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PostgresReconcilerTest {

    private static final String SCOPE = "conversation-1";
    private static final ActionKey ORDER_42 = ActionKey.of(SCOPE, "refund", "order-42");
    // Long enough that no CI pause expires the lease by itself; the test expires it explicitly in database time.
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration MAX_EFFECT = Duration.ofSeconds(10);

    private final DataSource dataSource = PostgresTestDatabase.get().newDatabase();
    private final ActionJournal journal = PostgresActionJournal.create(dataSource);
    private final List<ActionKey> lookedUpKeys = Collections.synchronizedList(new ArrayList<>());

    @Test
    void deadAttempt_becomesUnknown_andIsResolvedByItsLookup_withoutAnotherCall() {
        ReliableActions actions = ReliableActions.builder(journal)
                .tool("refund", ActionPolicy.reconcilable((key, idempotencyKey) -> {
                    lookedUpKeys.add(key);
                    return new LookupResult.Found("refund r-1 for " + idempotencyKey);
                }).key((call, args) -> ActionKey.of(SCOPE, call.toolName(), args.string("orderId")))
                        .maxEffectDuration(MAX_EFFECT))
                .lease(LEASE).storeKeyText(true).build();
        try {
            actions.runner().run(call("c1"), context -> {
                throw new SimulatedCrash();
            });
        } catch (SimulatedCrash expected) {
            // the row stays EXECUTING, as after a crash
        }
        ActionRecord crashed = row();
        int takenWhileLeaseLive = actions.reconciler().runOnce();
        moveAttemptStartIntoThePast(crashed, MAX_EFFECT.plusSeconds(1)); // lease expired, lookup window open

        int takenExpired = actions.reconciler().runOnce();
        ActionState afterExpiry = row().state();
        int takenUnknown = actions.reconciler().runOnce();

        assertThat(crashed.state()).isEqualTo(ActionState.EXECUTING);
        assertThat(crashed.keyText()).isEqualTo(ORDER_42.toText());
        assertThat(takenWhileLeaseLive).isZero();
        assertThat(takenExpired).isEqualTo(1);
        assertThat(afterExpiry).isEqualTo(ActionState.UNKNOWN);
        assertThat(takenUnknown).isEqualTo(1);
        assertThat(lookedUpKeys).containsExactly(ORDER_42);
        ActionRecord resolved = row();
        assertThat(resolved.state()).isEqualTo(ActionState.SUCCEEDED);
        assertThat(resolved.resultText()).isEqualTo("refund r-1 for "
                + ORDER_42.idempotencyKey(resolved.materialArgsHash()));
        assertThat(actions.runner().run(call("c2"), context -> "again"))
                .isInstanceOf(ActionOutcome.Replayed.class);
    }

    private static ToolCall call(String callId) {
        Map<String, Object> arguments = Map.of("orderId", "order-42", "amount", new BigDecimal("10"));
        return new ToolCall("refund", "{\"orderId\":\"order-42\",\"amount\":10}", arguments, callId, SCOPE);
    }

    private ActionRecord row() {
        return journal.findByProviderCallId(ORDER_42.scopeHash(), "c1").orElseThrow().action();
    }

    /** Shifts the attempt's start and lease end back by {@code by}, as if that much database time had passed. */
    private void moveAttemptStartIntoThePast(ActionRecord row, Duration by) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("update raa_action set"
                     + " started_at = started_at - ? * interval '1 millisecond',"
                     + " lease_until = least(lease_until - ? * interval '1 millisecond', clock_timestamp()"
                     + " - interval '1 millisecond') where key_hash = ? and attempt = ?")) {
            statement.setLong(1, by.toMillis());
            statement.setLong(2, by.toMillis() + LEASE.toMillis());
            statement.setBytes(3, row.keyHash());
            statement.setInt(4, row.attempt());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(databaseNow()).isAfter(row().leaseUntil());
    }

    private Instant databaseNow() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select clock_timestamp()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }
}
