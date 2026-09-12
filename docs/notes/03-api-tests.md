# Step 3 — Testing the HTTP contract

Three concepts.

---

## 1. MockMvc, and why that boundary

**What is it?**
`@SpringBootTest` + `@AutoConfigureMockMvc` starts the real application — real controller, service,
repository and database — but no network socket. Requests are handed to Spring's dispatcher
directly.

**Why does it matter?**
It covers the parts that actually hold the decisions: the 201, the `Location` header, `@Valid`
rejecting a blank name, and `ApiExceptionHandler` turning an exception into a ProblemDetail. None
of those live in `DeviceService` — a unit test with a mocked repository would exercise none of
them.

No socket means no port conflicts and it runs in milliseconds, which matters when the suite grows.

**Simple example**

```java
mockMvc.perform(post("/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"press-01\"}"))
        .andExpect(status().isCreated());
```

**Key takeaway**
Test at the boundary where the behaviour you care about actually lives.

---

## 2. A test is only proven when you have seen it fail

**What is it?**
After the five tests went green, the controller was deliberately broken — `ResponseEntity.created`
changed to `ResponseEntity.ok` — to check the tests noticed.

**Why does it matter?**
Green means "nothing contradicted the assertion", which is not the same as "the assertion is
meaningful". Four empty test methods passed earlier in this project for exactly that reason. A test
that has never failed might be asserting nothing at all.

**Simple example**

```
java.lang.AssertionError: Status expected:<201> but was:<200>
```

Two tests failed, both for the right reason. Reverting turned them green again.

**Key takeaway**
Break the code once on purpose. If nothing goes red, the test is decoration.

---

## 3. Assert the relationship, not just the presence

**What is it?**
`header().exists("Location")` only proves a header was sent. The test instead reads the id out of
the response body and asserts the header ends with `/devices/{that id}`.

**Why does it matter?**
A `Location` header pointing at the wrong device passes the weaker check and fails the real one.
The interesting property is not that the header exists, it is that it agrees with the body.

Same idea drives `createdDeviceCanBeFetched`: it creates a device and then fetches it by the
returned id. That deliberately couples two endpoints — it fails for two different reasons — but it
proves the id genuinely round-trips, which neither endpoint can prove alone.

**Key takeaway**
Assert what connects two things, not just that each of them showed up.
