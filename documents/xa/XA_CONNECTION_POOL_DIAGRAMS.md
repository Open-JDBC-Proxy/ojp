# XA Connection Pool Architecture Diagrams

This document provides visual representations of the proposed XA connection pool architecture.

---

## Current Architecture (No XA Pooling)

```
┌─────────────────────────────────────────────────────────────────────┐
│                          OJP Server                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Non-XA Connections:                                                │
│  ┌──────────────────────────────────────────────────┐              │
│  │ ConnectionPoolProviderRegistry                   │              │
│  │   ↓ ServiceLoader                                │              │
│  │ ┌──────────────┐  ┌──────────────┐              │              │
│  │ │   HikariCP   │  │  DBCP2       │              │              │
│  │ │  (priority   │  │  (priority   │              │              │
│  │ │   100)       │  │   10)        │              │              │
│  │ └──────────────┘  └──────────────┘              │              │
│  │         ↓                ↓                       │              │
│  │    HikariDataSource  BasicDataSource            │              │
│  │         ↓                ↓                       │              │
│  │    [Connection Pool]  [Connection Pool]         │              │
│  └──────────────────────────────────────────────────┘              │
│                                                                     │
│  XA Connections:                                                    │
│  ┌──────────────────────────────────────────────────┐              │
│  │ ❌ NO POOLING - Direct creation                  │              │
│  │                                                   │              │
│  │  xaDataSourceMap.get(connHash)                   │              │
│  │         ↓                                         │              │
│  │  XADataSourceFactory.createXADataSource()        │              │
│  │         ↓                                         │              │
│  │  PGXADataSource / MysqlXADataSource (native)     │              │
│  │         ↓                                         │              │
│  │  xaDataSource.getXAConnection()  ⚠️ ON DEMAND    │              │
│  └──────────────────────────────────────────────────┘              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Problems:**
- ❌ XA connections created on-demand (slow)
- ❌ No connection reuse
- ❌ Not production-ready

---

## Proposed Architecture (With XA Pooling)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          OJP Server                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Non-XA Connections:                                                        │
│  ┌────────────────────────────────────────────────────────┐                │
│  │ ConnectionPoolProviderRegistry                         │                │
│  │   ↓ ServiceLoader                                      │                │
│  │ ┌──────────────┐  ┌──────────────┐                    │                │
│  │ │   HikariCP   │  │  DBCP2       │                    │                │
│  │ │  (priority   │  │  (priority   │                    │                │
│  │ │   100)       │  │   10)        │                    │                │
│  │ └──────────────┘  └──────────────┘                    │                │
│  │         ↓                ↓                             │                │
│  │    HikariDataSource  BasicDataSource                  │                │
│  │         ↓                ↓                             │                │
│  │    [Connection Pool]  [Connection Pool]               │                │
│  └────────────────────────────────────────────────────────┘                │
│                                                                             │
│  XA Connections:                                                            │
│  ┌────────────────────────────────────────────────────────────┐            │
│  │ ✅ XAConnectionPoolProviderRegistry (NEW)                  │            │
│  │   ↓ ServiceLoader                                          │            │
│  │ ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │            │
│  │ │  Atomikos    │  │  Narayana    │  │   Custom     │     │            │
│  │ │  (priority   │  │  (priority   │  │  (priority   │     │            │
│  │ │   90)        │  │   80)        │  │   0)         │     │            │
│  │ └──────────────┘  └──────────────┘  └──────────────┘     │            │
│  │         ↓                ↓                  ↓              │            │
│  │  AtomikosDataSourceBean  NarayanaDS    CustomXADS        │            │
│  │         ↓                ↓                  ↓              │            │
│  │    [XAConnection Pool] [XAConnection Pool] [XA Pool]     │            │
│  │         ↓                ↓                  ↓              │            │
│  │    Wraps PGXADataSource, MysqlXADataSource, etc.         │            │
│  └────────────────────────────────────────────────────────────┘            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Benefits:**
- ✅ XA connections pooled and reused
- ✅ Pluggable providers via SPI
- ✅ Production-ready performance

---

## XAConnectionPoolProvider SPI

```
┌───────────────────────────────────────────────────────────┐
│              XAConnectionPoolProvider (SPI)               │
├───────────────────────────────────────────────────────────┤
│  + String id()                                            │
│  + XADataSource createXADataSource(PoolConfig)            │
│  + void closeXADataSource(XADataSource)                   │
│  + Map<String, Object> getStatistics(XADataSource)        │
│  + int getPriority()                                      │
│  + boolean isAvailable()                                  │
│  + boolean supportsDynamicResizing()  ← NEW               │
│  + void resizeXADataSource(XADataSource, max, min) ← NEW  │
└───────────────────────────────────────────────────────────┘
                           ▲
                           │ implements
                ┌──────────┴──────────┐
                │                     │
     ┌──────────┴──────────┐  ┌──────┴──────────────┐
     │ AtomikosXAProvider  │  │ NarayanaXAProvider  │
     ├─────────────────────┤  ├─────────────────────┤
     │ id: "atomikos"      │  │ id: "narayana"      │
     │ priority: 90        │  │ priority: 80        │
     │ resizing: false ⚠️  │  │ resizing: true ✅    │
     │                     │  │                     │
     │ Uses drain-replace  │  │ Direct resizing     │
     └─────────────────────┘  └─────────────────────┘
```

---

## Atomikos Drain-and-Replace Flow

```
Step 1: Cluster Health Change Detected
┌─────────────────────────────────┐
│ Cluster: 3 servers → 4 servers  │
│ Need to resize pool:            │
│   maxPoolSize: 30 → 24          │
│   minIdle: 10 → 8               │
└─────────────────────────────────┘
              ↓
Step 2: Create New Pool
┌─────────────────────────────────┐
│ AtomikosDataSourceBean (NEW)    │
│   maxPoolSize: 24               │
│   minIdle: 8                    │
│   uniqueResourceName: "v2-..."  │
│   init() called                 │
└─────────────────────────────────┘
              ↓
Step 3: Atomic Swap
┌─────────────────────────────────┐
│ xaDataSourceMap.put(hash, NEW)  │
│                                 │
│ OLD pool → marked "draining"    │
│ NEW pool → accepting requests   │
└─────────────────────────────────┘
              ↓
Step 4: Parallel Operations
┌─────────────────────┐  ┌─────────────────────┐
│   OLD Pool          │  │   NEW Pool          │
│ ┌─────────────────┐ │  │ ┌─────────────────┐ │
│ │ Active TX (5)   │ │  │ │ New requests    │ │
│ │ Waiting to      │ │  │ │ routed here ✅   │ │
│ │ complete...     │ │  │ │                 │ │
│ └─────────────────┘ │  │ └─────────────────┘ │
│ No new connections  │  │ Pool growing to     │
│ Counting down...    │  │ minIdle=8           │
└─────────────────────┘  └─────────────────────┘
              │                     
              ↓ (5-10 minutes)      
Step 5: Old Pool Drained
┌─────────────────────────────────┐
│ OLD pool: 0 active connections  │
│ oldPool.close()                 │
│ Resources released              │
└─────────────────────────────────┘
              ↓
Step 6: Complete
┌─────────────────────────────────┐
│ Only NEW pool exists            │
│ Memory reclaimed                │
│ Resize complete ✅               │
└─────────────────────────────────┘
```

**Timeline:** 5-10 minutes (transaction dependent)  
**Memory:** 2x during drain (temporary)  
**Risk:** Low with timeouts and monitoring

---

## Narayana Direct Resizing Flow

```
Step 1: Cluster Health Change Detected
┌─────────────────────────────────┐
│ Cluster: 3 servers → 4 servers  │
│ Need to resize pool:            │
│   maxPoolSize: 30 → 24          │
│   minIdle: 10 → 8               │
└─────────────────────────────────┘
              ↓
Step 2: Direct Resize (No Drain!)
┌─────────────────────────────────┐
│ narayanaDS.setMaxPoolSize(24)   │
│ narayanaDS.setMinPoolSize(8)    │
│                                 │
│ Pool adjusts immediately        │
│ Existing connections preserved  │
│ No second pool needed ✅         │
└─────────────────────────────────┘
              ↓
Step 3: Pool Adjusts
┌─────────────────────────────────┐
│ If decreasing:                  │
│   - Stop creating new           │
│   - Let excess idle close       │
│                                 │
│ If increasing:                  │
│   - Create up to new max        │
│   - Maintain new min idle       │
└─────────────────────────────────┘
              ↓
Step 4: Complete
┌─────────────────────────────────┐
│ Resize complete in seconds ✅    │
│ No memory overhead              │
│ No drain complexity             │
└─────────────────────────────────┘
```

**Timeline:** Seconds  
**Memory:** No overhead  
**Risk:** Very low

---

## Multinode Pool Coordination

```
                     ┌─────────────────────┐
                     │  OJP Cluster        │
                     │  ┌───┬───┬───┬───┐  │
                     │  │S1 │S2 │S3 │S4 │  │ (4 servers)
                     │  └───┴───┴───┴───┘  │
                     └─────────────────────┘
                              ↓
                     ┌─────────────────────┐
                     │ Client sends        │
                     │ clusterHealth to S1 │
                     │ "S1:H,S2:H,S3:H,S4:H"│
                     └─────────────────────┘
                              ↓
         ┌────────────────────────────────────────────┐
         │ Server 1 (S1) Pool Calculation             │
         │                                            │
         │ Original config:                           │
         │   maxPoolSize=80, minIdle=20               │
         │                                            │
         │ Healthy servers: 4                         │
         │                                            │
         │ Divided allocation:                        │
         │   maxPoolSize = 80/4 = 20                  │
         │   minIdle = 20/4 = 5                       │
         └────────────────────────────────────────────┘
                              ↓
                     ┌─────────────────────┐
                     │ If Atomikos:        │
                     │   Drain-replace     │
                     │   old (80) → new(20)│
                     │                     │
                     │ If Narayana:        │
                     │   Direct resize     │
                     │   setMax(20)        │
                     └─────────────────────┘
                              ↓
                     ┌─────────────────────┐
                     │ All 4 servers       │
                     │ each have pool:     │
                     │   max=20, min=5     │
                     │                     │
                     │ Total capacity:     │
                     │   max=80, min=20    │
                     │   (preserved!) ✅    │
                     └─────────────────────┘
```

**Benefit:** Dynamic load distribution across healthy servers  
**Challenge:** Atomikos requires drain-replace, Narayana doesn't

---

## Configuration Flow

```
┌─────────────────────────────────────────────────────────┐
│                   ojp.properties                        │
├─────────────────────────────────────────────────────────┤
│ # XA Pool Provider                                      │
│ ojp.xa.pool.provider=atomikos                           │
│                                                         │
│ # Pool Sizing (per datasource)                         │
│ primary.ojp.xa.pool.maximumPoolSize=30                  │
│ primary.ojp.xa.pool.minimumIdle=10                      │
│ primary.ojp.xa.pool.connectionTimeout=30000             │
│                                                         │
│ # Atomikos Resizing                                     │
│ ojp.xa.atomikos.resizing.enabled=true                   │
│ ojp.xa.atomikos.resizing.minIntervalMs=300000           │
│ ojp.xa.atomikos.drain.timeoutMs=600000                  │
│ ojp.xa.atomikos.drain.maxConcurrent=2                   │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│            DataSourceConfigurationManager                │
│  extractConfiguration(properties, "primary")             │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                     PoolConfig                          │
├─────────────────────────────────────────────────────────┤
│  url: "jdbc:postgresql://..."                           │
│  maxPoolSize: 30                                        │
│  minIdle: 10                                            │
│  connectionTimeoutMs: 30000                             │
│  ...                                                    │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│        XAConnectionPoolProviderRegistry                  │
│  getProvider("atomikos")                                 │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│           AtomikosXAConnectionPoolProvider               │
│  createXADataSource(poolConfig)                          │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│              AtomikosDataSourceBean                      │
├─────────────────────────────────────────────────────────┤
│  xaDataSource: PGXADataSource (native)                   │
│  maxPoolSize: 30                                        │
│  minPoolSize: 10                                        │
│  borrowConnectionTimeout: 30 (seconds)                   │
│  uniqueResourceName: "ojp-xa-primary-uuid"               │
└─────────────────────────────────────────────────────────┘
```

---

## Component Dependencies

```
┌─────────────────────────────────────────────────────────┐
│                    ojp-server                           │
│  (Main application)                                     │
│                                                         │
│  Depends on:                                            │
│  ├─ ojp-datasource-api (PoolConfig, Registry)          │
│  ├─ ojp-datasource-hikari (non-XA default)             │
│  └─ [Optional XA providers loaded at runtime]          │
└─────────────────────────────────────────────────────────┘
                           │
                           │ ServiceLoader discovers
                           ↓
┌─────────────────────────────────────────────────────────┐
│               ojp-datasource-atomikos                   │
│  (Optional module for XA pooling)                       │
│                                                         │
│  Provides:                                              │
│  └─ AtomikosXAConnectionPoolProvider                    │
│                                                         │
│  Depends on:                                            │
│  ├─ ojp-datasource-api                                  │
│  ├─ com.atomikos:transactions-jta                       │
│  └─ com.atomikos:transactions-jdbc                      │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│               ojp-datasource-narayana                   │
│  (Optional module for XA pooling)                       │
│                                                         │
│  Provides:                                              │
│  └─ NarayanaXAConnectionPoolProvider                    │
│                                                         │
│  Depends on:                                            │
│  ├─ ojp-datasource-api                                  │
│  └─ org.jboss.narayana.jta:narayana-jta                 │
└─────────────────────────────────────────────────────────┘
```

**Deployment Options:**
1. No XA: Deploy ojp-server only (current)
2. XA with Atomikos: Deploy ojp-server + ojp-datasource-atomikos
3. XA with Narayana: Deploy ojp-server + ojp-datasource-narayana
4. Both TMs: Deploy all three (choose via config)

---

## Summary Comparison

| Aspect | Current | With Atomikos | With Narayana |
|--------|---------|---------------|---------------|
| **XA Pooling** | ❌ No | ✅ Yes | ✅ Yes |
| **Performance** | ⚠️ Poor | ✅ Good | ✅ Good |
| **Resizing** | N/A | ⚠️ Drain-replace | ✅ Direct |
| **Memory** | Low | Medium | Low |
| **Complexity** | Low | Medium | High |
| **Setup** | Easy | Medium | Hard |
| **Production-Ready** | ❌ No | ✅ Yes | ✅ Yes |

**Recommendation:** Implement both, start with Atomikos (easier setup, proven), offer Narayana as alternative (better resizing).

---

For detailed analysis, see:
- [XA_CONNECTION_POOL_ANALYSIS.md](XA_CONNECTION_POOL_ANALYSIS.md) - Full technical details
- [XA_CONNECTION_POOL_SUMMARY.md](XA_CONNECTION_POOL_SUMMARY.md) - Executive summary  
- [XA_CONNECTION_POOL_DECISION_GUIDE.md](XA_CONNECTION_POOL_DECISION_GUIDE.md) - Decision guide
