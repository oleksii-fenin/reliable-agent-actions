# ADR-0006: The journal operation timeout is a storage setting

* Status: **accepted** (2026-11-18; the mechanism in points 3–5 was revised the same day, before the first commit,
  after an independent review)
* Deciders: project author (decision D1 of the week 6 plan: "В хранилище (a)")
* Supersedes in part: `docs/DESIGN.md` §5 (`ReliableActions.Builder.claimTimeout`) and §7 (claim transaction timeouts
  "from the remaining `claimTimeout`")
* Related: `docs/DESIGN.md` §2 Q6, §8 (pool exhausted, journal DB down, ambiguous commit), §9 (database faults)

## Context

Q6 asks that the model gets an answer within 3 s when the journal database is down, unreachable (TCP blackhole) or the
pool is exhausted, and that the effect is not started. DESIGN §5 put a `claimTimeout(Duration = 2.5 s)` on
`ReliableActions`, and §7 said the claim transaction derives its network and statement timeouts from it.

Implementing it showed two problems:

1. The storage contract `ActionJournal.claim(ClaimRequest, ClaimDecider)` carries no time budget, so a budget set on
   `ReliableActions` cannot reach the JDBC code without changing the SPI.
2. Only the claim would be bounded. Record writes, lease renewal and the reconciler's operations hang just as long on a
   blackholed database; the renewer and the record retry loop then have no bound at all.

A third limit applies to every option: `DataSource.getConnection()` has no timeout argument, so the wait for a pooled
connection can only be bounded by the pool itself (for example HikariCP `connectionTimeout`), and a new physical
connection only by the driver (pgjdbc `connectTimeout`, `loginTimeout`).

Options:

* **(a) Operation timeout in the storage.** `PostgresActionJournal` gets one operation timeout (default 2.5 s) that
  bounds every operation it performs. The SPI does not change.
* **(b) Budget per request.** `ClaimRequest` carries the caller's remaining budget; the journal derives its timeouts
  from it. Record writes, renewal and the reconciler still need a bound of their own, so there would be two mechanisms.

## Decision

Option (a).

1. `PostgresActionJournal.create(DataSource, Duration operationTimeout)`; `create(DataSource)` uses 2.5 s. The timeout
   must be positive.
2. The budget of one operation starts before `getConnection()`. Time spent waiting for the connection counts against
   it; a connection obtained after the budget is gone is returned unused and the operation fails.
3. At the end of the budget a watchdog aborts the connection (`Connection.abort`; pgjdbc closes the socket), which
   ends any blocked read or write. A per-read network timeout (`setNetworkTimeout`, a socket read timeout) is not
   enough: it does not bound a write to a database that stops reading, nor a response that keeps trickling in
   (independent review, reproduced by tests that fail with it). The watchdog runs the abort on its own thread under a
   lock and is disarmed before the connection is closed, so it never touches a connection that the pool may already
   have handed to another thread. The close itself does no round trip: the isolation level and the server timeouts are
   set transaction-locally (`SET TRANSACTION ISOLATION LEVEL READ COMMITTED`, `SET LOCAL`), so the session goes back
   to the pool unchanged except auto-commit, which pools reset locally when no transaction is open; a transaction
   whose rollback fails is not handed back open — the journal aborts that connection itself.
4. At the start of the transaction the journal sets `statement_timeout` and `lock_timeout` (`SET LOCAL`) to 80 % of
   the remaining budget, in the same round trip as the isolation level. A slow statement or a lock held by a frozen process is then
   normally cancelled by the database while the connection still works, and the server-side backend does not wait on
   after the client gave up. PostgreSQL applies them per statement and per lock wait, so after earlier statements used
   part of the budget the abort can come first.
5. A budget that is used up when the connection arrives fails the operation before any statement is sent.
6. Every failure, the timeouts included, is a `JournalException`, as before. For the claim this means
   `JournalUnavailable`, effect not started; a timed-out `COMMIT` is an ambiguous commit (DESIGN §8). After a
   successful `COMMIT`, a failure to close the connection is logged and the operation keeps its result.
7. `ReliableActions` gets no `claimTimeout`. The pool's wait and the driver's connection timeouts are the
   application's settings; the README documents values that keep Q6 (pool wait 1 s, validation 0.5 s, pgjdbc
   `connectTimeout` 1 s and `loginTimeout` 2 s), and the database fault tests use them.

## Consequences

* All journal operations are bounded by one setting, the lease renewer and the reconciler included. The record retry
  loop (`recordRetryBudget`, 3 s) makes at most two tries when each one runs into the 2.5 s timeout.
* Q6 holds only when the pool and the driver are configured as documented; the library cannot enforce it through
  `DataSource`. The fault tests show the documented configuration, not every configuration.
* One round trip per operation to start the transaction (it replaces the driver's round trip for the session isolation
  level), and one scheduled task per operation on a daemon thread of the journal, which ends 30 s after the last
  operation. Create one journal per database and reuse it.
* The data source must support `Connection.abort` (JDBC 4.1; pgjdbc and HikariCP do). An aborted connection is broken;
  pools discard it on return or on validation (HikariCP: on return, when its reset fails).
* The aborts of one journal share one thread. An abort that blocks would delay the other deadlines of that journal;
  pgjdbc's abort only closes the socket. Not tested with TLS connections.
* The budget includes the wait for the connection and the wait for the row lock. Without a pool (a physical connection
  per operation) or with a pool too small for the concurrency, many concurrent claims of one key can use it up and
  return `JournalUnavailable` — fail closed, no duplicate, but not the `InProgressElsewhere` of Q2. Seen in the two-JVM
  Q2 test without a pool (64 concurrent connection set-ups); the test now uses the recommended pool.
* The watchdog is disarmed before `close()`, so a pool whose return does a round trip on an unchanged session is not
  bounded by it (none of the pools checked do: HikariCP 7.1.0).
* A different storage implementation decides its own bound; the TCK does not test timeouts.
* Changing the behavior later (for example a per-call budget) needs a new ADR.
