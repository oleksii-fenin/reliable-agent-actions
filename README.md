# reliable-agent-actions

> **Status: implementation in progress, not released** (design approved 2026-11-09). Core, PostgreSQL journal,
> reconciler, the LangChain4j adapter with chat-memory repair (tested on 1.14.0 and 1.23.0) and the process-kill test
> through LangChain4j exist. See the verification status below the guarantees and `examples/langchain4j-refund`.
> Working name — see [`docs/OPEN-QUESTIONS.md`](docs/OPEN-QUESTIONS.md).

A small Java library that makes **side-effecting tool calls of AI agents effectively-once**. Your agent's
"refund the order", "send the e-mail", "create the ticket" takes effect **once per logical action** — when the external
system deduplicates by key or can be asked what happened (see the guarantees below for the exact conditions) — even when
the process dies in the middle of a tool call, the downstream call times out, or the model re-issues the call after a
restart — and when the library *cannot* know whether it happened, it says so instead of guessing.

No extra server: one table in the PostgreSQL you already run, a decorator around your LangChain4j tools, and an
explicit policy per tool.

## The failure this prevents

1. The model asks for `refundOrder(orderId=42, amount=30)`.
2. Your tool calls the payment API. The refund succeeds.
3. The JVM is killed (deploy, OOM, node drain) **before** the tool result is written to chat memory.
   LangChain4j stores the assistant's tool request *before* running tools and the results *after* them, so the
   persistent memory now holds a tool call with no result.
4. After restart, either the conversation is retried and the model asks for the refund again — **with a new
   tool-call id** — or the stored history is sent as is and the provider rejects it. Nobody knows whether the refund
   happened.
5. The customer is refunded twice, or support has to check by hand.

HTTP-client retries do not help (the duplicate comes from the model). The provider's tool-call id does not help (it
changes on regeneration). Durable-execution engines checkpoint *completed* steps but re-run a step that crashed
mid-flight. What helps is a durable record of the **logical** action, written before the side effect, keyed by business
meaning, and an explicit rule for what to do when the outcome is unknown.

## Guarantees and non-guarantees

| Guaranteed (when configured as documented) | **Not** guaranteed |
|---|---|
| For one action key, at most one attempt holds a live lease, and only the current attempt can change the journal (unique key + lease + fencing token) | Stopping a *paused* stale attempt from reaching the external system — only a downstream idempotency key prevents that duplicate; hence `IDEMPOTENT_DOWNSTREAM` for anything that matters |
| | Exactly-once against a system that offers neither an idempotency key nor an outcome lookup — impossible in general; such tools must choose `AT_MOST_ONCE` or `AT_LEAST_ONCE` |
| A succeeded action is never re-executed; a repeated call returns the **recorded result**; the same key with different material arguments returns an explicit conflict | Deduplication when the model changes the arguments the key is built from — that is a different action by design |
| An unknown outcome (crash, timeout) is never retried silently: it becomes `UNKNOWN` and is resolved only as the tool's policy allows | `RECONCILABLE` tools: a process paused longer than its lease *before* its request lands can still cause a duplicate after a "not found" lookup. Use `IDEMPOTENT_DOWNSTREAM` for money |
| Every action has a deadline and every attempt a maximum effect duration; after the deadline no new attempt starts, and an unresolved outcome is **escalated** — never reported as "not done", except when a declared outcome lookup said "not found" (the `RECONCILABLE` assumption below) | Ordering between different actions; multi-step workflows, timers, compensation — use Temporal / Restate / DBOS |
| Lease and deadline decisions use database time, not JVM clocks | Availability while the journal DB is down: side-effecting tools **fail closed** |
| Opt-in repair of LangChain4j chat memory from the journal after a crash | Replay after the retention period (default 7 days) — the row is gone |
| | Durability across a failover to an **asynchronous** replica that lost committed rows — use synchronous replication for the journal |
| | Automatic completion without the agent: the library never re-runs a tool by itself; an unknown outcome nobody asks about again is escalated at its deadline |
| | Unattended lookups without key text: the reconciler can look up only actions whose key is stored as text (`storeKeyText(true)`, which puts business identifiers such as order ids into the journal); otherwise the next call looks the outcome up, or it is escalated at its deadline ([ADR-0004](docs/adr/0004-reconciler-lookups-need-the-action-key.md)) |

Every row on the left is to be backed by an automated test, including a test that **kills the JVM in the middle of a
tool call**, one that **pauses** it past its lease, and negative controls proving the harness detects duplicates.

**Verification status (2026-11-16, PostgreSQL 14 and 18, plain Java caller, no LLM yet):**

* **Verified — process kill.** A child JVM is killed with SIGKILL at four points of a tool call (after the claim; effect
  applied with the response held; response received but not recorded; recorded but not handed back) and a second JVM
  calls again with a new tool-call id. For all four policies the effects applied, the final journal state and the final
  outcome match the expected-outcome table ([`KillMatrixTest`](raa-postgres/src/test/java/io/github/oleksiifenin/raa/postgres/KillMatrixTest.java), DESIGN §9.1, mode (a)). Negative controls without
  the library see the duplicate effect, so the harness can detect one.
* **Verified — journal rules.** One live lease per key and fenced writes of stale attempts (journal TCK on PostgreSQL),
  the recorded result on repeat and the conflict on changed arguments, unknown outcomes never retried silently,
  deadlines and escalation, database time only (runner, reconciler and decision-table tests).
* **Verified — LangChain4j adapter** through a real `AiServices` with a scripted model, on LangChain4j 1.14.0 and
  1.23.0: a tool runs once, a regenerated call or a call after lost memory gets the recorded result, a crash during the
  tool is retried with the same idempotency key, concurrent same-key calls run the tool once, invalid arguments and a
  missing `@MemoryId` are "not performed"; specifications and `ReturnBehavior` are kept, `IMMEDIATE` is rejected.
* **Verified — end to end through LangChain4j** (`examples/langchain4j-refund`): the refund agent, with its chat memory
  in PostgreSQL, is killed at the same four points and resumed either by retrying the conversation (mode (a)) or by
  repairing the memory from the journal and continuing (mode (b)); all 32 cells match the table, and in mode (b) the
  repaired answer itself is checked. Without the library the same agent refunds twice.
* **Argued, not verified yet:** the pause past the lease (Q1p), failover behavior, overhead.

## Quick start (API as implemented, not released)

```java
// 1. Journal in your PostgreSQL (plain SQL migration shipped for Flyway/Liquibase)
ActionJournal journal = PostgresActionJournal.create(dataSource);

// 2. Declare how each side-effecting tool is protected
ReliableActions actions = ReliableActions.builder(journal)
    .tool("refundOrder", ActionPolicy.idempotentDownstream(Duration.ofHours(24))   // payment API dedups by our key for 24 h
            .key((call, args) -> ActionKey.of(call.scope(), "refund", args.longValue("orderId")))
            .maxEffectDuration(Duration.ofSeconds(30))                            // ≥ the HTTP client timeout
            .deadline(Duration.ofMinutes(15)))
    .tool("sendEmail", ActionPolicy.reconcilable((key, idempotencyKey) -> mailer.findByReference(idempotencyKey))
            .key((call, args) -> ActionKey.of(call.scope(), "email", args.string("ticketId")))
            .freeTextArguments("body")                                            // regenerated text is not a new action
            .maxEffectDuration(Duration.ofSeconds(20)))
    .tool("getOrder", ActionPolicy.none())                                          // read-only, not journaled
    .storeKeyText(true)          // lets the reconciler look up sendEmail on its own; stores business ids (ADR-0004)
    .build();

// 3. Wrap tools for LangChain4j — tool code stays as it is. The AI service method needs @MemoryId: the memory id is
//    the scope of every action key, and without it the journaled tools are not performed (ADR-0005).
ReliableTools reliableTools = ReliableTools.builder(actions).build();
List<AiServiceTool> tools = reliableTools.wrap(new BillingTools());
Assistant assistant = AiServices.builder(Assistant.class)
    .chatModel(model)
    .chatMemoryProvider(memoryProvider)
    .tools(tools)
    .build();

// Inside a tool, pass the stable key downstream:
@Tool("Refund an order")
String refundOrder(long orderId, BigDecimal amount) {
    return payments.refund(orderId, amount, ActionContext.current().idempotencyKey());
}

// After a restart, answer tool calls the crash left without a result, before the next message of a conversation:
JournalChatMemoryRepair repair = reliableTools.memoryRepair(tools);
RepairReport report = repair.repair(chatMemory);   // report.complete() == false: an attempt is still running

// 4. Run the reconciler and the lease renewer (the Spring Boot starter will do this for you in v0.2)
scheduler.scheduleWithFixedDelay(actions.reconciler()::runOnce, 15, 15, SECONDS);
actions.leaseRenewer().start();
```

What the model sees when the library steps in: the recorded result on replay; "already performed with different
arguments"; "already in progress, do not repeat"; "outcome unknown, it is safe to call again with the same arguments"
(only for tools whose downstream deduplicates); "calling again may perform it twice" (for `AT_LEAST_ONCE`); "being verified, ask again later"; or "could not be verified
automatically, an operator has been notified". The texts are replaceable.

## Architecture

```mermaid
sequenceDiagram
    participant M as Agent loop (LangChain4j)
    participant E as ReliableToolExecutor
    participant J as Action journal (PostgreSQL)
    participant T as Your tool
    participant X as External system
    participant R as Reconciler (background)

    M->>E: tool call (name, args JSON, call id, memory id)
    E->>J: claim(key) — insert EXECUTING + lease, or load existing (commit)
    alt already SUCCEEDED
        J-->>E: recorded result
        E-->>M: recorded result (no side effect)
    else live lease elsewhere
        E-->>M: "in progress, do not repeat"
    else claimed (attempt n)
        E->>T: invoke with ActionContext (idempotency key)
        T->>X: side effect with idempotency key
        X-->>T: ok / timeout
        E->>J: SUCCEEDED + result, or UNKNOWN (fenced by attempt n)
        E-->>M: result or "being verified"
    end
    Note over J,R: process died → lease not renewed → expires
    R->>J: EXECUTING with expired lease → UNKNOWN
    R->>X: outcome lookup (if the policy has one)
    R->>J: SUCCEEDED / FAILED / ESCALATED
```

Modules: `raa-core` (no framework dependencies), `raa-storage-api` (the storage contract; core brings it in
transitively) and exactly one storage implementation that depends only on that contract — `raa-postgres` (PostgreSQL ≥ 14)
is the only supported one today; you choose it when wiring, core never knows which database it is. Other databases are
welcome if they pass the `raa-journal-tck` conformance suite. Plus `raa-langchain4j`, `raa-opentelemetry`, `raa-testkit` (fault injection and a kill-test harness
you can use for your own tools). Spring AI, a Spring Boot starter and an MCP-server wrapper are
planned for v0.2. Details: [`docs/DESIGN.md`](docs/DESIGN.md).

## How it compares

Checked against public docs on 2026-11-09 (see [`docs/research/`](docs/research)); to be re-verified before release.

| | This library | Temporal (Java SDK, + Spring AI preview) | Restate (Java SDK) | DBOS Transact Java | gruelbox transaction-outbox | Spring Modulith event registry |
|---|---|---|---|---|---|---|
| Extra infrastructure | none (your PostgreSQL) | Temporal service | Restate server | none (PostgreSQL) | none (your DB) | none (your DB) |
| Unit of protection | one agent tool call (business key) | activity in a workflow | `run` block in a handler | step / workflow | scheduled method call | event × listener |
| Regenerated call with a new tool-call id | deduplicated by business key | new activity → runs again | new journal entry → runs again (invocation idempotency keys exist, ~24 h, you derive them) | new step → runs again | dedup only with `uniqueRequestId` you supply | no |
| Crash mid side effect | `UNKNOWN` → per-policy resolution, lookup, escalation | retried after timeout; idempotency is on you | block re-run ¹ | step retried; idempotency is on you | retried until success | republished (if configured) |
| Stale-worker fencing of results | attempt number on every write | not documented ² | not documented ² | not documented ² | not documented ² | not documented ² |
| Whole workflows, timers, long waits, compensation | **no** | **strong** | **strong** | **strong** | no | no |
| Maturity | design | production | production | production (1.x since 07.2026) | mature (7.x) | production |

¹ From a third-party test, not from Restate's docs — unverified. ² Not found in their docs during desk research;
absence is not proof.

**Where alternatives are stronger:** anything that is a multi-step business process with waits, retries across steps
and compensation. This library is **complementary**: it can protect the side effect *inside* a Temporal activity or a
DBOS step.
**Where it fits:** a Java service that already runs an agent with LangChain4j and a PostgreSQL, and needs
side-effecting tools not to fire twice — without adopting a workflow engine. Similar ledger ideas exist as small Python,
TypeScript and Go projects; on the JVM, no library doing per-tool-call deduplication with unknown-outcome reconciliation
was found.

## Documents

* [`docs/DESIGN.md`](docs/DESIGN.md) — requirements, quality scenarios, modules, API, lifecycle, storage, failure
  table, test plan, roadmap.
* [`docs/adr/0001-guarantee-model-and-postgres-claim.md`](docs/adr/0001-guarantee-model-and-postgres-claim.md)
* [`docs/adr/0002-framework-integration-boundary.md`](docs/adr/0002-framework-integration-boundary.md)
* [`docs/OPEN-QUESTIONS.md`](docs/OPEN-QUESTIONS.md) — decisions needed before code.

## License

Apache-2.0 (the license file is added with the first code).
