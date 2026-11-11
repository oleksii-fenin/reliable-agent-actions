# Open questions — decisions for the author

Labels OQ1–OQ7 (not to be confused with quality scenarios Q1–Q7 in DESIGN). Each item: options, recommendation,
why, what it blocks. Decide before implementation week 1 unless noted.

## OQ1. Project name — **DECIDED 2026-11-09: `reliable-agent-actions`**

| Option | For | Against |
|---|---|---|
| `reliable-agent-actions` (working name) | says what it is; searchable | long; "reliable" is generic |
| `effectively-once` / `effectively-once-tools` | states the guarantee precisely, matches the docs' vocabulary | sounds like a pattern, not a product |
| `toolonce` / `once4j` | short, memorable, `4j` signals JVM | `once`, `agent-once` already exist in Python/TS — confusion; `4j` suggests a LangChain4j sub-project |

**Collision check (2026-11-09, GitHub search API `in:name`, Maven Central `search.maven.org` by artifactId):**

| Name | GitHub | Maven Central |
|---|---|---|
| `reliable-agent-actions` | no exact match (nearest: `reliable-agent-studio/reliable-actions-lab`, Python, 0★) | free |
| `toolonce` | free | free |
| `once4j` | **taken**: `Jashan-Khangura/once4j` (Java, 0★, 2026-05) | free |
| `effectively-once` | similar names (TypeScript payments demo; Heron topology, 2020) | free |

**DECIDED 2026-11-09:** `reliable-agent-actions` for the repository. Module prefix: `raa-` recommended (replaces the
placeholder `rea-` at implementation start). Blocks: repository creation, package names
(week 1).

## OQ2. License — **DECIDED 2026-11-09: Apache-2.0**

**Recommendation: Apache-2.0.** Same as LangChain4j, Spring AI, OpenTelemetry Java — removes friction for corporate
adopters and for a later `langchain4j-community` contribution; explicit patent grant. MIT is equally permissive but
without the patent clause; copyleft (LGPL/MPL) would deter exactly the organizations whose adoption is the goal.
Blocks: first public push.

## OQ3. Minimum Java version for users — **DECIDED 2026-11-09: build on JDK 25, `--release 17`, test 17/21/25**

| Option | Reach | Cost |
|---|---|---|
| **17** | covers every LangChain4j / Spring AI user (both build with Java 17, checked at LangChain4j 1.23.0, Spring AI 2.0.2) | no `ScopedValue`, no virtual-thread-specific APIs (not needed by the design) |
| 21 | most new projects; virtual threads | excludes Spring Boot 3 / Java 17 shops |
| 25 | newest language features | excludes most potential users; the library needs none of 25's features |

**Recommendation: build with JDK 25, emit `--release 17`, test on 17/21/25.** The prompt's "Java 25" stays true for the
toolchain and the demo; the user baseline is 17. Blocks: week 1 build setup.

## OQ4. Storages in v0.1 — **DECIDED 2026-11-09 (refined the same day)**

Decision (author): storage is a separate contract artifact. Published storage layer:
`raa-storage-api` (interfaces + data types, JDK only) ← `raa-core` (depends on it transitively) and ← `raa-postgres`
(depends on the API only, not on core). The application wires the implementation; core never knows which storage is
used, and any user can swap or write an implementation (dependency inversion). Supported implementation today:
**PostgreSQL only, ≥ 14** (decided). Other storages (MySQL, SQL Server, Oracle, …) are welcome as contributions.

Added by design to make that contract safe for third-party implementations: a **journal conformance test suite**
(`raa-journal-tck`, published) — the claim race, CAS/fencing, lease renewal predicates, `clock_timestamp()` semantics
and retention cases from DESIGN §9 written against the SPI. A storage implementation is "supported" only if it passes
the suite. An in-memory journal exists only in the testkit for unit tests and is not a production option.

## OQ5. Maven Central publication — **namespace: `io.github.oleksii-fenin` (registration pending)**

* Namespace: `io.github.<your-github-login>` (verified automatically through the GitHub account on the Sonatype Central
  Portal) — **or** your own domain if you own one (looks more professional, needs a DNS TXT record).
* When: **after v0.1** passes the full test plan, not earlier — a broken 0.0.x on Central is permanent.
* How: Central Portal + Gradle publishing plugin, GPG-signed, from GitHub Actions on tag; snapshots not published.
* Account: artifacts and commits are published under the author's own GitHub account and name.

Status 2026-11-09: namespace **`io.github.oleksii-fenin`** registered on the Sonatype Central Portal (verification
pending). Planned coordinates: `io.github.oleksii-fenin:raa-core`, `io.github.oleksii-fenin:raa-postgres`, …; Java
packages cannot contain `-`, so packages use `io.github.oleksiifenin.raa.*` (DECIDED 2026-11-09). Publishing waits for
v0.1 and the author's "ок"; the GPG signing key is not created yet. Blocks: week 7.

## OQ6. Spring AI in v0.1 or v0.2 (scope) — **DECIDED 2026-11-09: v0.2**

**Recommendation: v0.2.** v0.1 already fills 7 weeks at 8–12 h/week; LangChain4j is the primary target and the planned
upstream channel. Doing Spring AI in v0.1 adds ≈ 10–15 h (ASSUMPTION) and a second CI matrix. If the first adopters
turn out to be Spring-first, swap: Spring AI in week 5 instead of memory repair. Blocks: week 4 scope.

## OQ7. Upstream engagement timing

**Recommendation:** after week 5 (end-to-end kill test green), open a design discussion in LangChain4j referencing the
open memory-repair PR #4820 and quarkus-langchain4j #2744 ("tools are expected to be idempotent"), and offer the adapter
as a community module. Earlier = talk without evidence; later = risk that someone else fills the gap. Every public post
only after your "ок" on the exact text.
