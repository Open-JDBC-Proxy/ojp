# OJP Prepared Statement Cache Settings - Detailed Design Analysis

**Date:** May 10, 2026  
**Status:** 📋 **Design Proposal**  
**Scope:** OJP server property model for prepared statement caching, default-enabled behavior, and runtime translation to datasource-specific settings

---

## 1. Problem Statement

We need a server-side design that:

1. Defines prepared statement cache settings in **OJP standard properties notation**
2. Enables statement caching **by default**
3. Loads those settings through the existing OJP property resolution model
4. Translates canonical OJP settings to database/driver-specific datasource properties at runtime
5. Keeps this as design/report only (no implementation in this task)

---

## 2. Current-State Observations

From the current architecture:

- OJP already uses standardized keys for pool configuration (`ojp.connection.pool.*`, `ojp.xa.connection.pool.*`).
- OJP already resolves properties with datasource scoping and precedence across file/system/env/info.
- `PoolConfig` supports generic key-value datasource properties (`properties` map), which is the correct extension point for driver-specific statement-cache options.
- In the current connect path, `PoolConfig` is created primarily with canonical pool fields; additional datasource properties need explicit propagation path from resolved OJP properties.

This means the feature is architecturally compatible with existing design patterns.

---

## 3. Design Goals

### 3.1 Functional Goals

- Provide one canonical OJP configuration surface for statement caching.
- Default behavior should improve performance without requiring app-side driver flags.
- Work across all OJP-supported databases with graceful degradation.

### 3.2 Non-Functional Goals

- Keep user configuration portable (database-agnostic by default).
- Preserve backwards compatibility.
- Avoid breaking connection bootstrap on unsupported driver keys.
- Make effective behavior observable in logs/diagnostics.

---

## 4. Proposed Canonical Property Surface (Global Server Settings Only)

### 4.1 Non-XA Global Canonical Keys

```properties
ojp.connection.pool.statementCache.enabled=true
ojp.connection.pool.statementCache.maxSize=250
ojp.connection.pool.statementCache.sqlLimit=2048
ojp.connection.pool.statementCache.serverPrepare=true
ojp.connection.pool.statementCache.prepareThreshold=5
```

### 4.2 XA Global Canonical Keys

```properties
ojp.xa.connection.pool.statementCache.enabled=true
ojp.xa.connection.pool.statementCache.maxSize=250
ojp.xa.connection.pool.statementCache.sqlLimit=2048
ojp.xa.connection.pool.statementCache.serverPrepare=true
ojp.xa.connection.pool.statementCache.prepareThreshold=5
```

No datasource-scoped or per-database override keys are included in this design revision.
Only the global server keys in 4.1 and 4.2 are allowed.

---

## 5. Runtime Translation Contract (Canonical -> Driver Properties)

At datasource creation, OJP should:

1. Resolve effective canonical statement-cache settings.
2. Detect target database type from parsed JDBC URL.
3. Translate canonical fields into driver-specific datasource properties.
4. Attach translated key-value pairs to `PoolConfig.properties`.
5. Let the active pool provider pass those to the underlying datasource.

### 5.1 Proposed Translation Matrix

| Database | Canonical Input | Driver Property Output |
|---|---|---|
| MySQL | enabled/maxSize/sqlLimit/serverPrepare | `cachePrepStmts`, `prepStmtCacheSize`, `prepStmtCacheSqlLimit`, `useServerPrepStmts` |
| MariaDB | enabled/serverPrepare (+ supported cache keys) | `useServerPrepStmts` and supported connector cache keys |
| PostgreSQL | enabled/prepareThreshold | `prepareThreshold` |
| Cockroach (pgjdbc) | enabled/prepareThreshold | `prepareThreshold` |
| SQL Server | enabled/maxSize | `disableStatementPooling=false`, `statementPoolingCacheSize` |
| Oracle | enabled/maxSize | `oracle.jdbc.implicitStatementCacheSize` |
| DB2 | enabled/maxSize | `maxStatements` |
| H2 | enabled | no-op or minimal safe mapping |

Notes:

- If `enabled=false`, no statement-cache keys are emitted for the datasource.
- If a property is not supported by the current driver/version, OJP should skip and log.

---

## 6. Meaning, Units, and Scope

This section clarifies what each setting means and where it applies.

### 6.1 Setting Semantics

- `statementCache.enabled`  
  **Type:** boolean  
  **Meaning:** master on/off flag for statement cache property translation.

- `statementCache.maxSize`  
  **Type:** integer (count)  
  **Meaning:** maximum number of cached prepared statements.  
  **Unit:** statement entries (not KB/MB).

- `statementCache.sqlLimit`  
  **Type:** integer  
  **Meaning:** maximum SQL statement length considered for caching.  
  **Unit:** length limit passed to driver (commonly bytes/characters per driver semantics).

- `statementCache.serverPrepare`  
  **Type:** boolean  
  **Meaning:** requests server-side prepared statements where the driver supports it.

- `statementCache.prepareThreshold`  
  **Type:** integer (count)  
  **Meaning:** execution-count threshold before server-side prepare is used (driver-dependent behavior).

### 6.2 Scope of Application

- Canonical keys are configured once at OJP server level (global scope).
- At runtime, they are translated and applied when each datasource/pool is instantiated.
- Practical cache behavior is determined by the target JDBC driver/pool implementation. For common drivers, cache size values like `250` are interpreted as **number of statement entries, typically per physical JDBC connection in the pool** (for example, in MySQL Connector/J this aligns with per-connection prepared statement cache behavior), not as memory size.

---

## 7. Resolution and Precedence Rules

### 7.1 Precedence (High -> Low)

1. global generic (`ojp.connection.pool.statementCache.*` / `ojp.xa.connection.pool.statementCache.*`)
2. hardcoded defaults

### 7.2 Validation Rules

- `maxSize >= 0`
- `sqlLimit >= 0`
- `prepareThreshold >= 0`
- Boolean keys parse strictly to `true/false`

If invalid values are supplied:
- emit warning
- fall back to default for that field
- continue startup/connect (no hard fail)

---

## 8. Observability and Operational Behavior

Recommended runtime visibility:

- Log one “effective statement cache config” line per datasource during pool creation.
- Log translation output at debug level (canonical -> driver keys).
- Include resolved canonical values and translated keys in diagnostics endpoint output (future enhancement if endpoint already exists).

This helps users confirm that OJP, not app-side JDBC URL properties, is controlling statement-cache behavior.

---

## 9. Backward Compatibility

- Existing deployments without any statement cache keys should continue to work.
- New defaults turn caching on unless explicitly disabled.
- If a deployment relies on disabling cache behavior in a specific driver, user can set `...statementCache.enabled=false`.
- No protocol changes required for this design; this is server-side datasource configuration behavior.

---

## 10. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Driver property mismatch across versions | Wrong behavior or ignored settings | Keep translation map explicit by driver family/version docs; log applied keys |
| Over-aggressive defaults in some workloads | Memory/CPU impact | Expose `maxSize`, `sqlLimit`, threshold knobs globally and tune conservatively |
| Confusion with app-side JDBC URL properties | Operational ambiguity | Document precedence and recommend OJP canonical keys as source of truth |
| Unsupported key hard failure | Availability risk | Never fail bootstrap for unsupported key; skip with warning |

---

## 11. Implementation Outline (For Future Coding Task)

This section describes what to implement later (not in this task):

1. Add canonical property constants for statement-cache keys (non-XA/XA).
2. Extend datasource configuration parser to resolve these keys with existing precedence model.
3. Add a translation component that maps canonical keys to driver-specific properties by database type.
4. Populate `PoolConfig.properties(...)` with translated key-value pairs in non-XA and XA datasource creation paths.
5. Add logging/diagnostics output for effective config.
6. Add tests:
   - unit tests for precedence/validation/translation
   - integration tests per major database driver where feasible

---

## 12. Recommended Default Profile

Use this as baseline:

```properties
ojp.connection.pool.statementCache.enabled=true
ojp.connection.pool.statementCache.maxSize=250
ojp.connection.pool.statementCache.sqlLimit=2048
ojp.connection.pool.statementCache.serverPrepare=true
ojp.connection.pool.statementCache.prepareThreshold=5
```

XA baseline mirrors non-XA keys and values.

---

## 13. Final Recommendation

Adopt a **canonical OJP statement-cache property layer** that is:

- **default-enabled**
- **database-portable at config level**
- **translated server-side to concrete driver properties at runtime**

This gives users one stable OJP configuration model while preserving database-specific optimization potential through controlled translation.

---

## 14. Confidence

**Confidence: High (architecture fit), Medium (exact driver-key set by version).**

Why:
- High confidence on integration points and property model fit with existing OJP patterns.
- Medium confidence on exact property names/behavior across all driver versions, which requires verification against each driver’s supported properties matrix during implementation/testing.
