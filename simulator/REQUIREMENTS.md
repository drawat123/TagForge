# Simulator — what it has to do

A load generator: virtual PLCs publishing telemetry over MQTT, so the server's limits
can be measured rather than guessed.

Its job is to **apply pressure and report honestly what happened**. A simulator that
quietly slows down when the server struggles, or that retries failures out of sight,
destroys the measurement it exists to produce.

---

## Functional

**Virtual devices**

- Run N virtual devices in one process, N configurable from 10 to 10,000.
- Each device has its own device id, matching a real row in the server's `device` table.
- Each device publishes on a fixed interval, configurable, default 1 second.
- Each message carries a configurable number of tag values, default 50, in one publish.

**Provisioning**

- On startup, create the device rows it needs via `POST /devices` on the server.
- Ingestion rejects unknown device ids, so this must complete before publishing starts.
- Report how long provisioning took — it is itself a load test of that endpoint.

**Publishing**

- One MQTT connection per device. Identity comes from the connection, not the payload.
- Topic: `v1/devices/{deviceId}/telemetry`.
- Payload: `{"ts": <epoch millis>, "values": {"<tag>": <value>, ...}}`.
- `ts` is the device's own clock at publish time.
- Values vary over time in a way that looks like a real process, so charts are meaningful.
- QoS configurable, default 1.

**Arrival pattern — a setting, not an accident**

- **Spread** (default): each device starts at a random offset within its interval, so
  arrivals are even across the second. This is normal running.
- **Synchronised**: every device publishes on the same tick. This is what a broker
  restart produces in reality, and it is roughly 100× the load in any short window.
- Both must be selectable from the command line.

**Lifecycle**

- Start, run until stopped, shut down cleanly, disconnecting every client.
- Failure to connect one device must not prevent the others from running.

---

## Metrics

Reported once per second, each line describing **that second only** — not a running
average, which hides the moment things start to degrade.

- **Publish rate** — messages handed to the client library per second.
- **Ack rate** — messages the broker acknowledged per second.
- **In flight** — published minus acknowledged. Growing without bound is the
  backpressure signal.
- **Failures** — publishes that errored, and connections that dropped.
- **Ack latency** — p50, p99 and max, in milliseconds.

Percentiles, not averages: at high utilisation the average stays healthy long after
the tail has collapsed.

---

## What the metrics cannot see

The acknowledgement comes from **Mosquitto**, not from the server. The broker acks as
soon as it accepts a message; it does not wait for anything to be stored. So if the
database falls behind, every number above still looks healthy while messages pile up
inside the broker.

A server-side signal is needed alongside, and it does not belong in the simulator:

```sql
SELECT now() - max(ts) FROM telemetry;
```

Stable means keeping up. Growing means past capacity.

---

## Configuration

Command line, with sensible defaults:

| Option | Default | Meaning |
|---|---|---|
| devices | 10 | virtual devices |
| tags | 50 | values per message |
| interval | 1000 | milliseconds between publishes per device |
| synchronised | off | all devices publish on the same tick |
| broker | tcp://localhost:1883 | MQTT broker |
| server | http://localhost:8080 | for provisioning |
| qos | 1 | MQTT quality of service |

---

## Constraints to design around

**One file descriptor per connection.** macOS defaults `ulimit -n` to 256, so anything
past a couple of hundred devices fails on connect. Raise it, and make the failure say
so clearly rather than surfacing as an unexplained connection error.

**Sleep to a deadline, not for a duration.** `sleep(1000)` after work that took 200 ms
gives a 1.2 second period, and the drift compounds. The publish rate then sits quietly
below the target and the measurement is wrong in the direction that looks fine.

**Do not resume sessions.** A client that replays missed publishes after a reconnect
hides the failures the simulator exists to record.

**Metrics must not become the bottleneck.** Counters are written from every device at
once; contention there would look exactly like a server limit.

**A GC pause is indistinguishable from server latency** in the results. Run with GC
logging on when a number looks surprising.

---

## Done when

- 10 devices run for a minute with ack rate matching publish rate and stable latency.
- Device count can be raised until acks fall behind publishes, and that point is
  visible in the output rather than inferred.
- Both arrival patterns can be selected, and produce visibly different latency.
- Stopping it leaves no connected clients on the broker.

---

## Not in scope

- Disk buffering or retry after failure — that is the edge agent, and the opposite job.
- Reading telemetry back, or verifying what was stored.
- Simulating device authentication; there is none yet.
