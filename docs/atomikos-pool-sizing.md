# Atomikos Pool Sizing Documentation

## Overview

This document describes how Atomikos XA connection pool sizes are dynamically calculated and managed in the OJP server. The implementation mirrors HikariCP's dynamic pool-sizing approach, distributing global pool capacity across healthy servers in a multinode cluster.

## Pool Size Calculation

### Formula

When creating or recreating Atomikos XA pools, per-server pool sizes are calculated using the following formulas:

```
perServerMin = max(1, ceil(configuredMin / numServersUp))
perServerMax = max(1, ceil(configuredMax / numServersUp))
```

Where:
- `configuredMin` = Global minimum pool size from client configuration (`ojp.connection.pool.minimumIdle`)
- `configuredMax` = Global maximum pool size from client configuration (`ojp.connection.pool.maximumPoolSize`)
- `numServersUp` = Number of currently healthy/UP servers in the cluster
- `ceil()` = Ceiling function (rounds up to nearest integer)
- `max(1, ...)` = Ensures at least 1 connection per server

### Implementation Location

The size calculation logic is implemented in:

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/datasource/DynamicAtomikosPoolManager.java`

**Method:** `calculatePerServerSizes(int configuredMax, int configuredMin, int numServersUp)`

**Lines:** ~135-151

```java
public PerServerSizes calculatePerServerSizes(int configuredMax, int configuredMin, int numServersUp) {
    if (numServersUp <= 0) {
        numServersUp = 1; // Fallback for safety
    }
    
    int perServerMax = Math.max(1, (int) Math.ceil((double) configuredMax / numServersUp));
    int perServerMin = Math.max(1, (int) Math.ceil((double) configuredMin / numServersUp));
    
    log.debug("Calculated per-server sizes: configuredMax={}, configuredMin={}, servers={} -> perServerMax={}, perServerMin={}", 
            configuredMax, configuredMin, numServersUp, perServerMax, perServerMin);
    
    return new PerServerSizes(perServerMin, perServerMax);
}
```

## Example Calculations

### Example 1: Four Servers (Even Division)

**Configuration:**
- `ojp.connection.pool.maximumPoolSize` = 32
- `ojp.connection.pool.minimumIdle` = 8
- Number of UP servers = 4

**Calculation:**
```
perServerMax = max(1, ceil(32 / 4)) = max(1, ceil(8.0)) = max(1, 8) = 8
perServerMin = max(1, ceil(8 / 4)) = max(1, ceil(2.0)) = max(1, 2) = 2
```

**Result:** Each server gets a pool with `maxPoolSize=8` and `minPoolSize=2`

### Example 2: Three Servers (Requires Rounding)

**Configuration:**
- `ojp.connection.pool.maximumPoolSize` = 32
- `ojp.connection.pool.minimumIdle` = 8
- Number of UP servers = 3

**Calculation:**
```
perServerMax = max(1, ceil(32 / 3)) = max(1, ceil(10.67)) = max(1, 11) = 11
perServerMin = max(1, ceil(8 / 3)) = max(1, ceil(2.67)) = max(1, 3) = 3
```

**Result:** Each server gets a pool with `maxPoolSize=11` and `minPoolSize=3`

**Note:** Total capacity across 3 servers = 33 (slightly over configured 32 due to rounding up)

### Example 3: Single Server

**Configuration:**
- `ojp.connection.pool.maximumPoolSize` = 32
- `ojp.connection.pool.minimumIdle` = 8
- Number of UP servers = 1

**Calculation:**
```
perServerMax = max(1, ceil(32 / 1)) = max(1, 32) = 32
perServerMin = max(1, ceil(8 / 1)) = max(1, 8) = 8
```

**Result:** Single server gets full capacity with `maxPoolSize=32` and `minPoolSize=8`

### Example 4: Many Servers (Minimum Connection Guarantee)

**Configuration:**
- `ojp.connection.pool.maximumPoolSize` = 5
- `ojp.connection.pool.minimumIdle` = 2
- Number of UP servers = 10

**Calculation:**
```
perServerMax = max(1, ceil(5 / 10)) = max(1, ceil(0.5)) = max(1, 1) = 1
perServerMin = max(1, ceil(2 / 10)) = max(1, ceil(0.2)) = max(1, 1) = 1
```

**Result:** Each server gets minimum `maxPoolSize=1` and `minPoolSize=1` (ensures at least 1 connection per server)

## Pool Recreation Behavior

### When Pools Are Recreated

Atomikos XA pools are automatically recreated when server membership changes:

1. **Server Failure:** When a server becomes unhealthy/DOWN, remaining servers recreate their pools with increased capacity
2. **Server Recovery:** When a previously down server recovers, all servers recreate their pools to rebalance capacity

### Recreation Implementation Location

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/datasource/DynamicAtomikosPoolManager.java`

**Method:** `recreatePoolForNewMembership(String connHash, int newServerCount)`

**Lines:** ~170-208

### Recreation Process

When membership changes, the following steps occur:

1. **Trigger Detection:** Server membership change is detected (via health checks or cluster coordination)
2. **Size Recalculation:** New per-server sizes are calculated using updated `numServersUp`
3. **Graceful Closure:** Existing Atomikos pool is gracefully closed
   - Waits for leased connections to be returned
   - Force-closes any leaked connections with warnings
4. **New Pool Creation:** New `AtomikosDataSourceBean` is created with recalculated sizes
5. **Metadata Update:** Pool metadata is updated to track current server count

**Example Log Output:**
```
INFO  DynamicAtomikosPoolManager - Recreating Atomikos XA pool for conn-hash-123 due to membership change: 3 -> 2 servers
DEBUG DynamicAtomikosPoolManager - Closing old pool for conn-hash-123
INFO  AtomikosXAConnectionPool - Closing Atomikos XA pool 'ojp-xa-12345'...
INFO  AtomikosXAConnectionPool - Atomikos XA pool 'ojp-xa-12345' closed
INFO  AtomikosXAConnectionPool - Created Atomikos XA pool 'ojp-xa-67890': maxPoolSize=16, minPoolSize=4, ...
INFO  DynamicAtomikosPoolManager - Recreated Atomikos XA pool for conn-hash-123: new per-server max=16, min=4, ...
```

## Impact on Existing Transactions

### During Pool Recreation

- **In-flight transactions:** Existing XA connections that are currently leased to sessions remain valid
- **Connection return:** When transactions complete, connections are returned to the OLD pool
- **Old pool closure:** The old pool waits briefly for connection returns before force-closing
- **New connections:** New transaction requests after recreation get connections from the NEW pool

### Safety Guarantees

1. **No transaction loss:** Active XA branches continue using their existing connections
2. **Graceful transition:** New pools are created before old pools are closed
3. **Connection tracking:** `DynamicAtomikosPoolManager` tracks which connections belong to which pool
4. **Logging:** All recreation events are logged at INFO level for monitoring

### Potential Impacts

- **Brief connection latency:** During recreation, new connection requests may experience slight delays
- **Temporary capacity mismatch:** Between old pool closure and new pool initialization (typically < 1 second)
- **Connection churn:** Existing idle connections are closed; new connections are created

## Configuration Properties

Pool sizing is controlled by these client-side properties:

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.connection.pool.maximumPoolSize` | 20 | Global maximum connections across all servers |
| `ojp.connection.pool.minimumIdle` | 5 | Global minimum idle connections across all servers |
| `ojp.connection.pool.connectionTimeout` | 10000 ms | Timeout for acquiring connection from pool |
| `ojp.connection.pool.idleTimeout` | 600000 ms | Max idle time before connection is closed |
| `ojp.connection.pool.validationQuery` | "SELECT 1" | Query used to validate connections |

**Note:** These properties are specified **per datasource** using the named datasource pattern:
```properties
# Primary datasource
primary.ojp.connection.pool.maximumPoolSize=32
primary.ojp.connection.pool.minimumIdle=8

# Secondary datasource
secondary.ojp.connection.pool.maximumPoolSize=16
secondary.ojp.connection.pool.minimumIdle=4
```

## Logging

### INFO Level Logging

Logged when pools are created or recreated:

```
Creating Atomikos XA pool for {connHash}: servers={count}, configured max={max}, min={min}, per-server max={perMax}, min={perMin}
Recreating Atomikos XA pool for {connHash} due to membership change: {old} -> {new} servers
Recreated Atomikos XA pool for {connHash}: new per-server max={max}, min={min}, {stats}
```

### DEBUG Level Logging

Logged for detailed troubleshooting:

```
Calculated per-server sizes: configuredMax={max}, configuredMin={min}, servers={count} -> perServerMax={perMax}, perServerMin={perMin}
Membership change trigger for {connHash}: new server count = {count}
Closing old pool for {connHash}
```

## Manual Testing and Validation

### Local Cluster Simulation

To manually validate dynamic pool sizing in a local environment:

#### Prerequisites

1. **Multiple OJP server instances** running on different ports (e.g., 1059, 1060, 1061)
2. **Health check mechanism** to simulate server UP/DOWN events
3. **Test application** that creates XA connections and executes transactions

#### Test Procedure

**Step 1: Start with 3 Servers**

```bash
# Terminal 1
java -jar ojp-server.jar -Dojp.server.port=1059

# Terminal 2
java -jar ojp-server.jar -Dojp.server.port=1060

# Terminal 3
java -jar ojp-server.jar -Dojp.server.port=1061
```

**Expected:** Each server creates pools with `max = ceil(32/3) = 11`, `min = ceil(8/3) = 3`

**Step 2: Create XA Connection**

```java
Properties props = new Properties();
props.setProperty("ojp.connection.pool.maximumPoolSize", "32");
props.setProperty("ojp.connection.pool.minimumIdle", "8");
props.setProperty("ojp.datasource.name", "test");

ConnectionDetails details = ConnectionDetails.newBuilder()
    .setUrl("jdbc:postgresql://localhost:5432/testdb")
    .setUser("testuser")
    .setPassword("testpass")
    .setIsXA(true)
    .setProperties(ByteString.copyFrom(SerializationHandler.serialize(props)))
    .build();

SessionInfo session = statementService.connect(details);
```

**Verify Logs:**
```
INFO  DynamicAtomikosPoolManager - Creating Atomikos XA pool for [conn-hash]: servers=3, configured max=32, min=8, per-server max=11, min=3
```

**Step 3: Simulate Server Failure**

Stop one server (e.g., port 1061):
```bash
# Kill server on port 1061
```

**Expected:** Remaining 2 servers recreate pools with `max = ceil(32/2) = 16`, `min = ceil(8/2) = 4`

**Verify Logs:**
```
INFO  DynamicAtomikosPoolManager - Recreating Atomikos XA pool for [conn-hash] due to membership change: 3 -> 2 servers
INFO  DynamicAtomikosPoolManager - Recreated Atomikos XA pool for [conn-hash]: new per-server max=16, min=4, ...
```

**Step 4: Simulate Server Recovery**

Restart the stopped server:
```bash
java -jar ojp-server.jar -Dojp.server.port=1061
```

**Expected:** All 3 servers recreate pools back to `max=11`, `min=3`

**Verify Logs:**
```
INFO  DynamicAtomikosPoolManager - Recreating Atomikos XA pool for [conn-hash] due to membership change: 2 -> 3 servers
INFO  DynamicAtomikosPoolManager - Recreated Atomikos XA pool for [conn-hash]: new per-server max=11, min=3, ...
```

**Step 5: Verify Transactions Continue**

During server failure/recovery:
1. Execute XA transactions
2. Verify transactions complete successfully
3. Check that active transactions are not interrupted during pool recreation

### Expected Behavior Summary

| Scenario | Servers UP | Per-Server Max | Per-Server Min | Total Capacity |
|----------|------------|----------------|----------------|----------------|
| Normal Operation | 3 | 11 | 3 | 33 (3×11) |
| One Server Down | 2 | 16 | 4 | 32 (2×16) |
| Two Servers Down | 1 | 32 | 8 | 32 (1×32) |
| Recovery to 2 | 2 | 16 | 4 | 32 (2×16) |
| Full Recovery | 3 | 11 | 3 | 33 (3×11) |

### Monitoring Pool Statistics

Query pool statistics via JMX or logging:

```java
String stats = pool.getPoolStats();
// Returns: "AtomikosPool[ojp-xa-12345]: leased=5, maxPoolSize=16, minPoolSize=4"
```

## Comparison with HikariCP Non-XA Pools

The Atomikos XA pool sizing implementation follows the same pattern as HikariCP non-XA pools:

| Aspect | HikariCP (Non-XA) | Atomikos (XA) |
|--------|-------------------|---------------|
| **Calculation Formula** | Same | Same |
| **Membership Triggers** | Same | Same |
| **Recreation Behavior** | Pool resize | Full pool recreation |
| **Implementation** | `MultinodePoolCoordinator` | `DynamicAtomikosPoolManager` |
| **Location** | `ojp-server/.../pool/` | `ojp-server/.../datasource/` |

**Key Difference:** HikariCP supports dynamic resizing of existing pools, while Atomikos requires creating a new `AtomikosDataSourceBean` instance because the pool sizes are set at construction time and cannot be changed afterward.

## Troubleshooting

### Issue: Pools Not Recreating

**Symptom:** Server membership changes but pool sizes remain unchanged

**Possible Causes:**
1. Health check mechanism not triggering membership updates
2. `updateServerMembership()` not being called
3. Connection hash mismatch (pool not found in metadata map)

**Solution:**
- Verify health check logs show membership changes
- Check DEBUG logs for "Membership change trigger" messages
- Ensure `DynamicAtomikosPoolManager.updateServerMembership()` is called when cluster state changes

### Issue: Connection Exhaustion After Recreation

**Symptom:** "Failed to acquire XA connection" errors after membership change

**Possible Causes:**
1. New per-server size too small for current load
2. Old connections not properly returned before pool closure
3. Connection leaks

**Solution:**
- Increase global `maximumPoolSize` configuration
- Check for leaked connections in logs: "Force-closed leaked XAConnection"
- Verify applications properly close connections/transactions

### Issue: Transaction Failures During Recreation

**Symptom:** XA transactions fail with "Connection closed" during server changes

**Possible Causes:**
1. Old pool closed while transaction was active
2. Connection returned to old pool after closure
3. Race condition between pool closure and connection usage

**Solution:**
- This should not happen; pool recreation waits for connections to be returned
- If it occurs, check for code that improperly caches connections
- Review logs for "Force-closed leaked XAConnection" warnings

## Related Documentation

- [XA Transaction Flow](xa-transaction-flow.md) - Detailed XA transaction lifecycle
- [ATOMIKOS_XA_INTEGRATION.md](../ATOMIKOS_XA_INTEGRATION.md) - General Atomikos integration guide
- [multinode-architecture.md](multinode-architecture.md) - Multinode cluster coordination

## Summary

The dynamic Atomikos pool sizing implementation ensures:

1. ✅ **Fair distribution:** Global pool capacity is evenly divided among healthy servers
2. ✅ **High availability:** Pools automatically resize when servers fail or recover
3. ✅ **Minimum guarantees:** Each server gets at least 1 connection
4. ✅ **Safe recreation:** Existing transactions continue during pool recreation
5. ✅ **Clear logging:** All sizing decisions and recreations are logged for monitoring
6. ✅ **Consistent behavior:** Matches HikariCP's proven pool sizing approach
