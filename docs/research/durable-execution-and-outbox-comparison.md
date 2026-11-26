# Durable execution and outbox libraries: comparison

> Desk research, 2026-11-09, by an AI research agent from public docs/GitHub pages. Re-verified 2026-11-26 by a second AI research agent against the same primary sources, with spot checks by the author's coding agent; the corrections are listed under "Re-check 2026-11-26" at the end and override the table where they differ. Items marked UNVERIFIED are unconfirmed.

I could not get a primary-source statement that any of the six systems deduplicates a tool call by its business arguments. In every one, a duplicate side effect is possible if the process crashes after the call but before its result is recorded. Each of them needs the user to derive a key from the call's business arguments to stop an LLM re-issue from running twice. That is the gap your library fills.

## Comparison

| | Guarantee | Unit protected | Crash mid-side-effect | Dedup mechanism | Infra | Latest version |
|---|---|---|---|---|---|---|
| **Temporal Java SDK** | Activities may run "multiple times and may even partially complete more than once"; idempotency is recommended. [1][2] | Activity execution, inside a workflow | The server "doesn't detect failures when a Worker… crashes" and relies on Start-To-Close (or heartbeat) timeout, then retries. Work done but not reported is re-run (their example: double charge). [1][2] | Docs suggest Workflow Run ID + Activity ID as the idempotency key; the external service must honour it. [2] | Temporal Service (or Temporal Cloud) plus workers. Server persistence options not checked here: UNVERIFIED. | v1.40.0, 29 Sep 2026 [3] |
| **Restate Java/Kotlin SDK** | `Restate.run` stores the block's result "in the execution log"; failures are retried like any handler error unless it throws `TerminalException`. [4] | A run block (journal entry) inside a handler | The docs do not say. A third-party write-up says a block re-runs if the crash comes after the side effect but before the journal entry is stored (UNVERIFIED from Restate docs). | Invocation idempotency key; the response is kept about 24 h, and a repeat returns it without re-running. [5] Retention is configurable (UNVERIFIED how). | Restate server (separate process or cluster) plus your service. | v2.11.0, 28 Oct 2026 [6] |
| **DBOS Transact Java** | Steps are "tried at least once"; a completed step never re-runs; workflows run to completion. [7] | Step / workflow | A step whose checkpoint was not written runs again ("potentially writing twice"). `JdbcStepFactory` / `@TransactionalStep` commit a database write and its checkpoint together. [8] | Workflow ID: "called multiple times with the same ID, it executes only once." [7] | PostgreSQL only (a "system database"); no separate server. [9] | 1.2.0, 01 Oct 2026; Spring Boot starter since 0.8 (May 2026). [10][11] |
| **gruelbox transaction-outbox** | Retried "until confirmed successful", so effectively at-least-once; calls must be idempotent. [12] | A serialized method call written to `TXNO_OUTBOX` in the business transaction | The call is re-attempted by a flush thread. Default `blockAfterAttempts` value not stated (the config example uses 10). Afterwards it is "blocked" and needs `unblock()`. [12] | `uniqueRequestId(...)` raises `AlreadyScheduledException`; `retentionThreshold` defaults to 7 days. [12] | Your own database (MySQL, PostgreSQL, Oracle, SQL Server, H2). [12] | 7.1.785, 21 Oct 2026 (7.x line; mature) [13] |
| **Spring Modulith Event Publication Registry** | Effectively at-least-once (my inference; the docs never say it). A log entry is written in the business transaction and marked completed only after the listener succeeds. [14] | An event × listener pair | The entry stays incomplete. It is republished only if `republish-outstanding-events-on-restart` is set or the app calls `IncompleteEventPublications` / `FailedEventPublications` (2.0+). [14] | None. The listener must deduplicate. | Your own database. | 2.1.2, 23 Oct 2026 [15] |
| **db-scheduler** | "One and only one scheduler-instance" picks an execution. [16] | Task instance (`taskName` + `instanceId`, unique within the task) | Heartbeat every 5 min; dead after 6 missed beats; then rescheduled to `now()`, so a re-run is likely. [16] | Unique `instanceId` | Your own database (PostgreSQL, SQL Server, MySQL 8+ fully supported). | v17.0.0, 05 Nov 2026 [17] |
| **JobRunr** | Retries 10 times by default, then FAILED. [18] Orphaned jobs are rescheduled. [19] | Job, or a step via `runStepOnce` | OSS saves step state on the poll interval, so a hard crash can re-run a step that just finished; Pro saves per step. [20] | Job UUID dedup UNVERIFIED | Your own database | v9.0.3, 29 Oct 2026 [21] |

## Short notes

- **Shared limit.** None of them can tell whether an external, non-transactional call took effect before the crash. Each re-runs it after a timeout. None has an UNKNOWN outcome state or a reconciler that queries the remote system. Your journal's UNKNOWN state plus reconciler is a real difference.
- **Re-issue with a new tool-call id.**
  - **Temporal:** a new LLM turn creates a new activity, so it gets a new Activity ID and is not deduplicated.
  - **Restate:** a new run block is a new journal entry, also not deduplicated.
  - **DBOS:** the same applies to a new step.
  - **What users must build:** a key from canonical business arguments (normalisation, hashing, a per-tool policy), plus an insert-or-load claim. That could be a DBOS child workflow with ID = hash(args), a Restate idempotency key (only 24 h by default), an outbox `uniqueRequestId` (7 days), or a dedup table for Modulith, db-scheduler and JobRunr. Temporal (per-attempt task token) and Restate (per-attempt epoch) reject results of a stale attempt in their own log; none of them fences the external side effect, and DBOS, the outbox and Modulith document no such fencing (corrected 2026-11-26).
- **Where they are clearly stronger:**
  - Temporal and Restate cover the whole agent loop (timers, signals, replay, observability, multiple languages). Temporal also ships `temporal-spring-ai` (Public Preview), which runs Spring AI chat-model and tool calls as activities. [22][3]
  - DBOS needs only PostgreSQL, like you, but also gives whole-workflow recovery and atomic database-write steps.
  - The outbox is battle-tested for "schedule after commit."
  - db-scheduler and JobRunr give mature clustering, scheduling and dashboards.
- **Agent integrations:**
  - Temporal: Spring AI, official, Public Preview. [22]
  - Restate: a generic SDK integration guide that names LangChain4j as a good fit; I found no dedicated Java/LangChain4j module. [23]
  - DBOS: agent integrations are Python/TypeScript (Pydantic AI, OpenAI Agents, ADK, Vercel AI); I found no official Java/Spring AI/LangChain4j adapter (UNVERIFIED as complete).
  - Outbox, Modulith, db-scheduler, JobRunr: none found.
- **Correction to your brief.** The Modulith staleness monitor, `Status` lifecycle and `FailedEventPublications` are from 2.0, not 1.3. [14] What Modulith does not do: no argument-based deduplication, no lease or fencing, no ordering guarantee on resubmission. A stale-to-FAILED-to-resubmit path can overlap a listener that has hung but is still running (my inference).
- **Dates.** GitHub release pages showed day and month only; the year 2026 is inferred from today's date (DBOS 1.2.0 is confirmed by asset timestamps).

## Sources
1. https://docs.temporal.io/encyclopedia/detecting-activity-failures
2. https://docs.temporal.io/activity-definition and https://docs.temporal.io/activities
3. https://github.com/temporalio/sdk-java/releases
4. https://docs.restate.dev/develop/java/durable-steps
5. https://docs.restate.dev/services/invocation/clients/typescript-sdk
6. https://github.com/restatedev/sdk-java/releases
7. https://docs.dbos.dev/java/tutorials/workflow-tutorial
8. https://docs.dbos.dev/java/tutorials/step-tutorial
9. https://docs.dbos.dev/java/programming-guide
10. https://github.com/dbos-inc/dbos-transact-java/releases
11. https://dbos.dev/blog/spring-into-dbos-for-java
12. https://github.com/gruelbox/transaction-outbox
13. https://github.com/gruelbox/transaction-outbox/releases
14. https://docs.spring.io/spring-modulith/reference/events.html
15. https://github.com/spring-projects/spring-modulith/releases
16. https://github.com/kagkarlsson/db-scheduler
17. https://github.com/kagkarlsson/db-scheduler/releases
18. https://www.jobrunr.io/en/documentation/background-methods/dealing-with-exceptions/
19. https://jobrunr.io/en/documentation/faq
20. https://www.jobrunr.io/en/guides/advanced/durable-executions/
21. https://github.com/jobrunr/jobrunr/releases
22. https://docs.temporal.io/develop/java/integrations/spring-ai
23. https://docs.restate.dev/ai/sdk-integrations/integration-guide

Third-party, not Restate docs (behind the UNVERIFIED claim about Restate re-running a block): https://nerdleveltech.com/restate-durable-agent-duplicate-tool-calls-tested

## Re-check 2026-11-26

Every claim above was checked again against the primary sources (read 2026-11-26). Confirmed unless listed here.

- **Fencing (corrected).** Temporal rejects a late completion of a timed-out or rescheduled attempt (`NOT_FOUND`,
  "the Service discarded the in-flight task"; the task token is unique per Activity Task Execution) [24]. Restate
  fences stale handler attempts, which carry lower epochs, at the epoch boundary [25]. Neither fences the external
  side effect itself.
- **DBOS infrastructure (corrected).** PostgreSQL or CockroachDB; the Spring Boot starter requires PostgreSQL [26].
  Besides `JdbcStepFactory` there are `JdbiStepFactory` and `JooqStepFactory` [8].
- **gruelbox (made precise).** An entry is retried until it succeeds or is blocked after `blockAfterAttempts`; a blocked
  entry needs `unblock()` [12].
- **Restate.** Idempotency retention is configurable per service (`idempotencyRetention`, default 24 h) [27]. Restate's
  durable-agents guide says side effects of tool executions in `ctx.run` are executed exactly once [28]; its databases
  guide admits a small re-execution window for handler writes [29]. The re-run of a `run` block after a crash is still
  shown only by the third-party test.
- **temporal-spring-ai.** Model calls always run as activities; a tool runs as an activity only when it is an activity
  stub (Nexus stubs run as Nexus operations, `@SideEffectTool` in `Workflow.sideEffect()`, plain tools on the workflow
  thread) [22]. Published on Maven Central as `io.temporal:temporal-spring-ai` since 1.36.0; still Public Preview.
- **Spring Modulith.** 2.2 adds an `ABANDONED` status; the staleness monitor is off by default (all durations 0) [14].
- **Latest versions.** Temporal Java SDK 1.41.1 (16 Nov 2026) [3]; Restate Java SDK 2.11.0 (28 Oct 2026, unchanged) [6];
  DBOS Transact Java 1.3.0 (12 Nov 2026; Spring Boot starter since 0.8, 30 Apr 2026) [10]; gruelbox 7.1.785
  (21 Oct 2026, unchanged) [13]; Spring Modulith 2.2.0 (19 Nov 2026; 2.1.x: 2.1.3) [15]; db-scheduler 17.2.0
  (Nov 2026) [17]; JobRunr 9.0.5 (17 Nov 2026) [21]. Years confirmed from Maven Central publish dates.

24. https://docs.temporal.io/troubleshooting/request-failures and https://docs.temporal.io/activity-execution
25. https://docs.restate.dev/references/architecture
26. https://docs.dbos.dev/java/reference/lifecycle
27. https://docs.restate.dev/services/configuration
28. https://docs.restate.dev/ai/patterns/durable-agents
29. https://docs.restate.dev/guides/databases
