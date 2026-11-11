# ADR-0001: Guarantee model and the PostgreSQL claim mechanism

* Status: **accepted** (2026-11-09)
* Deciders: project author
* Related: `docs/DESIGN.md` §2 (quality scenarios), §6 (lifecycle), §7 (storage), §8 (failure table); revised 2026-11-09 after independent review (DESIGN §14)

## Context

An agent tool with a side effect (refund, e-mail, ticket, order) can be invoked more than once for one logical intent:

1. the process dies after the effect but before the tool result is stored in chat memory. In LangChain4j 1.23.0 the
   assistant message with tool requests is stored *before* execution and the results *after* the round
   (`docs/research/framework-integration-surface.md` §5). After a restart the conversation is either retried from the
   user message (the model emits the call again with a **new provider tool-call id**) or resumed from a persistent memory
   that holds a tool call without a result — which providers reject, and from which nobody knows whether the effect
   happened;
2. the downstream call times out: the effect may or may not have happened (**unknown outcome**) — the most common case,
   no crash needed;
3. the agent loop, an HTTP retry layer or a user ("try again") repeats the call;
4. two instances/threads process the same conversation concurrently.

A guarantee is useful only if it is precise about what the library can and cannot know. In general, exactly-once
delivery to an external system is impossible without cooperation from that system (an idempotency key or a way to
look up the outcome). The library therefore has to (a) make every tool declare which cooperation exists, and (b) never
retry silently when the outcome is unknown and no cooperation exists.

Drivers (scenarios in `docs/DESIGN.md` §2): Q1 kill test (effect count = 1 for cooperative downstreams), Q2 concurrent
same-key calls (1 effect), Q3 overhead (p99 < 10 ms added), Q6 fail closed when the journal is down. Constraint: **no
extra infrastructure** beyond a PostgreSQL the application already has.

## Decision

### 1. Unit of protection: one logical action, identified by a business key

An action is identified by `ActionKey(scope, name, businessId)` produced by a per-tool `KeyStrategy` declared by the
developer, for example `("conversation-123", "refund", "order-42")`. The provider tool-call id is **never** the key of a
side-effecting tool (it changes on regeneration); it is stored for tracing only. A hash of all raw arguments is not the
default either: LLM-generated free-text arguments (e-mail body, comment) differ between regenerations.

### 2. Guarantee is per-tool and explicit: five policies

| Policy | Downstream cooperation | Guarantee | Unknown outcome is resolved by |
|---|---|---|---|
| `IDEMPOTENT_DOWNSTREAM` | accepts our idempotency key and deduplicates within a window `W` | **effectively-once** if the action's deadline ≤ `W` | retry with the **same** key |
| `RECONCILABLE` | can look up whether our action happened (by key/reference) | **effectively-once under a bounded-pause assumption** (see consequences) | `OutcomeLookup`; retry only on a definite `NOT_FOUND` |
| `AT_MOST_ONCE` | none | ≤ 1 | nothing: `UNKNOWN` → escalated, never retried |
| `AT_LEAST_ONCE` | none, duplicates acceptable | ≥ 1 | retry |
| `NONE` | read-only tool | — | not journaled |

There is no default policy for a side-effecting tool: an unregistered tool is rejected at wiring time unless it is
explicitly `NONE`.

### 3. Synchronous guarded execution (journal-then-effect)

The tool runs in the caller's thread, as today. Before the effect, the library commits a journal row in state
`EXECUTING`; after the effect it records the result. A replay of a completed action returns the **recorded result**
to the model. The model therefore still gets a normal tool result in the same turn.

New attempts are started **only by a caller** (the agent calling the tool again, or memory repair leading to a new
turn). The background reconciler **never re-executes an effect** in v0.1: after a restart neither the effect closure
nor the conversation exist, and acting without the agent would turn the library into a job runner. The reconciler
detects dead attempts (`EXECUTING` → `UNKNOWN`), resolves `UNKNOWN` through `OutcomeLookup` where one is declared,
and escalates what cannot be resolved before the deadline.

### 4. Claim = unique row + lease + fencing token, all decisions on database time

* `INSERT … ON CONFLICT (key_hash) DO NOTHING` creates the row in `EXECUTING` with `attempt = 1`,
  `lease_until = clock_timestamp() + lease`, committed **before** the effect (own short READ COMMITTED transaction,
  not the caller's). The key is stored as a SHA-256 hash of its canonical form.
* Existing row → decision table (DESIGN §6): terminal → replay; `EXECUTING` with live lease → wait up to 2 s, then `InProgressElsewhere`;
  expired lease or `UNKNOWN` → resolved by the policy (inline or by the background reconciler).
* Every later write is a compare-and-set on `attempt` (the fencing token): a success uses
  `… WHERE key_hash = ? AND attempt = ? AND state <> 'SUCCEEDED'`, so a late result of the *same* attempt still lands
  after the reconciler marked it `UNKNOWN`/`FAILED`/`ESCALATED`, while a worker whose attempt was superseded gets 0 rows.
* **What fencing does and does not do:** it guarantees one *live lease* per key and that only the current attempt
  changes the journal. It cannot stop a paused stale attempt from reaching the downstream; that is prevented only by
  the downstream idempotency key (`IDEMPOTENT_DOWNSTREAM`). This is stated in the guarantees, not hidden.
* Leases are renewed every `lease/2` by one batched `UPDATE` per process, per `(key_hash, attempt)`, only while
  `state = 'EXECUTING'`, the owner matches and `clock_timestamp() < started_at + maxEffectDuration`.
  `maxEffectDuration` is mandatory for every journaled policy, so a hung effect loses its lease and becomes `UNKNOWN`.
* `clock_timestamp()` of PostgreSQL is the only clock (not `now()`, which is the transaction start). JVM clock skew does
  not affect decisions.

## Options considered

| Option | Why rejected |
|---|---|
| **A. Unique row + lease + fencing (chosen)** | — |
| B. PostgreSQL session advisory lock held for the duration of the effect | Crash detection is elegant (lock dies with the connection), but it pins one pooled connection per in-flight effect (effects take 0.1–30 s), breaks behind PgBouncer in transaction mode, and still needs a row for the result and for replay. |
| C. `SELECT … FOR UPDATE` in one transaction spanning the effect | Long transaction across a network call: connection pinning, lock waits for concurrent callers, vacuum pressure; a commit failure after a successful effect loses the record of it. |
| D. Redis/ZooKeeper lock + DB for results | Extra infrastructure, contradicts the main constraint; two stores to keep consistent. |
| E. Asynchronous outbox: the tool only enqueues intent and returns "accepted, id X"; a dispatcher performs the effect | Classic outbox semantics and good for long effects, but changes the agent UX (the model needs a status tool and must handle "pending") and is a different product. Kept as a possible v0.2+ mode; the journal format is compatible. |
| F. One "exactly-once" mode for every tool | Dishonest: impossible without downstream cooperation; hides the choice users must make. |
| G. Key = provider tool-call id | Does not deduplicate the main failure (regenerated call after resume gets a new id). |
| H. Key = hash of all arguments | Breaks on regenerated free text; may also merge two legitimately different actions with equal args across conversations unless scoped. Offered as an opt-in helper for tools with purely structured arguments. |

## Consequences

Good:
* The guarantee is testable per policy (kill test asserts `= 1`, `≤ 1`, `≥ 1`).
* No new infrastructure; one table, two short transactions on the happy path (claim, record); lease renewals are one batched
  statement per process.
* Stale workers are fenced; time decisions are immune to JVM clock skew.

Bad / accepted:
* **Keys are the user's hardest decision.** A wrong key either merges distinct actions (false replay) or fails to
  deduplicate. Mitigation: mandatory explicit strategy, scoped keys, documentation with examples, a replay message that
  tells the model the action was already done and when.
* **A legitimate repeat with the same key is replayed**, not executed ("send it again"). The key must then contain a
  discriminator the tool exposes (e.g. a `requestNumber` argument). Documented; no override in v0.1.
* **`RECONCILABLE` is not unconditional.** If a worker is paused (GC, VM freeze) longer than its lease *before* its
  request reaches the downstream, the reconciler can observe `NOT_FOUND`, retry, and the paused request can still land
  later → duplicate. Mitigation: retry after `NOT_FOUND` only when `clock_timestamp() > started_at + maxEffectDuration`, where
  `maxEffectDuration` (mandatory for every policy) must be ≥ the tool's client timeout. The residual risk is documented as
  a non-guarantee; for money, use `IDEMPOTENT_DOWNSTREAM`.
* **`IDEMPOTENT_DOWNSTREAM` depends on the downstream's dedup window** (e.g. 24 h for many payment APIs): the library
  rejects a policy whose deadline exceeds the declared window.
* **Replay depends on retention**: once a terminal row is deleted, the same key executes again. Retention must exceed
  the longest time a conversation can be resumed; default 7 days, configurable.
* The journal database becomes a dependency of side-effecting tools: when it is down, these tools **fail closed**
  (effect not started, explicit error to the model).
* Local effects in the **same** database as the journal could be made exactly-once by sharing the transaction; not in
  v0.1 (needs transaction-manager integration), candidate for v0.2.

## Compliance

* Kill matrix (DESIGN §9): 4 policies × 4 fault points × 2 resume modes on every PR, with negative controls that must
  fail; randomized 500 runs per policy and a pause (SIGSTOP/SIGCONT) test nightly; asserts effect counts against an
  expected-outcome table.
* SQL interleaving tests: a superseded `attempt` write returns 0 rows; a late result of the same attempt after
  `UNKNOWN` is applied; renewal after `UNKNOWN`, after a newer attempt, for another owner or after overrun renews nothing.
* Clock test: the child JVM runs under libfaketime shifted ±1 h; lease/deadline decisions do not change.
* Wiring test: a tool without a policy fails at build time of the tool set.
