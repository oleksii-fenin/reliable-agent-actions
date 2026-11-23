# ADR-0008: The clock test shifts the database clock

* Status: **accepted** (2026-11-23)
* Deciders: project author ("Accept ADR-0008", 2026-11-23), after the clock-skew test and its reviews
* Supersedes in part: ADR-0001, Compliance ("Clock test: the child JVM runs under libfaketime shifted ±1 h")
* Related: `docs/DESIGN.md` §6 (Time), §9 (Clock row), §13 (verification status); README guarantee "lease and
  deadline decisions use database time"

## Context

ADR-0001 requires that lease and deadline decisions use the journal database's time, never the JVM clock, and names
a compliance test: a child JVM under libfaketime shifted by one hour. Until 2026-11-23 no such test existed, and
every test ran with the two clocks in sync. A decision moved to the JVM clock would have passed all of them, so the
README labelled the guarantee "argued, not verified".

libfaketime is a native library preloaded into the process (`LD_PRELOAD`). It is not installed on the author's
machine or on the GitHub runner, and it would apply only to a child JVM, so only the few scenarios started that way
would run with the clocks apart.

## Decision

1. The clock test shifts the database clock instead of the JVM clock. In a test database of its own,
   `clock_timestamp()` resolves to a function of the schema `raa_test_clock` that adds the shift to
   `pg_catalog.clock_timestamp()`. The database's `search_path` (`public, raa_test_clock, pg_catalog`) finds it
   before `pg_catalog`, and the journal schema is created after the function, so the column defaults use it too.
2. The test (`DatabaseClockSkewTest`, `raa-postgres`) runs with the database clock one hour ahead of the JVM clock
   and one hour behind it. It checks that the clocks are apart, runs every time rule through `ReliableActions` and
   the reconciler, and runs the whole journal TCK on both shifted databases.

## Alternatives

| Option | Why not |
|---|---|
| Child JVM under libfaketime (ADR-0001) | A native library on every machine that runs the build; only child-JVM scenarios run shifted; the JVM's own handling of a preloaded clock is a further variable |
| A database container whose operating-system clock is shifted | Containers share the host's real-time clock (Linux time namespaces do not shift it); it would need libfaketime inside the image |
| An injectable clock in core | Contradicts §6 and ADR-0001: there is no clock setting, and a setting would invite exactly the regression the test looks for |

## Consequences

* For the test only the difference between the clocks matters, so a decision on the JVM clock fails it in at least
  one direction. Each time rule and each journal statement that writes a time a decision or retention reads was
  checked by a mutation (DESIGN §13).
* The shift replaces the function by name. Time read another way (`now()`, `current_timestamp`, a schema-qualified
  `pg_catalog.clock_timestamp()`) is not shifted. A statement that read it would disagree with the shifted rows, but
  this is not proven for every statement (DESIGN §13).
* Third-party journals can run the TCK the same way if their storage allows a shifted clock; the TCK does not require
  it.
