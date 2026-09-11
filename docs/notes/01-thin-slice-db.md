# Step 1 — Spring Boot talks to Postgres

Three concepts.

---

## 1. Schema migrations (Flyway)

**What is it?**
Your database schema lives in version-controlled `.sql` files, numbered in order. On startup
Flyway checks which ones have already run (it keeps a `flyway_schema_history` table) and applies
the rest.

**Why does it matter?**
Without it, the schema lives only in whatever database someone happened to create by hand — and
your machine, the test run, and production all drift apart. With it, the schema is code: same
files, same order, same result everywhere.

**Simple example**
`V1__create_device.sql` runs once. Add `V2__add_device_type.sql` tomorrow and only V2 runs — V1 is
already recorded as applied. You never edit V1 again.

**Key takeaway**
The schema is part of the codebase, not something you set up on the side.

---

## 2. The connection pool (HikariCP)

**What is it?**
Opening a database connection is expensive — TCP handshake, authentication, server-side session
setup, easily tens of milliseconds. A pool opens a handful up front, keeps them alive, and lends
one out per query. Spring Boot wires HikariCP in automatically; you saw `HikariPool-1 - Starting`
in the log.

**Why does it matter?**
It's the reason a web app can serve thousands of requests a second against a database that would
collapse if each request opened its own connection. It also sets a hard ceiling: the pool size is
the maximum number of queries that can be *in flight* at once. Everything else waits in line.

**Simple example**
Pool of 10. Request 11 arrives — it doesn't fail, it blocks until someone gives a connection back.
A slow query therefore doesn't just slow itself down; it holds a connection hostage and delays
everyone behind it.

**Key takeaway**
The pool is a queue with a fixed number of servers. That will matter a lot when we measure
ingestion.

---

## 3. Testing against a real database

**What is it?**
The test starts the actual Spring application, runs the real migrations against the real Postgres
in `docker-compose.yml`, then inserts and reads a row.

**Why does it matter?**
The alternative is faking the database — an in-memory one, or a mock. Both pass while your real
SQL is wrong, because they don't share Postgres's types, constraints or SQL dialect. `UUID` and
`TIMESTAMPTZ` don't exist everywhere. A test that doesn't run your migrations isn't testing the
thing that will break.

**Simple example**
A mocked repository "returns" a device happily, even if the column is misspelled. The real
database rejects the SQL immediately.

**Key takeaway**
For anything touching the database, test against the database.

---

## 4. A test can pass for the wrong reason

**What is it?**
After upgrading to Spring Boot 4 the test still passed — but Flyway was not running at all. Boot 4
split autoconfiguration into per-technology modules, so `flyway-core` on its own no longer wires
itself up; it needs `spring-boot-flyway` too. The test passed only because the `device` table was
still sitting in the database from the previous run.

**Why does it matter?**
Green does not mean correct. It means *nothing in this run contradicted the assertion*. A test
that depends on leftover state can keep passing long after the thing it claims to test has
stopped working — and it fails later, somewhere confusing, for a reason that looks unrelated.

**Simple example**
The failure only appeared after `docker compose down -v` wiped the volume:
`ERROR: relation "device" does not exist`. Same code, same test, opposite result — the difference
was state left behind, not anything in the codebase.

**Key takeaway**
A test that shares mutable state with previous runs is not fully trustworthy. When something
passes, ask what it would take for it to fail — if the answer is "nothing I can think of", the
test may not be testing anything.

---

## Note on the setup

Testcontainers (which starts a throwaway Postgres per test run) failed on this machine: Docker
Desktop 29.4 rejects the Java client's API handshake, though `curl` against the same socket works.
Using the long-running `docker-compose` Postgres instead. Worth revisiting — a shared database
between test runs means leftover rows, and eventually one test will fail because of another.
Also note Postgres runs on **5433**, because a native PostgreSQL 16 already holds 5432.
