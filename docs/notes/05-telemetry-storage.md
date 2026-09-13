# Step 5 — Storing telemetry

Three concepts.

---

## 1. The primary key is the duplicate protection

**What is it?**
A reading is identified by `(device_id, key, ts)`, and that triple is the primary key. Writing uses
`INSERT ... ON CONFLICT (device_id, key, ts) DO UPDATE`.

**Why does it matter?**
MQTT QoS 1 is at-least-once, so the same reading arrives more than once as a matter of routine —
a lost PUBACK, a device republishing after its own reconnect, an edge agent backfilling an outage.
With a natural key every one of those collapses onto the same row. With a generated id, each one
becomes a separate row and the chart shows readings that never happened.

So the key is a correctness decision forced by the transport, not a modelling preference.

**Simple example**
Sending the identical message twice adds **zero** rows the second time. Sending a different value at
the same timestamp overwrites:

```
temperature | 16:00 | 23.5   →  send 99.9 at 16:00  →  temperature | 16:00 | 99.9
```

**The consequence to be aware of:** `DO UPDATE` means a device can rewrite its own history. That is
what makes a corrected backfill work, and it is also a thing an attacker or a buggy device can do.

**Key takeaway**
At-least-once delivery means storage has to be idempotent. The cheapest way is a key that a repeat
naturally collides with.

---

## 2. Constraints are cheap to loosen, expensive to tighten

**What is it?**
The table enforces `num_nonnulls(bool_v, str_v, long_v, dbl_v) = 1` — exactly one value column per
row, not at most one.

**Why does it matter?**
`= 1` forbids a row with no value, which is how "the sensor was unreachable" would be recorded. That
fact is worth having eventually, but nothing in the system can produce it yet: the server only sees
what a device chooses to publish, and absence of a row already means "no data". Only the edge agent,
which polls and can watch a read fail, will be able to tell a failed read from silence.

The reason to start strict is the asymmetry:

- `= 1` → `<= 1` later: `DROP CONSTRAINT`, a catalogue update, instant on any table size.
- `<= 1` → `= 1` later: `ADD CONSTRAINT` scans every existing row. On hundreds of billions, hours.

**Simple example**

```
ERROR: new row violates check constraint "telemetry_exactly_one_value"
DETAIL: Failing row contains (..., t, null, null, 1).
```

Both a two-valued row and an all-null row are rejected — and the error names the constraint, which
is why constraints are named explicitly rather than left to Postgres.

**Key takeaway**
When unsure, choose the constraint that is cheap to reverse.

---

## 3. Read the exception you were actually given

**What is it?**
Storage failed with `BadSqlGrammarException`. The SQL was pasted into psql and ran fine.

**Why does it matter?**
The name was misleading and produced a wrong fix — untyped nulls were blamed and casts were added,
which changed nothing. The real message was three layers down in the exception chain:

```
Caused by: PSQLException: Can't infer the SQL type to use for an instance of java.time.Instant.
```

An `Instant` carries no offset; `timestamptz` binding needs one. `OffsetDateTime` is what the driver
accepts. Nothing to do with nulls or grammar.

It was invisible because the handler logged `e.getMessage()` — the outermost wrapper, which is
usually the least informative thing in the chain.

**Simple example**

```java
log.warn("Discarding message on {}: {}", topic, e.getMessage());  // wrapper only
log.debug("Cause", e);                                            // the whole chain
```

**Key takeaway**
Spring wraps driver exceptions. Diagnose from the root cause, and be suspicious of a fix that does
not change the error.
