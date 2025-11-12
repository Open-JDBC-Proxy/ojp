# XA Pool Recreation - Configuration and Behavior

## Overview

The OJP server supports safe recreation of Atomikos XA connection pools in response to cluster health changes. This feature ensures XA pools can adapt to infrastructure changes without interrupting ongoing transactions.

## Architecture

### Components

1. **XaPoolManager** - Central manager for all XA connection pools
   - Thread-safe pool management using `ReentrantReadWriteLock`
   - Asynchronous, debounced pool recreation
   - Timeout protection for recreation operations
   - Uses virtual threads for recreation tasks when available (Java 21+)

2. **ConnectionPoolConfigurer** - Handles health change notifications
   - Routes health changes to appropriate handlers (XA vs non-XA)
   - Triggers pool recreation for XA connections
   - Applies dynamic resizing for HikariCP (non-XA) connections

3. **StatementServiceImpl** - Integration point
   - Uses XaPoolManager for all XA pool operations
   - Tracks XA pool metadata for recreation
   - Processes cluster health from client requests

### Thread Safety

The XaPoolManager uses a `ReentrantReadWriteLock` to ensure safe concurrent access:

- **Read Lock**: Held during normal operations (borrow/return connections)
  - Multiple threads can hold read locks simultaneously
  - Prevents recreation while connections are in use

- **Write Lock**: Held during pool recreation
  - Exclusive access - blocks all other operations
  - Ensures atomic pool replacement

### Performance Optimization

- **Virtual Threads**: XaPoolManager automatically uses virtual threads for pool recreation tasks when running on Java 21 or later, providing better scalability and resource utilization
- **Platform Threads**: Falls back to platform threads on Java 17-20 for compatibility

## Pool Recreation Process

### Trigger Conditions

Pool recreation is triggered when:
1. Client reports cluster health changes via `SessionInfo.clusterHealth`
2. Health change is detected by `ClusterHealthTracker.hasHealthChanged()`
3. Debounce interval has elapsed since last recreation

### Recreation Flow

```
1. Client sends request with updated cluster health
   ↓
2. StatementServiceImpl.processClusterHealth() detects change
   ↓
3. ConnectionPoolConfigurer.processClusterHealthForXA() called
   ↓
4. XaPoolManager.triggerPoolRecreation() initiated
   ↓
5. Debounce check (5 second interval)
   ↓
6. Asynchronous recreation starts in background thread
   ↓
7. Write lock acquired
   ↓
8. Old pool closed (waits for active connections)
   ↓
9. New pool created with updated configuration
   ↓
10. Write lock released
   ↓
11. Pool available for use
```

### Debouncing

To prevent rapid successive recreations:
- **Interval**: 5 seconds (configurable via `DEBOUNCE_INTERVAL_MS`)
- **Behavior**: Additional recreation requests within interval are ignored
- **Purpose**: Avoid thrashing during unstable cluster states

### Timeout Protection

Recreation operations have timeout protection:
- **Timeout**: 30 seconds (configurable via `RECREATION_TIMEOUT_MS`)
- **Behavior**: Recreation aborted if timeout exceeded
- **Effect**: Prevents hung recreation attempts from blocking the system

## Configuration

### XA Pool Properties

XA pools use the same properties as regular connection pools:

| Property | Description | Default |
|----------|-------------|---------|
| `ojp.connection.pool.maximumPoolSize` | Maximum number of connections | 20 |
| `ojp.connection.pool.minimumIdle` | Minimum idle connections | 5 |
| `ojp.connection.pool.connectionTimeout` | Connection acquisition timeout (ms) | 10000 |
| `ojp.connection.pool.idleTimeout` | Idle connection timeout (ms) | 600000 |
| `ojp.connection.pool.validationQuery` | Connection validation query | SELECT 1 |

### XA Pool Recreation Configuration

XA pool recreation behavior can be configured via server configuration properties:

| Property | Description | Default |
|----------|-------------|---------|
| `ojp.server.xa.pool.recreation.debounceMs` | Minimum interval between recreation attempts (ms) | 5000 |
| `ojp.server.xa.pool.recreation.timeoutMs` | Maximum time to wait for recreation (ms) | 30000 |

These properties can be set via JVM arguments or environment variables:

```bash
# Via JVM arguments
java -Dojp.server.xa.pool.recreation.debounceMs=10000 \
     -Dojp.server.xa.pool.recreation.timeoutMs=60000 \
     -jar ojp-server.jar

# Via environment variables
export OJP_SERVER_XA_POOL_RECREATION_DEBOUNCEMS=10000
export OJP_SERVER_XA_POOL_RECREATION_TIMEOUTMS=60000
java -jar ojp-server.jar
```

**Configuration Guidelines:**

- **debounceMs**: Increase for unstable clusters (10-15 seconds), decrease for stable clusters that need faster response (3-5 seconds)
- **timeoutMs**: Should be at least 2-3x the expected pool recreation time. Increase if recreation logs show frequent timeouts

## Behavior Details

### Connection Lifecycle

1. **Borrow Connection**
   ```java
   XAConnection conn = xaPoolManager.borrowConnection(connHash, sessionId, branchId);
   ```
   - Acquires read lock (allows concurrent borrows)
   - Returns connection from current pool
   - Read lock held until connection returned

2. **Return Connection**
   ```java
   xaPoolManager.returnConnection(connHash, sessionId, branchId);
   ```
   - Acquires read lock
   - Returns connection to pool
   - Releases read lock

3. **Recreation**
   - Acquires write lock (blocks all operations)
   - Waits for active connections to complete
   - Closes old pool
   - Creates new pool
   - Releases write lock

### Active Connection Handling

During recreation:
- Active connections continue to work with old pool
- New connection requests wait for recreation to complete
- Old pool fully closes only after all connections returned
- No connection loss or transaction interruption

### Multi-Pool Support

XaPoolManager supports multiple independent pools:
- Each connection hash has separate pool
- Recreation of one pool doesn't affect others
- Independent debouncing per connection hash

## Monitoring

### Logging

Key log messages to monitor:

```
INFO  - Cluster health changed for XA pool {hash}, healthy servers: {count}, triggering pool recreation
INFO  - Starting XA pool recreation for {hash}
INFO  - Successfully recreated XA pool for {hash}: {stats}
ERROR - XA pool recreation timed out after {ms}ms for {hash}
ERROR - XA pool recreation failed for {hash}: {error}
```

### Metrics

Pool statistics available via:
```java
String stats = xaPoolManager.getPoolStats(connHash);
// Returns: "AtomikosPool[name]: leased=N, maxPoolSize=M, minPoolSize=K"
```

## Best Practices

### 1. Cluster Health Reporting

Ensure clients report accurate cluster health:
```
Format: "host1:port1(UP);host2:port2(DOWN);host3:port3(UP)"
```

### 2. Debounce Tuning

- **Stable clusters**: 5 seconds is sufficient
- **Unstable clusters**: Consider increasing to 10-15 seconds
- **Frequent changes**: Review cluster health detection logic

### 3. Connection Management

- Always return connections after use
- Use try-with-resources where possible
- Monitor for connection leaks (leak detection enabled by default)

### 4. Graceful Shutdown

Ensure proper shutdown:
```java
xaPoolManager.shutdown(); // Closes all pools, cancels pending recreations
```

## Troubleshooting

### Recreation Timeouts

**Symptom**: Log shows "XA pool recreation timed out"

**Causes**:
- Long-running transactions blocking old pool closure
- Database connectivity issues
- Resource contention

**Resolution**:
1. Check for long-running XA transactions
2. Verify database connectivity
3. Review connection timeout settings
4. Consider increasing `RECREATION_TIMEOUT_MS`

### Frequent Recreations

**Symptom**: Pools recreating every few seconds

**Causes**:
- Unstable cluster health reporting
- Network flapping
- Client-side health check issues

**Resolution**:
1. Review client health check logic
2. Increase debounce interval
3. Add hysteresis to health checks
4. Check network stability

### Connection Exhaustion

**Symptom**: Cannot acquire connections during recreation

**Causes**:
- Pool size too small for load
- Long recreation time
- Connections not being returned

**Resolution**:
1. Increase pool size
2. Check for connection leaks
3. Review transaction durations
4. Monitor active connection count

## Implementation Notes

### Why Recreation vs Dynamic Resizing?

**XA Pools (Atomikos)**: Use recreation strategy
- Atomikos doesn't support runtime pool size changes
- Must create new pool with new configuration
- Safe recreation ensures zero downtime

**Non-XA Pools (HikariCP)**: Use dynamic resizing
- HikariCP supports runtime resizing via `setMaximumPoolSize()`
- More efficient than recreation
- Instant adaptation to health changes

### Future Enhancements

Potential improvements:
1. Configurable debounce interval per connection hash
2. Health-based pool sizing (automatic scaling)
3. Gradual connection migration (old to new pool)
4. Circuit breaker integration for recreation failures
5. Prometheus metrics for recreation events

## Related Documentation

- [ATOMIKOS_XA_INTEGRATION.md](../ATOMIKOS_XA_INTEGRATION.md) - XA integration overview
- [ADDING_DATABASE_XA_SUPPORT.md](../ADDING_DATABASE_XA_SUPPORT.md) - Database-specific XA setup
- `XaPoolManager.java` - Source code and implementation details
- `ConnectionPoolConfigurer.java` - Health processing logic
