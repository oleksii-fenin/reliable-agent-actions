# ADR-0002: Integration boundary with agent frameworks

* Status: **accepted** (2026-11-09)
* Deciders: project author
* Related: ADR-0001; `docs/research/framework-integration-surface.md` (LangChain4j 1.23.0, Spring AI 2.0.2, checked
  2026-11-09)

## Context

The guarantee of ADR-0001 needs code that runs **around** the side effect: commit the journal row before the effect,
skip the effect and return the recorded result on replay, record the outcome after it. The integration must:

* work with existing `@Tool` beans without rewriting them (users adopt by wiring, not by refactoring);
* see the tool name and the arguments (for the business key) and a scope (conversation / memory id);
* be able to **skip** the call and **substitute** the result;
* not depend on a workflow engine and not fork the framework;
* survive framework upgrades (both frameworks release monthly; LangChain4j 1.13 → 1.23 in about a year).

Facts (sources in the research file):

* LangChain4j: `ToolExecutor.executeWithContext(ToolExecutionRequest, InvocationContext)` returns a
  `ToolExecutionResult`; `ToolService.findTools(Object)` (public, since 1.13.0) turns a `@Tool` bean into
  `List<AiServiceTool>`; `AiServices.tools(List<AiServiceTool>)` (since 1.14.0) accepts them back with `ReturnBehavior` preserved.
  `beforeToolExecution` / `afterToolExecution` / listeners are observers: they cannot skip a call.
  `@CompensateFor` (1.17.0+) is an in-memory, non-durable undo within one invocation.
* Spring AI 2.0.2: `ToolCallback.call(String, ToolContext)`; the provider tool-call id is **not** passed to callbacks;
  the tool loop lives in `ToolCallingAdvisor` / `ToolCallingManager`. Observation `spring.ai.tool` exists.
* LangChain4j has no repair for a tool call left without a result in persistent memory after a crash (open PR #4820);
  quarkus-langchain4j documented "tools are expected to be idempotent" (PR #2749) instead of solving it.
* Both frameworks build with Java 17.

## Decision

1. **Framework-free core.** `raa-core` exposes `ActionRunner.run(ToolCall, Effect)` (policy looked up by tool name) and knows nothing
   about LLM frameworks. Any caller — an agent tool, an MCP server handler, a plain service — can use it.
2. **Thin adapters that decorate the framework's tool executor:**
   * LangChain4j: `ReliableToolExecutor implements ToolExecutor`, overriding `executeWithContext` and `execute`; `executeAsync` throws
     `UnsupportedOperationException` in v0.1 (async tool execution is a non-goal); tools with `ReturnBehavior.IMMEDIATE`
     are rejected unless explicitly allowed, because library texts would become the final user-facing answer;
     `ReliableTools.wrap(Object bean, ReliableActions actions) : List<AiServiceTool>` built on `ToolService.findTools`.
     Scope = `InvocationContext.chatMemoryId()` unless the key strategy says otherwise; provider call id stored for
     tracing and memory repair.
   * Spring AI (second): `ReliableToolCallback implements ToolCallback` delegating to the original; scope from the
     user-supplied `ToolContext` map (documented key), since the provider id is not available.
3. **Memory repair (LangChain4j) as a separate, opt-in component**: `JournalChatMemoryRepair` finds assistant tool
   calls without results in a `ChatMemory` before the next model call, looks them up through the call-id history (every provider call id
   ever mapped to an action, so a superseded id still resolves), skips calls whose attempt is still live, and inserts a synthetic `ToolExecutionResultMessage` with the text produced by the same
   decision table in inspect mode (DESIGN §6): the recorded result, "safe to call again with the same arguments",
   "calling again may perform it twice", "being verified, ask again later", "not performed; you may call again", or
   "could not be verified, an operator has been notified". Without it, adapters still deduplicate by business key.
4. **Adapters own only translation**: arguments JSON → `ToolCall`, `ActionOutcome` → result text for the model
   (`OutcomeRenderer`, replaceable). Policies and keys are declared once in core types and are identical across
   frameworks.
5. **Compatibility contract**: each adapter declares a tested framework version range in CI (LangChain4j: 1.14.0 as
   the floor because of `findTools` + `tools(List<AiServiceTool>)`, plus latest; Spring AI: 2.0.x latest). No use of framework internals
   (`@Internal`, non-public packages).

## Options considered

| Option | Why rejected / deferred |
|---|---|
| **A. Core + executor/callback decorators (chosen)** | — |
| B. Annotation + AOP proxy on tool methods (`@ReliableAction(key = "…")`) | Framework-agnostic and pleasant in Spring, but keys in annotation strings (SpEL) are untyped and hard to test; needs AOP infrastructure outside Spring; proxies interfere with the frameworks' own reflection on `@Tool` methods. Can be added later on top of A as Spring-only sugar. |
| C. Framework hooks / listeners only | Cannot skip a call or substitute the recorded result, so cannot implement replay. Used only for telemetry correlation. |
| D. Contribute directly into LangChain4j core | Highest reach, but slow review, design must fit "tools are user code" philosophy, and the PostgreSQL journal does not belong in core. Plan: after v0.1 open a design discussion and offer the adapter as a `langchain4j-community` module; the standalone library stays the reference implementation. |
| E. Wrap the whole agent/AI-service invocation (durable agent loop) | That is workflow-engine territory (Temporal, Restate, DBOS, `langchain4j-agentic` checkpoints); far larger scope and competes with mature products. Non-goal. |
| F. Custom `ToolCallingManager` for Spring AI (to get the provider call id) | Reimplements loop limits, observation and exception handling; brittle across releases. Optional later if memory repair is needed for Spring AI. |
| G. MCP server-side adapter in v0.1 | Valuable (MCP has only `idempotentHint` hints; an idempotency-key proposal SEP-3182 was closed unmerged 2026-08-23), but the spec is moving. Deferred to v0.2 on top of core; no core change required. |

## Consequences

Good:
* Adoption is wiring only: `ReliableTools.wrap(bean, actions)` instead of `.tools(bean)`.
* The guarantee lives in core and is tested once; adapters are small (estimated < 300 lines each, ASSUMPTION) and
  tested by contract tests per framework version.
* Works inside a Temporal activity or DBOS step too — complementary to durable engines, not a competitor.

Bad / accepted:
* Two adapters to follow monthly framework releases; CI matrix is the mitigation.
* Spring AI cannot repair memory or tag spans with the provider call id in v0.1.
* Users who register tools by other means (`ToolProvider`, MCP clients) need an explicit wrap call per provider;
  documented, adapter offers a `ReliableToolProvider` decorator in v0.2 (decided 2026-11-09).

## Compliance

* ArchUnit (or a Gradle dependency check): `raa-core` has no dependency on `dev.langchain4j` or `org.springframework`.
* Adapter contract tests: scripted fake chat model emits a tool call, process crash is simulated, the call is
  re-emitted with a new id → tool body invoked once, model receives the recorded result.
* Memory-repair test: persistent memory with a dangling tool call → repaired message present, next model request
  well-formed (validated by the fake model against "every tool call has a result").
* CI runs adapter tests against the declared floor and latest framework versions.
