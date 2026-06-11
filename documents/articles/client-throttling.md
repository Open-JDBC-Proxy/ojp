# Client Throttling in OJP

OJP is a database control plane: your application can scale horizontally without overwhelming your database, because the server — not the application — owns and controls the real connection pools. Ten application instances, a hundred, a thousand — the database sees only the connections that the control plane permits.

But there is a subtler problem that emerges at scale, and controlling connections alone does not solve it: the **thundering herd**. When a cluster of application instances all fire requests simultaneously — a common pattern on startup, on cache expiry, or after a brief service interruption — the OJP server's connection pool may be fully saturated before any individual client has sent more than a few requests. The server starts rejecting requests with `RESOURCE_EXHAUSTED`. The clients retry. The retries make the situation worse. Latency climbs, timeouts cascade, and the database is buffered from the blast only by the slim guarantee that new connections won't be opened.

Client throttling addresses this at the source. Instead of letting every client thread fire requests until the server pushes back, each OJP driver instance limits its own concurrent in-flight count to its fair share of the server's real capacity. The queue never forms. The server never becomes saturated. Throughput stays high under pressure.

Early benchmark tests show the benefit clearly: under sustained peak load, client throttling increases the rate of successful requests while simultaneously reducing tail latency. Rather than competing for the last available slot, clients self-regulate — and the whole system finds its steady state faster.

This article explains how client throttling works, how to configure it, and how it interacts with other OJP features.

---

## The Two Layers of Throttling

OJP applies pressure management at two independent layers. Understanding both is useful context before diving into client throttling specifically.

The **server-side global gate** is a `ConcurrencyThrottleInterceptor` that caps the total number of concurrent in-flight gRPC requests the server will accept. Any request that arrives when the server is already at that limit is rejected immediately with `RESOURCE_EXHAUSTED`. This is a blunt backstop: it protects the JVM itself from being overrun, but it applies equally to every client and every datasource. It does not distinguish between fair-share requests and a burst from one misbehaving client.

The **client-side throttle** (`ClientThrottleManager` in the driver) is more sophisticated. It operates per-connection-hash — that is, per unique combination of server address, datasource, and credentials — and limits how many concurrent gRPC calls this specific JVM instance will send. The limit is computed from information the server sends back: how many connections the pool has, how many distinct application instances are connected, and — once the system has been under pressure — what the actual observed peak concurrency was just before the last admission timeout. The result is that each application instance automatically respects its fair share without any static configuration.

The two layers are complementary. The client throttle is the primary mechanism under normal conditions. The server gate is the final backstop for pathological cases.

---

## How the Client Throttle Works

When your application opens a JDBC connection, the OJP server includes three fields in the `SessionInfo` response that the driver uses to compute its concurrency limit:

- **`maxAdmission`** — the total number of connection slots the server has configured for this datasource pool.
- **`clientCount`** — the number of distinct JVM instances currently connected to this datasource on this server node.
- **`observedPeak`** — the actual peak in-flight count recorded just before the last admission timeout. Starts at `maxAdmission` and adapts over time.

The driver uses these to compute a per-instance concurrency budget. The formula is ceiling division with a 10% safety margin:

```
perInstanceLimit = ceil(maxAdmission / clientCount) × numServers × 0.9
```

Ceiling division ensures that capacity is not permanently wasted. Floor division at scale (say, 20 slots divided among 7 clients) yields only 2 per client with 6 slots sitting idle. Ceiling division gives 3 per client, and the 10% margin absorbs the slight over-allocation.

Before each SQL execution, the driver atomically checks its in-flight counter against this limit. If the counter is already at or above the limit, the request is rejected locally with a `SQLTransientException` — before it ever leaves the JVM, before it consumes a gRPC thread, before it touches the server. The check is a single `AtomicInteger` compare-and-swap with no blocking and no synchronization overhead.

Transactions are exempt. If a connection already has an open transaction (`autoCommit = false`), subsequent statements on that connection skip the throttle check entirely. Without this exception, a thread holding an open transaction could be blocked from sending its next statement, causing the server's transaction timeout to fire before the query arrives — an extremely frustrating failure mode that looks like a timeout from nowhere.

---

## The Three Modes

Client throttling has three operating modes, configured via the `ojp.jdbc.clientThrottle.mode` property. The default is `REACTIVE`.

### REACTIVE (default)

The reactive limit is derived from `observedPeak` rather than `maxAdmission`. Before any admission timeout has occurred, `observedPeak` is zero on the server. The driver treats `observedPeak = 0` as "no overload observed yet" and applies no cap — REACTIVE mode is effectively unlimited from startup. The throttle only engages once the server has genuinely seen pressure.

When the system does experience an admission timeout, the server begins tracking `observedPeak` using AIMD:

- **Multiplicative decrease**: when an admission timeout occurs, the server snaps `observedPeak` down to the actual active count at that moment (with a small floor to prevent collapse). This reflects how much concurrent load the database was actually handling when it started struggling.
- **Additive increase**: every `totalSlots × 2` successful slot releases, `observedPeak` increments by one, up to the full pool size. Recovery is deliberately slow — bursting back to full capacity too quickly defeats the purpose.

The driver uses `observedPeak` in place of `maxAdmission` in the fair-share formula. When `observedPeak` is zero (no pressure seen yet), the reactive limit is unlimited. When the server has been under pressure, the limit tightens automatically and recovers gradually.

REACTIVE mode requires no configuration and adapts to changing database conditions without any manual intervention. It is the right choice for the majority of deployments.

### PROACTIVE

The proactive limit uses the static configured `maxAdmission` directly. It activates from the very first connection and does not change unless the configured pool size changes. It guarantees fairness between clients at all times but does not adapt if the database is actually struggling below its configured pool size.

Use PROACTIVE when you want a hard, predictable upper bound on concurrency and your database is consistently healthy at its configured pool size.

### COMBINED

The effective limit is `min(proactiveLimit, reactiveLimit)`. Proactive provides the fairness guarantee from day one. Reactive tightens the limit when the database is genuinely struggling. Neither alone is sufficient in all conditions; COMBINED is the most correct choice for workloads that require both guarantees.

Switch from the REACTIVE default to COMBINED if your workload involves sudden client join/leave events that could cause a burst before `observedPeak` has caught up, or if you are operating in a multi-tenant environment where fairness between tenants matters.

---

## Reactive Throttle Hardening

The 0.5.0-beta release significantly hardened the reactive throttle after stress testing revealed a failure mode: under open-loop burst load, multiple simultaneous `RESOURCE_EXHAUSTED` responses could each trigger a separate halving of the reactive limit, collapsing it to 1 in milliseconds — and then it would stay stuck at 1 because the only source of additive recovery was new connection responses.

Four changes addressed this:

**Cooldown.** `notifyServerOverload()` now coalesces all overload signals received within a configurable window (default: 200 ms) into a single halving event. A burst of 20 simultaneous rejections produces one halving, not 20.

**Soft floor.** The reactive limit cannot be driven below `max(1, proactiveLimit / reactiveFloorDivisor)`. With the default divisor of 4, the floor is approximately 25% of the proactive limit. In a realistic deployment, this means the client will never throttle itself below a meaningful fraction of its fair-share allocation.

**Configurable decrease factor.** The multiplicative decrease defaults to 0.5 (halving) but can be tuned to a gentler value. Setting `reactiveDecreaseFactor=0.75` means each overload event reduces the limit to 75% of its current value rather than 50%. This is useful when your database degrades gradually rather than suddenly.

**Autonomous additive recovery.** Once the reactive limit has been reduced after an overload, the driver needs a path back up that does not depend on receiving a new connection response with fresh `observedPeak` data. It now counts successful `release()` calls: after enough consecutive successes with no new overload signal (default: `max(8, reactiveLimit)` successes per +1 step), the reactive limit goes up by one. This keeps recovery working even under sustained execute traffic with no new connections being opened.

---

## SessionInfo on Every Response

Prior to 0.5.0-beta, the server only populated throttle fields (`maxAdmission`, `observedPeak`, `clientCount`) on connect responses. Every subsequent SQL response carried an empty `SessionInfo`, meaning the reactive limit could only recover when a new connection was established.

The server now stamps these fields on every response: transaction start/commit/rollback, `executeUpdate`, and every result-set chunk. The driver processes them through the same AIMD discipline on each response. Combined with the autonomous recovery mechanism, this provides two independent recovery channels that work even under sustained execute traffic.

---

## Lane-Aware Overload

When Slow Query Segregation (SQS) is active, the OJP server partitions the connection pool into fast and slow lanes. A saturation event in the slow lane does not mean fast queries are being starved — but prior to 0.5.0-beta, a slow-lane `RESOURCE_EXHAUSTED` would trigger the same reactive limit halving that a fast-lane timeout would. In a workload dominated by short OLTP queries, this caused steady-state OLTP throughput to be unnecessarily penalised by occasional slow-lane saturation events.

The fix is lane-aware overload notification. When the server rejects a request, it attaches the overloaded lane to the gRPC response trailer (`ojp-overload-lane`). The driver parses this and applies a lane-specific policy:

| Lane | Driver behaviour |
|---|---|
| `FAST` | Apply AIMD decrease (with cooldown and soft floor). |
| `SLOW` | Suppressed — do not change the reactive limit. |
| `QUEUE` | Suppressed — transient burst signal, not saturation. |
| `UNKNOWN` | Treat as `FAST` for safety (preserves behaviour with older servers). |

The suppression for `SLOW` is the primary cross-lane contamination fix. A batch analytics query hitting the slow-lane ceiling is not a signal that OLTP throughput should be reduced. The two lanes are decoupled by design, and the throttling response now respects that decoupling.

---

## Configuration

Client throttling is enabled by default in REACTIVE mode and requires no configuration changes to start working. The following properties are available to tune its behaviour if needed:

```properties
# Throttle mode: OFF, PROACTIVE, REACTIVE (default), or COMBINED
ojp.jdbc.clientThrottle.mode=reactive

# Overload cooldown: minimum milliseconds between consecutive halvings (default: 200)
ojp.jdbc.clientThrottle.overloadCooldownMs=200

# Floor divisor: reactive limit never falls below proactiveLimit / divisor (default: 4)
ojp.jdbc.clientThrottle.reactiveFloorDivisor=4

# Decrease factor: fraction applied on overload, must be in (0, 1) (default: 0.5)
ojp.jdbc.clientThrottle.reactiveDecreaseFactor=0.5

# Recovery threshold: successes per +1 recovery step (0 = auto: max(8, reactiveLimit))
ojp.jdbc.clientThrottle.recoverySuccessThreshold=0
```

Most deployments should leave these at their defaults. The properties most worth reviewing if you observe unexpected behaviour are:

- **`mode`** — switch to `COMBINED` if you need both static fairness and adaptive protection. Use `PROACTIVE` if your database is consistently healthy and you want a hard static cap. Set to `OFF` only for debugging or if you have an external rate limiter you trust more.
- **`reactiveDecreaseFactor`** — lower this (toward 0.25) if you want the driver to back off more aggressively on the first overload signal; raise it (toward 0.75) if your database recovers quickly and you want the limit to converge faster after each event.
- **`reactiveFloorDivisor`** — raise this (to 8 or 16) if you want a higher floor — that is, a client that never throttles below 10–12% of its proactive limit even under extreme pressure. Lower it (to 2) for a more conservative floor.
- **`overloadCooldownMs`** — increase if your server sends bursts of rejection responses simultaneously and you want to treat the whole burst as one event.

---

## Interaction with Slow Query Segregation

When SQS is active, the proactive limit is computed against `totalSlots` (the full pool size) rather than `fastSlots`. This is correct because lane borrowing means a query can land on either type of slot, so the realistic concurrency ceiling is the total pool, not just the fast portion. The lane-aware overload suppression (described above) handles the decoupling at the reactive layer.

The full analysis of all interaction scenarios — including startup warm-up behaviour, `observedPeak` stability under each SQS classification mode, and recommended configurations for both features together — is in [documents/analysis/CLIENT_REACTIVE_THROTTLING_ANALYSIS.md](../analysis/CLIENT_REACTIVE_THROTTLING_ANALYSIS.md).

---

## Client Throttling Requires No Server Restart

Client throttling configuration is read from `ojp.properties` when the JDBC driver initialises. You can change `ojp.jdbc.clientThrottle.mode` and the hardening parameters without restarting the OJP server. The driver picks up the new values on next JVM startup or when the connection pool is recreated.

The complete JDBC driver configuration reference is in [documents/configuration/ojp-jdbc-configuration.md](../configuration/ojp-jdbc-configuration.md).
