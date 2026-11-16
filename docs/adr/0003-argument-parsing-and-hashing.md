# ADR-0003: Who parses tool arguments, and how argument hashes are computed

* Status: **accepted** (2026-11-11)
* Deciders: project author
* Related: `docs/DESIGN.md` §5 (`ToolCall`, `freeTextArguments`), §6 (rows 2, 4, 7, 14: "args differ"); ADR-0002
  (framework-free core)

## Context

The decision table compares a call with the journal row by two hashes: over the **material** arguments (conflict
check, idempotency key) and over **all** arguments including free text. §5 asks for canonical material arguments:
JSON keys sorted, numbers compared by value (`BigDecimal.stripTrailingZeros`), values coerced to the type in the
tool's schema. Two constraints:

* `raa-core` has no dependencies (CLAUDE.md, ADR-0002), so it has no JSON parser.
* The tool schema is known only to the framework adapter (LangChain4j `ToolSpecification`, Spring AI input schema).

Options considered:

1. **A minimal JSON parser in core** (~200 lines). One canonical form for all adapters, but core cannot coerce
   values to the schema type (`"10"` vs `10`), and a hand-written parser is a new correctness surface.
2. **The adapter parses, core hashes.** The adapter already parses the arguments for the tool invocation and knows
   the schema; it hands core a value tree. Core owns the canonical encoding and the hashing.

## Decision

Option 2.

1. `ToolCall` carries `arguments`: the parsed JSON arguments object as a value tree of `null`, `Boolean`, `String`,
   integral numbers or `BigDecimal`, `List` and `Map<String, ?>`. Floating-point values are rejected: the adapter
   converts JSON numbers from their text to `BigDecimal`. The raw `argumentsJson` stays for display and is never
   hashed.
2. Core computes both hashes (`ArgumentsHasher`, version tag `raa-arguments/v1`): SHA-256 over a type-tagged,
   length-prefixed encoding; map entries sorted by the UTF-8 bytes of their keys; numbers as the unscaled value and
   scale of `stripTrailingZeros()`, so `10`, `10.0` and `1E+1` are equal; strings with unpaired surrogates rejected.
   The material hash leaves out the top-level arguments declared by `freeTextArguments`.
3. Values are type-tagged: the string `"10"` and the number `10` differ. Coercing to the schema type is the
   adapter's job, so a type difference that reaches core is a real difference.
4. `KeyStrategy` reads the same tree through `Arguments`; a missing or mistyped argument fails the call as
   `Failed("invalid arguments: …", retryable)` before anything is claimed.

## Consequences

* Every adapter must build the same tree for the same JSON. Each adapter's tests include the shared canonicalization
  vectors (key order, number scale, nested objects) so two adapters cannot disagree silently.
* The encoding is persisted through the journal's hash columns. Changing it needs a new version tag; rows written with
  the old tag would otherwise compare as "args differ" (`Conflict`) after an upgrade.
* Free-text arguments are top-level names only; nested free text is not supported in v0.1.
