# Step 4 — Receiving MQTT messages

Three concepts.

---

## 1. QoS, and what "at least once" costs you

**What is it?**
MQTT offers three delivery guarantees. QoS 0 — fire and forget, may be lost. QoS 1 — at least once,
the sender retries until acknowledged. QoS 2 — exactly once, a four-step handshake.

**Why does it matter?**
QoS 1 is the usual choice for telemetry, and it means **duplicates are normal**, not an error. If an
acknowledgement is lost, the broker re-sends a message the server already handled. The message
arrives with `duplicate = true`, but that flag is only set on a *broker* retry — it will not save
you if a device re-publishes after its own reconnect.

So the ingestion path has to be safe to run twice on the same reading. That is a storage decision,
not an MQTT one, and it arrives before the telemetry table is designed.

QoS 2 would remove duplicates, at the cost of four network round trips per message. At 10,000
messages a second that is 40,000 packets a second of protocol overhead.

**Simple example**

```
MQTT message on v1/devices/press-01/telemetry (65 bytes, qos 1, duplicate false)
```

**Key takeaway**
At least once means design for repeats, not for hoping they don't happen.

---

## 2. A persistent session is what survives a restart

**What is it?**
`cleanStart = false` with a session expiry tells the broker to remember this client: its
subscriptions, and any QoS 1 messages that arrived while it was gone.

**Why does it matter?**
Without it, every deploy silently loses whatever devices published during the restart. With it,
the broker queues and delivers on reconnect.

**Simple example**
Tested directly — the server was stopped, a message published, the server restarted:

```
MQTT message on v1/devices/press-01/telemetry: {"sent":"while server was down"}
```

**The limit:** `persistence false` in `mosquitto.conf` keeps that queue in memory. A *broker*
restart loses it. Server restarts are covered; broker restarts are not.

**Key takeaway**
Durability here is the broker's, not yours — so it is only as good as the broker's configuration.

---

## 3. The callback runs on the network thread

**What is it?**
`messageArrived` is called by Paho's own I/O thread — the one reading the socket.

**Why does it matter?**
Blocking in that method stops the client reading. Messages back up in the broker's queue, the
keepalive can be missed, and the broker disconnects a client it thinks is dead. A slow database
insert in the callback is enough to do it.

So anything beyond trivial work has to move off that thread — a queue and a worker pool, or a
batching writer. That will be the shape of the ingestion path once storage exists.

**Simple example**
Logging is fine. `deviceRepository.save(...)` per message is not.

**Key takeaway**
The callback's job is to hand the message on quickly, not to process it.
