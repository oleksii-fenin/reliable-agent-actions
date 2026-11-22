package io.github.oleksiifenin.raa.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.oleksiifenin.raa.storage.ActionState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@code count} runs in every reconciler run once a telemetry is configured (docs/DESIGN.md §10), {@code find} on
 * operator request. Both must use the partial index of their state even as a generic plan, which pgjdbc's server-side
 * prepared statements reach after a few executions; a scan of the whole journal every run would grow with the
 * retention.
 */
class PostgresAdminQueryPlanTest {

    /** The partial indexes of V1 that cover each state; any of them serves {@code state = <literal>}. */
    private static final Map<ActionState, Set<String>> INDEXES = Map.of(
            ActionState.EXECUTING, Set.of("raa_action_expired_lease"),
            ActionState.SUCCEEDED, Set.of("raa_action_terminal"),
            ActionState.ABANDONED, Set.of("raa_action_terminal"),
            ActionState.FAILED, Set.of("raa_action_resolve"),
            ActionState.UNKNOWN, Set.of("raa_action_resolve", "raa_action_unresolved"),
            ActionState.ESCALATED, Set.of("raa_action_unresolved"));
    private static final Set<String> PARTIAL_INDEXES = Set.of("raa_action_expired_lease", "raa_action_terminal",
            "raa_action_resolve", "raa_action_unresolved");

    private final PostgresTestDatabase database = PostgresTestDatabase.get();

    @ParameterizedTest
    @EnumSource(ActionState.class)
    void countAndFind_useThePartialIndexOfTheirState_asAGenericPlan(ActionState state) throws SQLException {
        String count = genericPlan(PostgresActionJournal.countSql(state), "", null);
        String find = genericPlan(PostgresActionJournal.findSql(state).replace("?", "$1"), "(int)", "10");

        assertUsesOneOf(count, INDEXES.get(state));
        assertUsesOneOf(find, INDEXES.get(state));
    }

    /** The control: with the state as a parameter, the generic plan cannot use any partial index. */
    @ParameterizedTest
    @EnumSource(value = ActionState.class, names = {"UNKNOWN", "ESCALATED"})
    void stateAsAParameter_cannotUseThePartialIndexes(ActionState state) throws SQLException {
        String plan = genericPlan("select count(*) from raa_action where state = $1", "(varchar)",
                "'" + state.name() + "'");

        assertThat(PARTIAL_INDEXES).as(plan).noneMatch(plan::contains);
    }

    /** Ties the plans above to what the journal really sends. */
    @Test
    void theJournal_sendsExactlyTheSqlWhosePlansAreChecked() {
        List<String> prepared = Collections.synchronizedList(new ArrayList<>());
        PostgresActionJournal journal = PostgresActionJournal.create(recording(database.newDataSource(), prepared));

        for (ActionState state : ActionState.values()) {
            journal.count(state);
            journal.find(state, 5);
        }

        for (ActionState state : ActionState.values()) {
            assertThat(prepared).contains(PostgresActionJournal.countSql(state), PostgresActionJournal.findSql(state));
        }
        assertThat(prepared).noneMatch(sql -> sql.contains("state = ?"));
    }

    private static void assertUsesOneOf(String plan, Set<String> indexes) {
        assertThat(indexes).as(plan).anyMatch(plan::contains);
        assertThat(plan).doesNotContain("Seq Scan").doesNotContain("raa_action_pkey");
    }

    /** The plan of {@code sql} as a generic plan, with sequential scans priced out so that any usable index wins. */
    private String genericPlan(String sql, String parameterTypes, String argument) throws SQLException {
        try (Connection connection = database.newDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("set local plan_cache_mode = force_generic_plan");
            statement.execute("set local enable_seqscan = off");
            statement.execute("prepare raa_plan" + parameterTypes + " as " + sql);
            List<String> lines = new ArrayList<>();
            try (ResultSet plan = statement.executeQuery("explain execute raa_plan"
                    + (argument == null ? "" : "(" + argument + ")"))) {
                while (plan.next()) {
                    lines.add(plan.getString(1));
                }
            } finally {
                statement.execute("deallocate raa_plan");
                connection.rollback();
            }
            return String.join("\n", lines);
        }
    }

    /** A data source whose connections record the SQL of every {@code prepareStatement}. */
    private static DataSource recording(DataSource dataSource, List<String> prepared) {
        return proxy(DataSource.class, dataSource, (method, args) -> {
            if (method.getName().equals("getConnection")) {
                Connection connection = (Connection) method.invoke(dataSource, args);
                return proxy(Connection.class, connection, (connectionMethod, connectionArgs) -> {
                    if (connectionMethod.getName().equals("prepareStatement")) {
                        prepared.add((String) connectionArgs[0]);
                    }
                    return connectionMethod.invoke(connection, connectionArgs);
                });
            }
            return method.invoke(dataSource, args);
        });
    }

    private static <T> T proxy(Class<T> type, T target, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            try {
                return call.invoke(method, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }));
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(Method method, Object[] args) throws Throwable;
    }
}
