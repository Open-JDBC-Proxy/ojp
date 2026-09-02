# JDBC 4.3 Support Analysis for OJP

## Overview

This document analyzes whether Open J Proxy (OJP) should currently be described as supporting JDBC 4.3, and what work would be required to make that claim defensible.

## Question

Should OJP move from its current JDBC 4.2 positioning to JDBC 4.3?

## Executive Answer

Not yet.

The codebase can compile and run against modern JDKs, and some JDBC 4.3 methods are present through inherited interface defaults. But OJP does not currently implement the newer builder-based APIs as OJP features, and it does not provide a defined sharding model for JDBC 4.3 sharding APIs. The current state is best described as:

- **JDBC 4.2 by documented contract**
- **Partial JDBC 4.3 compatibility via JDK default methods**
- **No strong basis yet for a full JDBC 4.3 product claim**

## Evidence from the Current Repository

### 1. The repository consistently documents JDBC 4.2, not JDBC 4.3

Current documentation repeatedly positions OJP as a JDBC 4.2 driver:

- `README.md` describes OJP as offering "standard JDBC 4.2"
- `documents/ebook/appendix-e-jdbc-compatibility.md` says OJP implements a subset of JDBC 4.2 interfaces
- `documents/ebook/part1-chapter2-architecture.md` describes the JDBC driver as a JDBC 4.2 implementation

This matters because product claims should match both code behavior and the documented support contract.

### 2. OJP is built on Java versions that expose JDBC 4.3 APIs

The runtime story is newer than the JDBC contract:

- The root build targets **Java 11** (`pom.xml`)
- The server module targets **Java 25** (`ojp-server/pom.xml`)

That means OJP is compiled in an environment where JDBC 4.3 interfaces and default methods exist. This explains why some JDBC 4.3 methods are available even when OJP does not explicitly implement them.

### 3. Some JDBC 4.3 behavior is inherited, not intentionally implemented

The JDK itself provides default implementations for several JDBC 4.3 methods:

- `Connection.beginRequest()` → default no-op
- `Connection.endRequest()` → default no-op
- `Connection.setShardingKey*()` → default `SQLFeatureNotSupportedException`
- `DataSource.createConnectionBuilder()` → default `SQLFeatureNotSupportedException`
- `XADataSource.createXAConnectionBuilder()` → default `SQLFeatureNotSupportedException`

OJP currently benefits from some of these inherited defaults rather than exposing its own end-to-end implementation for the feature area.

### 4. Existing tests already show partial JDBC 4.3 behavior

`ojp-jdbc-driver/src/test/java/openjproxy/jdbc/H2ConnectionExtensiveTests.java` and `ojp-jdbc-driver/src/test/java/openjproxy/jdbc/PostgresConnectionExtensiveTests.java` verify that:

- `beginRequest()` and `endRequest()` do not throw
- `setShardingKeyIfValid(...)` throws `SQLFeatureNotSupportedException`
- `setShardingKey(...)` throws `SQLFeatureNotSupportedException`

This is useful evidence because it shows the current behavior is known and stable enough to test. It also shows that the present behavior is explicitly **not** full sharding support.

### 5. The driver-side datasource classes do not implement JDBC 4.3 builders

`org.openjproxy.jdbc.OjpDataSource` implements `javax.sql.DataSource`, but it does not override `createConnectionBuilder()`.

`org.openjproxy.jdbc.xa.OjpXADataSource` implements `javax.sql.XADataSource`, but it does not override `createXAConnectionBuilder()`.

In practice, this means OJP currently falls back to the JDK defaults for those builder entry points instead of providing OJP-specific builder behavior.

That is an important gap because builder-based connection creation is one of the clearest JDBC 4.3-facing APIs for applications and frameworks.

### 6. The server recognizes newer JDBC interface types, but that is not the same as feature support

`ojp-server/src/main/java/org/openjproxy/grpc/server/JavaSqlInterfacesConverter.java` contains mappings for:

- `java.sql.ConnectionBuilder`
- `java.sql.ShardingKey`
- `java.sql.ShardingKeyBuilder`

This is a sign that the codebase is at least aware of newer JDBC types. But recognition in a converter is not enough to claim support. There is no evidence in the driver code of a complete builder flow or sharding-key flow that starts in public OJP APIs and is meaningfully handled end-to-end.

## What JDBC 4.3 Support Would Mean for OJP

For OJP, supporting JDBC 4.3 is not mainly about syntax or compilation. It is about defining behavior that fits OJP's architecture.

### Area A: Connection builders

JDBC 4.3 introduced builder-based connection creation on datasource types. For OJP, that raises practical design questions:

- How should builder credentials interact with OJP URL parsing?
- Should builder options be translated into connection properties before calling `DriverManager` or before creating an XA connection?
- Should builder-created connections preserve current multinode and datasource-name behavior?
- Do builders need parity across `DataSource` and `XADataSource`?

Without answering those questions, implementing the builder methods risks producing behavior that is technically present but operationally confusing.

### Area B: Sharding APIs

JDBC 4.3 also added `ShardingKey` APIs. This is the most ambiguous area for OJP.

OJP already has its own multinode concepts:

- endpoint lists in the JDBC URL
- load-aware routing
- session stickiness
- failover behavior

That creates a design conflict:

- JDBC sharding keys suggest application-directed shard selection
- OJP multinode behavior is currently framed as proxy-controlled routing and failover

Before OJP can claim JDBC 4.3 support in a strong sense, it should decide whether sharding keys are:

1. unsupported by design,
2. accepted but ignored,
3. mapped to routing hints, or
4. translated into datasource or endpoint selection rules.

Right now, the tests and inherited defaults show that the answer is effectively "unsupported."

### Area C: Request boundaries

`beginRequest()` and `endRequest()` are the least risky JDBC 4.3 additions because the JDK defaults are safe no-ops.

For OJP, that means there are two viable options:

- leave them as explicit no-ops and document that choice, or
- later map them to telemetry or request-scoped optimizations if there is a real use case

These methods do not block a future JDBC 4.3 effort, but they also do not by themselves justify a JDBC 4.3 label.

## Risks of Claiming JDBC 4.3 Too Early

### 1. Documentation drift

If OJP starts claiming JDBC 4.3 now, the public documentation would overstate real capability in builders and sharding.

### 2. Framework expectations

Some frameworks or advanced users may interpret a JDBC 4.3 claim to mean that datasource builders and sharding features are intentionally supported, not merely inherited from interface defaults.

### 3. Architectural ambiguity

Sharding is especially sensitive in OJP because the product already has a strong opinionated routing model. A premature JDBC 4.3 claim could create incompatible user expectations about routing control.

## Recommended Position

### Short-term recommendation

Keep the official claim at **JDBC 4.2-compliant**.

Also document the nuance clearly:

- OJP runs on modern JDKs
- some JDBC 4.3 methods are available through inherited defaults
- OJP does not yet provide full, intentional JDBC 4.3 feature coverage

### Medium-term recommendation

If the project wants to advance toward JDBC 4.3, use a staged approach.

#### Stage 1: Builder support only

Implement:

- `OjpDataSource.createConnectionBuilder()`
- `OjpXADataSource.createXAConnectionBuilder()`

Goals:

- make builder behavior explicit and testable
- preserve existing URL, property, and credential semantics
- avoid changing routing behavior

This would provide practical JDBC 4.3 value without forcing a sharding design immediately.

#### Stage 2: Explicit request-boundary documentation

Decide whether to:

- keep `beginRequest()` / `endRequest()` as documented no-ops, or
- wire them into telemetry/context propagation

This is optional and low urgency.

#### Stage 3: Sharding decision

Make an explicit product decision:

- **Option A:** permanently unsupported in OJP
- **Option B:** supported as routing hints
- **Option C:** supported only for specific deployment modes

This should be a design decision first, not a code-first change.

## Suggested Acceptance Criteria for a Future JDBC 4.3 Claim

OJP should only move its public wording from JDBC 4.2 to JDBC 4.3 when all of the following are true:

1. `DataSource` and `XADataSource` builder APIs are explicitly implemented by OJP
2. Their behavior is covered by automated tests
3. The documentation explains builder semantics in OJP terms
4. The project has a deliberate and documented position on sharding APIs
5. The compatibility appendix is updated to describe JDBC 4.3 support boundaries

## Final Conclusion

OJP is not blocked from JDBC 4.3 by Java runtime level. The real gap is product semantics.

Today, the repository shows:

- modern Java baselines,
- partial compatibility through inherited JDBC 4.3 defaults,
- explicit non-support for sharding methods,
- no OJP-specific implementation of datasource builders,
- and repository-wide documentation that still targets JDBC 4.2.

So the honest current conclusion is:

**OJP should continue to present itself as JDBC 4.2-compliant until JDBC 4.3 builder support and a sharding position are implemented deliberately.**
