# ADR-0007: The fencing token includes the row's creation time

* Status: **accepted** (2026-11-22)
* Deciders: project author ("(1) Токен поколения", 2026-11-22), after an independent review of week 7 stage A
* Supersedes in part: ADR-0001 §4 ("every later write is a compare-and-set on `attempt`") and `docs/DESIGN.md` §5
  (write signatures of `ActionJournal`)
* Related: `docs/DESIGN.md` §7 (retention), README guarantee "only the current attempt can change the journal"

## Context

ADR-0001 fences every write after the claim by `(key_hash, attempt)`. That was sound while rows were never deleted.
Retention (week 7) deletes finished rows; the next call with the same key inserts a new row that starts again at
attempt 1. A writer of the deleted row's attempt 1 — a process frozen for longer than the retention, a VM restored from
a snapshot — then matches the new row: its late `recordSuccess` marks the new action succeeded with the old result,
and its failure, expiry or resolution writes can change the new action too. The independent review (Codex, request
changes) reproduced this on PostgreSQL 14 and in memory.

## Decision

1. The fencing token is `KeyAttempt(keyHash, createdAt, attempt)`: the row's creation time (database time, already
   stored as `created_at`, set once when the row is inserted) plus the attempt number. Every row created for a key has
   a different creation time — a new row needs the old one deleted first, which retention does only past a retention
   longer than every deadline.
2. Every write after the claim takes the token: `recordSuccess`, `recordFailure`, `markUnknownIfLeaseExpired`,
   `renewLeases`, `resolve`, `resolveManually`. PostgreSQL adds `created_at = ?` to each predicate (record writes compare
   it on the locked row); lease renewal passes creation times as microseconds since the epoch.
3. A record write for another row of the key returns `MISSING` (the row it was for no longer exists); the other writes
   change nothing and return `false`/`0`, as for a superseded attempt.
4. `KeyAttempt.of(row)` builds the token from a row; the core takes it from the claim result or the reconciler batch.

## Alternatives

| Option | Why not |
|---|---|
| Document the gap (fencing holds only within the retention) | Weakens a stated guarantee; a restored snapshot is an ordinary operational event |
| Attempt numbers that never repeat across rows (for example derived from time) | `attempt` is also the attempt count the policy limits (`maxAttempts`) and the number tools see |
| Keep a tombstone of each deleted key | The table never shrinks for keys that are never used again |
| A separate random generation column | Same effect as `created_at`, which already exists and is set once |

## Consequences

* The SPI write signatures change (not published yet). Third-party journals must compare the creation time; the TCK
  test `retention_writesOfTheDeletedRowsAttempts_neverLandOnTheRowCreatedAgain` fails otherwise.
* Equality of creation times relies on the storage keeping them exactly (PostgreSQL: microseconds); implementations
  must not round them differently between the row they return and the comparison.
