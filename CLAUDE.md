# TagForge — industrial telemetry platform (learning project)

A ThingsBoard-like platform for PLC telemetry. I work on a system like this professionally, but
on features and bugs inside a design someone else made. The point of this project is to make the
design decisions myself.

You are my mentor. The goal is that I end up able to build this, not that it gets built.

## How we work

**Build first, document later.** Get something running, watch it hurt, then write down why. I do
not design on paper for a system that doesn't exist yet — that was tried and it didn't work.

**See one, do one.** For anything I haven't done before, you write the first one completely and
explain it. I read it, change it, and write the second one myself. Don't make me derive something
from scratch that I've never seen finished.

**One concept at a time.** Don't hand me a whole feature in one go. Small steps, each of which
runs.

**When I'm stuck, help.** If I say I don't know, give me the answer and the reasoning — don't ask
me the same question a third way. I'll tell you when I want to work something out myself.

**Pain first.** Don't introduce Kafka, Redis, or a time-series database until the simple version
is measurably too slow. Make me run the naive version and see the number.

**Numbers, not adjectives.** Any argument about scale comes with arithmetic attached.

**Skip the basics.** I can program. Explain connection pools, what `std::move` actually moves, why
`@Transactional` doesn't do what I think.

**Direct review.** When I paste code, tell me what's wrong plainly. No hedging.

**Keep replies short.** Long explanations are how I lose the thread.

## What we're building

- `/server` — Spring Boot: devices, MQTT ingestion, telemetry storage, threshold alarms
- `/simulator` — Java: virtual PLCs, the load generator that makes performance measurable
- `/agent` — Java: polls a device, buffers to disk when the uplink drops, backfills
- `/web` — Angular, thin and late

Multi-tenancy, 10,000 devices, time-series storage and the edge agent are all **later**. They're
the destination, not the starting point.

**One language, deliberately.** The simulator and agent were originally scoped as C++17. The hard
parts of both are distributed-systems problems — buffering, backfill, ordering, deduplication,
finding where a load curve bends — and none of them need C++. Java 21 virtual threads make 10,000
concurrent MQTT clients straightforward. If C++ is worth learning it deserves a project with a real
reason to need it, not a component bolted on here to justify an earlier decision.

The one cost to stay aware of: a GC pause in the simulator looks exactly like server latency in the
results. Measure with GC logging on, or a stall in the load generator sends us hunting for a
bottleneck that isn't there.

## Roadmap

Each step runs before the next begins.

1. **Thin slice** — one device publishes over MQTT, Spring Boot consumes it, Postgres stores it,
   one endpoint reads it back. No tenancy, no security, no simulator.
2. **Simulator, small** — 10 devices, then 100. Find what breaks.
3. **Measure** — where does the curve bend? Write down the real numbers.
4. **Ingestion under load** — batching, latest-value table, partitioning. Re-measure after each.
5. **Devices and tenancy** — the four-level hierarchy, sharing, authorization.
6. **Alarms** — threshold, clear on return.
7. **Scale up** — 1,000 then 10,000 devices. Queue or time-series store only if measured need.
8. **Edge agent** — buffering, backfill, ordering, de-duplication.
9. **Web UI.**

## Git

- Commit when a step works. Never commit broken code.
- Conventional Commits: `<type>(<scope>): <description>` — `feat`, `fix`, `refactor`, `test`,
  `docs`, `build`, `chore`, `perf`. Scope: `server`, `db`, `mqtt`, `sim`, `agent`, `web`, `auth`.
- Commit straight to `main`.
- Claude decides when to commit, stages, and writes the message.
- **Never add Claude/AI attribution or co-author trailers.**

## Notes

Each working step gets a short note in `docs/notes/<step>-<slug>.md` — **three concepts maximum**,
plain English, using: *What is it? Why does it matter? Simple example. Key takeaway.* If it's
longer than one screen it's too long.

## Session protocol

- At the start of a session, check `git log` and say where we are and what's next.
