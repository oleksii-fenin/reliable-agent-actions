# LangChain4j 1.23.0 and Spring AI 2.0.2: tool-execution integration surface

> Desk research, 2026-11-09, by an AI research agent from shallow clones of the sources at the named tags and GitHub pages. Not re-verified by the author. Re-check before publishing.

Report below. I checked LangChain4j at tag `1.23.0` and Spring AI at tag `v2.0.2` against shallow clones of the source. LC4J = `https://github.com/langchain4j/langchain4j/blob/1.23.0/`, SAI = `https://github.com/spring-projects/spring-ai/blob/v2.0.2/`.

**Versions (GitHub Releases API)**
- **LangChain4j:** the latest is **1.23.0**, published 2026-10-28.
- **Spring AI:** the latest GA is **v2.0.2**, published 2026-10-23. v2.1.0-RC2 (2026-11-05) is a pre-release.
- **Java baseline:** both build with `java.version` = **17**.
  - LangChain4j: LC4J `langchain4j-parent/pom.xml` sets `maven.compiler.release=${java.version}`.
  - Spring AI: SAI `pom.xml` line 241, with Spring Boot 4.1.2.
- **Consequence:** a Java 17 baseline covers both. Requiring 21 or 25 would cut off users of both frameworks.

## LangChain4j

**1. `ToolExecutor`** (`dev.langchain4j.service.tool`, LC4J `langchain4j/src/main/java/dev/langchain4j/service/tool/ToolExecutor.java`)
- It is a `@FunctionalInterface` with three methods:
  - `String execute(ToolExecutionRequest request, Object memoryId)`, the abstract method.
  - `default ToolExecutionResult executeWithContext(ToolExecutionRequest, InvocationContext)`, which calls `execute(req, context.chatMemoryId())`.
  - `@Experimental default CompletableFuture<ToolExecutionResult> executeAsync(ToolExecutionRequest, InvocationContext)`, which by default fails unless overridden.
- `InvocationContext` (`dev.langchain4j.invocation`, in `langchain4j-core`) carries `invocationId` (UUID), `interfaceName`, `methodName`, `methodArguments`, `userMessage`, `chatMemoryId`, `invocationParameters`, `managedParameters` and `timestamp`.
- `ToolExecutionResult` holds `isError`, `result` (Object), `resultText` (from a lazy supplier) and `attributes`.

**2. `ToolExecutionRequest`** (`dev.langchain4j.agent.tool`, LC4J `langchain4j-core/.../agent/tool/ToolExecutionRequest.java`)
- It has three final fields: `id`, `name` and `arguments` (a JSON string).
- **`id` is copied verbatim from the provider:**
  - OpenAI: `.id(toolCall.id())` in `langchain4j-open-ai/.../internal/OpenAiUtils.java:540`.
  - Anthropic: `.id(content.id)` in `AnthropicMapper.java:395`.
  - Gemini: `.id(functionCall.id())` in `FunctionMapper.java:79`.
  - Ollama: `.id(toolCall.getId())` in `InternalOllamaHelper.java:95`.
- LangChain4j never generates the id itself, so a regenerated response gets a new id.
- For Gemini and Ollama, `id` may be null. UNVERIFIED: whether each provider always sends one.
- **Do not key idempotency on `id`.** This matches your design.

**3. Registration and generic wrapping** (LC4J `.../service/AiServices.java` lines 505–624)
- Ways to register tools: `tools(Object...)`, `tools(Collection<Object>)`, `toolProvider(ToolProvider)`, `toolProviders(...)` and `tools(Map<ToolSpecification,ToolExecutor>)`.
- **`tools(List<AiServiceTool>)`** (since 1.14.0) carries the spec, the executor and the `ReturnBehavior` together. `tools(Map, Set<String> immediateReturnToolNames)` is deprecated in its favour.
- **Public introspection:**
  - **`ToolService.findTools(Object) : List<AiServiceTool>`** is `public static` and documented `@since 1.13.0` (`ToolService.java:257`). It builds a `DefaultToolExecutor` for each `@Tool` method and keeps its `returnBehavior`.
  - `ToolSpecifications.toolSpecificationsFrom(Object|Class)` is also public.
  - `DefaultToolExecutor` has public constructors `(Object, Method)`, `(Object, Method originalMethod, Method methodToInvoke)` and a Builder.
- **Wrapping recipe:** `findTools(bean)`, then wrap each `toolExecutor()` in a decorator, then pass the list to `.tools(List<AiServiceTool>)`.

**4. Hooks** (all present in 1.23.0, `AiServices.java`)
- `beforeToolExecution(Consumer<BeforeToolExecution>)` receives `request()` and `invocationContext()`.
- `afterToolExecution(Consumer<ToolExecution>)` provides request, result, `hasFailed`, `attributes` and start/finish times.
- `toolExecutionErrorHandler` and `toolArgumentsErrorHandler` have the signature `ToolErrorHandlerResult handle(Throwable, ToolErrorContext)`.
- `hallucinatedToolNameStrategy` takes a `Function<ToolExecutionRequest, ToolExecutionResultMessage>`.
- Also present: `executeToolsConcurrently()` / `(Executor)`, `maxSequentialToolsInvocations`, `maxToolCallingRoundTrips` and `toolSearchStrategy`.
- `registerListeners(AiServiceListener...)` delivers events such as `ToolExecutedEvent` and `ToolCompensatedEvent` (LC4J `langchain4j-core/.../observability/api/event/`).
- `ReturnBehavior` (`TO_LLM`, `IMMEDIATE`) is in `langchain4j-core/.../agent/tool/ReturnBehavior.java`.
- **New and relevant to you:** `compensateOnToolErrors(boolean)` with `@CompensateFor` (since 1.17.0).
  - When a tool fails, the compensating actions of the tools that already ran are called in reverse order.
  - It works only for `@Tool` methods; MCP or programmatic tools are not supported (`CompensateFor.java`).
  - This is a saga-style undo within a single invocation. It is not durable.

**5. Chat memory ordering** (LC4J `ToolService.executeInferenceAndToolsLoop`, roughly lines 645–720)
- The `AiMessage` with the tool requests is written to memory (`chatMemory.add(aiMessage)`) **before** execution.
- The tools in the round then run.
- Only after the whole round finishes does `persistToolResultsAndResolveMessagesSync` add the `ToolExecutionResultMessage`s (`resultMessages.forEach(chatMemory::add)`).
- **If the JVM dies after the side effect but before that write:** the persistent store holds an `AiMessage` whose tool calls have no results. I found no repair code at 1.23.0; grep for dangling/orphan handling finds only eviction of orphaned *result* messages in `MessageWindowChatMemory` and `TokenWindowChatMemory`.
  - On the next call that history is sent as-is. Providers such as OpenAI reject an assistant `tool_calls` message that has no tool responses following it.
  - Open bug #3133 records an `InvalidRequestException` caused by corrupt memory. Open PR #4820, "repair unanswered/orphaned tool calls in chat memory", explicitly names "application restart mid tool execution".
  - The model therefore never sees whether the tool ran. Your journal is the only record of the outcome. A memory-repair step that injects a synthetic result read from your journal would be useful.
  - UNVERIFIED: the exact error text each provider returns.

**6. Durability and idempotency**
- **`langchain4j-agentic` has persistence:**
  - The SPI is `AgenticScopeStore` (`save`/`load`/`delete`/`getAllKeys`), set via `AgenticScopePersister.setStore` or ServiceLoader.
  - Scopes are checkpointed after each **sub-agent** invocation or suspension (`PlannerBasedInvocationHandler.java` ~545/561, `DefaultAgenticScope.checkpoint`), and also flushed at the end of the root call.
  - `SuspendedResponse` and `AgenticSystemSuspendedException` support human-in-the-loop resume.
  - Checkpoints are per agent, **not per tool call**.
- **No tool idempotency exists in the core repo.** Grep of the main sources finds only unrelated uses of "idempotent".
- Related open work:
  - PR #4638 and issue #4637, "durable long-lived task execution layer (experimental)".
  - PR #4820, mentioned above.
- **quarkus-langchain4j:**
  - Issue #2744 reported that `@Retry` on an AI service method with tools silently re-runs tool side effects.
  - It was resolved by merged PR #2749, which is documentation only. Its stated position: "tools are expected to be idempotent". This is direct evidence of the gap your library fills.

**7. OpenTelemetry**
- Grep of 1.23.0 finds no `execute_tool` or `gen_ai.tool` in LangChain4j main code.
- `langchain4j-observation` contains only a chat-model observation convention.
- **Conclusion: LangChain4j emits no tool span.** UNVERIFIED: whether quarkus-langchain4j or a Spring Boot starter adds one.
- **The spec:** the GenAI conventions have moved from `open-telemetry/semantic-conventions` (latest v1.45.0, 2026-11-02, where the old page now says "Moved") to `open-telemetry/semantic-conventions-genai`, file `docs/gen-ai/execute-tool.md` (main branch; I found no tagged release).
  - Span `gen_ai.execute_tool`, status **Development**.
  - Name `execute_tool {gen_ai.tool.name}`, kind `INTERNAL`.
  - Required: `gen_ai.operation.name=execute_tool` and `gen_ai.tool.name`.
  - Conditionally required: `gen_ai.tool.call.id` ("if and only if the model or provider supplied" one), `gen_ai.agent.name` and `gen_ai.conversation.id`.
  - Recommended: `gen_ai.tool.type` and `gen_ai.tool.description`.
  - Opt-in: `gen_ai.tool.call.arguments` and `gen_ai.tool.call.result`.
  - It also defines a metric `gen_ai.execute_tool.duration` and an event `gen_ai.execute_tool.exception`.

## Spring AI 2.0.2

**8. Tool API**
- **`ToolCallback`** (`org.springframework.ai.tool`, SAI `spring-ai-model/.../tool/ToolCallback.java`): `ToolDefinition getToolDefinition()`, `default ToolMetadata getToolMetadata()`, `String call(String toolInput)` and `default String call(String, @Nullable ToolContext)`.
- **`ToolCallingManager`**: `resolveToolDefinitions(ToolCallingChatOptions)` and `ToolExecutionResult executeToolCalls(Prompt, ChatResponse)`.
- **`ToolExecutionExceptionProcessor`**: `String process(ToolExecutionException)`.
- **`internalToolExecutionEnabled` has been removed** (SAI `spring-ai-docs/.../upgrade-notes.adoc` ~430):
  - The loop now lives in **`ToolCallingAdvisor`** (`spring-ai-client-chat/.../advisor/ToolCallingAdvisor.java`), which takes a `ToolExecutionEligibilityChecker`.
  - For a user-controlled loop, call `ChatModel` directly and drive it with `ToolCallingManager`.
- **The tool-call id is not available to a `ToolCallback`.**
  - `DefaultToolCallingManager.buildToolContext` (~207) copies only `ToolCallingChatOptions.getToolContext()`, the user-supplied map.
  - `toolCall.id()` goes only into `ToolCallingObservationContext` and into the `ToolResponse`.
  - UNVERIFIED: the 1.0.x `TOOL_CALL_HISTORY` key; it is not present in the v2.0.2 main sources.

**9. Idempotency and observability**
- I found no idempotency or durable tool execution, either in main sources or via GitHub issue search. The closest item is PR #6462, which prevents identical-argument tool-call loops.
- **Observation** `spring.ai.tool` (`DefaultToolCallingObservationConvention.DEFAULT_NAME`):
  - Contextual name `execute_tool {toolName}`.
  - Low-cardinality keys: `gen_ai.operation.name`, `gen_ai.system`, `spring.ai.kind`, `spring.ai.tool.definition.name`, `spring.ai.tool.type`.
  - High-cardinality keys: `spring.ai.tool.call.id`, `.arguments`, `.result`, `.definition.description`, `.definition.schema`.
  - These are Spring-prefixed names, not the semconv `gen_ai.tool.*` names.

## Recommended decoration point for LangChain4j
Decorate at the **`ToolExecutor`** level by overriding `executeWithContext` (and `executeAsync` if you support async).
- Input comes from `ToolService.findTools(bean)` (public since 1.13) and output goes back through `AiServices.tools(List<AiServiceTool>)`, which keeps `ReturnBehavior`.
- `executeWithContext` gives you the tool name, the argument JSON (for your business key), `chatMemoryId` and `invocationId` (for scoping). It runs synchronously around the side effect, so you can write the journal row before running the side effect and update it afterwards.
- Programmatic or MCP tools can be wrapped the same way through `ToolProvider`.
- The `beforeToolExecution` and `afterToolExecution` hooks and listeners are only observers: they cannot skip a call or substitute a result, so they cannot provide the effectively-once guarantee.
- Add a resume helper that repairs dangling tool calls in memory using journal outcomes, since the framework lacks one (PR #4820 is still open).

## Recommended decoration point for Spring AI
Use a **delegating `ToolCallback`** that wraps each callback from `ToolCallbacks.from(bean)` or MCP, applied by a `BeanPostProcessor` or a `ToolCallbackResolver` wrapper.
- This works with both `ToolCallingAdvisor` and a manual loop.
- Your business-args key fits well because the callback never sees the provider id.
- Scope (conversation or tenant) has to come from the user-supplied `ToolContext` map.
- If you need the provider tool-call id (for correlation or the `gen_ai.tool.call.id` span attribute), you would have to provide a custom `ToolCallingManager`. That is heavier and means reimplementing `DefaultToolCallingManager`'s limits, observation and exception logic, so keep it optional.