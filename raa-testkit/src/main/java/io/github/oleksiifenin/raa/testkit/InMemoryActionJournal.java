package io.github.oleksiifenin.raa.testkit;

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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An in-memory action journal for tests, with per-key transaction locks and PostgreSQL's microsecond precision.
 * The supplied clock is the journal's database clock; decisions sample it after acquiring their row locks.
 * Rows and call history live only as long as this instance and are not durable across process termination.
 * Retention removes a row under its lock and marks it deleted, so an operation that found it before and locks it
 * afterwards treats it as missing.
 */
public final class InMemoryActionJournal implements ActionJournal {

    private static final int CLAIM_ROUNDS = 3;

    private final Clock clock;
    private final ConcurrentHashMap<String, Row> rows = new ConcurrentHashMap<>();
    private final Map<CallKey, History> history = new HashMap<>();
    // Publication only: no decider or row-lock acquisition runs while this monitor is held.
    private final Object commitMonitor = new Object();

    /** Creates an empty journal whose database clock is {@link Clock#systemUTC()}. */
    public InMemoryActionJournal() {
        this(Clock.systemUTC());
    }

    /**
     * Creates an empty journal using the given clock for every database time.
     *
     * @param clock the journal's database clock
     */
    public InMemoryActionJournal(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns an estimate of the threads currently queued for this journal's row locks.
     *
     * @return the sum of the per-key lock queue lengths
     */
    public int transactionsWaitingForLock() {
        return rows.values().stream().mapToInt(row -> row.lock.getQueueLength()).sum();
    }

    @Override
    public ClaimResult claim(ClaimRequest request, ClaimDecider decider) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decider, "decider");
        String key = hash(request.keyHash());
        for (int round = 0; round < CLAIM_ROUNDS; round++) {
            Row row = rows.get(key);
            if (row == null) {
                // There is no row lock yet: two first deciders must be able to run together.
                ClaimDecision decision = decider.decide(request, Optional.empty(), now());
                if (!(decision.write() instanceof JournalWrite.StartAttempt)
                        || ((JournalWrite.StartAttempt) decision.write()).attempt() != 1) {
                    throw new IllegalStateException("decider must start attempt 1 when there is no row, got " + decision);
                }
                Row candidate = new Row();
                candidate.lock.lock();
                try {
                    ActionRecord created = firstAttempt(request);
                    if (commitClaim(key, candidate, created, request, decision, true)) {
                        return new ClaimResult(decision, created);
                    }
                } finally {
                    candidate.lock.unlock();
                }
            } else {
                row.lock.lock();
                try {
                    if (row.deleted) {
                        continue; // retention removed it meanwhile: decide again without a row
                    }
                    ClaimDecision decision = decider.decide(request, Optional.of(row.record), now());
                    ActionRecord updated = applyClaim(request, row.record, decision.write());
                    commitClaim(key, row, updated, request, decision, false);
                    return new ClaimResult(decision, updated);
                } finally {
                    row.lock.unlock();
                }
            }
        }
        throw new JournalException("claim did not settle after " + CLAIM_ROUNDS + " rounds", null);
    }

    private ActionRecord firstAttempt(ClaimRequest request) {
        validateAttemptText(request);
        String arguments = jsonText(request.argumentsJson());
        Instant time = now();
        return new ActionRecord(request.keyHash().clone(), request.keyText(), columnText(request.toolName(), 200),
                columnText(request.policy(), 32), ActionState.EXECUTING, 1, columnText(request.leaseOwner(), 64),
                time.plus(interval(request.lease())),
                interval(request.maxEffectDuration()), time, time.plus(interval(request.deadline())), time, time,
                null, 0, null, request.allArgsHash().clone(), request.scopeHash().clone(),
                request.materialArgsHash().clone(), arguments, null, null, null);
    }

    private ActionRecord applyClaim(ClaimRequest request, ActionRecord current, JournalWrite write) {
        if (write instanceof JournalWrite.None) {
            return current;
        }
        Update update = new Update(current);
        if (write instanceof JournalWrite.StartAttempt) {
            int attempt = ((JournalWrite.StartAttempt) write).attempt();
            if (attempt != current.attempt() + 1) {
                throw new IllegalStateException("next attempt must be " + (current.attempt() + 1) + ", got " + write);
            }
            validateAttemptText(request);
            update.argumentsJson = jsonText(request.argumentsJson());
            Instant time = now();
            update.state = ActionState.EXECUTING;
            update.attempt = attempt;
            update.keyText = request.keyText() == null ? current.keyText() : request.keyText();
            update.toolName = columnText(request.toolName(), 200);
            update.policy = columnText(request.policy(), 32);
            update.leaseOwner = columnText(request.leaseOwner(), 64);
            update.leaseUntil = time.plus(interval(request.lease()));
            update.maxEffectDuration = interval(request.maxEffectDuration());
            update.startedAt = time;
            update.updatedAt = time;
            update.allArgsHash = request.allArgsHash().clone();
            update.materialArgsHash = request.materialArgsHash().clone();
            update.resultText = null;
            update.lastError = null;
            update.failureBasis = null;
            update.lookupFailures = 0;
            update.resolveAfter = null;
        } else {
            Instant time = now();
            update.state = ((JournalWrite.ChangeState) write).state();
            update.leaseOwner = null;
            update.leaseUntil = null;
            update.updatedAt = time;
            update.resolveAfter = update.state == ActionState.UNKNOWN ? time : null;
        }
        return update.record();
    }

    private boolean commitClaim(String key, Row row, ActionRecord record, ClaimRequest request,
                                ClaimDecision decision, boolean insert) {
        synchronized (commitMonitor) {
            if (insert && rows.containsKey(key)) {
                return false;
            }
            CallKey callKey = null;
            History nextHistory = null;
            if (request.providerCallId() != null) {
                String callId = columnText(request.providerCallId(), 200);
                callKey = new CallKey(hash(request.scopeHash()), callId);
                History previous = history.get(callKey);
                Instant seenAt = now();
                if (previous == null || decision.write() instanceof JournalWrite.StartAttempt
                        && previous.key().equals(key)) {
                    nextHistory = new History(key, record.attempt(), seenAt);
                }
            }
            // All potentially failing work is staged before either table becomes visible.
            row.record = record;
            if (insert) {
                rows.putIfAbsent(key, row);
            }
            if (nextHistory != null) {
                history.put(callKey, nextHistory);
            }
            return true;
        }
    }

    @Override
    public RecordResult recordSuccess(KeyAttempt attempt, String resultText) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(resultText, "resultText");
        return record(attempt, null, resultText);
    }

    @Override
    public RecordResult recordFailure(KeyAttempt attempt, FailureKind kind, String error) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(kind, "kind");
        return record(attempt, kind, error);
    }

    private RecordResult record(KeyAttempt attempt, FailureKind failure, String text) {
        Row row = lockLive(hash(attempt.keyHash()));
        if (row == null) {
            return new RecordResult(RecordOutcome.MISSING, Optional.empty(), Optional.empty());
        }
        try {
            ActionRecord before = row.record;
            if (!attempt.isOf(before)) {
                return new RecordResult(RecordOutcome.MISSING, Optional.empty(), Optional.empty());
            }
            RecordOutcome outcome;
            if (before.attempt() != attempt.attempt()) {
                outcome = RecordOutcome.SUPERSEDED;
            } else if (before.state() == ActionState.SUCCEEDED) {
                outcome = RecordOutcome.ALREADY_SUCCEEDED;
            } else if (failure != null && before.state() != ActionState.EXECUTING
                    && before.state() != ActionState.UNKNOWN) {
                outcome = RecordOutcome.ALREADY_RESOLVED;
            } else {
                columnText(text, Integer.MAX_VALUE);
                Instant time = now();
                Update update = new Update(before);
                update.leaseOwner = null;
                update.leaseUntil = null;
                update.updatedAt = time;
                if (failure == null) {
                    update.state = ActionState.SUCCEEDED;
                    update.resultText = text;
                    update.failureBasis = null;
                    update.resolveAfter = null;
                } else {
                    update.state = failure == FailureKind.DEFINITE ? ActionState.FAILED : ActionState.UNKNOWN;
                    update.failureBasis = failure == FailureKind.DEFINITE ? FailureBasis.DEFINITE : null;
                    update.lastError = text;
                    update.resolveAfter = time;
                }
                publish(row, update.record());
                outcome = RecordOutcome.APPLIED;
            }
            return new RecordResult(outcome, Optional.of(row.record), Optional.of(before.state()));
        } finally {
            row.lock.unlock();
        }
    }

    @Override
    public boolean markUnknownIfLeaseExpired(KeyAttempt attempt, String reason) {
        Objects.requireNonNull(attempt, "attempt");
        Row row = lockLive(hash(attempt.keyHash()));
        if (row == null) {
            return false;
        }
        try {
            Instant time = now();
            ActionRecord current = row.record;
            if (!attempt.isCurrentAttemptOf(current) || current.state() != ActionState.EXECUTING
                    || current.leaseUntil() == null || !current.leaseUntil().isBefore(time)) {
                return false;
            }
            columnText(reason, Integer.MAX_VALUE);
            Update update = new Update(current);
            update.state = ActionState.UNKNOWN;
            update.lastError = reason;
            update.leaseOwner = null;
            update.leaseUntil = null;
            update.updatedAt = time;
            update.resolveAfter = time;
            publish(row, update.record());
            return true;
        } finally {
            row.lock.unlock();
        }
    }

    @Override
    public int renewLeases(String owner, Collection<KeyAttempt> inFlight, Duration lease) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(inFlight, "inFlight");
        Objects.requireNonNull(lease, "lease");
        if (inFlight.isEmpty()) {
            return 0;
        }
        Map<String, List<KeyAttempt>> attempts = new TreeMap<>();
        for (KeyAttempt target : inFlight) {
            attempts.computeIfAbsent(hash(target.keyHash()), ignored -> new ArrayList<>()).add(target);
        }
        List<Row> locked = new ArrayList<>();
        try {
            for (Map.Entry<String, List<KeyAttempt>> entry : attempts.entrySet()) {
                Row row = rows.get(entry.getKey());
                if (row != null && entry.getValue().stream().anyMatch(target -> target.isCurrentAttemptOf(row.record))) {
                    row.lock.lock();
                    locked.add(row);
                }
            }
            Instant time = now();
            Duration length = interval(lease);
            Map<Row, ActionRecord> updates = new LinkedHashMap<>();
            for (Row row : locked) {
                ActionRecord current = row.record;
                if (!row.deleted && attempts.get(hash(current.keyHash())).stream()
                        .anyMatch(target -> target.isCurrentAttemptOf(current))
                        && current.state() == ActionState.EXECUTING && owner.equals(current.leaseOwner())
                        && time.isBefore(current.startedAt().plus(current.maxEffectDuration()))) {
                    Update update = new Update(current);
                    update.leaseUntil = time.plus(length);
                    updates.put(row, update.record());
                }
            }
            publish(updates);
            return updates.size();
        } finally {
            unlock(locked);
        }
    }

    @Override
    public List<ActionRecord> takeReconcileBatch(Duration hold, int limit) {
        Objects.requireNonNull(hold, "hold");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        Duration length = interval(hold);
        List<Candidate> candidates = new ArrayList<>();
        synchronized (commitMonitor) {
            rows.forEach((key, row) -> candidates.add(new Candidate(key, row, row.record)));
        }
        candidates.sort(Comparator.comparing((Candidate candidate) -> candidate.snapshot().resolveAfter(),
                Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(Candidate::key));
        List<Row> locked = new ArrayList<>();
        Map<Row, ActionRecord> updates = new LinkedHashMap<>();
        try {
            Instant time = null;
            for (Candidate candidate : candidates) {
                Row row = candidate.row();
                if (!row.lock.tryLock()) {
                    continue;
                }
                locked.add(row);
                if (time == null) {
                    time = now();
                }
                ActionRecord current = row.record;
                if (!row.deleted && needsReconciliation(current, time)) {
                    Update update = new Update(current);
                    update.resolveAfter = time.plus(length);
                    updates.put(row, update.record());
                    if (updates.size() == limit) {
                        break;
                    }
                } else {
                    locked.remove(locked.size() - 1);
                    row.lock.unlock();
                }
            }
            publish(updates);
            return new ArrayList<>(updates.values());
        } finally {
            unlock(locked);
        }
    }

    private static boolean needsReconciliation(ActionRecord row, Instant time) {
        if (row.resolveAfter() != null && row.resolveAfter().isAfter(time)) {
            return false;
        }
        return row.state() == ActionState.UNKNOWN
                || row.state() == ActionState.EXECUTING && row.leaseUntil() != null && row.leaseUntil().isBefore(time)
                || row.state() == ActionState.FAILED && !row.deadlineAt().isAfter(time);
    }

    @Override
    public boolean resolve(KeyAttempt attempt, Resolution resolution) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(resolution, "resolution");
        Row row = lockLive(hash(attempt.keyHash()));
        if (row == null) {
            return false;
        }
        try {
            ActionRecord current = row.record;
            ActionState source = resolution instanceof Resolution.Abandon ? ActionState.FAILED : ActionState.UNKNOWN;
            if (!attempt.isCurrentAttemptOf(current) || current.state() != source) {
                return false;
            }
            Instant time = now();
            Update update = new Update(current);
            update.updatedAt = time;
            if (resolution instanceof Resolution.Applied) {
                update.state = ActionState.SUCCEEDED;
                update.resultText = columnText(((Resolution.Applied) resolution).resultText(), Integer.MAX_VALUE);
                update.failureBasis = null;
                update.resolveAfter = null;
            } else if (resolution instanceof Resolution.NotApplied) {
                update.state = ActionState.FAILED;
                update.failureBasis = FailureBasis.LOOKUP;
                update.resolveAfter = time;
            } else if (resolution instanceof Resolution.LookupUnreachable) {
                update.lookupFailures = Math.incrementExact(current.lookupFailures());
                update.resolveAfter = time.plus(interval(((Resolution.LookupUnreachable) resolution).backoff()));
            } else {
                boolean abandon = resolution instanceof Resolution.Abandon;
                update.state = abandon ? ActionState.ABANDONED : ActionState.ESCALATED;
                update.lastError = columnText(abandon ? ((Resolution.Abandon) resolution).reason()
                        : ((Resolution.Escalate) resolution).reason(), Integer.MAX_VALUE);
                update.resolveAfter = null;
            }
            publish(row, update.record());
            return true;
        } finally {
            row.lock.unlock();
        }
    }

    @Override
    public Optional<CallRecord> findByProviderCallId(byte[] scopeHash, String providerCallId) {
        Objects.requireNonNull(scopeHash, "scopeHash");
        Objects.requireNonNull(providerCallId, "providerCallId");
        synchronized (commitMonitor) {
            History mapping = history.get(new CallKey(hash(scopeHash), providerCallId));
            if (mapping == null) {
                return Optional.empty();
            }
            return Optional.of(new CallRecord(providerCallId, mapping.attempt(), mapping.seenAt(),
                    rows.get(mapping.key()).record));
        }
    }

    @Override
    public int deleteTerminalOlderThan(Duration retention, int batchSize) {
        Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive: " + retention);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1: " + batchSize);
        }
        Instant cutoff = now().minus(interval(retention));
        List<Candidate> candidates = new ArrayList<>();
        synchronized (commitMonitor) {
            rows.forEach((key, row) -> candidates.add(new Candidate(key, row, row.record)));
        }
        candidates.sort(Comparator.comparing((Candidate candidate) -> candidate.snapshot().updatedAt())
                .thenComparing(Candidate::key));
        int deleted = 0;
        for (Candidate candidate : candidates) {
            if (deleted == batchSize) {
                break;
            }
            Row row = candidate.row();
            if (!row.lock.tryLock()) {
                continue; // skip locked, like FOR UPDATE SKIP LOCKED
            }
            try {
                ActionRecord current = row.record;
                if (row.deleted || !isTerminal(current.state()) || !current.updatedAt().isBefore(cutoff)) {
                    continue;
                }
                synchronized (commitMonitor) {
                    rows.remove(candidate.key(), row);
                    history.values().removeIf(mapping -> mapping.key().equals(candidate.key()));
                    row.deleted = true;
                }
                deleted++;
            } finally {
                row.lock.unlock();
            }
        }
        return deleted;
    }

    @Override
    public List<ActionRecord> find(ActionState state, int limit) {
        Objects.requireNonNull(state, "state");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1: " + limit);
        }
        List<Candidate> found = new ArrayList<>();
        synchronized (commitMonitor) {
            rows.forEach((key, row) -> {
                if (row.record.state() == state) {
                    found.add(new Candidate(key, row, row.record));
                }
            });
        }
        return found.stream()
                .sorted(Comparator.comparing((Candidate candidate) -> candidate.snapshot().updatedAt())
                        .thenComparing(Candidate::key))
                .limit(limit)
                .map(Candidate::snapshot)
                .toList();
    }

    @Override
    public long count(ActionState state) {
        Objects.requireNonNull(state, "state");
        synchronized (commitMonitor) {
            return rows.values().stream().filter(row -> row.record.state() == state).count();
        }
    }

    @Override
    public boolean resolveManually(KeyAttempt attempt, ManualResolution resolution) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(resolution, "resolution");
        Row row = lockLive(hash(attempt.keyHash()));
        if (row == null) {
            return false;
        }
        try {
            ActionRecord current = row.record;
            if (!attempt.isCurrentAttemptOf(current) || current.state() != ActionState.ESCALATED) {
                return false;
            }
            Update update = new Update(current);
            update.updatedAt = now();
            update.resolveAfter = null;
            if (resolution instanceof ManualResolution.Applied) {
                update.state = ActionState.SUCCEEDED;
                update.resultText = columnText(((ManualResolution.Applied) resolution).resultText(),
                        Integer.MAX_VALUE);
                update.failureBasis = null;
            } else {
                update.state = ActionState.ABANDONED;
                update.lastError = ManualResolution.NOT_APPLIED_ERROR;
            }
            publish(row, update.record());
            return true;
        } finally {
            row.lock.unlock();
        }
    }

    @Override
    public boolean redact(byte[] keyHash) {
        Objects.requireNonNull(keyHash, "keyHash");
        Row row = lockLive(hash(keyHash));
        if (row == null) {
            return false;
        }
        try {
            Update update = new Update(row.record);
            if (update.redactedAt == null) {
                update.redactedAt = now();
            }
            publish(row, update.record()); // the update time stays
            return true;
        } finally {
            row.lock.unlock();
        }
    }

    private static boolean isTerminal(ActionState state) {
        return state == ActionState.SUCCEEDED || state == ActionState.ABANDONED;
    }

    /** The row of {@code key}, locked, or {@code null} if there is none; never a row retention removed. */
    private Row lockLive(String key) {
        while (true) {
            Row row = rows.get(key);
            if (row == null) {
                return null;
            }
            row.lock.lock();
            if (!row.deleted) {
                return row;
            }
            row.lock.unlock(); // removed from the map meanwhile; look again
        }
    }

    private void publish(Row row, ActionRecord record) {
        synchronized (commitMonitor) {
            row.record = record;
        }
    }

    private void publish(Map<Row, ActionRecord> updates) {
        synchronized (commitMonitor) {
            updates.forEach((row, record) -> row.record = record);
        }
    }

    private static void unlock(List<Row> locked) {
        for (int index = locked.size() - 1; index >= 0; index--) {
            locked.get(index).lock.unlock();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static Duration interval(Duration duration) {
        long micros = Math.addExact(duration.toNanos(), 999) / 1000;
        return Duration.of(micros, ChronoUnit.MICROS);
    }

    private static void validateAttemptText(ClaimRequest request) {
        columnText(request.keyText(), Integer.MAX_VALUE);
        columnText(request.toolName(), 200);
        columnText(request.policy(), 32);
        columnText(request.leaseOwner(), 64);
    }

    private static String columnText(String text, int maximumLength) {
        if (text == null) {
            return null;
        }
        if (text.indexOf('\0') >= 0) {
            throw new JournalException("text columns cannot contain a zero character", null);
        }
        int length = text.codePointCount(0, text.length());
        if (length > maximumLength) {
            int end = text.offsetByCodePoints(0, maximumLength);
            if (!text.substring(end).chars().allMatch(character -> character == ' ')) {
                throw new JournalException("text exceeds column length " + maximumLength, null);
            }
            return text.substring(0, end);
        }
        return text;
    }

    private static final class Row {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile ActionRecord record;
        /** Set under the row lock when retention removed the row; it is then never written again. */
        private volatile boolean deleted;
    }

    private record CallKey(String scope, String callId) {}

    private record History(String key, int attempt, Instant seenAt) {}

    private record Candidate(String key, Row row, ActionRecord snapshot) {}

    /** A private staged copy; identity, scope, creation time and deadline always come from the original row. */
    private static final class Update {
        private final ActionRecord original;
        private String keyText;
        private String toolName;
        private String policy;
        private ActionState state;
        private int attempt;
        private String leaseOwner;
        private Instant leaseUntil;
        private Duration maxEffectDuration;
        private Instant startedAt;
        private Instant updatedAt;
        private Instant resolveAfter;
        private int lookupFailures;
        private FailureBasis failureBasis;
        private byte[] allArgsHash;
        private byte[] materialArgsHash;
        private String argumentsJson;
        private String resultText;
        private String lastError;
        private Instant redactedAt;

        private Update(ActionRecord row) {
            original = row;
            keyText = row.keyText();
            toolName = row.toolName();
            policy = row.policy();
            state = row.state();
            attempt = row.attempt();
            leaseOwner = row.leaseOwner();
            leaseUntil = row.leaseUntil();
            maxEffectDuration = row.maxEffectDuration();
            startedAt = row.startedAt();
            updatedAt = row.updatedAt();
            resolveAfter = row.resolveAfter();
            lookupFailures = row.lookupFailures();
            failureBasis = row.failureBasis();
            allArgsHash = row.allArgsHash();
            materialArgsHash = row.materialArgsHash();
            argumentsJson = row.argumentsJson();
            resultText = row.resultText();
            lastError = row.lastError();
            redactedAt = row.redactedAt();
        }

        /** The staged row; a redacted row keeps none of its texts. */
        private ActionRecord record() {
            boolean redacted = redactedAt != null;
            return new ActionRecord(original.keyHash(), redacted ? null : keyText, toolName, policy, state, attempt,
                    leaseOwner, leaseUntil, maxEffectDuration, original.createdAt(), original.deadlineAt(), startedAt,
                    updatedAt, resolveAfter, lookupFailures, failureBasis, allArgsHash, original.scopeHash(),
                    materialArgsHash, redacted ? null : argumentsJson, redacted ? null : resultText,
                    redacted ? null : lastError, redactedAt);
        }
    }

    private static String jsonText(String text) {
        if (text == null) {
            return null;
        }
        JsonText parser = new JsonText(text);
        String value = parser.value();
        parser.whitespace();
        if (parser.position != text.length()) {
            throw parser.invalid();
        }
        return value;
    }

    /** Parses stored arguments and emits jsonb's text form without an external JSON dependency. */
    private static final class JsonText {
        private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
        private final String text;
        private int position;

        private JsonText(String text) {
            this.text = text;
        }

        private String value() {
            whitespace();
            if (position == text.length()) {
                throw invalid();
            }
            char first = text.charAt(position);
            if (first == '"') {
                return quote(string());
            }
            if (first == '{') {
                return object();
            }
            if (first == '[') {
                position++;
                List<String> values = new ArrayList<>();
                if (!take(']')) {
                    do {
                        values.add(value());
                    } while (take(','));
                    require(']');
                }
                return "[" + String.join(", ", values) + "]";
            }
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, position)) {
                    position += literal.length();
                    return literal;
                }
            }
            Matcher matcher = NUMBER.matcher(text).region(position, text.length());
            if (!matcher.lookingAt()) {
                throw invalid();
            }
            position = matcher.end();
            try {
                BigDecimal number = new BigDecimal(matcher.group());
                if ((long) number.precision() - number.scale() > 131_072 || number.scale() > 16_383) {
                    throw invalid();
                }
                return number.toPlainString();
            } catch (NumberFormatException e) {
                throw invalid();
            }
        }

        private String object() {
            position++;
            Map<String, String> fields = new TreeMap<>((left, right) -> {
                byte[] a = left.getBytes(StandardCharsets.UTF_8);
                byte[] b = right.getBytes(StandardCharsets.UTF_8);
                int length = Integer.compare(a.length, b.length);
                return length == 0 ? Arrays.compareUnsigned(a, b) : length;
            });
            if (!take('}')) {
                do {
                    whitespace();
                    String key = string();
                    require(':');
                    fields.put(key, value());
                } while (take(','));
                require('}');
            }
            List<String> values = new ArrayList<>();
            fields.forEach((key, value) -> values.add(quote(key) + ": " + value));
            return "{" + String.join(", ", values) + "}";
        }

        private String string() {
            require('"');
            StringBuilder decoded = new StringBuilder();
            boolean closed = false;
            while (position < text.length()) {
                char character = text.charAt(position++);
                if (character == '"') {
                    closed = true;
                    break;
                }
                if (character < 0x20) {
                    throw invalid();
                }
                if (character == '\\') {
                    if (position == text.length()) {
                        throw invalid();
                    }
                    char escape = text.charAt(position++);
                    switch (escape) {
                        case '"': case '\\': case '/': character = escape; break;
                        case 'b': character = '\b'; break;
                        case 'f': character = '\f'; break;
                        case 'n': character = '\n'; break;
                        case 'r': character = '\r'; break;
                        case 't': character = '\t'; break;
                        case 'u':
                            if (position + 4 > text.length()) {
                                throw invalid();
                            }
                            int code = 0;
                            for (int index = 0; index < 4; index++) {
                                char hex = text.charAt(position++);
                                int digit = "0123456789abcdef".indexOf(Character.toLowerCase(hex));
                                if (digit < 0) {
                                    throw invalid();
                                }
                                code = code * 16 + digit;
                            }
                            character = (char) code;
                            break;
                        default: throw invalid();
                    }
                }
                if (character == 0) {
                    throw invalid();
                }
                decoded.append(character);
            }
            if (!closed) {
                throw invalid();
            }
            for (int index = 0; index < decoded.length(); index++) {
                char character = decoded.charAt(index);
                if (Character.isHighSurrogate(character)) {
                    if (++index == decoded.length() || !Character.isLowSurrogate(decoded.charAt(index))) {
                        throw invalid();
                    }
                } else if (Character.isLowSurrogate(character)) {
                    throw invalid();
                }
            }
            return decoded.toString();
        }

        private static String quote(String value) {
            StringBuilder result = new StringBuilder("\"");
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"': result.append("\\\""); break;
                    case '\\': result.append("\\\\"); break;
                    case '\b': result.append("\\b"); break;
                    case '\f': result.append("\\f"); break;
                    case '\n': result.append("\\n"); break;
                    case '\r': result.append("\\r"); break;
                    case '\t': result.append("\\t"); break;
                    default:
                        if (character < 0x20) {
                            result.append("\\u00").append(HexFormat.of().toHexDigits((byte) character));
                        } else {
                            result.append(character);
                        }
                }
            }
            return result.append('"').toString();
        }

        private void whitespace() {
            while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
        }

        private boolean take(char character) {
            whitespace();
            if (position < text.length() && text.charAt(position) == character) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char character) {
            if (!take(character)) {
                throw invalid();
            }
        }

        private JournalException invalid() {
            return new JournalException("invalid argumentsJson at character " + position, null);
        }
    }
}
