# Step 6 — Reading telemetry back

Three concepts.

---

## 1. Two endpoints, because the costs are not comparable

**What is it?**
`GET /devices/{id}/telemetry/latest` returns the most recent reading per key.
`GET /devices/{id}/telemetry` returns a time range, grouped by key, with a mandatory `limit`.

**Why does it matter?**
They look like the same query with different parameters, and they are not. `latest` reads one row
per key no matter how much history exists — `DISTINCT ON (key) ... ORDER BY key, ts DESC` walks
straight to the newest entry using the primary key index. The range query's cost grows with the
range: at 1 Hz and seven days of retention, one tag is 604,800 rows.

Folding both into one endpoint with optional parameters hides that cliff behind a query string.

**Simple example**

```
/telemetry/latest?keys=temperature,pressure     → 2 rows, always
/telemetry?keys=temperature&from=…&to=…&limit=  → up to `limit` rows
```

**Key takeaway**
Split endpoints when the work they do differs by orders of magnitude, not when the parameters do.

---

## 2. ORDER BY ts is not a total order

**What is it?**
Every message writes several keys at the same timestamp. `ORDER BY ts DESC LIMIT 2` therefore has
ties, and Postgres may break them differently between runs — so the same query can return different
rows each time.

**Why does it matter?**
A test caught this by failing on a row it expected to be there. The deeper problem is that any
pagination built on an unstable sort silently skips and repeats rows: page 2 is computed from a
different ordering than page 1, and readings fall through the gap.

The fix is a tiebreak that makes the order total: `ORDER BY ts DESC, key`.

**Simple example**

```
16:02 temperature
16:01 temperature   ← both at 16:01; which one survives LIMIT 2?
16:01 pressure
```

**Key takeaway**
If a sort has ties, add a column until it doesn't. "Usually consistent" is not an ordering.

---

## 3. A client error must not be a 500

**What is it?**
`limit=99999` returned **500 Internal Server Error**. The cause was class-level `@Validated`, which
routes parameter validation through Spring's older AOP path and throws
`ConstraintViolationException` — an exception nothing was handling.

**Why does it matter?**
The status code is a contract. 4xx says "you sent something wrong, fix it and retry"; 5xx says "the
server is broken, this is not your fault". Getting it backwards means clients retry a request that
can never succeed, and whoever is on call gets paged for a caller's typo.

Spring 6.1+ validates controller parameters natively, without `@Validated`, and throws
`HandlerMethodValidationException` instead — which the handler maps to a 400 ProblemDetail.

**Simple example**

```
before:  500 {"error":"Internal Server Error"}
after:   400 {"title":"Invalid request","errors":{"limit":"must be less than or equal to 10000"}}
```

**Key takeaway**
Every error a caller can trigger by sending bad input belongs in the 4xx range. A 500 should mean
you have a bug.
