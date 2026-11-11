# Agent tool-call idempotency: landscape

> Desk research, 2026-11-09, by an AI research agent from public docs/GitHub pages. Not re-verified by the author. Items marked UNVERIFIED are unconfirmed. Re-check every fact before publishing.

The niche is real but narrow. The "idempotency ledger for agent tool calls" idea is not original: there are at least six small open-source implementations, all in Python, TypeScript or Go. Durable-execution engines already cover the "resume after crash without redoing completed steps" part, including on the JVM. What I found no JVM library doing is per-tool-call deduplication with explicit handling of unknown outcomes. That is the only defensible spot.

**Limits of this research:** `gh` was denied, so star counts come only from pages I fetched. I did not read any of these libraries' source code.

## 1. Tools aimed at agent tool calls (any language)

**Durable-execution engines.** These give durable steps: a tool step that completed is replayed from history and not run again. None of them solves a step that crashed mid-flight. The step re-runs, so the tool itself still has to be idempotent.
- **Temporal + Spring AI (Java):** Public Preview, on Temporal Java SDK 1.35 and Spring AI 1.1. `@Tool` methods on Activity interfaces run as Activities with retries and timeouts. The docs do not cover duplicate external effects. https://docs.temporal.io/develop/java/integrations/spring-ai
- **Restate:** its integration guide lists LangChain4j and Spring AI. The pattern is to wrap each tool call in `ctx.run`. I found no Java sample (UNVERIFIED). https://docs.restate.dev/ai/sdk-integrations/integration-guide.md
- **DBOS:** has integrations for the OpenAI Agents SDK and Pydantic AI, both Python. A third-party comparison notes that a crashed in-flight tool call re-runs. https://docs.dbos.dev/integrations/openai-agents , https://www.developersdigest.tech/blog/durable-ai-agent-frameworks-compared-2026
- **Microsoft Agent Framework:** durable agents on the Durable Task stack, in preview. They checkpoint tool results, but the backend is tied to Azure. https://learn.microsoft.com/azure/durable-task/sdks/durable-task-for-ai-agents , https://www.diagrid.io/blog/still-not-durable-how-microsoft-agent-framework-and-strands-agents-repeat-the-same-mistake
- **LangGraph:** on resume, the interrupted node re-runs from its start, so side effects placed before `interrupt()` must be idempotent. This is documented behaviour, and the docs leave idempotency to the user. https://forum.langchain.com/t/twice-execution-of-agent-when-using-the-interrupt/2964
- **Not researched:** Inngest, Trigger.dev, Bedrock AgentCore, Cloudflare, Golem and Letta. From prior knowledge (UNVERIFIED) they follow the same step-memoization model.

**Dedicated ledger libraries.** These are your direct conceptual competitors. All are tiny, and I saw 0 stars where the page showed it.
- **frogscooper/once** (Python), the closest match to your design. Records move through `pending`, `done`, `failed` and `unknown`, and `unknown` is never retried automatically. It has a `verify` callback with ready-made Stripe and HTTP verifiers, takeover via `stale_after` (it warns a slow call can run twice), Postgres or SQLite storage, and decorators for MCP, LangChain, OpenAI Agents, Pydantic AI and CrewAI. 0 stars. https://github.com/frogscooper/once
- **rune0-dev/agent-ledger** (Python): Postgres, key derived from `(workflow, tool, args)`, timeout-based stale takeover, approvals. No fencing and no reconciler. https://github.com/rune0-dev/agent-ledger
- **ajibadedapo/agent-once** (TS/Python): an MCP stdio proxy with read-back reconciliation. 0 stars. https://github.com/ajibadedapo/agent-once
- Also: swarmproof/exactly-once (Python), knbrlo/exactly-once-agent-actions (Go, Postgres + outbox + saga), bobbyhalljr/tool-call-idempotency (TS, teaching code). https://github.com/swarmproof/exactly-once , https://github.com/knbrlo/exactly-once-agent-actions , https://github.com/bobbyhalljr/tool-call-idempotency

**MCP.** `idempotentHint` and `destructiveHint` are hints only. Idempotency keys were deferred during SEP-1686 (UNVERIFIED, from a python-sdk PR). SEP-3182 "Request Idempotency" proposed an optional `idempotencyKey` on `tools/call`. It was closed unmerged on 2026-08-23 for being undisclosed AI-generated, not on technical grounds. A question about in-flight duplicates and downstream provider keys was left unanswered. The spec gap is still open. https://github.com/modelcontextprotocol/modelcontextprotocol/pull/3182

## 2. JVM

- **Koog (JetBrains):** checkpoints plus `RollbackToolRegistry` (since 0.5.0). This is compensation on rollback, not deduplication. https://docs.koog.ai/agent-persistence
- **Embabel 1.0** (InfoQ, Aug 2026): replans when a tool fails. The article says nothing about durability or idempotency. https://infoq.com/news/2026/08/embabel-1
- **Akka Agents:** durable agent memory, with durable workflows for step guarantees. Nothing on tool-call deduplication found. https://doc.akka.io/akka-agents.html
- **DBOS Java:** exists and is production-ready since 1.0 (July 2026); current version is 1.2. It has a Spring Boot starter and `@TransactionalStep`. I found no Spring AI or LangChain4j integration. It is the most dangerous adjacent competitor: Postgres-backed, Spring-native, and a short step from "durable tools". https://docs.dbos.dev/java/programming-guide , https://dbos.dev/blog/new-in-dbos-july-2026
- **LangGraph4j, Quarkus LangChain4j, Spring AI core:** I found nothing on tool-call deduplication (UNVERIFIED that none exists; I did not search their issue trackers). One blog notes Spring AI 2.0.1 has no max-iterations cap but offers a tool-execution eligibility hook. That hook is a possible integration point. https://ankurm.com/spring-ai-langchain4j-production-ai-microservices-java/
- **JobRunr, db-scheduler:** job runners, not relevant to per-call deduplication.
- **Result:** I found no JVM library doing per-tool-call deduplication for agents.

## 3. Java idempotency-key libraries

- **transferwise/idempotence4j:** Postgres, Spring starter, about 58 stars. It runs the action and the status update in one DB transaction. https://github.com/transferwise/idempotence4j
- **josipmusa/idempotency4j:** a new generic engine for Java 21 and Spring Boot 4. Its README says plainly that it does not guarantee exactly-once for arbitrary downstream side effects. https://github.com/josipmusa/idempotency4j
- Many smaller `@Idempotent` starters exist. One, `mustafakemalv/idem-client-spring-boot-starter`, is client-side for outbound WebClient calls, but per its search snippet it only mints keys.

These libraries dedupe incoming requests on the server. Your library would sit on the caller side, where the risk is different: the effect happens in someone else's system and the outcome may be unknown. None of them has a reconciler, fencing, or a key derived from business arguments.

## 4. Demand evidence

- **Real but early, and mostly not from JVM users.**
- OpenAI forum thread "Tool calls that fire twice", Oct 30 – Nov 1, 2026. It states your exact premise: `tool_call_id` is not a mutation id. It converges on a pending state and a downstream check. https://community.openai.com/t/tool-calls-that-fire-twice-idempotency-keys-before-the-model-retries/1411223
- A steady stream of 2026 blog posts (tianpan.co, formation.dev, DEV, HackerNoon). https://tianpan.co/blog/2026/04/19/idempotency-agentic-tool-calling-saga-deduplication
- arXiv preprints, e.g. "Where Does Exactly-Once Live?" (2609.29095). It finds a ledger only bounds issuance, and physically exactly-once effects also need an idempotent sink. That supports your reconciler focus. https://arxiv.org/pdf/2609.29095
- LangChain Python issue #33787 reportedly covers a timeout retry re-running a side-effecting tool (UNVERIFIED, could not open).
- I found no LangChain4j or Spring AI issues on this.
- The number of repos with 0 stars suggests it is easy to build and hard to get adopted.

## Conclusions

**(a) Where the niche is real**
- JVM teams already on Postgres and LangChain4j or Spring AI who will not adopt Temporal or Restate.
- The parts nobody does well:
  - a reconciler for the unknown outcome left by a crash mid-call
  - fencing so an expired lease holder cannot commit
  - keys derived from business arguments, not `tool_call_id`
  - explicit per-tool policy: retry-safe / verify-then-retry / never-auto-retry
- Engines memoize completed steps. Nobody owns the crashed-mid-call window on the JVM.

**(b) Where competitors are clearly stronger**
- **Whole-run durability:** Temporal, Restate, DBOS Java and MS Agent Framework win outright. Do not compete there.
- **Rollback:** Koog's rollback model is already shipped.
- **The ledger concept:** frogscooper/once already has the same state model and verifiers. It is Python and unadopted, but your design is not new.
- **The biggest threat:** DBOS shipping a Spring AI tool integration. That would erase most of the "no workflow engine, just Postgres" pitch, though not the reconciler.

**(c) Why a Java team would pick yours**
- One table in an existing Postgres, no new infrastructure.
- A decorator over existing `@Tool` beans.
- It works with or inside Temporal or DBOS activities. They make the step durable; you make the external effect safe.
- An honest UNKNOWN state plus a pluggable verifier (Stripe-style lookup, HTTP GET by key).

Position it as complementary to durable engines, not a replacement. Otherwise every architecture review will ask "why not Temporal?"

**(d) Proposed shift.** The ledger alone is weak.
1. Lead with unknown-outcome reconciliation: the Verifier SPI, a reconciler job, fencing, and per-tool policy. Treat the ledger as plumbing. That is where the claim "exactly-once needs the sink" from the research is addressed.
2. Ship it as small adapters (a LangChain4j `ToolExecutor` decorator, a Spring AI `ToolCallback` wrapper) over a framework-free core. Propose the LangChain4j piece upstream as a `langchain4j-community` module early. Distribution matters more than code here.
3. A second front with less competition: a server-side variant for MCP Java SDK / Spring AI MCP servers. It would accept a `_meta` idempotency key now and follow a future SEP. Comment on the MCP idempotency discussion while it is still open.
4. Drop "exactly-once" from the name and use "effectively-once" with stated preconditions (idempotent or verifiable sink). The research literature and every competing README already say a ledger alone can't promise it.