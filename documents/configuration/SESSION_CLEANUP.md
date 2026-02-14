# Session Cleanup and Timeout Management

## Overview

OJP (Open J Proxy) now includes automatic session cleanup functionality to handle abandoned sessions. This feature prevents memory leaks and resource exhaustion when clients disconnect without properly calling `terminateSession()`.

## Problem Statement

Previously, if a client disconnected abruptly (network failure, crash, etc.) without explicitly calling `terminateSession()`, the session object would remain in the server's memory indefinitely, along with:
- Database connections (eventually returned to the pool by HikariCP/DBCP timeouts)
- ResultSet objects
- Statement objects
- LOB (Large Object) references
- Session metadata

This could lead to:
- Memory leaks
- Resource exhaustion
- Stale session accumulation

## Solution

OJP now includes:

1. **Session Activity Tracking**: Each session tracks its creation time and last activity time
2. **Background Cleanup Task**: A scheduled executor that periodically checks for and terminates inactive sessions
3. **Configurable Timeouts**: Fully configurable session timeout and cleanup interval settings

## How It Works

### Session Activity Tracking

Each `Session` object now tracks:
- **Creation Time**: When the session was created (immutable)
- **Last Activity Time**: Updated on every gRPC operation (mutable)

Activity is automatically updated when clients perform operations like:
- Execute queries
- Execute updates
- Fetch result sets
- Any other gRPC call that uses the session

### Cleanup Process

The cleanup task runs periodically (default: every 5 minutes) and:

1. Retrieves all active sessions from the SessionManager
2. Checks each session's inactivity duration
3. Identifies sessions inactive longer than the configured timeout (default: 30 minutes)
4. Terminates each abandoned session:
   - Rolls back any active transactions
   - Closes database connections (returned to the pool)
   - Frees all session resources (ResultSets, Statements, LOBs)
   - Removes the session from memory

### Relationship with Connection Pool Timeouts

OJP session timeouts complement the existing connection pool timeouts:

| Component | Timeout | Purpose | Since (OJP Feature) |
|-----------|---------|---------|---------------------|
| **OJP Session** | 30 min (default) | Cleans up session objects and associated resources | 0.4.0-beta |
| **HikariCP idle** | 10 min (default) | Returns idle connections to the pool | N/A (HikariCP) |
| **HikariCP maxLifetime** | 30 min (default) | Maximum connection lifetime | N/A (HikariCP) |
| **DBCP2 idle** | 10 min (default) | Evicts idle connections | N/A (DBCP2) |
| **DBCP2 maxLifetime** | 30 min (default) | Maximum connection lifetime | N/A (DBCP2) |

## Configuration

### Server Configuration Properties

Configure session cleanup using JVM system properties or environment variables:

```properties
# Enable/disable session cleanup (default: true)
ojp.server.sessionCleanup.enabled=true

# Session timeout in minutes (default: 30)
ojp.server.sessionCleanup.timeoutMinutes=30

# Cleanup task interval in minutes (default: 5)
ojp.server.sessionCleanup.intervalMinutes=5
```

### Environment Variables

Environment variables use uppercase with underscores:

```bash
# Enable session cleanup
export OJP_SERVER_SESSIONCLEANUP_ENABLED=true

# Set 60-minute timeout
export OJP_SERVER_SESSIONCLEANUP_TIMEOUTMINUTES=60

# Run cleanup every 10 minutes
export OJP_SERVER_SESSIONCLEANUP_INTERVALMINUTES=10
```

### Docker Configuration

Pass configuration via environment variables:

```bash
docker run -d \
  -e OJP_SERVER_SESSIONCLEANUP_ENABLED=true \
  -e OJP_SERVER_SESSIONCLEANUP_TIMEOUTMINUTES=60 \
  -e OJP_SERVER_SESSIONCLEANUP_INTERVALMINUTES=10 \
  rrobetti/ojp:latest
```

### Java Application

Set JVM system properties:

```bash
java -jar ojp-server.jar \
  -Dojp.server.sessionCleanup.enabled=true \
  -Dojp.server.sessionCleanup.timeoutMinutes=30 \
  -Dojp.server.sessionCleanup.intervalMinutes=5
```

## Default Values

| Setting | Default Value | Description | Since |
|---------|---------------|-------------|-------|
| `enabled` | `true` | Session cleanup is enabled by default | 0.4.0-beta |
| `timeoutMinutes` | `30` | Sessions inactive for 30+ minutes are cleaned up | 0.4.0-beta |
| `intervalMinutes` | `5` | Cleanup task runs every 5 minutes | 0.4.0-beta |

## Monitoring and Logging

The cleanup task logs important events:

### Normal Operation

```
INFO  o.o.grpc.server.SessionCleanupTask - Starting session cleanup task (timeout: 1800000ms)
INFO  o.o.grpc.server.SessionCleanupTask - No inactive sessions found (total sessions: 5)
```

### Cleanup Activity

```
INFO  o.o.grpc.server.SessionCleanupTask - Found 2 inactive sessions out of 10 total sessions
INFO  o.o.grpc.server.SessionCleanupTask - Cleaning up abandoned session: sessionUUID=abc-123, clientUUID=client-1, inactiveDuration=1850000ms, threshold=1800000ms, isXA=false
INFO  o.o.grpc.server.SessionManagerImpl - Terminating session -> abc-123
INFO  o.o.grpc.server.SessionManagerImpl - Rolling back active transaction
INFO  o.o.grpc.server.SessionCleanupTask - Successfully terminated abandoned session: abc-123
INFO  o.o.grpc.server.SessionCleanupTask - Session cleanup completed: 2 sessions terminated
```

### Error Handling

```
ERROR o.o.grpc.server.SessionCleanupTask - Error terminating abandoned session: xyz-456
ERROR o.o.grpc.server.SessionCleanupTask - Unexpected error during session cleanup
```

## Best Practices

### Recommended Settings

For production environments:

```properties
# Match or slightly exceed connection pool maxLifetime
ojp.server.sessionCleanup.timeoutMinutes=30

# Run cleanup frequently enough to catch abandoned sessions quickly
# but not so frequently that it impacts performance
ojp.server.sessionCleanup.intervalMinutes=5
```

### High-Traffic Environments

For high-traffic environments with many short-lived sessions:

```properties
# Shorter timeout for faster cleanup
ojp.server.sessionCleanup.timeoutMinutes=15

# More frequent cleanup
ojp.server.sessionCleanup.intervalMinutes=2
```

### Low-Traffic Environments

For low-traffic environments or development:

```properties
# Longer timeout
ojp.server.sessionCleanup.timeoutMinutes=60

# Less frequent cleanup
ojp.server.sessionCleanup.intervalMinutes=10
```

### Long-Running Operations

If your application performs long-running operations (>30 minutes):

```properties
# Increase timeout to accommodate long operations
ojp.server.sessionCleanup.timeoutMinutes=120

# Adjust interval accordingly
ojp.server.sessionCleanup.intervalMinutes=15
```

## Disabling Session Cleanup

To disable automatic session cleanup:

```properties
ojp.server.sessionCleanup.enabled=false
```

**Warning**: Disabling session cleanup means you rely entirely on:
1. Clients properly calling `terminateSession()`
2. Connection pool timeouts to reclaim database connections
3. JVM garbage collection to free memory

Only disable if you have:
- Extremely reliable clients that always terminate sessions
- Very low session volume
- Specific monitoring and manual cleanup processes

## XA Transaction Support

Session cleanup fully supports XA transactions:

- **Active XA Transactions**: Sessions with active XA transactions are terminated, rolling back the transaction if necessary
- **XA Connection Pooling**: When using XA connection pooling, the cleanup task properly returns backend sessions to the pool
- **XA Transaction Registry**: Cleanup integrates with the XATransactionRegistry for proper resource management

## Implementation Details

### Session Class Extensions

```java
public class Session {
    private final long creationTime;
    private volatile long lastActivityTime;
    
    public void updateActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    public boolean isInactive(long timeoutMillis) {
        long inactiveDuration = System.currentTimeMillis() - this.lastActivityTime;
        return inactiveDuration > timeoutMillis;
    }
    
    public long getInactiveDuration() {
        return System.currentTimeMillis() - this.lastActivityTime;
    }
}
```

### SessionManager Extensions

```java
public interface SessionManager {
    void updateSessionActivity(SessionInfo sessionInfo);
    Collection<Session> getAllSessions();
}
```

### Automatic Activity Updates

Activity is automatically updated in `StatementServiceImpl` for all operations:

```java
private void updateSessionActivity(SessionInfo sessionInfo) {
    if (sessionInfo != null && sessionInfo.getSessionUUID() != null) {
        sessionManager.updateSessionActivity(sessionInfo);
    }
}
```

## Troubleshooting

### Sessions Being Cleaned Up Too Quickly

If legitimate sessions are being terminated:

1. Increase the timeout: `ojp.server.sessionCleanup.timeoutMinutes=60`
2. Check if your operations take longer than the timeout
3. Verify clients are actively using sessions (not holding them idle)

### Sessions Not Being Cleaned Up

If abandoned sessions persist:

1. Verify cleanup is enabled: `ojp.server.sessionCleanup.enabled=true`
2. Check server logs for cleanup task execution
3. Verify timeout is appropriate for your environment
4. Check if cleanup interval is too infrequent

### Memory Still Growing

If memory continues to grow despite cleanup:

1. Monitor session count over time
2. Check for session leaks in client code
3. Verify ResultSets and Statements are being properly closed
4. Consider reducing timeout and interval values

## See Also

- [Connection Pool Configuration](ojp-jdbc-configuration.md)
- [OJP Server Configuration](ojp-server-configuration.md)
- [XA Transaction Support](../designs/XA_POOLING.md)
