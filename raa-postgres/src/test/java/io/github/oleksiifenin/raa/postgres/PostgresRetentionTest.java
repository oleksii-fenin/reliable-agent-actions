package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Retention against a backlog on PostgreSQL: a full batch fits the default operation timeout (docs/DESIGN.md §7). */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PostgresRetentionTest {

    private static final int BACKLOG = 5000;
    /** The batch size of {@code Reconciler.runOnce()}. */
    private static final int BATCH = 1000;

    private final PostgresTestDatabase database = PostgresTestDatabase.get();

    @Test
    void aBacklogOfFinishedRows_isDeletedInFullBatches_withinTheDefaultOperationTimeout() throws SQLException {
        String prefix = "backlog-" + System.nanoTime() + "-";
        // Finished 8 days ago, each with a result of 2 KB and one call history row (the estimate of DESIGN §3).
        database.execute("insert into raa_action (key_hash, tool_name, policy, state, attempt, max_effect_duration,"
                + " created_at, deadline_at, started_at, updated_at, all_args_hash, scope_hash, material_args_hash,"
                + " result_text) select sha256(convert_to('" + prefix + "' || g, 'UTF8')), 'refund', 'AT_LEAST_ONCE',"
                + " 'SUCCEEDED', 1, interval '1 minute', t, t + interval '15 minutes', t, t, '\\x00', '\\x01', '\\x00',"
                + " repeat('r', 2000) from generate_series(1, " + BACKLOG + ") g,"
                + " (select clock_timestamp() - interval '8 days' as t) a");
        database.execute("insert into raa_action_call (scope_hash, provider_call_id, key_hash, attempt)"
                + " select '\\x01', '" + prefix + "' || g, sha256(convert_to('" + prefix + "' || g, 'UTF8')), 1"
                + " from generate_series(1, " + BACKLOG + ") g");
        ActionJournal journal = PostgresActionJournal.create(database.newDataSource());

        int deleted = 0;
        int batches = 0;
        int lastBatch;
        do {
            lastBatch = journal.deleteTerminalOlderThan(Duration.ofDays(7), BATCH); // throws if it ran out of time
            deleted += lastBatch;
            batches++;
        } while (lastBatch == BATCH);

        assertThat(deleted).isGreaterThanOrEqualTo(BACKLOG);
        assertThat(batches).isGreaterThanOrEqualTo(BACKLOG / BATCH);
        assertThat(count("select count(*) from raa_action where key_hash in (select sha256(convert_to('" + prefix
                + "' || g, 'UTF8')) from generate_series(1, " + BACKLOG + ") g)")).isZero();
        assertThat(count("select count(*) from raa_action_call where provider_call_id like '" + prefix + "%'"))
                .isZero();
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = database.newDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
