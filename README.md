# TagForge

A telemetry platform for industrial PLCs — device management, MQTT ingestion, telemetry storage
and threshold alarms. Built as a learning project; see `CLAUDE.md` for how it's being built and
`docs/notes/` for what each step taught.

## Status

The thin slice works end to end:

```
device created over HTTP → publishes over MQTT → stored in Postgres → read back over HTTP
```

The simulator runs N virtual devices against it and reports publish rate, ack rate, in-flight,
failures and ack latency percentiles. Next step is finding where the curve bends.

No authentication, no tenancy, no alarms yet.

## Requirements

- Java 21 (virtual threads — the simulator needs them)
- Maven 3.9+
- Docker

## Running it

Start Postgres and Mosquitto:

```bash
docker compose up -d
```

Start the server:

```bash
cd server && mvn spring-boot:run
```

Run the simulator:

```bash
cd simulator && mvn package
java -jar target/simulator-0.0.1-SNAPSHOT.jar --devices 20
```

**Raise the file descriptor limit before going past ~200 devices** — one MQTT connection per
device, and macOS defaults `ulimit -n` to 256:

```bash
ulimit -n 65536
```

### Simulator options

| Option | Default | Meaning |
|---|---|---|
| `--devices`, `-d` | 10 | virtual devices |
| `--tags`, `-t` | 50 | values per message |
| `--interval`, `-i` | 1000 | milliseconds between publishes per device |
| `--synchronised`, `-s` | off | all devices publish on the same tick |
| `--broker`, `-b` | tcp://localhost:1883 | MQTT broker |
| `--server` | http://localhost:8080 | server, used to provision devices |
| `--qos`, `-q` | 1 | MQTT quality of service |

## The API

Swagger UI at **http://localhost:8080/swagger-ui.html**, spec at `/v3/api-docs`.

```bash
# create a device
curl -i -X POST localhost:8080/devices \
  -H 'Content-Type: application/json' -d '{"name":"press-01"}'

# publish a reading as that device
docker exec tagforge-mosquitto mosquitto_pub -h localhost -q 1 \
  -t "v1/devices/<id>/telemetry" \
  -m '{"ts":1757606400000,"values":{"temperature":23.5,"pressure":4}}'

# latest value per key
curl -s "localhost:8080/devices/<id>/telemetry/latest?keys=temperature,pressure"

# a time range, grouped by key — limit is mandatory
curl -s "localhost:8080/devices/<id>/telemetry?keys=temperature\
&from=2025-09-11T00:00:00Z&to=2025-09-12T00:00:00Z&limit=100"

# delete a device (its telemetry is left for retention to remove)
curl -i -X DELETE localhost:8080/devices/<id>
```

## Measuring

The simulator prints one line per second, describing that second only:

```
pub 20/s | ack 20/s | inflight 0 | failed 0 | missed 0 | ack p50 0.8 ms p99 2.5 ms max 2.5 ms
```

- **inflight** — published minus acknowledged. Growing without bound is backpressure.
- **missed** — a device that could not keep up with its publish interval.
- **ack latency** — percentiles, because the average stays healthy long after the tail collapses.

**These numbers come from the broker, not the server.** Mosquitto acknowledges on accept and never
waits for anything to be stored, so the database could stop entirely and every number above would
still look fine. Watch ingest lag separately:

```bash
watch -n1 "docker exec tagforge-postgres psql -U tagforge -d tagforge -t \
  -c \"SELECT now() - max(ts) FROM telemetry\""
```

Stable means keeping up. Growing means past capacity.

The first few seconds of any run are JIT and connection warmup, not steady state — provisioning
20 devices measured 10 ms each, while 10,000 measured 0.96 ms each. Ignore the first ten seconds.

## Resetting between runs

```bash
docker exec tagforge-postgres psql -U tagforge -d tagforge -c "TRUNCATE telemetry; TRUNCATE device;"
```

Or wipe everything including the schema:

```bash
docker compose down -v && docker compose up -d
```

With an empty volume, Flyway logs `Migrating schema "public" to version "1 - create device"` —
that line confirms the migrations actually ran.

**Postgres runs on port 5433**, not 5432, to avoid clashing with a native PostgreSQL install.
Credentials are `tagforge` / `tagforge`, database `tagforge`.

## Looking inside the database

```bash
docker exec -it tagforge-postgres psql -U tagforge -d tagforge -c '\dt'
docker exec -it tagforge-postgres psql -U tagforge -d tagforge \
  -c 'SELECT key, ts, bool_v, str_v, long_v, dbl_v FROM telemetry ORDER BY ts DESC LIMIT 10'
```

`telemetry` holds one row per value, not per message, with four typed columns — the type of a
reading is whichever column is non-null.

## Layout

```
server/       Spring Boot: devices, MQTT ingestion, telemetry storage
  src/main/resources/db/migration/   Flyway migrations, applied in order
simulator/    Java load generator; REQUIREMENTS.md says what it must do
mosquitto/    broker config
docs/notes/   short notes, one per step
CLAUDE.md     how this project is built
```

## Known gaps

**No authentication.** Mosquitto allows anonymous connections, and the device id is taken from the
topic without verification — any client can publish as any device. The plan is a Mosquitto ACL
pattern (`pattern write v1/devices/%u/telemetry`) so the broker enforces that a device can only
publish under its own id.

**Ingestion is the naive path on purpose.** One insert per value, on Paho's callback thread. A
50-tag message is 50 round trips, and while one message is being stored no other is being read.
That is what the simulator is for.

**Tests share one long-running database.** Testcontainers does not work on this machine — Docker
Desktop 29.4 rejects the Java Docker client's API handshake even though `curl` against the same
socket succeeds. Rows accumulate between runs; revisit when a test fails because of a row another
test left behind.

**`limit` on the range endpoint is shared across keys**, not applied per key, so requesting several
series truncates them unevenly.
