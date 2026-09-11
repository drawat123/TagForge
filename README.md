# TagForge

A telemetry platform for industrial PLCs — device management, MQTT ingestion, telemetry storage
and threshold alarms. Built as a learning project; see `CLAUDE.md` for how it's being built and
`docs/notes/` for what each step taught.

## Status

Thin slice, step 1: the server boots, connects to Postgres, Flyway creates the `device` table,
and a test inserts a row and reads it back. No MQTT or telemetry yet.

## Requirements

- Java 21
- Maven 3.9+
- Docker (for Postgres)

## Running it

Start Postgres:

```bash
docker compose up -d
```

Run the tests:

```bash
cd server && mvn test
```

Flyway applies the migrations on startup, so the schema is created for you.

**Postgres runs on port 5433**, not the usual 5432, to avoid clashing with a native PostgreSQL
install. Credentials are `tagforge` / `tagforge`, database `tagforge`.

## Resetting the database

The tests share one long-running database, so rows accumulate between runs. To start clean:

```bash
docker compose down -v   # -v also deletes the volume, so the data goes
docker compose up -d
```

With an empty volume, Flyway logs `Migrating schema "public" to version "1 - create device"` —
that line confirms the migrations actually ran.

## Looking inside the database

```bash
docker exec -it tagforge-postgres psql -U tagforge -d tagforge -c '\dt'
docker exec -it tagforge-postgres psql -U tagforge -d tagforge -c 'SELECT * FROM flyway_schema_history'
```

Two tables: `device`, and Flyway's own bookkeeping table recording which migrations have run.

## Layout

```
server/            Spring Boot application
  src/main/resources/db/migration/   Flyway migrations, applied in order
docker-compose.yml Postgres
docs/notes/        Short notes, one per step
CLAUDE.md          How this project is built
```

## Known issues

**Testcontainers doesn't work on this machine.** Docker Desktop 29.4 rejects the Java Docker
client's API handshake even though `curl` against the same socket succeeds, so tests use the
long-running compose Postgres instead. The cost is shared state between test runs. Revisit when
a test fails because of a row another test left behind.
