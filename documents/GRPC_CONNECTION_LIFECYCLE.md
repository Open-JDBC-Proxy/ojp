# gRPC Connection Lifecycle in OJP JDBC Driver

## Overview

This document explains exactly when gRPC connections are established between the OJP JDBC client and OJP server, and how they are managed throughout the application lifecycle.

## TL;DR - Quick Answers

### Q: When is the gRPC connection established?
**A:** On the **first JDBC connection** for a given server configuration. The gRPC channel is created lazily and then cached for reuse.

### Q: Does it happen only once?
**A:** Yes, for a given server endpoint. The gRPC channel is created once and reused for all subsequent JDBC connections with the same server configuration.

### Q: Do all JDBC Connection instances reuse the same gRPC connection?
**A:** Yes. All JDBC `Connection` objects that use the same server configuration share the underlying gRPC channel(s).

## Detailed Lifecycle

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│ JDBC Application (Multiple JDBC Connection Objects)            │
└────┬────────────────────────────────────────────────────┬───────┘
     │                                                     │
     │  Connection.close() → terminates server session    │
     │  Does NOT close gRPC channel                       │
     │                                                     │
┌────▼────────────────────────────────────────────────────▼───────┐
│ StatementService Layer                                          │
│ - Single-node: StatementServiceGrpcClient                       │
│ - Multinode:   MultinodeStatementService                        │
│                + MultinodeConnectionManager                     │
└────┬────────────────────────────────────────────────────┬───────┘
     │                                                     │
     │  Persistent gRPC channels (shared, long-lived)     │
     │                                                     │
┌────▼────────────────────────────────────────────────────▼───────┐
│ gRPC ManagedChannel(s)                                          │
│ - Created once per server endpoint                              │
│ - Cached in StatementService implementation                     │
│ - Reused by all JDBC Connections                                │
└─────────────────────────────────────────────────────────────────┘
```

### Single-Node Configuration

**File:** `StatementServiceGrpcClient.java`

1. **First JDBC Connection Request:**
   ```java
   DriverManager.getConnection("jdbc:ojp[server:1059]_postgresql://...");
   ```

2. **URL Parsing and Service Lookup:**
   - Driver parses the URL to extract server endpoint
   - Calls `MultinodeUrlParser.getOrCreateStatementService(url)`
   - Cache key: `"single:server:1059"`
   - If not in cache, creates new `StatementServiceGrpcClient`

3. **First `connect()` Call:**
   - Triggers `grpcChannelOpenAndStubsInitialized(url)`
   - Checks: `if (statemetServiceStub == null && statemetServiceBlockingStub == null)`
   - Creates gRPC channel: `ManagedChannel channel = GrpcChannelFactory.createChannel(target)`
   - Creates stubs: 
     - `StatementServiceGrpc.newBlockingStub(channel)`
     - `StatementServiceGrpc.newStub(channel)`
   - **Critical comment in code (line 83):** *"Once channel is open it remains open and is shared among all requests."*

4. **Subsequent JDBC Connection Requests:**
   - Same URL → Retrieves cached `StatementServiceGrpcClient` 
   - Channel already initialized → Skips channel creation
   - Reuses existing gRPC channel for new server session

### Multinode Configuration

**File:** `MultinodeConnectionManager.java`

1. **First JDBC Connection Request:**
   ```java
   DriverManager.getConnection("jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://...");
   ```

2. **Service Creation (First Time):**
   - Parses multinode URL → extracts multiple endpoints
   - Cache key: `"multinode:server1:1059,server2:1059,server3:1059"`
   - Creates `MultinodeConnectionManager` with all endpoints
   - Calls `initializeConnections()` immediately

3. **Channel Initialization (Constructor):**
   - **All gRPC channels created upfront** in `initializeConnections()` (line 139-151)
   - For each server endpoint:
     ```java
     String target = DNS_PREFIX + endpoint.getHost() + ":" + endpoint.getPort();
     ManagedChannel channel = GrpcChannelFactory.createChannel(target);
     StatementServiceBlockingStub blockingStub = StatementServiceGrpc.newBlockingStub(channel);
     StatementServiceStub asyncStub = StatementServiceGrpc.newStub(channel);
     ```
   - Stores in `channelMap: Map<ServerEndpoint, ChannelAndStub>`
   - Channels remain open for application lifetime

4. **All `connect()` Calls:**
   - Both XA and non-XA connections call `connectToAllServers()`
   - Uses pre-initialized channels from `channelMap`
   - No new channels created

### Service Caching

**File:** `MultinodeUrlParser.java` (line 30)

```java
private static final Map<String, StatementService> statementServiceCache = new ConcurrentHashMap<>();
```

**Cache Keys:**
- Single-node: `"single:host:port"` (e.g., `"single:localhost:1059"`)
- Multinode: `"multinode:host1:port1,host2:port2,..."` (e.g., `"multinode:server1:1059,server2:1059,server3:1059"`)

**Cache Behavior:**
- `ConcurrentHashMap.computeIfAbsent()` ensures thread-safe, atomic creation
- Same server configuration → Same `StatementService` → Same gRPC channels
- Cache never cleared (lives for JVM lifetime)

## JDBC Connection vs gRPC Channel

### Important Distinction

| Aspect | JDBC Connection | gRPC Channel |
|--------|----------------|--------------|
| **Creation** | Every `DriverManager.getConnection()` | Once per server configuration |
| **Scope** | Per-session (maps to server-side session) | Shared across all sessions |
| **Lifecycle** | Created and closed by application | Created once, persists for app lifetime |
| **Close behavior** | `Connection.close()` terminates server session | gRPC channel **never closed** by JDBC driver |
| **Thread safety** | Not thread-safe (per JDBC spec) | Thread-safe (gRPC guarantee) |

### Connection Close Behavior

**File:** `Connection.java` (line 182-191)

```java
@Override
public void close() throws SQLException {
    log.debug("close called");
    // Always call terminateSession to ensure server-side resources are released
    // This is critical for multinode scenarios where connect() may have been called on multiple servers
    if (this.session != null) {
        this.statementService.terminateSession(this.session);
        this.session = null;
    }
    this.closed = true;
}
```

**Key Points:**
- `Connection.close()` sends `terminateSession()` RPC to server
- Server releases database connection and session resources
- gRPC channel remains open for future connections
- This is **intentional** - channels are expensive to create, cheap to reuse

## Performance Implications

### Benefits of Channel Reuse

1. **Reduced Latency:** No TCP handshake or TLS negotiation on subsequent connections
2. **Connection Pooling:** Server can pool backend database connections per gRPC channel
3. **Load Balancing:** Multinode manager can distribute sessions efficiently across pre-connected channels
4. **Health Checks:** Channels remain connected for continuous health monitoring

### Resource Considerations

1. **Memory:** Each gRPC channel consumes ~1-2MB of memory (acceptable for typical deployments)
2. **File Descriptors:** One socket per server endpoint (minimal in practice)
3. **Thread Safety:** gRPC channels are fully thread-safe, unlike JDBC connections

## Configuration Examples

### Example 1: Single Application, Single Server

```java
// First connection - creates gRPC channel
Connection conn1 = DriverManager.getConnection("jdbc:ojp[localhost:1059]_postgresql://localhost:5432/db1");

// Second connection - reuses existing gRPC channel
Connection conn2 = DriverManager.getConnection("jdbc:ojp[localhost:1059]_postgresql://localhost:5432/db1");

// Different database on same server - reuses gRPC channel
Connection conn3 = DriverManager.getConnection("jdbc:ojp[localhost:1059]_postgresql://localhost:5432/db2");
```

**Result:** All three connections share **one gRPC channel** to `localhost:1059`

### Example 2: Single Application, Multiple Servers

```java
// First server connection - creates gRPC channel to server1
Connection conn1 = DriverManager.getConnection("jdbc:ojp[server1:1059]_postgresql://localhost:5432/db1");

// Second server connection - creates SEPARATE gRPC channel to server2
Connection conn2 = DriverManager.getConnection("jdbc:ojp[server2:1059]_postgresql://localhost:5432/db1");
```

**Result:** Two separate gRPC channels (one per server endpoint)

### Example 3: Multinode Cluster

```java
// First connection - creates 3 gRPC channels immediately (one per server)
Connection conn1 = DriverManager.getConnection(
    "jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/db1");

// Second connection - reuses all 3 existing gRPC channels
Connection conn2 = DriverManager.getConnection(
    "jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/db1");
```

**Result:** Three gRPC channels (one per server), all created on first connection and reused thereafter

## Connection Pool Integration

When using connection pools (HikariCP, DBCP2, etc.):

```
┌───────────────────────────────────────────────────────────┐
│ HikariCP / DBCP2 Connection Pool                          │
│ - Manages multiple JDBC Connection objects                │
│ - Each has separate server-side session                   │
└──────────────┬────────────────────────────────────────────┘
               │
               │  All pooled connections share gRPC channel
               │
┌──────────────▼────────────────────────────────────────────┐
│ Single gRPC ManagedChannel (per server endpoint)          │
└───────────────────────────────────────────────────────────┘
```

**Benefits:**
- Pool can maintain 10, 50, or 100 JDBC connections
- All share 1 gRPC channel (or 3 channels for 3-node cluster)
- Efficient resource utilization
- Server can properly pool backend DB connections per gRPC channel

## Monitoring and Observability

### Logging

The driver logs channel creation at INFO level:

**Single-node:**
```
Creating StatementServiceGrpcClient for single-node
```

**Multinode:**
```
Multinode URL detected with 3 endpoints: server1:1059,server2:1059,server3:1059
Creating MultinodeStatementService for endpoints: server1:1059,server2:1059,server3:1059
MultinodeConnectionManager initialized with 3 servers
```

### Verification

To verify channel reuse, enable DEBUG logging:

```properties
logging.level.org.openjproxy.grpc.client=DEBUG
logging.level.org.openjproxy.jdbc=DEBUG
```

Look for:
- `grpcChannelOpenAndStubsInitialized`: Only appears once per server configuration
- `connect called`: Appears for every JDBC connection
- Cache hits in `MultinodeUrlParser`

## Summary

The gRPC connection lifecycle in OJP follows a **lazy-create, eager-reuse** pattern:

1. **First JDBC connection** for a server configuration creates the gRPC channel(s)
2. **All subsequent JDBC connections** reuse the existing channel(s)
3. **JDBC Connection.close()** terminates the server session but leaves channels open
4. **Channels persist** for the lifetime of the JVM process

This design provides optimal performance while maintaining correct resource management semantics expected by JDBC applications.
