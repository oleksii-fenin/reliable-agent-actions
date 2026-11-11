# CLAUDE.md

Guidance for Claude Code (and other coding agents) working in this repository.

## Purpose

Open-source Java library that makes side-effecting tool calls of AI agents **effectively-once**: a durable action
journal in the application's own PostgreSQL, an idempotency key derived from business arguments, leases with fencing,
deadlines and a reconciler for actions whose outcome is unknown after a crash. First integration: LangChain4j;
second: Spring AI. Working name `reliable-agent-actions` (see `docs/OPEN-QUESTIONS.md`).

This is the author's flagship public project. Its value is **correctness that is proven by tests**, honest
documentation of non-guarantees, and adoption by other organizations — not feature count.

## Status and the design gate

* Current status: **DESIGN APPROVED** (2026-11-09, author: "постепенно начнём"); implementation week 1 in progress. Source of truth: `README.md`, `docs/DESIGN.md`, `docs/adr/`,
  `docs/OPEN-QUESTIONS.md`.
* **No implementation code until the author writes "дизайн утверждён" (design approved).** Until then only the
  design documents may change.
* After approval: implement strictly by the roadmap in `docs/DESIGN.md` §11, stage by stage; every stage ends with a
  green build and its tests executed. First stage: core + PostgreSQL journal + the process-kill test.
* Accepted ADRs are constraints. Changing a decision = a new ADR that supersedes the old one (`docs/adr/NNNN-title.md`);
  never rewrite an accepted ADR in substance.

## Clean-room rules (mandatory)

* Implementation from scratch. **No code, class names, service names, table names, ticket numbers or other details
  from the author's employers** (current or past), not even "from memory". Only general, publicly known patterns
  (transactional outbox, leases, fencing tokens, reconciliation) may be used.
* Do not open corporate folders (`/srv/ai-corporate-projects`, `~/projects`).
* Facts about other projects (LangChain4j, Spring AI, Temporal, Restate, DBOS, …) must carry a source link and the date
  checked; re-verify before any public release.

## Engineering rules

* Never claim "compiles", "tests pass" or "verified" without the executed command and its output.
* Every guarantee in the README must have an automated test that would fail if the guarantee broke; a guarantee
  without such a test is labeled "argued, not verified".
* Time decisions (lease, deadline) use database time, never JVM clocks.
* `raa-core` has no framework dependencies; adapters depend on core, never the other way round.
* No speculative abstractions: a new module, SPI or option needs a scenario in `docs/DESIGN.md`.
* Tests: Testcontainers for PostgreSQL; no `Thread.sleep` for synchronization in tests (use latches, polling with
  timeout, or injected fault points).

## Public actions

Local commits — allowed during implementation. Creating the GitHub repository, pushing, publishing artifacts, opening
issues/discussions in other projects — **only after the author's explicit "ок" for that specific action**.

## Language

Code, docs and commit messages in English. Reports to the author in Russian, short: what was done, what to decide.
