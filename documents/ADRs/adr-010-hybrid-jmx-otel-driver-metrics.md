# ADR 010: Hybrid JMX core + opt-in OpenTelemetry adapter for driver-side metrics

In the context of the OJP JDBC driver (`ojp-jdbc-driver`),
facing the need to expose client-side throttling metrics (in-flight, proactive/reactive/effective limits, acquired/rejected counters, server-overload events, AIMD limit changes) without forcing observability dependencies onto every host application that uses the driver,

we decided for a **hybrid approach**: ship **JMX MBeans in the driver core** (zero new runtime dependencies) and publish a **separate optional adapter module** (`ojp-jdbc-driver-otel-metrics`) that maps the same metrics onto OpenTelemetry for users who want native dimensional metrics.
A small internal abstraction (`ClientThrottleMetrics` interface, with `NoOp`, `Jmx`, and — later — `OpenTelemetry` implementations selected by `ClientThrottleMetricsFactory` via the `ojp.jdbc.metrics` system property) lets the binding be chosen at runtime, mirroring the server-side `SqlStatementMetrics` / `NoOpSqlStatementMetrics` / `OpenTelemetrySqlStatementMetrics` pattern already in `ojp-server`.

We neglected:
- **JMX-only.** Rejected because it forces operators on an OTel pipeline to bolt on the `jmx_exporter` agent plus YAML rules and gives up native dimensional labels, histograms, and trace exemplars.
- **OpenTelemetry-API-only in the driver core.** Rejected because adding `io.opentelemetry:opentelemetry-api` as a runtime dependency of a JDBC driver creates real classpath / version-skew risk (estimated ~70% chance of biting at least one user during the 0.x lifecycle), introduces the silent no-op trap when a host has the API but no SDK, and is essentially irreversible once shipped under our metric names. Shading the API would defeat the integration value that motivates choosing OTel in the first place.
- **Micrometer in the driver core.** Rejected for the same dependency-cost reason; Micrometer is a strictly larger commitment than the OTel API.

to achieve:
- **Zero new mandatory runtime dependencies** for the driver — it stays a polite guest in any host JVM.
- **Universal out-of-the-box consumption** of metrics via JConsole / VisualVM / `jmx_exporter` / any APM agent that already scrapes JMX.
- **Native OpenTelemetry integration as an opt-in** for users who want it, without paying any cost when they don't.
- **A reversible, low-risk Phase 1** whose architectural artefact (the `ClientThrottleMetrics` interface) is exactly what an eventual Phase 2 OTel adapter needs.
- **Cultural alignment** with how HikariCP (the de facto reference for JDBC-driver-adjacent metrics) evolved: JMX in core, Micrometer / Dropwizard as separately-published optional adapters.

accepting:
- **Two implementations to maintain** (JMX and, in Phase 2, OTel). This cost is bounded by the small surface of the `ClientThrottleMetrics` interface.
- **JMX exposes scalars only.** The proposed driver metrics in `documents/analysis/THROTTLING_METRICS_ANALYSIS.md` §5 are all gauges and counters — no histograms — so this is not currently a functional limitation.
- **Operators on Prometheus via JMX need a `jmx_exporter` configuration**. We will ship a sample rules YAML alongside the documentation.
- **MBean lifecycle is real work.** Registration must be idempotent per `connHash`, unregister-on-close must be best-effort, and duplicate-name registration must be tolerated. Encapsulated in `JmxClientThrottleMetrics`.
- **Phase 2 timing is not committed by this ADR.** The OTel adapter module will be authored under a follow-up issue once Phase 1 adoption produces a real signal that it is wanted.

because:
- The driver lives in the host application's classpath, where the dominant operational risk is dependency conflict, not metric expressiveness; JMX has zero such risk.
- The hybrid keeps the door open for OpenTelemetry-native consumption without forcing it on anyone.
- The internal interface makes the decision reversible: if the maintainers later prefer to consolidate on OTel, the JMX implementation can be deprecated without touching the throttle-management code paths.

## Scope

- **In scope.** Driver-side client throttling metrics defined in `THROTTLING_METRICS_ANALYSIS.md` §5 (`ojp.client.throttle.*`).
- **Out of scope.** Server-side `ojp.throttle.*` metrics (gRPC interceptor and `SlotManager`); the server already standardises on OpenTelemetry per ADR-005 and that does not change here.

## Configuration

A single system property controls the binding:

```
-Dojp.jdbc.metrics=jmx   # default — register JMX MBeans
-Dojp.jdbc.metrics=none  # disable; use NoOpClientThrottleMetrics
-Dojp.jdbc.metrics=otel  # Phase 2 — only available with ojp-jdbc-driver-otel-metrics on the classpath
```

If `otel` is requested but the OTel adapter is not on the classpath, the factory falls back to `jmx` and logs a single warning.

## Rollout

1. **Phase 1 (this ADR).** Land `ClientThrottleMetrics` interface, `NoOp` and `JMX` implementations, factory, and wiring in `ClientThrottleManager` / `Connection`. Default: `jmx`. No new runtime dependencies in `ojp-jdbc-driver`.
2. **Phase 2 (follow-up issue).** Author `ojp-jdbc-driver-otel-metrics` as a separate Maven module that depends on `opentelemetry-api` and implements the same interface. Users who want OTel add the adapter jar; users who don't, don't pay any cost.

| Status        | PROPOSED            |
|---------------|---------------------|
| Proposer(s)   | GitHub Copilot, on direction from Rogerio Robetti |
| Proposal date | 23/05/2026          |
| Approver(s)   |                     |
| Approval date |                     |
