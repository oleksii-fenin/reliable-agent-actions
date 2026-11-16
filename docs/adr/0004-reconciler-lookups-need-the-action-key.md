# ADR-0004: Reconciler lookups need the action key; key text is opt-in

* Status: **accepted** (2026-11-16)
* Deciders: project author
* Narrows: ADR-0001 ("the reconciler … resolves `UNKNOWN` through `OutcomeLookup` where one is declared")
* Related: `docs/DESIGN.md` §5 (`OutcomeLookup`, `ReliableActions`), §6 (Reconciler), §7 (`key_text`), §12 (personal
  data risk)

## Context

`OutcomeLookup.lookup(ActionKey key, String idempotencyKey)` receives the business key, because many downstreams can
only be searched by a business reference (for example "the refund of order 42"), not by our idempotency key. The
journal stores `key_hash` (SHA-256 of the key) and, opt-in, `key_text`; until now nothing wrote `key_text`. Inline,
row 8 of the decision table has the key from the current call. The reconciler has only the row, so it cannot build an
`ActionKey` and cannot call the lookup. The idempotency key alone can be recomputed from `key_hash` and the material
arguments hash, but the business parts cannot.

Options considered:

1. **Opt-in key text.** `ReliableActions.builder().storeKeyText(true)` writes a reversible canonical text of the key
   into `key_text`; the reconciler parses it and looks up only those rows. Default off: no business identifiers in
   the database unless the application asks for it.
2. **No reconciler lookups in v0.1.** The reconciler only moves expired leases to `UNKNOWN` and escalates or abandons at
   the deadline; lookups happen on the next call. A `RECONCILABLE` action whose conversation never resumes is escalated
   instead of resolved.
3. **Always store the key.** Simplest, but business identifiers (order ids, e-mail addresses) land in the database by
   default, against "keys and arguments hashed by default" (§12).

## Decision

Option 1.

1. `ActionKey.toText()` returns a reversible canonical text, `ActionKey.parse(String)` reads it back:
   `raa-key/v1` followed by each component (scope, name, business parts) as `;<length>:<text>`, the length counted in
   UTF-16 code units. Length prefixes make the text unambiguous whatever the components contain, and it stays
   readable for operators.
2. `ReliableActions.Builder.storeKeyText(boolean)`, default `false`. When on, every claim writes the key text. A key
   that contains U+0000 (PostgreSQL text rejects it) is stored without text, with a warning; the reconciler then treats
   that row like one without key text.
3. The reconciler looks up an `UNKNOWN` row only if it has key text that parses. Without it the row is left to the next
   call (row 8 looks up inline); at or after the deadline the reconciler escalates it, as it does for a policy without a
   lookup.

## Consequences

* Applications that want unattended resolution of `RECONCILABLE` actions (scenario Q4) must enable key text and accept
  business identifiers in `key_text`; the README says so next to the policy. Retention and `redact` (roadmap week 7)
  cover this column.
* The text format is persisted. Changing it needs a new version tag; rows written with an unknown tag are treated as
  having no key text (logged), never mis-parsed.
* Turning the option on later fills `key_text` when a key starts its next attempt (`coalesce(?, key_text)`); rows that
  start no further attempt keep no text.
