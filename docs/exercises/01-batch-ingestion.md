# Exercise 1 — Batch the telemetry insert

## Where you are

Measured, not estimated:

```
server throughput   128 messages/sec   (6,396 values/sec)
target           10,000 messages/sec
gap                    78x
```

The mechanism is known. `TelemetryService.store` loops over a message's 50 values and calls
`TelemetryRepository.save` once per value. Each call is a separate statement **and** a separate
transaction, on Paho's single callback thread.

A psql experiment measured what that costs, writing the same 1,000 rows three ways:

| | rows/sec | µs/row | |
|---|---|---|---|
| 1,000 inserts, autocommit | 3,802 | 263 | what the server does now |
| 1,000 inserts, one transaction | 16,949 | 59 | **4.5x** — commits removed |
| 1,000 rows, one statement | 50,000 | 20 | **13.1x** — round trips removed too |

So batching should give roughly 13x: **128 → ~1,700 messages/sec**. Still 6x short of target, which
is the point of measuring before building. Concurrency is a separate exercise.

---

## Exercise 1a — One transaction per message

**Goal:** a message's 50 inserts commit once instead of 50 times.

**Acceptance criteria**

- `TelemetryService.store` completes in a single transaction.
- All existing tests still pass.
- Throughput measured by the recipe below is meaningfully above 128 msg/sec.
- A message that fails halfway leaves *no* rows from that message, not 25.

**Think about before you write it**

- `@Transactional` works through a proxy. If `store(deviceId, upload)` calls a private `store(...)`
  on itself, does the annotation apply to the inner call?
- The current code has no transaction *deliberately* — the commit comment says a partial write is
  not corrupt because every value is independently idempotent. Adding a transaction changes that
  trade. Is all-or-nothing per message actually better here? Write down why.
- Ingestion runs on the MQTT callback thread. A transaction held open is a pooled connection held
  open. What is the connection pool size, and what happens when concurrency arrives later?

---

## Exercise 1b — One statement per message

**Goal:** 50 values reach Postgres as one statement rather than 50.

**Acceptance criteria**

- One round trip per message, whatever the tag count.
- Duplicate protection still works: publishing the same message twice adds no rows.
- A corrected value at the same timestamp still overwrites (`ON CONFLICT DO UPDATE`).
- Mixed types in one message still land in the right columns, and the
  `telemetry_exactly_one_value` check still passes.
- Throughput measured again, and compared against 1a so you know what each step bought.

**Two ways to do it — pick one and say why**

- **JDBC batch** (`JdbcClient` / `JdbcTemplate.batchUpdate`) — 50 statements, one round trip. Small
  change, keeps the existing SQL.
- **Multi-row `VALUES`** — one statement carrying 50 tuples. This is what the 13x measurement used.
  The SQL is built per message, so a 50-tag and a 30-tag message produce different statements.

Worth knowing: statements are cached by text. If every distinct tag count produces different SQL,
how many variants will there be, and does that matter?

**Traps**

- **`ON CONFLICT` fails if one statement contains the same key twice** — Postgres raises
  *"cannot affect row a second time"*. A JSON object cannot have duplicate keys, so today you are
  safe. What happens the day a payload does?
- **Postgres caps a statement at 65,535 bind parameters.** Seven columns per row is ~9,300 rows
  maximum. Not a problem at 50 tags. It becomes one if you ever batch across messages.
- **`java.time.Instant` is not bindable** by the Postgres driver — it carries no offset and
  `timestamptz` needs one. That conversion has to survive the rewrite.

---

## How to measure

Same recipe both times, so the numbers are comparable.

**Write your prediction down first.** A number that confirms a guess teaches more than a number
with no expectation attached.

```bash
# 1. clean slate
pkill -f simulator-0.0.1-SNAPSHOT.jar; pkill -f TagForgeApplication
docker exec tagforge-postgres psql -U tagforge -d tagforge \
  -c "TRUNCATE telemetry; TRUNCATE device;"
docker compose restart mosquitto        # drops any queued backlog

# 2. one server
cd server && mvn spring-boot:run

# 3. 300 devices for 90 seconds
cd simulator && ulimit -n 65536
java -jar target/simulator-0.0.1-SNAPSHOT.jar --devices 300 --tags 50
#    ...wait 90s, then Ctrl+C

# 4. wait until the row count stops growing, then:
docker exec tagforge-postgres psql -U tagforge -d tagforge \
  -c "SELECT count(*) FROM telemetry"
```

**Work out three numbers:**

```
values published  = (sum of the simulator's pub/s lines) x 50
values stored     = the row count
loss %            = 1 - stored/published
throughput        = stored values / seconds of the run / 50   → messages/sec
```

**Loss matters as much as throughput.** At 128 msg/sec, 56.7% of readings were silently discarded
by the broker. If batching takes you to 1,700 msg/sec, loss at 300 devices should fall to zero —
and if it does not, something else is wrong.

**While it runs, watch the number the simulator cannot see:**

```bash
watch -n1 "docker exec tagforge-postgres psql -U tagforge -d tagforge -t \
  -c \"SELECT now() - max(ts) FROM telemetry\""
```

Flat means keeping up. Growing means still behind.

---

## Done when

- Both changes are in, each measured separately.
- You can say what 1a bought and what 1b bought, in messages/sec.
- Loss at 300 devices is zero.
- You know how far the remaining gap to 10,000 msg/sec is, and therefore how much the concurrency
  work has to find.

## Not in this exercise

- Getting off the MQTT callback thread. That is the next one, and doing it at the same time would
  make it impossible to say which change was worth what.
- Partitioning, a latest-value table, or anything that changes the schema.
