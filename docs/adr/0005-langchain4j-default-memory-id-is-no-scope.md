# ADR-0005: LangChain4j's default memory id is no scope

* Status: **accepted** (2026-11-16)
* Deciders: project author
* Refines: ADR-0002 §2 ("Scope = `InvocationContext.chatMemoryId()` unless the key strategy says otherwise")

## Context

The LangChain4j adapter takes the scope of an action key from the chat memory id of the invocation. For an AI service
method without `@MemoryId`, LangChain4j passes `ChatMemoryService.DEFAULT` (`"default"`) for every conversation
(checked in the sources of 1.14.0 and 1.23.0, `DefaultAiServices`: `findMemoryId(method, args).orElse(DEFAULT)`).
Taken as a scope, it would make actions of different users with the same business key one action: the second user
would get the first user's recorded result (`Replayed`) or a `Conflict`.

Options: (1) treat `"default"` as no scope; (2) pass it through and document that such services deduplicate across
users.

## Decision

Option 1. `ReliableToolExecutor` maps a `null` or `"default"` memory id to `ToolCall.scope = null`. A key strategy
that builds the key from `call.scope()` then cannot build one; the runner returns `Failed("invalid arguments: …")`
before anything is claimed (a null key component counts as invalid arguments, logged with its stack trace), and the
adapter adds to that text "this conversation has no scope: add @MemoryId to the AI service method or set the scope in
the tool's key strategy" — only for that error; other invalid arguments keep their own text. A key strategy that
sets its own scope (for example a tenant id from the arguments) works unchanged.

## Consequences

* A misconfigured service fails visibly on the first call instead of mixing users' actions.
* A conversation whose `@MemoryId` value is literally `"default"` is treated the same way; documented.
