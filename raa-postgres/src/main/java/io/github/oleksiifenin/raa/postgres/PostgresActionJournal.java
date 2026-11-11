package io.github.oleksiifenin.raa.postgres;

import io.github.oleksiifenin.raa.storage.ActionJournal;
import io.github.oleksiifenin.raa.storage.ActionRecord;
import io.github.oleksiifenin.raa.storage.ActionState;
import io.github.oleksiifenin.raa.storage.CallRecord;
import io.github.oleksiifenin.raa.storage.ClaimDecider;
import io.github.oleksiifenin.raa.storage.ClaimDecision;
import io.github.oleksiifenin.raa.storage.ClaimRequest;
import io.github.oleksiifenin.raa.storage.ClaimResult;
import io.github.oleksiifenin.raa.storage.FailureBasis;
import io.github.oleksiifenin.raa.storage.FailureKind;
import io.github.oleksiifenin.raa.storage.JournalException;
import io.github.oleksiifenin.raa.storage.JournalWrite;
import io.github.oleksiifenin.raa.storage.KeyAttempt;
import io.github.oleksiifenin.raa.storage.RecordOutcome;
import io.github.oleksiifenin.raa.storage.RecordResult;
import io.github.oleksiifenin.raa.storage.Resolution;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * {@link ActionJournal} on PostgreSQL 14 or later, plain JDBC. Every operation is one short READ COMMITTED
 * transaction on a connection from the given {@link DataSource}; every time is {@code clock_timestamp()} of the
 * database. The schema is {@code io/github/oleksiifenin/raa/postgres/schema/V1__raa_action.sql} on the class path; the
 * application applies it (Flyway, Liquibase or psql).
 */
public final class PostgresActionJournal implements ActionJournal {

    private static final int CLAIM_ROUNDS = 3;
    private static final String MICROS = " * interval '1 microsecond'";
    private static final String NOW = "(select clock_timestamp() as now) t";

    private final DataSource dataSource;

    private PostgresActionJournal(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Creates a journal on a data source whose database has the schema applied.
     *
     * @param dataSource connections to the journal database; a small dedicated pool is recommended
     * @return the journal
     */
    public static PostgresActionJournal create(DataSource dataSource) {
        return new PostgresActionJournal(dataSource);
    }

    // ---- claim

    @Override
    public ClaimResult claim(ClaimRequest request, ClaimDecider decider) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decider, "decider");
        return inTransaction("claim", connection -> {
            for (int round = 1; round <= CLAIM_ROUNDS; round++) {
                Optional<ActionRecord> current = lockRow(connection, request.keyHash());
                Instant now = databaseTime(connection);
                ClaimDecision decision = decider.decide(request, current, now);
                Optional<ActionRecord> written = current.isPresent()
                        ? Optional.of(apply(connection, request, decision.write(), current.get()))
                        : insertFirstAttempt(connection, request, decision);
                if (written.isPresent()) {
                    writeCallHistory(connection, request, decision, written.get());
                    return new ClaimResult(decision, written.get());
                }
                // A concurrent claim inserted the row first (or it was deleted in between): lock and decide again.
            }
            throw new JournalException("claim did not settle after " + CLAIM_ROUNDS + " rounds", null);
        });
    }

    private Optional<ActionRecord> lockRow(Connection connection, byte[] keyHash) throws SQLException {
        String sql = "select " + columns("") + " from raa_action where key_hash = ? for update";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, keyHash);
            return singleRow(statement);
        }
    }

    private Instant databaseTime(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select clock_timestamp()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return instant(resultSet, 1);
        }
    }

    private Optional<ActionRecord> insertFirstAttempt(Connection connection, ClaimRequest request,
                                                      ClaimDecision decision) throws SQLException {
        if (!(decision.write() instanceof JournalWrite.StartAttempt)
                || ((JournalWrite.StartAttempt) decision.write()).attempt() != 1) {
            throw new IllegalStateException("decider must start attempt 1 when there is no row, got " + decision);
        }
        String sql = "insert into raa_action (key_hash, key_text, tool_name, policy, state, attempt, lease_owner,"
                + " lease_until, max_effect_duration, created_at, deadline_at, started_at, updated_at,"
                + " all_args_hash, scope_hash, material_args_hash, arguments_json)"
                + " select ?, ?, ?, ?, 'EXECUTING', 1, ?, t.now + ?" + MICROS + ", ?" + MICROS + ", t.now,"
                + " t.now + ?" + MICROS + ", t.now, t.now, ?, ?, ?, cast(? as jsonb) from " + NOW
                + " on conflict (key_hash) do nothing returning " + columns("");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 0;
            statement.setBytes(++i, request.keyHash());
            statement.setString(++i, request.keyText());
            statement.setString(++i, request.toolName());
            statement.setString(++i, request.policy());
            statement.setString(++i, request.leaseOwner());
            statement.setLong(++i, micros(request.lease()));
            statement.setLong(++i, micros(request.maxEffectDuration()));
            statement.setLong(++i, micros(request.deadline()));
            statement.setBytes(++i, request.allArgsHash());
            statement.setBytes(++i, request.scopeHash());
            statement.setBytes(++i, request.materialArgsHash());
            statement.setString(++i, request.argumentsJson());
            return singleRow(statement);
        }
    }

    private ActionRecord apply(Connection connection, ClaimRequest request, JournalWrite write, ActionRecord current)
            throws SQLException {
        if (write instanceof JournalWrite.None) {
            return current;
        }
        if (write instanceof JournalWrite.StartAttempt) {
            return startNextAttempt(connection, request, (JournalWrite.StartAttempt) write, current);
        }
        return changeState(connection, ((JournalWrite.ChangeState) write).state(), current);
    }

    private ActionRecord startNextAttempt(Connection connection, ClaimRequest request, JournalWrite.StartAttempt write,
                                          ActionRecord current) throws SQLException {
        if (write.attempt() != current.attempt() + 1) {
            throw new IllegalStateException("next attempt must be " + (current.attempt() + 1) + ", got " + write);
        }
        // deadline_at and created_at are kept: a new attempt never moves the deadline.
        String sql = "update raa_action set state = 'EXECUTING', attempt = ?, lease_owner = ?,"
                + " lease_until = t.now + ?" + MICROS + ", started_at = t.now, updated_at = t.now,"
                + " tool_name = ?, policy = ?, max_effect_duration = ?" + MICROS + ","
                + " all_args_hash = ?, material_args_hash = ?, arguments_json = cast(? as jsonb),"
                + " key_text = coalesce(?, key_text), result_text = null, last_error = null, failure_basis = null,"
                + " lookup_failures = 0, resolve_after = null from " + NOW
                + " where key_hash = ? and attempt = ? returning " + columns("raa_action.");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 0;
            statement.setInt(++i, write.attempt());
            statement.setString(++i, request.leaseOwner());
            statement.setLong(++i, micros(request.lease()));
            statement.setString(++i, request.toolName());
            statement.setString(++i, request.policy());
            statement.setLong(++i, micros(request.maxEffectDuration()));
            statement.setBytes(++i, request.allArgsHash());
            statement.setBytes(++i, request.materialArgsHash());
            statement.setString(++i, request.argumentsJson());
            statement.setString(++i, request.keyText());
            statement.setBytes(++i, current.keyHash());
            statement.setInt(++i, current.attempt());
            return requireRow(singleRow(statement), "start attempt");
        }
    }

    private ActionRecord changeState(Connection connection, ActionState state, ActionRecord current)
            throws SQLException {
        String sql = "update raa_action set state = ?, lease_owner = null, lease_until = null, updated_at = t.now,"
                + " resolve_after = case when ? then t.now else null end from " + NOW
                + " where key_hash = ? and attempt = ? returning " + columns("raa_action.");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.name());
            statement.setBoolean(2, state == ActionState.UNKNOWN);
            statement.setBytes(3, current.keyHash());
            statement.setInt(4, current.attempt());
            return requireRow(singleRow(statement), "change state");
        }
    }

    private void writeCallHistory(Connection connection, ClaimRequest request, ClaimDecision decision,
                                  ActionRecord row) throws SQLException {
        if (request.providerCallId() == null) {
            return;
        }
        boolean started = decision.write() instanceof JournalWrite.StartAttempt;
        String sql = "insert into raa_action_call (scope_hash, provider_call_id, key_hash, attempt, seen_at)"
                + " values (?, ?, ?, ?, clock_timestamp()) on conflict (scope_hash, provider_call_id) "
                + (started
                        ? "do update set attempt = excluded.attempt, seen_at = excluded.seen_at"
                        + " where raa_action_call.key_hash = excluded.key_hash"
                        : "do nothing");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, request.scopeHash());
            statement.setString(2, request.providerCallId());
            statement.setBytes(3, row.keyHash());
            statement.setInt(4, row.attempt());
            statement.executeUpdate();
        }
    }

    // ---- record writes

    @Override
    public RecordResult recordSuccess(byte[] keyHash, int attempt, String resultText) {
        Objects.requireNonNull(keyHash, "keyHash");
        Objects.requireNonNull(resultText, "resultText");
        return inTransaction("recordSuccess", connection -> {
            Optional<ActionRecord> current = lockRow(connection, keyHash);
            Optional<RecordResult> notApplicable = classifyRecordTarget(current, attempt);
            if (notApplicable.isPresent()) {
                return notApplicable.get();
            }
            String sql = "update raa_action set state = 'SUCCEEDED', result_text = ?, failure_basis = null,"
                    + " lease_owner = null, lease_until = null, resolve_after = null, updated_at = clock_timestamp()"
                    + " where key_hash = ? and attempt = ? and state <> 'SUCCEEDED' returning " + columns("");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, resultText);
                statement.setBytes(2, keyHash);
                statement.setInt(3, attempt);
                return applied(requireRow(singleRow(statement), "recordSuccess"), current.get());
            }
        });
    }

    @Override
    public RecordResult recordFailure(byte[] keyHash, int attempt, FailureKind kind, String error) {
        Objects.requireNonNull(keyHash, "keyHash");
        Objects.requireNonNull(kind, "kind");
        return inTransaction("recordFailure", connection -> {
            Optional<ActionRecord> current = lockRow(connection, keyHash);
            Optional<RecordResult> notApplicable = classifyRecordTarget(current, attempt);
            if (notApplicable.isPresent()) {
                return notApplicable.get();
            }
            ActionState state = current.get().state();
            if (state != ActionState.EXECUTING && state != ActionState.UNKNOWN) {
                return new RecordResult(RecordOutcome.ALREADY_RESOLVED, current, Optional.of(state));
            }
            boolean definite = kind == FailureKind.DEFINITE;
            String sql = "update raa_action set state = ?, failure_basis = ?, last_error = ?, lease_owner = null,"
                    + " lease_until = null, resolve_after = t.now, updated_at = t.now from " + NOW
                    + " where key_hash = ? and attempt = ? and state in ('EXECUTING','UNKNOWN')"
                    + " returning " + columns("raa_action.");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, definite ? ActionState.FAILED.name() : ActionState.UNKNOWN.name());
                statement.setString(2, definite ? FailureBasis.DEFINITE.name() : null);
                statement.setString(3, error);
                statement.setBytes(4, keyHash);
                statement.setInt(5, attempt);
                return applied(requireRow(singleRow(statement), "recordFailure"), current.get());
            }
        });
    }

    /** The outcomes shared by both record writes when the write must not land; empty if it may. */
    private static Optional<RecordResult> classifyRecordTarget(Optional<ActionRecord> current, int attempt) {
        if (current.isEmpty()) {
            return Optional.of(new RecordResult(RecordOutcome.MISSING, Optional.empty(), Optional.empty()));
        }
        ActionRecord row = current.get();
        if (row.attempt() != attempt) {
            return Optional.of(new RecordResult(RecordOutcome.SUPERSEDED, current, Optional.of(row.state())));
        }
        if (row.state() == ActionState.SUCCEEDED) {
            return Optional.of(new RecordResult(RecordOutcome.ALREADY_SUCCEEDED, current, Optional.of(row.state())));
        }
        return Optional.empty();
    }

    private static RecordResult applied(ActionRecord after, ActionRecord before) {
        return new RecordResult(RecordOutcome.APPLIED, Optional.of(after), Optional.of(before.state()));
    }

    // ---- lease and reconciliation

    @Override
    public boolean markUnknownIfLeaseExpired(byte[] keyHash, int attempt, String reason) {
        Objects.requireNonNull(keyHash, "keyHash");
        // Lock first, so the time below is read after any lock wait (a FROM-subquery time would be stale).
        String sql = "update raa_action set state = 'UNKNOWN', last_error = ?, lease_owner = null, lease_until = null,"
                + " resolve_after = t.now, updated_at = t.now from " + NOW
                + " where key_hash = ? and attempt = ? and state = 'EXECUTING' and lease_until < t.now";
        return inTransaction("markUnknownIfLeaseExpired", connection -> {
            lockRow(connection, keyHash);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, reason);
                statement.setBytes(2, keyHash);
                statement.setInt(3, attempt);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public int renewLeases(String owner, Collection<KeyAttempt> inFlight, Duration lease) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(inFlight, "inFlight");
        Objects.requireNonNull(lease, "lease");
        if (inFlight.isEmpty()) {
            return 0;
        }
        // Lock the rows first (in key order, so concurrent renewers cannot deadlock), then update with the time read
        // after the locks: a renewal that waited past started_at + max_effect_duration renews nothing. A predicate
        // evaluated before a lock wait is not re-checked when the lock holder did not change the row.
        String lock = "select 1 from raa_action a join unnest(?, ?) as f(key_hash, attempt)"
                + " on a.key_hash = f.key_hash and a.attempt = f.attempt order by a.key_hash for update of a";
        String sql = "update raa_action a set lease_until = t.now + ?" + MICROS + " from " + NOW
                + ", unnest(?, ?) as f(key_hash, attempt)"
                + " where a.key_hash = f.key_hash and a.attempt = f.attempt and a.state = 'EXECUTING'"
                + " and a.lease_owner = ? and t.now < a.started_at + a.max_effect_duration";
        byte[][] keyHashes = new byte[inFlight.size()][];
        Integer[] attempts = new Integer[inFlight.size()];
        int index = 0;
        for (KeyAttempt keyAttempt : inFlight) {
            keyHashes[index] = keyAttempt.keyHash();
            attempts[index] = keyAttempt.attempt();
            index++;
        }
        return inTransaction("renewLeases", connection -> {
            Array keyArray = connection.createArrayOf("bytea", keyHashes);
            Array attemptArray = connection.createArrayOf("int4", attempts);
            try (PreparedStatement statement = connection.prepareStatement(lock)) {
                statement.setArray(1, keyArray);
                statement.setArray(2, attemptArray);
                statement.executeQuery().close();
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, micros(lease));
                statement.setArray(2, keyArray);
                statement.setArray(3, attemptArray);
                statement.setString(4, owner);
                return statement.executeUpdate();
            } finally {
                keyArray.free();
                attemptArray.free();
            }
        });
    }

    @Override
    public List<ActionRecord> takeReconcileBatch(Duration hold, int limit) {
        Objects.requireNonNull(hold, "hold");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        String sql = "with t as (select clock_timestamp() as now), picked as ("
                + " select key_hash from raa_action, t"
                + " where coalesce(resolve_after, '-infinity') <= t.now"
                + " and ((state = 'EXECUTING' and lease_until < t.now) or state = 'UNKNOWN'"
                + " or (state = 'FAILED' and deadline_at <= t.now))"
                + " order by coalesce(resolve_after, '-infinity'), key_hash limit ? for update of raa_action skip locked)"
                + " update raa_action a set resolve_after = t.now + ?" + MICROS + " from picked, t"
                + " where a.key_hash = picked.key_hash returning " + columns("a.");
        return inTransaction("takeReconcileBatch", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, limit);
                statement.setLong(2, micros(hold));
                List<ActionRecord> rows = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(actionRecord(resultSet));
                    }
                }
                return rows;
            }
        });
    }

    @Override
    public boolean resolve(byte[] keyHash, int attempt, Resolution resolution) {
        Objects.requireNonNull(keyHash, "keyHash");
        Objects.requireNonNull(resolution, "resolution");
        String set;
        String sourceState = ActionState.UNKNOWN.name();
        String text = null;
        long backoffMicros = 0;
        if (resolution instanceof Resolution.Applied) {
            set = "state = 'SUCCEEDED', result_text = ?, failure_basis = null, resolve_after = null";
            text = ((Resolution.Applied) resolution).resultText();
        } else if (resolution instanceof Resolution.NotApplied) {
            set = "state = 'FAILED', failure_basis = 'LOOKUP', resolve_after = t.now";
        } else if (resolution instanceof Resolution.LookupUnreachable) {
            set = "lookup_failures = lookup_failures + 1, resolve_after = t.now + ?" + MICROS;
            backoffMicros = micros(((Resolution.LookupUnreachable) resolution).backoff());
        } else if (resolution instanceof Resolution.Escalate) {
            set = "state = 'ESCALATED', last_error = ?, resolve_after = null";
            text = ((Resolution.Escalate) resolution).reason();
        } else {
            set = "state = 'ABANDONED', last_error = ?, resolve_after = null";
            text = ((Resolution.Abandon) resolution).reason();
            sourceState = ActionState.FAILED.name();
        }
        String sql = "update raa_action set " + set + ", updated_at = t.now from " + NOW
                + " where key_hash = ? and attempt = ? and state = ?";
        String textParameter = text;
        long backoffParameter = backoffMicros;
        String sourceParameter = sourceState;
        return inTransaction("resolve", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 0;
                if (textParameter != null) {
                    statement.setString(++i, textParameter);
                }
                if (resolution instanceof Resolution.LookupUnreachable) {
                    statement.setLong(++i, backoffParameter);
                }
                statement.setBytes(++i, keyHash);
                statement.setInt(++i, attempt);
                statement.setString(++i, sourceParameter);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public Optional<CallRecord> findByProviderCallId(byte[] scopeHash, String providerCallId) {
        Objects.requireNonNull(scopeHash, "scopeHash");
        Objects.requireNonNull(providerCallId, "providerCallId");
        String sql = "select c.provider_call_id, c.attempt as call_attempt, c.seen_at, " + columns("a.")
                + " from raa_action_call c join raa_action a on a.key_hash = c.key_hash"
                + " where c.scope_hash = ? and c.provider_call_id = ?";
        return inTransaction("findByProviderCallId", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, scopeHash);
                statement.setString(2, providerCallId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new CallRecord(resultSet.getString("provider_call_id"),
                            resultSet.getInt("call_attempt"), instant(resultSet, "seen_at"),
                            actionRecord(resultSet)));
                }
            }
        });
    }

    // ---- JDBC plumbing

    /** Work inside one transaction. */
    @FunctionalInterface
    private interface TransactionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T inTransaction(String operation, TransactionWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackAfterFailure(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new JournalException(operation + " failed: " + e.getMessage(), e);
        }
    }

    private static void rollbackAfterFailure(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static String columns(String prefix) {
        return String.join(", ",
                prefix + "key_hash", prefix + "key_text", prefix + "tool_name", prefix + "policy", prefix + "state",
                prefix + "attempt", prefix + "lease_owner", prefix + "lease_until",
                "cast(extract(epoch from " + prefix + "max_effect_duration) * 1000000 as bigint) as max_effect_micros",
                prefix + "created_at", prefix + "deadline_at", prefix + "started_at", prefix + "updated_at",
                prefix + "resolve_after", prefix + "lookup_failures", prefix + "failure_basis",
                prefix + "all_args_hash", prefix + "scope_hash", prefix + "material_args_hash",
                "cast(" + prefix + "arguments_json as text) as arguments_json", prefix + "result_text",
                prefix + "last_error");
    }

    private static Optional<ActionRecord> singleRow(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Optional.of(actionRecord(resultSet)) : Optional.empty();
        }
    }

    private static ActionRecord requireRow(Optional<ActionRecord> row, String operation) {
        return row.orElseThrow(() -> new IllegalStateException(operation + ": the locked row was not updated"));
    }

    private static ActionRecord actionRecord(ResultSet resultSet) throws SQLException {
        String failureBasis = resultSet.getString("failure_basis");
        return new ActionRecord(
                resultSet.getBytes("key_hash"),
                resultSet.getString("key_text"),
                resultSet.getString("tool_name"),
                resultSet.getString("policy"),
                ActionState.valueOf(resultSet.getString("state")),
                resultSet.getInt("attempt"),
                resultSet.getString("lease_owner"),
                instant(resultSet, "lease_until"),
                Duration.ofNanos(resultSet.getLong("max_effect_micros") * 1000),
                instant(resultSet, "created_at"),
                instant(resultSet, "deadline_at"),
                instant(resultSet, "started_at"),
                instant(resultSet, "updated_at"),
                instant(resultSet, "resolve_after"),
                resultSet.getInt("lookup_failures"),
                failureBasis == null ? null : FailureBasis.valueOf(failureBasis),
                resultSet.getBytes("all_args_hash"),
                resultSet.getBytes("scope_hash"),
                resultSet.getBytes("material_args_hash"),
                resultSet.getString("arguments_json"),
                resultSet.getString("result_text"),
                resultSet.getString("last_error"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet resultSet, int column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    /** Microseconds (PostgreSQL's interval resolution), rounded up so a positive duration stays positive. */
    private static long micros(Duration duration) {
        return Math.addExact(duration.toNanos(), 999) / 1000;
    }
}
