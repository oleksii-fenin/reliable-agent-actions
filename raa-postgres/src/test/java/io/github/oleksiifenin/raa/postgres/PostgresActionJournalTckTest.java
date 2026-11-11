package io.github.oleksiifenin.raa.postgres;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.tck.ActionJournalTck;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Runs the storage conformance suite against PostgreSQL. */
class PostgresActionJournalTckTest extends ActionJournalTck {

    private static final DataSource DATA_SOURCE = PostgresTestDatabase.get().newDataSource();
    private static final ActionJournal JOURNAL = PostgresActionJournal.create(DATA_SOURCE);

    @Override
    protected ActionJournal journal() {
        return JOURNAL;
    }

    @Override
    protected void awaitTransactionsWaitingForLock(int count) {
        eventually(() -> waitingForLock() >= count);
    }

    static int waitingForLock() {
        String sql = "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()";
        try (Connection connection = DATA_SOURCE.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
