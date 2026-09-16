# Exercise 2 — A measurement harness, in Java, by hand

## Goal

One command that runs a whole experiment and prints the result:

```
clean the database → start the server → start the simulator → wait out warm-up
→ sample throughput → stop everything
```

Today that is six manual steps, which means fewer experiments get run, and the
ones that do get run are inconsistent.

**This is Java practice on purpose.** It teaches the language rather than Spring —
`ProcessBuilder`, JDBC, try-with-resources, exception handling. Worth knowing that
is the smaller half of the gap.

---

## Decisions to make first

**Where does it live?**

- A single file run directly: `java scripts/Measure.java`. No build, no pom. But adding a
  dependency means wrangling `--class-path` by hand.
- A second `main` class in the simulator module. Gets Maven, dependencies and the shade plugin
  for free. Costs a build step on every edit.

**How does it talk to Postgres?**

- **JDBC** — a real connection, `PreparedStatement`, `ResultSet`. More Java practice, needs the
  driver on the classpath.
- **Shell out to `docker exec ... psql`** — no dependency at all, pure JDK, and it is what the
  Python version does. Less to learn.

**How does it start the server?** `mvn spring-boot:run`, or `java -jar` on a packaged jar. One
needs Maven on the path; the other needs a `mvn package` first. They fail differently.

Write down which you picked and why, in a comment at the top.

---

## Acceptance criteria

- One command runs the whole experiment end to end.
- Device count, sample window, sample count and warm-up are all arguments with defaults.
- It waits for the server to be **ready**, not a fixed sleep. Readiness means the log line
  `Started TagForgeApplication`, or an HTTP call that succeeds.
- It fails loudly and exits non-zero if the server or simulator dies, naming which one.
- It reports values/sec, messages/sec, ms/insert and ingest lag — same numbers as today.
- It stops both processes afterwards, including when it fails partway through.
- Running it twice in a row gives comparable numbers, because it resets state each time.

---

## Traps, in the order you will hit them

**A child process whose output nobody reads will freeze.** Its stdout goes to a pipe with a
finite buffer; once full, the child blocks on its next write and never recovers. It looks exactly
like a hang. Either redirect to a file (`ProcessBuilder.redirectOutput`) or read the stream on
another thread. Do not leave it as the default pipe and ignore it.

**`mvn spring-boot:run` forks a second JVM.** Killing the Maven process leaves the actual server
running and holding port 8080 — which has already cost this project two debugging sessions.
`ProcessHandle.descendants()` exists for this. Check what is still alive after your teardown.

**`destroy()` and `destroyForcibly()` are not the same.** One is a polite signal that lets the
simulator's shutdown hook disconnect its clients; the other is immediate. Which do you want, and
what happens to the broker's view of 300 clients that vanished without disconnecting?

**Cleanup must survive an exception.** If sampling throws, both processes must still be stopped —
otherwise the next run finds port 8080 taken and a second MQTT client stealing the server's
session. `try`/`finally`, or try-with-resources with a wrapper that implements `AutoCloseable`.

**`ulimit -n` cannot be set from inside a JVM.** One file descriptor per MQTT connection, and
macOS defaults to 256. Either launch the simulator through a shell that raises it, or require the
caller to have raised it and say so clearly when connections start failing.

**Waiting for a log line needs a timeout.** If the server never starts, a loop reading the file
waits forever. Bound it, and print the log path when it expires.

---

## Done when

- `java <your harness> --devices 300` prints a throughput number you trust.
- Killing it halfway leaves no server and no simulator running.
- You can explain why `destroy()` was the right choice over `destroyForcibly()`, or the reverse.

## Not in this exercise

- Asserting on the result, or failing a build. That comes later as a real regression test.
- Measuring anything the existing script does not already measure.
