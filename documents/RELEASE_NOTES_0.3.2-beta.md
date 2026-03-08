# What's New in OJP 0.3.2-beta

So, what exactly has been cooking since `v0.3.1-beta`? Quite a lot, as it turns out. This release is one of the biggest since the project started — it touches nearly every layer of OJP, from the JDBC driver right up through observability, security, and developer tooling. Here's the full rundown in plain English.

---

## Spring Boot Starter — Zero-Boilerplate Integration

The headline addition is the brand-new **`spring-boot-starter-ojp`** module. Before this, wiring OJP into a Spring Boot project required a handful of manual steps: register the driver class, set up a `SimpleDriverDataSource`, forward JVM system properties, and hope you got the order right. Now it's just one dependency and a connection URL.

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>spring-boot-starter-ojp</artifactId>
    <version>0.3.2-beta</version>
</dependency>
```

Drop that in your `pom.xml`, set `spring.datasource.url` to an OJP URL, and the starter takes care of everything else automatically. It detects any `spring.datasource.url` that starts with `jdbc:ojp`, then silently sets the driver class and datasource type for you, no other config needed.

**Named datasource support** is baked in too. If you embed a name in your JDBC URL — `jdbc:ojp[localhost:1059(myApp)]_postgresql://...` — the starter will forward pool configuration with the correct prefix so the OJP server can apply named-pool settings independently per datasource.

The starter targets Spring Boot 4.x (with backwards compatibility for Spring Boot 3.x) and was upgraded to Spring Boot 4.0.3 during the cycle, picking up the fix for a `NoClassDefFoundError` that could crop up on startup in some Spring Boot 4 environments.

---

## XA Transactions — Production-Grade Distributed Support

XA support was technically introduced in an earlier release, but 0.3.2-beta is where it really grew up. This cycle delivered a production-quality XA connection pool built on **Apache Commons Pool 2** — a deliberate choice documented in [ADR-006](ADRs) — and the work to make it behave correctly under real-world conditions.

### Connection Pool SPI

The most architecturally significant piece is the new **Connection Pool Provider SPI**. Two new modules back this up:

- **`ojp-datasource-api`** — the provider interface and contracts
- **`ojp-datasource-dbcp`** — an Apache Commons DBCP2 implementation
- **`ojp-datasource-hikari`** — a HikariCP implementation (for non-XA pools)
- **`ojp-xa-pool-commons`** — the Apache Commons Pool 2 XA provider

The SPI lets you swap out the underlying pool implementation without touching OJP itself. Higher priority number wins, so dropping in a custom provider is as simple as putting it on the classpath. The [Understanding OJP SPIs](Understanding-OJP-SPIs.md) guide has the full walkthrough.

### XA Action Pattern Refactoring

All six core XA operations — `xaStart`, `xaEnd`, `xaPrepare`, `xaCommit`, `xaRollback`, and `xaRecover` — were refactored from inline logic into dedicated **Action classes**. This brings XA in line with the rest of the server's request-handling architecture, makes each operation independently testable, and dramatically reduces the cognitive load of reading `StatementServiceImpl`.

### XA SQL Server Support

SQL Server XA transactions are now supported (tracked as `ojp-74`). This required driver-level XA enlistment and integration tests via TestContainers to validate the full two-phase commit flow. SQL Server joins PostgreSQL as a first-class XA-capable backend.

### Multinode XA Coordination

A persistent race condition in XA pool rebalancing has been fixed. The symptom was the pool staying at a fractional size after a node failure instead of expanding back to the expected capacity. The root cause was `processClusterHealth()` being called too late — after borrowing a session — instead of before. Several related fixes landed alongside this: proper `Xid` object reuse across all XA operations, eager `BackendSession` allocation to support `getConnection()` before `xaStart()`, and session sanitization between transactions to prevent stale state from leaking across XA boundaries.

### Transaction Isolation at Runtime

XA connections now support **configurable transaction isolation**, defaulting to `READ_COMMITTED`. Isolation level can be changed at runtime, and the pool resets it between sessions — so you get a clean slate on every borrow without needing to set it manually in application code.

---

## OpenTelemetry — Richer Observability

The telemetry story got significantly more complete this release.

### Distributed Tracing

OJP now emits **distributed traces** for all gRPC calls via the OpenTelemetry SDK. Two exporters are supported out of the box:

- **Zipkin** — the default; works with any Zipkin-compatible backend
- **OTLP** — for Jaeger, Grafana Tempo, the OpenTelemetry Collector, or any cloud APM tool

Tracing is off by default and turned on with `ojp.tracing.enabled=true`. Sample rate is configurable if you don't want 100% coverage.

### Granular Metrics Flags

Fine-grained control over what gets measured is now available:

| Property | What it controls |
|---|---|
| `ojp.telemetry.enabled` | Master switch for all OpenTelemetry infrastructure |
| `ojp.telemetry.grpc.metrics.enabled` | gRPC call metrics (request counts, latency, errors) |
| `ojp.telemetry.pool.metrics.enabled` | Connection pool metrics |

Previously everything was all-or-nothing.

### SQL Execution Metrics per Statement

The most visible change on the metrics side: SQL execution histograms now use the **actual SQL statement text** as the label, not a hash. Instead of seeing `sql.statement="a3f8c21d"` in your dashboards, you now see `sql.statement="SELECT * FROM orders WHERE status = 'PENDING'"`. Filtering and alerting on slow queries becomes dramatically easier.

### XA and HikariCP Pool Metrics Alignment

XA pools now emit the same connection acquisition time histograms that HikariCP pools have had for a while. The metric naming was also standardized so XA and HikariCP use consistent suffixes while keeping their respective provider prefixes. A `DuplicateLabelsException` that could appear in Prometheus on pool close or restart was fixed by cleaning up gauge handles properly and using sequential, non-sensitive pool names.

### Connection Queue Depth and Wait Time

Three new signals are now tracked:

1. **Connection queue depth** — how many requests are waiting for a connection
2. **Connection wait time** — how long those requests are waiting
3. **SQL execution time by statement** — per-query histograms (mentioned above)

These give you early warning before a connection bottleneck becomes visible to end users.

---

## Security — mTLS and CVE Fixes

### Mutual TLS (mTLS)

OJP now supports **mutual TLS** between the JDBC driver (client) and the OJP server. Both sides can be configured with certificates:

```bash
# Server side
-Dojp.ssl.enabled=true
-Dojp.ssl.cert=/path/to/server.crt
-Dojp.ssl.key=/path/to/server.key
-Dojp.ssl.ca=/path/to/ca.crt

# Client side (JDBC URL)
jdbc:ojp[localhost:1059]_postgresql://...?ssl=true&sslCert=/path/to/client.crt
```

### SSL Certificate Path Placeholders

Long file paths in JDBC URLs are awkward. OJP now supports **placeholder syntax** for SSL certificate paths in the URL, resolving them from system properties or environment variables at connection time. This is particularly useful for containerized deployments where cert paths are injected at runtime.

### CVE Fixes

Several known vulnerabilities were patched:

- **CVE-2026-1225** — logback-core upgraded to 1.5.32
- **jackson-core DoS** (CVSS 8.0) — forced to 2.18.6
- **assertj-core XXE** — upgraded to 3.27.7
- gRPC upgraded to 1.78.0, Netty forced to 4.1.130.Final
- opentelemetry-exporter-prometheus aligned to 1.59.0-alpha

---

## java.time Type Support

The OJP JDBC driver now has **comprehensive support for `java.time` types** as JDBC parameters. You can call `setObject(n, value)` with any of the modern date/time types and it will work correctly:

| Java type | Supported |
|---|---|
| `LocalDate` | ✅ |
| `LocalTime` | ✅ |
| `LocalDateTime` | ✅ |
| `OffsetDateTime` | ✅ |
| `OffsetTime` | ✅ |
| `Instant` | ✅ |
| `ZonedDateTime` | ✅ |

The implementation routes through `ProtoConverter` and `TemporalConverter` on the server side. Databases that don't support a given type (for example, some JDBC drivers have quirks around `OffsetTime`) will return a clear unsupported-type error rather than hanging or returning corrupt data.

This was tested across every database OJP supports: PostgreSQL, H2, MySQL, MariaDB, Oracle, SQL Server, DB2, and CockroachDB. Each database now has its own split integration tests covering both the success path and the unsupported-type path.

---

## ojp-testcontainers — First-Class Testing Module

A new **`ojp-testcontainers`** module is now available on Maven Central. It provides an `OjpContainer` class that wraps an OJP server in a TestContainers-managed Docker container, making integration tests straightforward:

```java
@Container
static OjpContainer ojp = new OjpContainer()
    .withBackendUrl("postgresql://user@localhost/mydb");
```

The container handles port mapping, readiness checks, and lifecycle management. Session affinity tests and XA rebalancing tests both use it as their foundation.

---

## Circuit Breaker — Per-Datasource Isolation

A **circuit breaker** with per-datasource isolation has been added, implemented via a Registry pattern. Each datasource gets its own circuit breaker instance, so a cascade failure in one backend doesn't trip the breaker for unrelated connections. Configuration and behavior follow the existing OJP configuration convention (JVM system properties or environment variables).

---

## Slow Query Segregation — Now Off by Default

The slow query segregation feature, which routes slow-running queries to a separate execution path, is now **disabled by default**. It previously defaulted to enabled, which created surprising behavior for users who hadn't explicitly opted in. The feature itself is unchanged; you enable it with `ojp.slow.query.segregation.enabled=true`.

---

## Unified Connection Architecture

A quiet but important internal change: the old `connectToSingleServer()` code path has been removed. OJP now always uses the multinode-capable connection manager, even when only one server is configured. This eliminates a class of bugs that only appeared when switching from single-node to multinode deployments, and simplifies future work significantly.

---

## Virtual Thread Support (Java 21)

OJP Server now targets **Java 21** and uses virtual threads for pool housekeeping tasks. The `synchronized` blocks that were incompatible with virtual thread pinning have been replaced with `ReentrantLock`. The XA pool's evictor and metrics listener both run on virtual thread executors, with a fallback to platform threads on older JVM versions.

---

## Action Pattern — Server Architecture Consistency

`StatementServiceImpl`, the heart of the OJP server, was refactored to use the **Action pattern** consistently throughout. New action classes include `ResultSetHelper`, `FetchNextRowsAction`, `StartTransactionAction`, and `CreateLobAction`. Every XA operation now follows the same pattern. This doesn't change anything observable from the outside, but it makes the server significantly easier to maintain and extend.

---

## Summary

That's a big list! To put it in perspective:

- **New modules**: `spring-boot-starter-ojp`, `ojp-datasource-api`, `ojp-datasource-dbcp`, `ojp-datasource-hikari`, `ojp-xa-pool-commons`, `ojp-testcontainers`
- **New features**: Spring Boot autoconfiguration, mTLS, distributed tracing, `java.time` types, circuit breaker, SSL placeholders, XA SQL Server support, per-datasource metrics
- **Improved features**: XA pool stability, telemetry granularity, pool metrics alignment, slow query defaults
- **Security**: 6+ CVEs patched, mTLS, security validation for SSL placeholders
- **Architecture**: Unified connection model, Action pattern throughout, Virtual thread compatibility

The next release (`0.4.0-beta`) is already underway, focusing on the full SPI ecosystem and expanded observability. Check the [ROADMAP](../ROADMAP.md) for details.
