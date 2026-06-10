# OJP Prepared Statement Cache Settings - Summary

**Full Analysis:** [PREPARED_STATEMENT_CACHE_SETTINGS_ANALYSIS.md](PREPARED_STATEMENT_CACHE_SETTINGS_ANALYSIS.md)

---

## Quick Answer

Yes, OJP should expose prepared statement cache settings in **OJP standard properties notation**, keep them **enabled by default**, and translate them to driver-specific datasource properties at runtime on the server side.

---

## Proposed Standard Properties (Server-Side Canonical, Global Only)

### Non-XA

```properties
ojp.connection.pool.statementCache.enabled=true
ojp.connection.pool.statementCache.maxSize=250
ojp.connection.pool.statementCache.sqlLimit=2048
ojp.connection.pool.statementCache.serverPrepare=true
ojp.connection.pool.statementCache.prepareThreshold=5
```

### XA

```properties
ojp.xa.connection.pool.statementCache.enabled=true
ojp.xa.connection.pool.statementCache.maxSize=250
ojp.xa.connection.pool.statementCache.sqlLimit=2048
ojp.xa.connection.pool.statementCache.serverPrepare=true
ojp.xa.connection.pool.statementCache.prepareThreshold=5
```

## Runtime Translation Model

At datasource creation time, OJP resolves canonical statement cache settings and translates them to database-driver properties:

- **MySQL**: `cachePrepStmts`, `prepStmtCacheSize`, `prepStmtCacheSqlLimit`, `useServerPrepStmts`
- **PostgreSQL/Cockroach**: `prepareThreshold` (and optional driver cache keys when explicitly enabled)
- **SQL Server**: `disableStatementPooling=false`, `statementPoolingCacheSize`
- **Oracle**: `oracle.jdbc.implicitStatementCacheSize`
- **DB2**: `maxStatements`
- **MariaDB/H2**: map only safe/supported keys; otherwise no-op

If unsupported for a given driver/version, OJP should skip the mapping and log a clear message (no bootstrap failure).

---

## Precedence and Safety

Property precedence (highest to lowest):
1. global key (`ojp...`)
2. OJP default

Safety rules:
- `enabled=false` means no statement-cache driver properties are emitted.
- Invalid numeric values fall back to defaults with warning.
- Effective resolved values are logged once per pool creation.

---

## Meaning, Units, and Scope

These settings are **server-global defaults**. They are not configured per datasource in this design.

- `statementCache.enabled` (`true/false`): master toggle for statement cache translation.
- `statementCache.maxSize` (count): maximum cached prepared statements. **Unit: number of statements, not bytes**.
- `statementCache.sqlLimit` (bytes/characters depending on driver semantics): max SQL length eligible for cache.
- `statementCache.serverPrepare` (`true/false`): requests server-side prepare where supported by the driver.
- `statementCache.prepareThreshold` (count): executions before server-side prepare is used (driver-dependent).

**Application scope:** The canonical keys are global in OJP server config, then applied when each pool/datasource is created.
So limits like `maxSize=250` are interpreted by the target driver/pool as cache-entry counts (for example, typically per physical JDBC connection in drivers like MySQL), not a memory size.

---

## Why This Fits OJP

- It keeps configuration database-agnostic for users.
- It centralizes portability logic in OJP server.
- It aligns with existing OJP property-loading patterns.
- It is compatible with existing pool provider pass-through of datasource properties.

---

## Status

- **Design only** (this document).
- **No code changes proposed in this report.**
