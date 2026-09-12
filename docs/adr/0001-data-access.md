# 0001 — JPA for administrative entities, JDBC for telemetry

**Status:** accepted · **Date:** 2026-09-12

## Context

The server reads and writes two kinds of data with opposite shapes.

**Administrative data** — devices, and later tenants, customer groups and users. Small tables,
rich relationships, ordinary CRUD, low request rates, driven by humans through a UI.

**Telemetry** — the reason this project exists. The target is 10,000 devices × 50 tags at 1 Hz:
500,000 values/sec arriving as 10,000 MQTT messages/sec. Write-heavy, append-only, no
relationships to traverse, and eventually hundreds of billions of rows.

Spring offers two data access styles: Spring Data JPA (Hibernate entities and repository
interfaces) or plain JDBC through `JdbcClient`.

## Options

**JPA everywhere.** `save` and `findById` for free, declarative relationships — but an entity
manager on the telemetry path, which is the wrong tool for bulk inserts.

**JDBC everywhere.** Full control over every statement, one consistent style — but every CRUD
method hand-written, and the permission queries in the tenancy model are exactly the join-heavy
work JPA is good at.

**Split by workload.** JPA for administrative entities, JDBC for telemetry.

## Decision

**Split by workload,** which is what ThingsBoard does: Spring Data JPA for entities such as
devices and tenants, hand-written batched SQL for the timeseries write path.

**Why JPA for administrative data.** Devices belong to tenants, are shared with customer groups
and are visible to users. That graph is what JPA exists for, and the request rates are low enough
that its overhead is irrelevant. `JpaRepository` removes CRUD that has no learning value.

**Why not JPA for telemetry.** The entity manager tracks every object it has loaded, checks each
for modification at flush time, and emits one INSERT per entity. At 500,000 values/sec that is
pure overhead, and the answer will be batched inserts or `COPY`. Adopting JPA and then bypassing
it on the only path that matters is worse than not adopting it there at all.

**Flyway keeps ownership of the schema.** `spring.jpa.hibernate.ddl-auto: validate` — Hibernate
never creates or alters anything; it checks the entities against the real tables at startup and
refuses to start if they disagree. Migrations stay the single source of truth, and a mapping that
drifts from the table is caught immediately rather than at the first query.

**`open-in-view` is off.** Spring Boot's default keeps a persistence session open for the whole
HTTP request, which allows lazy loading in the view layer and quietly holds a database connection
for the entire request. With a fixed connection pool that is a throughput problem, and it hides
where queries actually happen.

## What we give up

**Two data access styles in one codebase.** Someone reading the code has to know which half they
are in. The boundary is workload, not package, so it needs to stay clearly documented.

**JPA's failure modes arrive with it** — lazy loading exceptions, flush timing surprises, N+1
queries, and `@Transactional` not doing what it looks like it does. Accepted deliberately: these
are worth learning on the low-stakes half of the model.

**Duplicate mapping.** `Device` is described twice — once in a Flyway migration, once in an
entity. `validate` catches disagreement at startup, which is the mitigation, not a cure.

## When to revisit

- If telemetry ever needs relationships, question whether the split is still in the right place.
- If administrative queries need SQL that JPA makes awkward, drop to `JdbcClient` for those
  specific queries rather than abandoning JPA.
