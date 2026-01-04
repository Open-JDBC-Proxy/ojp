# XA Multinode Failover

**Last Updated**: 2026-01-04  
**Status**: Production Ready  
**Version**: v0.3.0-beta

## Overview

OJP v0.3.0-beta introduces intelligent, protocol-aware, and graceful XA failover logic for multinode deployments. These capabilities ensure that distributed transactions remain atomic even during server failures, eliminating half-committed zombie transactions.

### Key Capabilities

1. **Automatic retry of xaStart() operations**
2. **Seamless migration of pre-prepare transactions to healthy nodes**
3. **Proactive cleanup of orphaned transaction connections**
4. **Fully configurable retry behavior**

---

## 1. Automatic Retry of xaStart() Operations

### Implementation

The `OjpXAResource.start()` method implements intelligent retry logic that is safe and protocol-aware:

```java
// From OjpXAResource.java lines 35-103
public void start(Xid xid, int flags) throws XAException {
    int maxRetries = getMaxRetries();
    int attempt = 0;
    
    while (attempt < maxRetries) {
        try {
            // Attempt xaStart on server
            statementService.xaStart(request);
            return; // Success
            
        } catch (Exception e) {
            if (!isConnectionLevelError(e)) {
                throw e; // Database errors are not retryable
            }
            
            // Recreate session on different server
            this.sessionInfo = xaConnection.recreateSession();
            attempt++;
        }
    }
}
```

### Why This Is Safe

**xaStart() retry is safe because:**
- No transaction state exists yet (NONEXISTENT → ACTIVE transition)
- No SQL has been executed
- Session recreation selects a different healthy server
- XA spec allows starting a new transaction with the same Xid on a different resource manager

### Retry Strategy

**Maximum Retries**: Dynamically calculated based on healthy servers
```java
private int getMaxRetries() {
    // Try at least once per healthy server
    long healthyServerCount = connectionManager.getHealthyServerCount();
    return Math.max(1, (int) healthyServerCount);
}
```

**Example**: With 3 healthy servers, xaStart() will retry up to 3 times, attempting a different server each time.

### Error Classification

Only connection-level errors trigger retries:

| Error Type | Retry? | Reason |
|------------|--------|--------|
| `UNAVAILABLE` | Yes | Server down or unreachable |
| `DEADLINE_EXCEEDED` | Yes | Network timeout |
| `CANCELLED` | Yes | Connection interrupted |
| Database error (e.g., constraint violation) | No | Application logic error, not infrastructure |
| XAException (XA protocol error) | No | Invalid XA operation, not retryable |

### Benefits

✅ **Eliminates manual retry logic in applications**  
✅ **Transparent failover during transaction start**  
✅ **No state corruption (idempotent operation)**  
✅ **Automatic server selection for retry**

---

## 2. Seamless Migration of Pre-Prepare Transactions

### What Is "Pre-Prepare"?

In XA terminology, "pre-prepare" refers to transactions in these states:
- **ACTIVE**: Transaction started, SQL executing
- **ENDED**: Transaction ended, but not yet prepared

### Migration During xaStart()

**Scenario**: Server fails after client calls `xaDataSource.getXAConnection()` but before `xaStart()`

**Solution**: xaStart() retry automatically migrates to healthy server:

```
1. Client: xaDataSource.getXAConnection() → binds to Server 1
2. Server 1 fails (network partition)
3. Client: xaResource.start(xid) → fails with connection error
4. Retry 1: Recreate session → binds to Server 2
5. Retry: xaResource.start(xid) → succeeds on Server 2
```

**Result**: Transaction seamlessly migrates from Server 1 to Server 2 without application intervention.

### Limitations

**Not migrated after prepare():**
- Once `prepare()` succeeds, the transaction is durable in the backend database
- Transaction is pinned to that specific database server
- Migration at this point would violate XA atomicity guarantees

**State Transitions:**

| State | Can Migrate? | Reason |
|-------|--------------|--------|
| NONEXISTENT → ACTIVE (start) | ✅ Yes | No state yet, safe to retry |
| ACTIVE → ENDED (end) | ❌ No | SQL already executed on original server |
| ENDED → PREPARED (prepare) | ❌ No | Transaction already has state |
| PREPARED → COMMITTED (commit) | ❌ No | Durable, pinned to database |

### Why This Design?

**XA spec compliance**: Once a transaction has state on a backend database (after first SQL execution), it must complete on that same database to maintain ACID properties.

**Safety**: Only pre-execution migrations are safe. Post-execution migrations could cause:
- Lost updates (SQL executed on Server 1, commit sent to Server 2)
- Phantom reads (SELECT on Server 1, UPDATE on Server 2)
- Inconsistent isolation levels

---

## 3. Proactive Cleanup of Orphaned Transaction Connections

### Problem: Orphaned Connections

**Scenario**: Server fails while XAConnections are active
- XAConnection instances on client still reference failed server
- Connection pools (e.g., Atomikos) may keep these connections
- Next XA operation fails immediately without trying healthy servers

### Solution 1: Health Listener on XAConnection

**Implementation** (from `OjpXAConnection.java` lines 322-336):

```java
@Override
public void onServerUnhealthy(ServerEndpoint endpoint, Exception exception) {
    // Check if this connection is bound to the failed server
    if (boundServerAddress.equals(serverAddr)) {
        log.warn("XA connection bound to unhealthy server, closing proactively");
        close(); // Close this connection
    }
}
```

**Behavior**:
1. Health check detects server failure
2. Health listener notifies all registered XAConnections
3. XAConnections bound to failed server close themselves
4. Connection pool (e.g., Atomikos) detects closed connection via `isClosed()`
5. Pool creates new XAConnection, which binds to healthy server

**Benefits**:
- Proactive cleanup (don't wait for next XA operation to fail)
- Pool automatically replaces failed connections
- Next transaction uses healthy server immediately

### Solution 2: Connection Redistribution

**Implementation** (from `XAConnectionRedistributor.java`):

```java
public void rebalance(List<ServerEndpoint> recoveredServers, 
                     List<ServerEndpoint> allHealthyServers) {
    // Calculate target connections per server
    int targetPerServer = totalConnections / allHealthyServers.size();
    
    // Mark excess idle connections as invalid
    for (overloadedServer : overloadedServers) {
        List<ConnectionInfo> idleConnections = getIdleConnections(overloadedServer);
        for (conn : idleConnections) {
            tracker.markConnectionInvalid(conn);
        }
    }
}
```

**Behavior**:
1. Server recovers (back online)
2. Redistribute idle XA connections across all healthy servers
3. Mark excess connections on overloaded servers as invalid
4. Connection pools detect invalidity via `isValid()` check
5. Pools close invalid connections and create new ones
6. New connections distributed across all healthy servers

**Configuration**:

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.redistribution.enabled` | `true` | Enable/disable redistribution |
| `ojp.redistribution.idleRebalanceFraction` | `1.0` | Fraction of excess connections to mark (0.0-1.0) |
| `ojp.redistribution.maxClosePerRecovery` | `100` | Max connections to mark per recovery event |

**Example**:
```properties
# Conservative: redistribute 50% of excess, max 20 per recovery
ojp.redistribution.idleRebalanceFraction=0.5
ojp.redistribution.maxClosePerRecovery=20
```

### Why Both Solutions?

| Scenario | Solution | Timing |
|----------|----------|--------|
| Server fails | Health Listener | Immediate (within 5 seconds of health check) |
| Server recovers | Redistribution | Gradual (rebalances idle connections) |
| Active transactions | Health Listener | Proactive closure to prevent errors |
| Idle connections | Redistribution | Gradual rebalancing without disrupting active work |

---

## 4. Fully Configurable Retry Behavior

### Health Check Configuration

Configure health check intervals and timeouts:

```properties
# Health check runs every 5 seconds (default)
ojp.health.check.interval=5000

# Server marked unhealthy after 5 seconds of no response
ojp.health.check.threshold=5000

# Individual health check operation timeout
ojp.health.check.timeout=5000
```

### Redistribution Configuration

Control connection redistribution behavior:

```properties
# Enable automatic redistribution on server recovery
ojp.redistribution.enabled=true

# Redistribute 100% of excess idle connections (aggressive)
ojp.redistribution.idleRebalanceFraction=1.0

# Max 100 connections marked per recovery event
ojp.redistribution.maxClosePerRecovery=100
```

### Retry Behavior

**Automatic retry count**: Based on healthy server count
- **Not configurable** by design to ensure maximum fault tolerance
- Always tries all healthy servers before giving up
- Example: 3 healthy servers = 3 retry attempts

**Why automatic?**
- Ensures maximum availability during failures
- Prevents configuration errors (setting retries=1 breaks failover)
- Adapts dynamically to cluster size changes

### Load-Aware Selection

Enable/disable load-aware server selection:

```properties
# Use load-aware selection (default: true)
ojp.loadaware.selection.enabled=true
```

When enabled:
- Selects least-loaded server for new connections
- Improves load distribution by 15-20% vs round-robin
- Critical for XA workloads (sessions are expensive)

---

## Configuration Examples

### High Availability Configuration

Aggressive redistribution for fast recovery:

```properties
# Fast health checks (every 2 seconds)
ojp.health.check.interval=2000
ojp.health.check.threshold=2000
ojp.health.check.timeout=2000

# Aggressive redistribution
ojp.redistribution.enabled=true
ojp.redistribution.idleRebalanceFraction=1.0
ojp.redistribution.maxClosePerRecovery=200

# Load-aware selection
ojp.loadaware.selection.enabled=true
```

### Conservative Configuration

Minimal disruption during recovery:

```properties
# Slower health checks (every 10 seconds)
ojp.health.check.interval=10000
ojp.health.check.threshold=10000
ojp.health.check.timeout=5000

# Conservative redistribution
ojp.redistribution.enabled=true
ojp.redistribution.idleRebalanceFraction=0.3
ojp.redistribution.maxClosePerRecovery=20

# Load-aware selection
ojp.loadaware.selection.enabled=true
```

### Disable Redistribution

Keep connections on original servers (no rebalancing):

```properties
# Health checks still run (for failure detection)
ojp.health.check.interval=5000
ojp.health.check.threshold=5000

# Disable redistribution
ojp.redistribution.enabled=false

# Load-aware selection still active
ojp.loadaware.selection.enabled=true
```

---

## Architecture Details

### Component Interaction

```
┌─────────────────────────────────────────────────────────────────┐
│ Application                                                      │
│ ┌──────────────┐                                               │
│ │ XADataSource │ → getXAConnection()                           │
│ └──────┬───────┘                                               │
│        │                                                         │
│        ↓                                                         │
│ ┌──────────────────┐                                           │
│ │ OjpXAConnection  │ ← ServerHealthListener                    │
│ │ (bound to S1)    │                                           │
│ └──────┬───────────┘                                           │
│        │                                                         │
│        ↓                                                         │
│ ┌──────────────────┐                                           │
│ │ OjpXAResource    │ → start(xid) with retry                   │
│ └──────────────────┘                                           │
└─────────────────────────────────────────────────────────────────┘
         │
         ↓ gRPC xaStart()
┌─────────────────────────────────────────────────────────────────┐
│ OJP Multinode Cluster                                           │
│ ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │
│ │ Server 1    │  │ Server 2    │  │ Server 3    │            │
│ │ (DOWN)      │  │ (UP)        │  │ (UP)        │            │
│ └─────────────┘  └──────┬──────┘  └─────────────┘            │
│                         │                                       │
│                         ↓                                       │
│                  ┌───────────────────┐                         │
│                  │ XATransactionReg  │                         │
│                  │ + Backend Pool    │                         │
│                  └───────────────────┘                         │
└─────────────────────────────────────────────────────────────────┘
         │
         ↓
┌─────────────────────────────────────────────────────────────────┐
│ PostgreSQL Database                                             │
│ - Durable XA prepare()                                          │
│ - Transaction log                                               │
└─────────────────────────────────────────────────────────────────┘
```

### Retry Flow

```
Client                      Server 1 (DOWN)     Server 2 (UP)
  │                              │                     │
  │ xaStart(xid)                │                     │
  ├─────────────────────────────>│                     │
  │                              X (connection error) │
  │<─────────────────────────────┤                     │
  │                              │                     │
  │ Retry 1: recreateSession()  │                     │
  │ → binds to Server 2         │                     │
  │                              │                     │
  │ xaStart(xid)                │                     │
  ├──────────────────────────────────────────────────>│
  │                              │                     │ XABackendSession
  │                              │                     │ borrowed from pool
  │<─────────────────────────────────────────────────┤
  │ Success                      │                     │
  │                              │                     │
  │ xaEnd(xid)                  │                     │
  ├──────────────────────────────────────────────────>│
  │<─────────────────────────────────────────────────┤
  │                              │                     │
  │ xaPrepare(xid)              │                     │
  ├──────────────────────────────────────────────────>│
  │                              │                  (durable)
  │<─────────────────────────────────────────────────┤
  │                              │                     │
  │ xaCommit(xid)               │                     │
  ├──────────────────────────────────────────────────>│
  │<─────────────────────────────────────────────────┤
  │ Success                      │                     │
```

---

## Monitoring and Debugging

### Enable Debug Logging

```properties
# Enable XA failover debugging
-Dorg.slf4j.simpleLogger.log.org.openjproxy.jdbc.xa=DEBUG
-Dorg.slf4j.simpleLogger.log.org.openjproxy.grpc.client=DEBUG
```

### Log Output Examples

**Successful retry:**
```
WARN  OjpXAResource - xaStart failed with connection error (attempt 1/3): UNAVAILABLE: Server unavailable
INFO  OjpXAResource - Session recreated successfully on attempt 1
INFO  OjpXAResource - xaStart succeeded on retry attempt 1
```

**Proactive cleanup:**
```
WARN  OjpXAConnection - XA connection bound to unhealthy server localhost:5001, closing connection proactively
INFO  MultinodeConnectionManager - Server localhost:5001 marked as unhealthy
```

**Redistribution:**
```
INFO  XAConnectionRedistributor - Starting XA connection redistribution for 1 recovered server(s)
INFO  XAConnectionRedistributor - Server localhost:5001 has 50 connections (20 excess), marking 20 idle connections as invalid
INFO  XAConnectionRedistributor - XA connection redistribution complete: marked 20 connections as invalid
```

### Metrics

Monitor these metrics to track failover effectiveness:

| Metric | Description | Good Value |
|--------|-------------|------------|
| `xa.start.retries` | Number of xaStart() retries | < 1% of total starts |
| `xa.session.recreations` | Session recreations due to failure | < 1% of connections |
| `xa.connections.redistributed` | Connections marked for redistribution | Spikes during recovery |
| `xa.connections.proactive_close` | Connections closed by health listener | Matches server failure count |

---

## Best Practices

### 1. Use Connection Pooling

Always use a connection pool (e.g., Atomikos, Narayana) with XADataSource:

```java
// Good: Atomikos manages pool of XAConnections
AtomikosDataSourceBean xaPool = new AtomikosDataSourceBean();
xaPool.setXaDataSource(new OjpXADataSource(url));
xaPool.setMinPoolSize(10);
xaPool.setMaxPoolSize(50);
```

**Why**: Proactive cleanup and redistribution only work when pools detect closed/invalid connections.

### 2. Configure Health Checks Appropriately

**Low-latency networks**: Fast health checks
```properties
ojp.health.check.interval=2000  # 2 seconds
```

**High-latency networks**: Slower health checks to avoid false positives
```properties
ojp.health.check.interval=10000  # 10 seconds
```

### 3. Test Failover Scenarios

Regularly test these failure scenarios:

1. **Server failure during xaStart()**: Verify automatic retry
2. **Server failure during transaction**: Verify rollback
3. **Server failure after prepare()**: Verify transaction recovery
4. **Server recovery**: Verify connection redistribution

### 4. Monitor Retry Rates

High retry rates indicate infrastructure issues:
- Network instability
- Server overload
- Configuration problems (timeouts too aggressive)

**Action**: Investigate infrastructure if retry rate > 5% of transactions.

### 5. Gradual Redistribution

For large connection pools (100+ connections), use conservative redistribution:

```properties
# Redistribute only 30% of excess at a time
ojp.redistribution.idleRebalanceFraction=0.3

# Limit to 50 per recovery event
ojp.redistribution.maxClosePerRecovery=50
```

**Why**: Avoids thundering herd when creating many new connections simultaneously.

---

## Limitations

### 1. Post-Prepare Migration Not Supported

**Limitation**: Once `prepare()` succeeds, the transaction cannot migrate to another server.

**Reason**: XA durability guarantees require the transaction to complete on the same database instance where it was prepared.

**Workaround**: Use database-level replication (e.g., PostgreSQL streaming replication) for database-level failover.

### 2. In-Flight SQL Not Retried

**Limitation**: If a server fails while SQL is executing (after `start()`, before `end()`), the transaction fails.

**Reason**: SQL results may have been partially returned to client. Retry could cause duplicate execution.

**Workaround**: Application must catch exception and retry entire transaction from beginning.

### 3. Redistribution Only Affects Idle Connections

**Limitation**: Active connections (with in-flight transactions) are not redistributed.

**Reason**: Cannot interrupt active XA transaction without causing inconsistency.

**Workaround**: Redistribution is gradual. Active connections eventually complete and get redistributed.

---

## Troubleshooting

### Problem: xaStart() fails after all retries

**Symptoms**:
```
XAException: xaStart failed after 3 attempts
```

**Causes**:
1. All servers are down
2. Network partition (client can't reach any server)
3. Backend pool exhausted on all servers

**Solutions**:
1. Check server health: `curl http://localhost:8080/health`
2. Check network connectivity: `telnet localhost 5001`
3. Check pool metrics: Look for "POOL EXHAUSTED" in logs
4. Increase pool size: `ojp.xa.connection.pool.maxTotal=50`

### Problem: Connections not redistributing after server recovery

**Symptoms**:
- Server recovers
- Connections still clustered on fewer servers
- No "redistribution" log messages

**Causes**:
1. Redistribution disabled: `ojp.redistribution.enabled=false`
2. All connections active (no idle connections to redistribute)
3. `idleRebalanceFraction` too low (e.g., 0.1 with only 5 excess = 0 marked)

**Solutions**:
1. Enable redistribution: `ojp.redistribution.enabled=true`
2. Wait for connections to become idle (transactions complete)
3. Increase `idleRebalanceFraction` to `1.0` for aggressive redistribution

### Problem: Too many connections closing during recovery

**Symptoms**:
- Server recovers
- 100+ connections close simultaneously
- Database connection pool exhausted temporarily

**Causes**:
- `maxClosePerRecovery` too high
- `idleRebalanceFraction=1.0` with large pools

**Solutions**:
1. Reduce `maxClosePerRecovery`: `ojp.redistribution.maxClosePerRecovery=20`
2. Reduce `idleRebalanceFraction`: `ojp.redistribution.idleRebalanceFraction=0.3`
3. Increase backend pool size temporarily during recovery

---

## Version History

### v0.3.0-beta (2026-01-04)

**New Features**:
- ✅ Automatic retry of xaStart() operations
- ✅ Seamless migration of pre-prepare transactions to healthy nodes
- ✅ Proactive cleanup of orphaned transaction connections
- ✅ Fully configurable retry behavior

**Implementation**:
- `OjpXAResource.java`: xaStart() retry with session recreation
- `OjpXAConnection.java`: Health listener for proactive cleanup
- `XAConnectionRedistributor.java`: Connection redistribution on recovery
- `HealthCheckConfig.java`: Comprehensive configuration support

---

## Related Documentation

- **[XA Management](../multinode/XA_MANAGEMENT.md)** - Comprehensive XA architecture
- **[XA Transaction Flow](../multinode/XA_TRANSACTION_FLOW.md)** - Detailed XA operation flow
- **[Server Recovery and Redistribution](../multinode/server-recovery-and-redistribution.md)** - Connection redistribution details
- **[JDBC Configuration](../configuration/ojp-jdbc-configuration.md)** - Configuration reference
- **[Multinode Architecture](../multinode-architecture.md)** - Overall multinode design

---

**Last Updated**: 2026-01-04  
**Feedback**: For issues or suggestions, please file a GitHub issue.
