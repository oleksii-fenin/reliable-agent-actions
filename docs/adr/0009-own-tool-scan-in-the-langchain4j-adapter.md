# ADR-0009: Own `@Tool` scan in the LangChain4j adapter

* Status: **accepted** (2026-11-27)
* Deciders: project author ("Свой поиск @Tool", 2026-11-27), after the fact re-check of 2026-11-26
* Supersedes in part: ADR-0002, Decision 2 (`ReliableTools.wrap(Object bean, …)` "built on `ToolService.findTools`")
  and Decision 5 (the 1.14.0 floor "because of `findTools`")
* Related: `docs/research/framework-integration-surface.md` (Re-check 2026-11-26); `docs/DESIGN.md` §5

## Context

ADR-0002 builds `ReliableTools.wrap(Object)` on `ToolService.findTools(Object)` and, in Decision 5, forbids framework
internals (`@Internal`, non-public packages). The re-check of 2026-11-26 found that `ToolService` is annotated
`@Internal` in LangChain4j 1.14.0 and 1.24.1, so 0.1.0 breaks the rule it states. Nothing fails today, and CI tests
both ends of the supported range, but LangChain4j may change an internal class in any release.

What `findTools` finds depends on the LangChain4j version. This was checked in the sources of 1.14.0 and 1.24.1 and
in the bytecode of 1.15.0 to 1.23.0 from Maven Central, on 2026-11-27:

| LangChain4j | Methods scanned | Override without `@Tool` | `@P` checked when wiring |
|---|---|---|---|
| 1.14 | declared by the class | JDK proxy only (its interfaces) | no |
| 1.15 | declared by the class | JDK proxy only | yes |
| 1.16–1.19 | class, superclasses, interface default methods | JDK proxy only | yes |
| 1.20+ | same | any supertype (JDK and class-based proxies) | yes |

The public API covers everything the scan needs: `ToolSpecifications.toolSpecificationFrom(Method)`,
`DefaultToolExecutor.builder()` (with `wrapToolArgumentsExceptions` and `propagateToolExecutionExceptions`, both in
1.14.0), `Tool.returnBehavior()`, `AiServiceTool.builder()` and `IllegalConfigurationException`. None of them is
`@Internal` in 1.14.0 or 1.24.1. Only the method walk (`dev.langchain4j.internal.Utils`) and the `@P` check (a private
method of `ToolService`) are internal.

## Decision

1. `ReliableTools.wrap(Object)` finds the tools itself (`ToolBeanScanner`, package-private) and uses only the public
   API above. The main code of `raa-langchain4j` no longer references `ToolService`.
2. The scan follows LangChain4j 1.20 and later on every supported version: the concrete methods of the class and its
   superclasses, the default and static methods of its interfaces, an override hiding the overridden method, and a
   method without `@Tool` counted as a tool when a supertype declares the same signature with `@Tool`. The executor
   is configured as `findTools` configures it, so the adapter's error classification (DESIGN Revision 11) stays the
   same. A class, an `Iterable` or a bean without tools is rejected with `IllegalConfigurationException`, as before.
3. Tests compare the scan with `ToolService.findTools` as an oracle: on every version for beans without supertype
   tools, and from 1.20 on for every bean (`ToolBeanScannerTest`). The tests may use internals; the main code may
   not.

## Alternatives

| Option | Why not |
|---|---|
| Keep `findTools` and accept it in a new ADR, with CI watching | An `@Internal` class can change in any release; the ADR-0002 rule would carry an exception for the adapter's main entry point |
| Follow the scan of the LangChain4j version on the classpath | Copies four version-dependent behaviours and needs version detection at run time, for older versions that find fewer tools anyway |
| `ToolSpecifications.toolSpecificationsFrom(Object)` for discovery, then match methods by name | Before 1.20 it does not see JDK proxies' interfaces, unlike `findTools`; it would still need a method walk of its own |

## Consequences

* On LangChain4j 1.14 to 1.19, `wrap(bean)` can find more tools than `AiServices.tools(bean)`: inherited ones (1.14,
  1.15) and overrides in a class-based proxy, such as a Spring CGLIB bean. Each needs a policy, so an unexpected tool
  fails the wiring with "has no policy" and does not run unjournaled.
* LangChain4j's `@P` wiring check (1.15 and later) does not run for wrapped beans. A default value that does not parse
  fails the call with `ToolArgumentsException`, before the tool runs, so the action is definitely not applied.
* A scan change in a later LangChain4j release does not reach the adapter on its own. The oracle test against the
  latest version shows the difference.
