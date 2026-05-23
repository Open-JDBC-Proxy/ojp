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

The driver has no metrics infrastructure today. Two pure options and one
hybrid; see **Appendix §9** for the full deep dive.

- **Option A: JMX MBeans.** Zero new driver dependency, universal consumer
  support (JConsole, `jmx_exporter`, any APM agent). Scalars only; needs
  exporter config for Prometheus.
- **Option B: OpenTelemetry API in the driver.** Native dimensional model
  and histograms, but introduces a real classpath dependency for every host
  application — including those that don't use OTel — and creates a non-zero
  version-skew risk.
- **Option C (recommended): Hybrid.** JMX in the driver core, plus an
  optional `ojp-jdbc-driver-otel-metrics` adapter jar published separately.
  Mirrors HikariCP's history (JMX core + opt-in Micrometer/Dropwizard
  adapters). Decision is reversible and Phase 2 is gated on real adoption
  signal.

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

- **Driver-side transport.** See Appendix §9 for the full deep dive.
  Recommendation: hybrid (JMX in driver core + opt-in OTel adapter module
  published separately). Confidence: ~80%. Still warrants an ADR before
  Phase 2.
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

## 9. Appendix — Deep dive: JMX vs OpenTelemetry for **driver-side** metrics

> Scope: this appendix is **driver-only**. The server already uses
> OpenTelemetry and that is not in question. The asymmetry matters: a JDBC
> driver is loaded into someone else's JVM, so it must be a polite guest in a
> way the server never has to be.

### 9.1 Baseline facts that frame the decision

- **Driver has zero OTel dependency today** (`grep opentelemetry
  ojp-jdbc-driver/pom.xml` → 0; server → 21). Adding OTel is therefore an
  *introduction*, not an extension.
- **Driver minimum runtime is Java 11**, server is Java 21. Anything pulled
  into the driver must compile and run on 11.
- **The driver ships into the host application's classpath.** It does not
  control which JVM, which OTel version (if any), or which exporter the host
  application uses.
- **Driver metric volume is tiny** (~8 series per `connHash`, no per-query
  cardinality). The technical demand is well below what JMX can handle.

### 9.2 Option A — JMX MBeans

**How it would work.** Register one `ThrottleManagerMXBean` per `connHash`
under e.g. `org.openjproxy:type=ClientThrottle,connHash=<hash>` with attributes
mirroring §5 (inflight, limits, counters). No new runtime dependency. Host
apps consume via:
- JConsole / VisualVM out of the box,
- `jmx_exporter` Java agent → Prometheus,
- any APM agent (New Relic, AppDynamics, Datadog) that already scrapes JMX
  (they all do).

**Benefits**
- **Zero new dependencies.** Stays inside `java.management`. No classloader,
  no shading, no version-skew risk for host applications.
- **Universally consumable.** Every Java monitoring tool understands JMX.
  Operators don't need to install or configure anything OJP-specific.
- **Java 11 friendly with no caveats.** No multi-release jar concerns, no
  conditional bytecode.
- **Cheap to remove or evolve.** MBeans are a thin façade over the existing
  `AtomicInteger`s; if we change our mind in 6 months, deletion is trivial and
  affects no public Java API.
- **Matches the cultural norm for JDBC drivers.** HikariCP exposes pool
  metrics via JMX by default (it added Micrometer/Dropwizard as *optional*
  adapters). Operators expect this.
- **No risk of "double OTel".** If the host app already uses OTel with a
  different version, OJP's metrics still work — there is no classpath
  collision to resolve.

**Implications and downsides**
- **Pull-only and rate-unaware by itself.** JMX exports raw values; rates
  (`rate(rejected[5m])`) happen on the scraper side. This is exactly what
  `jmx_exporter` is built for, but it is one more moving part operators must
  configure.
- **Naming / hierarchy maps awkwardly to dimensional metrics.** Prometheus
  labels via `jmx_exporter` require a regex-based YAML config; operators must
  copy or be given a sample rules file. We should ship one.
- **No histograms natively.** JMX attributes are scalars. We would expose
  `count` + `sum` + a few percentiles computed locally (e.g. via HdrHistogram)
  if needed. For the proposed driver metric set this is acceptable because
  there are no histograms — wait time lives only on the server side.
- **No trace correlation.** JMX cannot attach exemplars or link a metric
  point to a trace span. For a driver that does no tracing today, this is
  zero practical loss.
- **MBean lifecycle = a small footgun.** We must unregister on
  `Connection`/throttle-manager teardown, and re-register safely if the same
  `connHash` reappears. A static set of registered ObjectNames plus a JVM
  shutdown hook handles this; it is a known pattern but real work.
- **Security manager / module-system friction is essentially nil today** but
  worth noting: `java.management` is a base module; no `--add-opens`
  gymnastics.

### 9.3 Option B — OpenTelemetry **API** in the driver

> Important distinction: **API**, not **SDK**. The driver would call
> `GlobalOpenTelemetry.get().meterBuilder(...)`, and the host application
> supplies the SDK and exporter. If the host app has no SDK installed, OTel's
> API returns a no-op meter and nothing breaks.

**Benefits**
- **Native dimensional model.** Attributes (`connHash`, `mode`, …) map 1:1 to
  the proposed metric design. No YAML translation rules to maintain.
- **Histograms first-class.** If we later want client-side wait-time
  histograms (we currently don't), they are free.
- **Trace correlation / exemplars.** A future "trace each throttled
  rejection" feature lands without extra plumbing.
- **One observability stack across server and driver.** A single dashboard
  template, one mental model for operators who are already on OTel.
- **Modern, where the industry is heading.** Most new-issue observability work
  in Java assumes OTel.

**Implications and downsides**
- **Real dependency on the host application's classpath.** Even just the API
  jar (`io.opentelemetry:opentelemetry-api`) means:
  - One more jar in apps that don't use OTel.
  - **Version-skew risk** is the dominant concern. If the host already pulls
    a different OTel API version, the usual "newest wins" classpath rule
    applies. OTel's API has been stable since 1.0 and tries hard to avoid
    breaks, but transitive dependency conflicts are the #1 reason JDBC
    drivers get blamed for "weird" startup failures. *Confidence this will
    bite us at least once: ~70%.*
  - We may eventually need to **shade and relocate** the OTel API
    (`org.openjproxy.shaded.io.opentelemetry`) to be safe — but that breaks
    the "metrics flow into the host's OTel pipeline" property, which is the
    main reason to choose OTel in the first place. Shading defeats the
    purpose; not shading risks conflicts. There is no clean middle.
- **Silent no-op trap.** If the host application has the API but not an SDK
  installed (a very common state — many apps include OTel transitively
  without configuring it), `GlobalOpenTelemetry.get()` returns a no-op meter
  and OJP metrics silently disappear. Operators will file bugs.
- **API stability is *good*, not perfect.** The metrics API was incubating
  for longer than tracing. We must pin a minimum version (≥ 1.31 is safe)
  and document it.
- **Larger driver jar / longer cold start.** Marginal (~200 KB, a few classes
  loaded), but JDBC drivers compete on smallness.
- **Java 11 baseline is fine** for current OTel API versions, but we lose
  some freedom — if upstream raises its baseline to 17, we either pin an
  older OTel or raise the driver's baseline. Either is awkward.
- **Harder to remove.** Once shipped, removing the OTel dependency is a
  breaking change for any user who wired exporters to our metric names.

### 9.4 Hybrid — JMX now, optional OTel adapter later

A pragmatic third path:

1. **Phase 1:** Ship JMX. Counters/gauges live behind a tiny
   `ClientThrottleMetrics` interface with a `JmxClientThrottleMetrics`
   implementation, plus a `NoOp` (same pattern as
   `OpenTelemetrySqlStatementMetrics` / `NoOpSqlStatementMetrics` on the
   server side).
2. **Phase 2 (optional, separate release):** Add a *separate Maven
   coordinate* — e.g. `ojp-jdbc-driver-otel-metrics` — that depends on the
   OTel API and implements the same interface. Users who want OTel add the
   adapter jar; users who don't, don't pay any cost. No shading needed
   because users opt in. No version conflicts for non-OTel users.

**Why this is attractive**
- The hard architectural decision (`ClientThrottleMetrics` interface) is
  needed under *any* option. Doing it once unlocks both.
- Mirrors HikariCP's history: JMX in core; Micrometer/Dropwizard as
  separately-published adapters that nobody is forced to depend on.
- Lets the maintainers see real adoption signal before committing the driver
  to an OTel dependency.
- Reversible. Phase 2 can be cancelled with zero impact on Phase 1 users.

### 9.5 Comparison summary

| Dimension | JMX | OTel API in driver | Hybrid (JMX core + adapter) |
|---|---|---|---|
| New driver dependency | None | `opentelemetry-api` | None (adapter is optional jar) |
| Risk of host-classpath conflicts | None | Medium-High | None for core; opt-in for adapter |
| Out-of-the-box for OTel users | Needs `jmx_exporter` or APM agent | Native | Native (with adapter) |
| Out-of-the-box for non-OTel users | Native (JConsole, APMs) | They pay the dep cost anyway | Native |
| Dimensional labels | Via `jmx_exporter` rules | Native | Both, depending on jar |
| Histograms | Scalars only (fine for §5) | Native | Both, depending on jar |
| Trace exemplars | No | Yes | Adapter-only |
| Driver jar size impact | ~0 | ~200 KB | ~0 for core |
| Reversibility | High | Low | High |
| Cultural fit for a JDBC driver | Strong (Hikari precedent) | Mixed | Strong + future-proof |
| Implementation effort | Low | Low | Low + Low (separate module) |

### 9.6 Recommendation

**Adopt the hybrid.** Concretely:

1. Introduce `org.openjproxy.jdbc.metrics.ClientThrottleMetrics` in
   `ojp-jdbc-driver` with `NoOp` and `Jmx` implementations.
2. Wire `ClientThrottleManager` and `Connection` to the interface; default
   binding selected by a system property (`ojp.jdbc.metrics=jmx|none`,
   default `jmx`).
3. Open a follow-up issue to evaluate an `ojp-jdbc-driver-otel-metrics`
   adapter module after one minor release of real-world usage.

**Confidence: ~80% (High).** The main residual uncertainty is whether enough
users will ask for native OTel in the driver to justify Phase 2; that
question answers itself with telemetry-of-the-telemetry over the next couple
of releases.

### 9.7 Things that would *change* the recommendation

- If maintainers already plan to add tracing to the driver, jumping straight
  to OTel becomes more defensible — counters and spans want to share the
  same context.
- If we want exemplar-linked debugging (`rejected.total{trace_id=...}`) as a
  first-class operator feature, OTel becomes strongly preferred.
- If the project decides to raise the driver's minimum Java to 17+, the
  classpath-conflict risk shrinks (more apps will have aligned to modern OTel
  baselines), nudging toward straight OTel.

---

## 10. Code references

- `ojp-server/src/main/java/org/openjproxy/grpc/server/ConcurrencyThrottleInterceptor.java`
- `ojp-server/src/main/java/org/openjproxy/grpc/server/SlotManager.java`
- `ojp-server/src/main/java/org/openjproxy/grpc/server/GrpcServer.java` (interceptor wiring)
- `ojp-server/src/main/java/org/openjproxy/grpc/server/metrics/` (existing OTel metrics pattern to follow)
- `ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/ClientThrottleManager.java`
- `documents/telemetry/README.md` (where the new metrics should be documented)
- `documents/analysis/CLIENT_REACTIVE_THROTTLING_ANALYSIS.md` (related background)
