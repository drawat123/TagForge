# Step 7 — The first load measurement

Three concepts.

---

## 1. Virtual threads

**What is it?**
A thread the JVM manages itself rather than asking the operating system for one. A platform thread
reserves about 1 MB of stack and is scheduled by the OS; a virtual thread starts at a few hundred
bytes and is parked and resumed by the JVM. When it blocks — on a sleep, a socket, a lock — the JVM
unmounts it from its carrier thread and runs something else there.

**Why does it matter?**
It makes the obvious design correct. Each virtual device wants a simple loop: publish, sleep until
the next interval, repeat. With platform threads, 10,000 of those is 10 GB of stack reservations and
an OS scheduler in trouble, so you would have to rewrite it as a shared scheduler with callbacks —
much harder to read for no gain. With virtual threads the naive loop just works.

**Simple example**

```java
Thread.ofVirtual().start(() -> publishLoop(deviceId, client));
```

300 of those cost almost nothing. So do 10,000.

**The trap, on Java 21:** a virtual thread that blocks *inside a `synchronized` block* pins its
carrier thread and cannot be unmounted. Libraries with synchronized internals — Paho among them —
can therefore cap concurrency near the core count no matter how many virtual threads are started.
That would look exactly like the server saturating, when in fact the load generator had.

Checked rather than assumed:

```bash
java -Djdk.tracePinnedThreads=full -jar simulator.jar --devices 300
```

Zero pinning events, because the async MQTT client never blocks in the publish path.

**Key takeaway**
Virtual threads make blocking code cheap again. Verify pinning before trusting throughput numbers.

---

## 2. Serial work hides in the setup, not just the hot path

**What is it?**
Connecting 300 devices took **over 74 seconds** — the run was killed before it published anything.
Each `connect().waitForCompletion()` waited for the broker's CONNACK before the next began.

**Why does it matter?**
The publish loop had been thought about carefully; the connect loop had not, because it "only runs
once". But it runs once *per experiment*, and an experiment you cannot start is an experiment you
do not run. Connecting through a virtual-thread executor took it to **715 ms** — roughly 100×.

**Simple example**

```java
try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
    for (UUID id : deviceIds) pool.submit(() -> connectAndStart(id));
}   // close() waits for all of them
```

**Key takeaway**
Startup cost is part of the tool. A measurement you avoid running because it is slow to start is a
measurement you do not have.

---

## 3. The broker drops what the server cannot take, and says nothing

**What is it?**
300 devices, 90 seconds, one server:

```
published  1,315,000 values   (26,300 messages)
acked      1,315,000 values   (100%)
stored       569,200 values   (11,384 messages)
lost         745,800 values   (56.7%)
```

**Why does it matter?**
Every metric said healthy. `failed 0`, `missed 0`, `inflight 0`, ack p50 0.15 ms. The server logged
no errors. More than half the readings simply ceased to exist.

Mosquitto queues for a subscriber that falls behind, but only to `max_queued_messages` — default
**1000**. At 128 messages/sec consumed against 300 arriving, the queue fills in about four seconds
and everything after that is discarded. One line in the broker log records it:

```
Outgoing messages are being dropped for client tagforge-server.
```

The reason nothing else can see it: **MQTT has no end-to-end flow control.** A publisher's PUBACK
comes from the broker, not from the server, so a device can never learn that the far end is
drowning. The broker absorbs the mismatch until it cannot, then drops silently.

This also bounds the durability verified earlier. A persistent session protects a *short* gap — a
restart or a deploy. It does nothing under sustained overload, because the queue has a ceiling.

**Simple example**
The only honest measure of whether ingestion is keeping up is on the server side:

```sql
SELECT now() - max(ts) FROM telemetry;
```

**Key takeaway**
Measure the consumer, not the producer. A producer's success only proves the *broker* accepted the
message.

---

## The numbers, against the predictions

```
predicted (me)   ~100 messages/sec, limited by per-value inserts on one thread
predicted (mine)  bend at 250 devices
measured          128 messages/sec = 6,396 values/sec = 0.157 ms per insert
```

The mechanism was right: one thread, one INSERT per value, fully saturated. The gap to the 10,000
messages/sec target is **78×**, and the two candidate fixes are independent — batch the 50 inserts
into one statement, and stop doing the work on the MQTT callback thread.
