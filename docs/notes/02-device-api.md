# Step 2 — The device API

Three concepts.

---

## 1. Layers, and where the transaction lives

**What is it?**
Controller → service → repository. The controller translates HTTP. The service holds the use case
and the transaction. The repository talks to the database. Each layer knows only the one below it.

**Why does it matter?**
The real reason isn't tidiness, it's the **transaction boundary**. `@Transactional` on a service
method means everything inside it commits or rolls back together. Put it on the controller and the
transaction spans HTTP concerns; leave it off and every repository call is its own transaction —
fine for one `save`, wrong as soon as creating a device also writes an audit row.

It also keeps the use case reusable. MQTT ingestion will need `deviceService.findById(...)` and
there is no HTTP response to send there.

**Simple example**

```java
@Transactional
public Device create(String name) {
    return deviceRepository.save(new Device(name, Instant.now()));  // commits on return
}
```

**Where the id comes from:** the entity generates its own — `@Id private UUID id = UUID.randomUUID();`
— so a device has an identity the moment it is constructed and no caller can choose one. The cost
is that the id is never null, and Spring Data's `save()` uses null to tell a new entity from an
existing one. With an id already set it calls `merge()`, which issues a SELECT before the INSERT.
Irrelevant at device rates. ThingsBoard avoids it by keeping an explicit `isNew` flag and calling
`entityManager.persist()` directly instead of `save()`.

**The trap:** `@Transactional` works through a **proxy**. Spring hands callers a wrapper around
your service. A service method calling another method *on itself* bypasses the wrapper, so the
annotation on the inner method does nothing. This is the classic "why isn't my transaction rolling
back" bug.

**Key takeaway**
The service layer exists to own the transaction, not to forward calls.

---

## 2. DTOs are not entities

**What is it?**
`CreateDeviceRequest` and `DeviceResponse` are records for the HTTP layer. `Device` is the JPA
entity. They look similar and are deliberately separate.

**Why does it matter?**
They change for different reasons. Rename a database column and the entity changes — the API
should not. Return the entity directly and your public contract is now your database schema, plus
Hibernate's lazy-loading behaviour starts leaking into JSON serialisation.

It also controls what you accept. `CreateDeviceRequest` has no `id` field, so a client *cannot*
supply one — the id is generated server-side. That rule is enforced by the shape of the type, not
by a check someone might forget.

**Simple example**

```java
public record CreateDeviceRequest(@NotBlank String name) { }   // in: name only
public record DeviceResponse(UUID id, String name, Instant createdAt) { }  // out
```

**Key takeaway**
Every field in a response is a promise. Choose them, don't inherit them from the table.

---

## 3. Errors in one place: @Valid and ProblemDetail

**What is it?**
`@Valid` on the request body makes Spring check the constraints (`@NotBlank`, `@Size`) before the
controller method runs. A failure throws. `@RestControllerAdvice` catches exceptions from every
controller and turns them into responses. `ProblemDetail` is the standard JSON error format
(RFC 9457) — `type`, `title`, `status`, `detail`, plus any extra fields.

**Why does it matter?**
The controller contains no error handling at all: no null checks, no status codes, no try/catch.
The service throws `DeviceNotFoundException`, which knows nothing about HTTP — so it still makes
sense when MQTT ingestion calls the same service. One class decides that it means 404.

**Simple example**

Unknown id:

```
HTTP/1.1 404
Content-Type: application/problem+json

{"title":"Device not found","status":404,
 "detail":"Device not found: 0000...","deviceId":"0000..."}
```

Blank name:

```
HTTP/1.1 400
{"title":"Invalid request","status":400,"errors":{"name":"name must not be blank"}}
```

Note the validation response reports **every** rejected field at once, so a client can fix them in
one go instead of discovering them one request at a time.

**Key takeaway**
Exceptions carry what went wrong. One handler decides what that means over HTTP.
