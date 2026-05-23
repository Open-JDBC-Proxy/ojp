# Throttling Metrics Analysis

**Status:** Analysis only — no implementation yet, awaiting approval.
**Author:** Investigation requested 2026-05-23.
**Scope:** Identify which metrics OJP should expose about its existing throttling
behaviour so operators can observe, alert on, and tune it.

---

## 1. Why this analysis exists

OJP enforces back-pressure in **three independent layers**, but none of them
currently emit metrics. The only observable signals today are `log.warn` /
`log.debug` lines and the human-readable `SlotManager.getStatus()` string.

Consequence: when OJP starts rejecting work, an operator cannot tell:

- whether requests are being shed at all,
- which layer is doing the shedding,
- whether the configured limits are too tight or too loose,
- whether the AIMD feedback loops are converging or oscillating.

This document proposes a concrete metrics surface that closes that gap, and
flags the design decisions that need a human call before implementation begins.

**Confidence in the gap analysis: High** — verified by reading each class
directly; no metric registration exists in any of them.

---

## 2. The three throttling layers today

| Layer | Class | Scope | Rejection signal |
|---|---|---|---|
| **A. Global gRPC concurrency gate** | `ConcurrencyThrottleInterceptor` (server) | Server-wide, all in-flight gRPC calls | Closes call with `RESOURCE_EXHAUSTED` "too many concurrent requests" |
| **B. Admission control / SQS slot manager** | `SlotManager` (server) | Per-datasource, fast/slow lanes + borrowing + AIMD `observedPeak` | `acquireFastSlot` / `acquireSlowSlot` return `false` after timeout or when wait-queue cap is reached |
| **C. Client-side reactive throttle** | `ClientThrottleManager` (driver) | Per JVM, per `connHash` | Local fail-fast via CAS on an `AtomicInteger`; no network round-trip |

The layers compose: a request can be rejected at C (cheapest, no network), at A
(once it reaches the server), or at B (once a session is established and a slot
is needed for a query). Healthy steady state should have C dominating
rejections, with A and B contributing only during true overload.

---

## 3. Operator-facing questions the metrics must answer

Each proposed metric is justified by which of these questions it answers:

1. *Is OJP rejecting requests right now? Which layer?*
2. *How close to capacity am I — scale OJP horizontally, or raise pool size?*
3. *Is SQS doing its job (fast lane stays fast even when slow queries pile up)?*
4. *Is the client-side throttle protecting the server, or over-tightening?*
5. *Is AIMD converging on a stable `observedPeak` / `reactiveLimit`, or oscillating?*
6. *Per datasource / per database — which is the hot spot?*

---

## 4. Proposed metrics — Server side

All metrics live under a new OpenTelemetry meter scope **`ojp.throttle`**,
following the existing `ojp.sql`, `ojp.hikari.pool`, `ojp.xa.pool` convention.
Implementation should mirror the existing pattern: interface +
OpenTelemetry impl + NoOp impl + factory (see
`OpenTelemetrySqlStatementMetrics`, `OpenTelemetryPoolMetrics`).

### 4.1 Global gRPC throttle (`ConcurrencyThrottleInterceptor`)

| Metric | Type | Attributes | Question answered |
|---|---|---|---|
| `ojp.throttle.grpc.inflight` | Gauge | – | Live concurrency level |
| `ojp.throttle.grpc.limit` | Gauge | – | Configured limit visibility |
| `ojp.throttle.grpc.utilization` | Gauge (0–100) | – | One-glance "how close to the wall" |
| `ojp.throttle.grpc.rejected.total` | Counter | `grpc.method` | Are we shedding load? Which RPCs? |
| `ojp.throttle.grpc.accepted.total` | Counter | `grpc.method` | Denominator for rejection ratio |
| `ojp.throttle.grpc.hold.time` | Histogram (ms) | – | Detect "blocked on backend" vs "burst arrivals" |

Rejection ratio in PromQL: `rate(rejected) / (rate(rejected) + rate(accepted))`.

### 4.2 Admission control / SlotManager (per datasource)

Attributes carry `datasource` and, where meaningful, `lane = fast|slow`.

| Metric | Type | Attributes | Question answered |
|---|---|---|---|
| `ojp.throttle.slots.total` | Gauge | `datasource`, `lane` | Configured lane size |
| `ojp.throttle.slots.active` | Gauge | `datasource`, `lane` | Live in-use count |
| `ojp.throttle.slots.available` | Gauge | `datasource`, `lane` | `semaphore.availablePermits()` |
| `ojp.throttle.slots.utilization` | Gauge 0–100 | `datasource`, `lane` | Single-pane lane saturation |
| `ojp.throttle.slots.queue.depth` | Gauge | `datasource`, `lane` | `semaphore.getQueueLength()` — earliest warning, rises *before* timeouts |
| `ojp.throttle.slots.queue.max` | Gauge | `datasource` | Configured `maxWaitQueueDepth` |
| `ojp.throttle.slots.acquired.total` | Counter | `datasource`, `lane`, `path=immediate\|wait\|borrowed` | Fast-path hit rate; lane borrowing frequency |
| `ojp.throttle.slots.rejected.total` | Counter | `datasource`, `lane`, `reason=timeout\|queue_full` | Why are we shedding? Tune queue vs slots |
| `ojp.throttle.slots.wait.time` | Histogram (ms) | `datasource`, `lane` | p95/p99 admission latency |
| `ojp.throttle.slots.borrowed` | Gauge | `datasource`, `direction=slow_to_fast\|fast_to_slow` | Are lanes well-sized? |
| `ojp.throttle.slots.observedPeak` | Gauge | `datasource` | AIMD-tracked peak sent to clients |
| `ojp.throttle.slots.enabled` | Gauge (1/0) | `datasource` | Avoid "all clear but feature was off" trap |

> **Note:** `observedPeak` is the value the server ships to clients to size
> their throttles. Exposing it makes the otherwise-invisible AIMD loop
> observable — essential for confidence in the auto-tuning.

---

## 5. Proposed metrics — Driver / client side

The driver has no metrics infrastructure today. Two options:

- **Option A (recommended): JMX MBeans.** Lowest dependency cost; integrates
  with any monitoring stack via the JMX→Prometheus exporter; no new transitive
  OpenTelemetry dependency added to applications using the driver.
- **Option B: OpenTelemetry API (not SDK) as a driver dependency.** Higher
  integration value but a non-trivial dependency decision for a JDBC driver —
  warrants its own ADR before adopting.

Metrics themselves (regardless of transport), attributes `connHash`, `mode`:

| Metric | Type | Question answered |
|---|---|---|
| `ojp.client.throttle.inflight` | Gauge | Live in-flight at the driver |
| `ojp.client.throttle.proactiveLimit` | Gauge | Current proactive limit (from `SessionInfo`) |
| `ojp.client.throttle.reactiveLimit` | Gauge | Current reactive limit (AIMD) |
| `ojp.client.throttle.effectiveLimit` | Gauge | `min(proactive, reactive)` actually enforced |
| `ojp.client.throttle.rejected.total` | Counter | Driver-local fail-fast count (no server round-trip) |
| `ojp.client.throttle.acquired.total` | Counter | Denominator for ratio |
| `ojp.client.throttle.serverOverload.events.total` | Counter | Every `notifyServerOverload()` (RESOURCE_EXHAUSTED) |
| `ojp.client.throttle.limitChanges.total` | Counter, `direction=increase\|decrease` | AIMD stability — frequent flips → unstable cluster sizing |

Healthy steady state: `ojp.client.throttle.rejected.total` dominates
`ojp.throttle.grpc.rejected.total` and `ojp.throttle.slots.rejected.total`,
because client-side rejection avoids a network round-trip.

---

## 6. Cross-cutting recommendations

1. **Reuse existing `OpenTelemetryHolder`** that already feeds `ojp.sql` and
   `ojp.*.pool` scopes — do not introduce a second OTel bootstrap path.
2. **NoOp impl by default** in unit tests, matching `NoOpSqlStatementMetrics` /
   `NoOpPoolMetrics`. Throttle logic must not be coupled to OTel directly.
3. **Cardinality discipline.** Allowed labels: `datasource`, `lane`,
   `grpc.method`, `mode`, `path`, `reason`, `direction`. Disallowed:
   `client.uuid`, `session.id`, `sql.statement` — these would explode
   cardinality. The proposed labels are all bounded by configuration or by a
   small fixed enum.
4. **Document a recommended alerting set** in
   `documents/telemetry/README.md`. At minimum:
   - sustained `utilization > 80%` → scale out;
   - any non-zero `rejected` rate → page;
   - `observedPeak < totalSlots * 0.5` for >10 minutes → DB is the
     bottleneck, not OJP.
5. **Reduce `SlotManager.getStatus()` log spam to DEBUG** once equivalent
   metrics exist — the periodic INFO line becomes redundant.

---

## 7. Suggested implementation sequence

1. **Layer B — SlotManager.** Biggest operator value, no new transport, fits
   the existing OTel scope pattern. Ship first.
2. **Layer A — gRPC interceptor.** Small surface; counters and gauges around
   the existing `AtomicInteger`.
3. **Layer C — driver.** Last, with its own ADR for the JMX-vs-OTel-API
   transport decision.

Each layer can ship independently; each is observable from PromQL the same day.

---

## 8. Open decisions (need approval before implementing)

- **Driver-side transport.** JMX (recommended) vs adding the OpenTelemetry API
  as a driver dependency. Confidence: Medium — leaning JMX, but this is a
  design call the maintainers should own. Likely warrants an ADR.
- **`datasource` attribute identity.** The hash (`connHash`) is already
  available in code; a human-friendly datasource name would require config
  plumbing. Recommend starting with the hash and revisiting once needs are
  clearer.
- **`grpc.method` cardinality.** ~10 RPC methods today; confirm this label is
  acceptable before adopting it on `ojp.throttle.grpc.*`.
- **Overlap with existing `ojp.sql.slow.executions.total`.** Intentionally
  kept `lane` attribution only on *slot* metrics (admission concern); SQL
  statement metrics keep their existing shape. Confirm this split is right.

---

## 9. Code references

- `ojp-server/src/main/java/org/openjproxy/grpc/server/ConcurrencyThrottleInterceptor.java`
- `ojp-server/src/main/java/org/openjproxy/grpc/server/SlotManager.java`
- `ojp-server/src/main/java/org/openjproxy/grpc/server/GrpcServer.java` (interceptor wiring)
- `ojp-server/src/main/java/org/openjproxy/grpc/server/metrics/` (existing OTel metrics pattern to follow)
- `ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/ClientThrottleManager.java`
- `documents/telemetry/README.md` (where the new metrics should be documented)
- `documents/analysis/CLIENT_REACTIVE_THROTTLING_ANALYSIS.md` (related background)
