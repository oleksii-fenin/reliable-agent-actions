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
import io.github.oleksiifenin.raa.storage.ManualResolution;
import io.github.oleksiifenin.raa.storage.RecordOutcome;
import io.github.oleksiifenin.raa.storage.RecordResult;
import io.github.oleksiifenin.raa.storage.Resolution;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * {@link ActionJournal} on PostgreSQL 14 or later, plain JDBC. Every operation is one short READ COMMITTED
 * transaction on a connection from the given {@link DataSource}; every time is {@code clock_timestamp()} of the
 * database. The schema is {@code io/github/oleksiifenin/raa/postgres/schema/V1__raa_action.sql} on the class path; the
 * application applies it (Flyway, Liquibase or psql).
 *
 * <p>Every operation is bounded by the operation timeout (ADR-0006, default 2.5 s), counted from the moment it asks the
 * data source for a connection. The transaction's {@code statement_timeout} and {@code lock_timeout} are set to 80 % of
 * the remaining budget, so a slow statement or a row lock held by a frozen process is normally cancelled by the
 * database while the connection stays usable; at the end of the budget the connection is aborted
 * ({@link Connection#abort}), which ends any blocked read or write. A failure, a timeout included, is a
 * {@link JournalException}; a {@code COMMIT} cut off this way may still have committed. Isolation level and timeouts
 * are transaction-local, so connections go back to the pool with their session unchanged. Create one journal per
 * database and reuse it: each has one daemon thread for the deadlines, which ends 30 s after the last operation.
 *
 * <p>The wait for a pooled connection and the set-up of a new physical connection cannot be bounded through
 * {@link DataSource}; configure them below the operation timeout (for example HikariCP {@code connectionTimeout} and
 * {@code validationTimeout}, pgjdbc {@code connectTimeout} and {@code loginTimeout}). A connection obtained after the
 * budget is used up is given back unused and the operation fails. The data source must support
 * {@link Connection#abort}.
 *
 * <p>A redacted row ({@link #redact}) keeps {@code redacted_at}; every text a later write would store there (key text,
 * arguments, result, error) is written as {@code null} by the same statement.
 */
public final class PostgresActionJournal implements ActionJournal {

    /** The operation timeout of {@link #create(DataSource)}. */
    public static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofMillis(2500);

    private static final int CLAIM_ROUNDS = 3;
    private static final String MICROS = " * interval '1 microsecond'";
    private static final String NOW = "(select clock_timestamp() as now) t";
    /** Text parameter of a write, stored only while the row is not redacted. */
    private static final String UNLESS_REDACTED = "case when redacted_at is null then ? end";
    private static final System.Logger LOG = System.getLogger(PostgresActionJournal.class.getName());
    /** Runs the abort on the watchdog thread; pgjdbc only closes the socket. */
    private static final Executor DIRECT = Runnable::run;

    private final DataSource dataSource;
    private final Duration operationTimeout;
    private final long operationTimeoutNanos;
    /** One daemon thread that aborts connections at their deadline; it ends when the journal is idle. */
    private final ScheduledThreadPoolExecutor deadlines;

    private PostgresActionJournal(DataSource dataSource, Duration operationTimeout) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.operationTimeout = Objects.requireNonNull(operationTimeout, "operationTimeout");
        if (operationTimeout.isNegative() || operationTimeout.isZero()) {
            throw new IllegalArgumentException("operationTimeout must be positive: " + operationTimeout);
        }
        this.operationTimeoutNanos = operationTimeout.toNanos();
        this.deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "raa-journal-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.deadlines.setRemoveOnCancelPolicy(true);
        this.deadlines.setKeepAliveTime(30, TimeUnit.SECONDS);
        this.deadlines.allowCoreThreadTimeOut(true);
    }

    /**
     * Creates a journal with the default operation timeout (2.5 s) on a data source
     * whose database has the schema applied.
     *
     * @param dataSource connections to the journal database; a small dedicated pool is recommended
     * @return the journal
     */
    public static PostgresActionJournal create(DataSource dataSource) {
        return new PostgresActionJournal(dataSource, DEFAULT_OPERATION_TIMEOUT);
    }

    /**
     * Creates a journal on a data source whose database has the schema applied.
     *
     * @param dataSource       connections to the journal database; a small dedicated pool is recommended
     * @param operationTimeout the bound of every journal operation, connection wait included; positive
     * @return the journal
     * @throws IllegalArgumentException if the timeout is not positive
     * @throws ArithmeticException      if the timeout does not fit in nanoseconds
     */
    public static PostgresActionJournal create(DataSource dataSource, Duration operationTimeout) {
        return new PostgresActionJournal(dataSource, operationTimeout);
    }

    /**
     * Returns the bound of every journal operation.
     *
     * @return the operation timeout
     */
    public Duration operationTimeout() {
        return operationTimeout;
    }

    // ---- claim

    @Override
    public ClaimResult claim(ClaimRequest request, ClaimDecider decider) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decider, "decider");
        return inTransaction("claim", tx -> {
            for (int round = 1; round <= CLAIM_ROUNDS; round++) {
                Optional<ActionRecord> current = lockRow(tx, request.keyHash());
                Instant now = databaseTime(tx);
                ClaimDecision decision = decider.decide(request, current, now);
                Optional<ActionRecord> written = current.isPresent()
                        ? Optional.of(apply(tx, request, decision.write(), current.get()))
                        : insertFirstAttempt(tx, request, decision);
                if (written.isPresent()) {
                    writeCallHistory(tx, request, decision, written.get());
                    return new ClaimResult(decision, written.get());
                }
                // A concurrent claim inserted the row first (or it was deleted in between): lock and decide again.
            }
            throw new JournalException("claim did not settle after " + CLAIM_ROUNDS + " rounds", null);
        });
    }

    private Optional<ActionRecord> lockRow(Transaction tx, byte[] keyHash) throws SQLException {
        String sql = "select " + columns("") + " from raa_action where key_hash = ? for update";
        try (PreparedStatement statement = tx.prepare(sql)) {
            statement.setBytes(1, keyHash);
            return singleRow(statement);
        }
    }

    private Instant databaseTime(Transaction tx) throws SQLException {
        try (PreparedStatement statement = tx.prepare("select clock_timestamp()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return instant(resultSet, 1);
        }
    }

    private Optional<ActionRecord> insertFirstAttempt(Transaction tx, ClaimRequest request,
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
        try (PreparedStatement statement = tx.prepare(sql)) {
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

    private ActionRecord apply(Transaction tx, ClaimRequest request, JournalWrite write, ActionRecord current)
            throws SQLException {
        if (write instanceof JournalWrite.None) {
            return current;
        }
        if (write instanceof JournalWrite.StartAttempt) {
            return startNextAttempt(tx, request, (JournalWrite.StartAttempt) write, current);
        }
        return changeState(tx, ((JournalWrite.ChangeState) write).state(), current);
    }

    private ActionRecord startNextAttempt(Transaction tx, ClaimRequest request, JournalWrite.StartAttempt write,
                                          ActionRecord current) throws SQLException {
        if (write.attempt() != current.attempt() + 1) {
            throw new IllegalStateException("next attempt must be " + (current.attempt() + 1) + ", got " + write);
        }
        // deadline_at and created_at are kept: a new attempt never moves the deadline.
        String sql = "update raa_action set state = 'EXECUTING', attempt = ?, lease_owner = ?,"
                + " lease_until = t.now + ?" + MICROS + ", started_at = t.now, updated_at = t.now,"
                + " tool_name = ?, policy = ?, max_effect_duration = ?" + MICROS + ","
                + " all_args_hash = ?, material_args_hash = ?,"
                + " arguments_json = case when redacted_at is null then cast(? as jsonb) end,"
                + " key_text = case when redacted_at is null then coalesce(?, key_text) end,"
                + " result_text = null, last_error = null, failure_basis = null,"
                + " lookup_failures = 0, resolve_after = null from " + NOW
                + " where key_hash = ? and attempt = ? returning " + columns("raa_action.");
        try (PreparedStatement statement = tx.prepare(sql)) {
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

    private ActionRecord changeState(Transaction tx, ActionState state, ActionRecord current)
            throws SQLException {
        String sql = "update raa_action set state = ?, lease_owner = null, lease_until = null, updated_at = t.now,"
                + " resolve_after = case when ? then t.now else null end from " + NOW
                + " where key_hash = ? and attempt = ? returning " + columns("raa_action.");
        try (PreparedStatement statement = tx.prepare(sql)) {
            statement.setString(1, state.name());
            statement.setBoolean(2, state == ActionState.UNKNOWN);
            statement.setBytes(3, current.keyHash());
            statement.setInt(4, current.attempt());
            return requireRow(singleRow(statement), "change state");
        }
    }

    private void writeCallHistory(Transaction tx, ClaimRequest request, ClaimDecision decision,
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
        try (PreparedStatement statement = tx.prepare(sql)) {
            statement.setBytes(1, request.scopeHash());
            statement.setString(2, request.providerCallId());
            statement.setBytes(3, row.keyHash());
            statement.setInt(4, row.attempt());
            statement.executeUpdate();
        }
    }

    // ---- record writes

    @Override
    public RecordResult recordSuccess(KeyAttempt attempt, String resultText) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(resultText, "resultText");
        return inTransaction("recordSuccess", tx -> {
            Optional<ActionRecord> current = lockRow(tx, attempt.keyHash());
            Optional<RecordResult> notApplicable = classifyRecordTarget(current, attempt);
            if (notApplicable.isPresent()) {
                return notApplicable.get();
            }
            String sql = "update raa_action set state = 'SUCCEEDED', result_text = " + UNLESS_REDACTED
                    + ", failure_basis = null,"
                    + " lease_owner = null, lease_until = null, resolve_after = null, updated_at = clock_timestamp()"
                    + " where key_hash = ? and attempt = ? and state <> 'SUCCEEDED' returning " + columns("");
            try (PreparedStatement statement = tx.prepare(sql)) {
                statement.setString(1, resultText);
                statement.setBytes(2, attempt.keyHash());
                statement.setInt(3, attempt.attempt());
                return applied(requireRow(singleRow(statement), "recordSuccess"), current.get());
            }
        });
    }

    @Override
    public RecordResult recordFailure(KeyAttempt attempt, FailureKind kind, String error) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(kind, "kind");
        return inTransaction("recordFailure", tx -> {
            Optional<ActionRecord> current = lockRow(tx, attempt.keyHash());
            Optional<RecordResult> notApplicable = classifyRecordTarget(current, attempt);
            if (notApplicable.isPresent()) {
                return notApplicable.get();
            }
            ActionState state = current.get().state();
            if (state != ActionState.EXECUTING && state != ActionState.UNKNOWN) {
                return new RecordResult(RecordOutcome.ALREADY_RESOLVED, current, Optional.of(state));
            }
            boolean definite = kind == FailureKind.DEFINITE;
            String sql = "update raa_action set state = ?, failure_basis = ?, last_error = " + UNLESS_REDACTED
                    + ", lease_owner = null,"
                    + " lease_until = null, resolve_after = t.now, updated_at = t.now from " + NOW
                    + " where key_hash = ? and attempt = ? and state in ('EXECUTING','UNKNOWN')"
                    + " returning " + columns("raa_action.");
            try (PreparedStatement statement = tx.prepare(sql)) {
                statement.setString(1, definite ? ActionState.FAILED.name() : ActionState.UNKNOWN.name());
                statement.setString(2, definite ? FailureBasis.DEFINITE.name() : null);
                statement.setString(3, error);
                statement.setBytes(4, attempt.keyHash());
                statement.setInt(5, attempt.attempt());
                return applied(requireRow(singleRow(statement), "recordFailure"), current.get());
            }
        });
    }

    /**
     * The outcomes shared by both record writes when the write must not land; empty if it may. Decided on the locked
     * row, so the update that follows (by key and attempt) changes exactly the row checked here.
     */
    private static Optional<RecordResult> classifyRecordTarget(Optional<ActionRecord> current, KeyAttempt attempt) {
        if (current.isEmpty() || !attempt.isOf(current.get())) {
            return Optional.of(new RecordResult(RecordOutcome.MISSING, Optional.empty(), Optional.empty()));
        }
        ActionRecord row = current.get();
        if (row.attempt() != attempt.attempt()) {
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
    public boolean markUnknownIfLeaseExpired(KeyAttempt attempt, String reason) {
        Objects.requireNonNull(attempt, "attempt");
        // Lock first, so the time below is read after any lock wait (a FROM-subquery time would be stale).
        String sql = "update raa_action set state = 'UNKNOWN', last_error = " + UNLESS_REDACTED
                + ", lease_owner = null, lease_until = null,"
                + " resolve_after = t.now, updated_at = t.now from " + NOW
                + " where key_hash = ? and created_at = ? and attempt = ? and state = 'EXECUTING' and lease_until < t.now";
        return inTransaction("markUnknownIfLeaseExpired", tx -> {
            lockRow(tx, attempt.keyHash());
            try (PreparedStatement statement = tx.prepare(sql)) {
                statement.setString(1, reason);
                statement.setBytes(2, attempt.keyHash());
                statement.setObject(3, timestamp(attempt.createdAt()));
                statement.setInt(4, attempt.attempt());
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
        // Creation times travel as microseconds since the epoch (PostgreSQL's resolution, exact in bigint).
        String match = " on a.key_hash = f.key_hash and a.attempt = f.attempt"
                + " and a.created_at = timestamptz 'epoch' + f.created_micros" + MICROS;
        String lock = "select 1 from raa_action a join unnest(?, ?, ?) as f(key_hash, attempt, created_micros)"
                + match + " order by a.key_hash for update of a";
        String sql = "update raa_action a set lease_until = t.now + ?" + MICROS + " from " + NOW
                + " join unnest(?, ?, ?) as f(key_hash, attempt, created_micros) on true"
                + " where a.key_hash = f.key_hash and a.attempt = f.attempt"
                + " and a.created_at = timestamptz 'epoch' + f.created_micros" + MICROS + " and a.state = 'EXECUTING'"
                + " and a.lease_owner = ? and t.now < a.started_at + a.max_effect_duration";
        byte[][] keyHashes = new byte[inFlight.size()][];
        Integer[] attempts = new Integer[inFlight.size()];
        Long[] created = new Long[inFlight.size()];
        int index = 0;
        for (KeyAttempt keyAttempt : inFlight) {
            keyHashes[index] = keyAttempt.keyHash();
            attempts[index] = keyAttempt.attempt();
            created[index] = epochMicros(keyAttempt.createdAt());
            index++;
        }
        return inTransaction("renewLeases", tx -> {
            Array keyArray = tx.connection().createArrayOf("bytea", keyHashes);
            Array attemptArray = tx.connection().createArrayOf("int4", attempts);
            Array createdArray = tx.connection().createArrayOf("int8", created);
            try (PreparedStatement statement = tx.prepare(lock)) {
                statement.setArray(1, keyArray);
                statement.setArray(2, attemptArray);
                statement.setArray(3, createdArray);
                statement.executeQuery().close();
            }
            try (PreparedStatement statement = tx.prepare(sql)) {
                statement.setLong(1, micros(lease));
                statement.setArray(2, keyArray);
                statement.setArray(3, attemptArray);
                statement.setArray(4, createdArray);
                statement.setString(5, owner);
                return statement.executeUpdate();
            } finally {
                keyArray.free();
                attemptArray.free();
                createdArray.free();
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
        return inTransaction("takeReconcileBatch", tx -> {
            try (PreparedStatement statement = tx.prepare(sql)) {
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
    public boolean resolve(KeyAttempt attempt, Resolution resolution) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(resolution, "resolution");
        String set;
        String sourceState = ActionState.UNKNOWN.name();
        String text = null;
        long backoffMicros = 0;
        if (resolution instanceof Resolution.Applied) {
            set = "state = 'SUCCEEDED', result_text = " + UNLESS_REDACTED + ", failure_basis = null,"
                    + " resolve_after = null";
            text = ((Resolution.Applied) resolution).resultText();
        } else if (resolution instanceof Resolution.NotApplied) {
            set = "state = 'FAILED', failure_basis = 'LOOKUP', resolve_after = t.now";
        } else if (resolution instanceof Resolution.LookupUnreachable) {
            set = "lookup_failures = lookup_failures + 1, resolve_after = t.now + ?" + MICROS;
            backoffMicros = micros(((Resolution.LookupUnreachable) resolution).backoff());
        } else if (resolution instanceof Resolution.Escalate) {
            set = "state = 'ESCALATED', last_error = " + UNLESS_REDACTED + ", resolve_after = null";
            text = ((Resolution.Escalate) resolution).reason();
        } else {
            set = "state = 'ABANDONED', last_error = " + UNLESS_REDACTED + ", resolve_after = null";
            text = ((Resolution.Abandon) resolution).reason();
            sourceState = ActionState.FAILED.name();
        }
        String sql = "update raa_action set " + set + ", updated_at = t.now from " + NOW
                + " where key_hash = ? and created_at = ? and attempt = ? and state = ?";
        String textParameter = text;
        long backoffParameter = backoffMicros;
        String sourceParameter = sourceState;
        return inTransaction("resolve", tx -> {
            try (PreparedStatement statement = tx.prepare(sql)) {
                int i = 0;
                if (textParameter != null) {
                    statement.setString(++i, textParameter);
                }
                if (resolution instanceof Resolution.LookupUnreachable) {
                    statement.setLong(++i, backoffParameter);
                }
                statement.setBytes(++i, attempt.keyHash());
                statement.setObject(++i, timestamp(attempt.createdAt()));
                statement.setInt(++i, attempt.attempt());
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
        return inTransaction("findByProviderCallId", tx -> {
            try (PreparedStatement statement = tx.prepare(sql)) {
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

    // ---- retention and operator operations

    @Override
    public int deleteTerminalOlderThan(Duration retention, int batchSize) {
        Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive: " + retention);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1: " + batchSize);
        }
        // SKIP LOCKED: a row a claim or a late write holds is left for the next batch instead of being waited for, so
        // the time read before the locks is never stale. The call history goes with the row (on delete cascade).
        String sql = "with t as (select clock_timestamp() as now), doomed as ("
                + " select key_hash from raa_action, t"
                + " where state in ('SUCCEEDED','ABANDONED') and updated_at < t.now - ?" + MICROS
                + " order by updated_at limit ? for update of raa_action skip locked)"
                + " delete from raa_action a using doomed where a.key_hash = doomed.key_hash";
        return inTransaction("deleteTerminalOlderThan", tx -> {
            try (PreparedStatement statement = tx.prepare(sql)) {
                statement.setLong(1, micros(retention));
                statement.setInt(2, batchSize);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public List<ActionRecord> find(ActionState state, int limit) {
        Objects.requireNonNull(state, "state");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        String sql = "select " + columns("") + " from raa_action where state = ? order by updated_at, key_hash limit ?";
        return inTransaction("find", tx -> {
            try (PreparedStatement statement = tx.prepare(sql)) {
                statement.setString(1, state.name());
                statement.setInt(2, limit);
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
    public long count(ActionState state) {
        Objects.requireNonNull(state, "state");
        return inTransaction("count", tx -> {
            try (PreparedStatement statement = tx.prepare("select count(*) from raa_action where state = ?")) {
                statement.setString(1, state.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getLong(1);
                }
            }
        });
    }

    @Override
    public boolean resolveManually(KeyAttempt attempt, ManualResolution resolution) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(resolution, "resolution");
        boolean applied = resolution instanceof ManualResolution.Applied;
        String sql = "update raa_action set " + (applied
                ? "state = 'SUCCEEDED', result_text = " + UNLESS_REDACTED + ", failure_basis = null"
                : "state = 'ABANDONED', last_error = " + UNLESS_REDACTED)
                + ", resolve_after = null, updated_at = clock_timestamp()"
                + " where key_hash = ? and created_at = ? and attempt = ? and state = 'ESCALATED'";
        String text = applied ? ((ManualResolution.Applied) resolution).resultText()
                : ManualResolution.NOT_APPLIED_ERROR;
        return inTransaction("resolveManually", tx -> {
            try (PreparedStatement statement = tx.prepare(sql)) {
                statement.setString(1, text);
                statement.setBytes(2, attempt.keyHash());
                statement.setObject(3, timestamp(attempt.createdAt()));
                statement.setInt(4, attempt.attempt());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean redact(byte[] keyHash) {
        Objects.requireNonNull(keyHash, "keyHash");
        String sql = "update raa_action set key_text = null, arguments_json = null, result_text = null,"
                + " last_error = null, redacted_at = coalesce(redacted_at, clock_timestamp()) where key_hash = ?";
        return inTransaction("redact", tx -> {
            try (PreparedStatement statement = tx.prepare(sql)) {
                statement.setBytes(1, keyHash);
                return statement.executeUpdate() == 1;
            }
        });
    }

    // ---- JDBC plumbing

    /** Work inside one transaction. */
    @FunctionalInterface
    private interface TransactionWork<T> {
        T run(Transaction tx) throws SQLException;
    }

    /** The connection of one operation and the end of its budget. */
    private static final class Transaction {

        private final Connection connection;
        private final long deadlineNanos;

        Transaction(Connection connection, long deadlineNanos) {
            this.connection = connection;
            this.deadlineNanos = deadlineNanos;
        }

        Connection connection() {
            return connection;
        }

        PreparedStatement prepare(String sql) throws SQLException {
            return connection.prepareStatement(sql);
        }

        /**
         * Starts the transaction: READ COMMITTED and {@code statement_timeout}/{@code lock_timeout} at 80 % of the
         * remaining budget, all transaction-local (one round trip), so the session goes back to the pool unchanged.
         */
        void begin() throws SQLException {
            connection.setAutoCommit(false);
            long millis = Math.max(1, remainingMillis() * 4 / 5); // 0 would disable the timeouts
            try (Statement statement = connection.createStatement()) {
                statement.execute("set transaction isolation level read committed; set local statement_timeout = "
                        + millis + "; set local lock_timeout = " + millis);
            }
        }

        /**
         * Rolls back, keeping the failure as the primary exception.
         *
         * @return {@code true} if no transaction is left open
         */
        boolean rollbackAfter(Exception failure) {
            try {
                if (connection.getAutoCommit()) {
                    return true; // no transaction began
                }
                connection.rollback();
                return true;
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                return false;
            }
        }

        long remainingNanos() {
            return deadlineNanos - System.nanoTime();
        }

        /** Whole milliseconds left, rounded up; zero when the budget is used up. */
        private long remainingMillis() {
            long remainingNanos = remainingNanos();
            return remainingNanos <= 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(remainingNanos + 999_999);
        }
    }

    /**
     * Runs one operation within the operation timeout. The watchdog is disarmed before the connection is closed, so it
     * never touches a connection the pool may already have handed to another thread; the close does no round trip,
     * because no transaction is left open (a failed rollback aborts the connection) and no session setting changed
     * except auto-commit, which pools reset locally.
     */
    private <T> T inTransaction(String operation, TransactionWork<T> work) {
        long deadlineNanos = System.nanoTime() + operationTimeoutNanos;
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new JournalException(operation + " failed: " + e.getMessage(), e);
        }
        Transaction tx = new Transaction(connection, deadlineNanos);
        Watchdog watchdog = null;
        T result;
        try {
            if (tx.remainingNanos() <= 0) {
                throw new SQLTimeoutException("the operation timeout of " + operationTimeout
                        + " was used up before the transaction began");
            }
            watchdog = new Watchdog(connection, tx.remainingNanos());
            tx.begin();
            result = work.run(tx);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            if (!tx.rollbackAfter(e)) {
                abortQuietly(connection, e); // never hand a connection with an open transaction back to the pool
            }
            boolean aborted = watchdog != null && watchdog.disarm();
            closeAfterFailure(connection, e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            String budget = aborted ? " (operation timeout " + operationTimeout + ": connection aborted)" : "";
            throw new JournalException(operation + " failed" + budget + ": " + e.getMessage(), e);
        }
        watchdog.disarm();
        closeAfterCommit(connection, operation);
        return result;
    }

    private static void abortQuietly(Connection connection, Exception failure) {
        try {
            connection.abort(DIRECT);
        } catch (SQLException | RuntimeException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }

    private static void closeAfterFailure(Connection connection, Exception failure) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /** The operation committed: a failure to give the connection back does not change its result. */
    private static void closeAfterCommit(Connection connection, String operation) {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, operation + " committed, but its connection could not be closed"
                    + " cleanly", e);
        }
    }

    /**
     * Aborts the operation's connection when its budget ends ({@link Connection#abort}): pgjdbc closes the socket, which
     * ends a blocked read or write. The abort runs on the deadline thread under the watchdog's lock, so once
     * {@link #disarm} returns the watchdog has finished with the connection.
     */
    private final class Watchdog {

        private final Connection connection;
        private final ScheduledFuture<?> task;
        private boolean disarmed;
        private boolean fired;

        Watchdog(Connection connection, long delayNanos) {
            this.connection = connection;
            this.task = deadlines.schedule(this::fire, delayNanos, TimeUnit.NANOSECONDS);
        }

        private synchronized void fire() {
            if (disarmed) {
                return;
            }
            fired = true;
            try {
                connection.abort(DIRECT);
            } catch (SQLException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "could not abort a journal connection at its deadline", e);
            }
        }

        /**
         * Stops the watchdog; it does not touch the connection afterwards.
         *
         * @return whether it had aborted the connection
         */
        synchronized boolean disarm() {
            disarmed = true;
            task.cancel(false);
            return fired;
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
                prefix + "last_error", prefix + "redacted_at");
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
                resultSet.getString("last_error"),
                instant(resultSet, "redacted_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet resultSet, int column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static long epochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000);
    }

    /** Microseconds (PostgreSQL's interval resolution), rounded up so a positive duration stays positive. */
    private static long micros(Duration duration) {
        return Math.addExact(duration.toNanos(), 999) / 1000;
    }
}
