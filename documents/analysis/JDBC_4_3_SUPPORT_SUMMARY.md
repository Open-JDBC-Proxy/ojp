# JDBC 4.3 Support in OJP - Executive Summary

## Question

Should OJP now be documented and positioned as a JDBC 4.3 driver?

## Short Answer

Not yet.

OJP already runs on Java runtimes that include the JDBC 4.3 API, and a few JDBC 4.3 additions work through inherited JDK defaults. However, the current implementation still behaves like a JDBC 4.2-focused driver in the areas that matter most for a JDBC 4.3 claim: connection builders, XA connection builders, and sharding APIs are not implemented as first-class OJP features.

## Current State

- **Documented support level:** JDBC 4.2
- **Driver runtime:** Java 11+
- **Server runtime:** Java 25
- **Observed JDBC 4.3 behavior:**
  - `Connection.beginRequest()` and `Connection.endRequest()` work as inherited no-ops
  - `Connection.setShardingKey*()` is not supported and throws `SQLFeatureNotSupportedException`
  - `DataSource.createConnectionBuilder()` is not implemented by OJP and falls back to the JDK default unsupported behavior
  - `XADataSource.createXAConnectionBuilder()` is not implemented by OJP and falls back to the JDK default unsupported behavior

## Why This Is Not Yet JDBC 4.3

JDBC 4.3 is more than compiling against newer JDK interfaces. To credibly claim JDBC 4.3 support, OJP should provide a deliberate implementation story for the new builder-based connection APIs and decide how sharding concepts map to OJP's own multinode routing and datasource model.

Right now the codebase shows partial compatibility, not a complete JDBC 4.3 contract.

## Recommendation

Keep OJP documented as **JDBC 4.2-compliant** for now.

If JDBC 4.3 support becomes a goal, treat it as a small design effort rather than a wording change. The work should start with a documented scope decision:

1. **Minimum support path**
   - Keep `beginRequest()` / `endRequest()` as explicit no-ops
   - Leave sharding unsupported
   - Implement `createConnectionBuilder()` and `createXAConnectionBuilder()` in a way that is consistent with OJP URLs and credentials

2. **Fuller JDBC 4.3 path**
   - Add builder implementations
   - Define how sharding keys interact with OJP multinode routing
   - Add tests and compatibility documentation for all JDBC 4.3 entry points

## Conclusion

OJP is currently **JDBC 4.2 by contract, partially JDBC 4.3-aware by runtime environment, but not yet JDBC 4.3 by product claim**.
