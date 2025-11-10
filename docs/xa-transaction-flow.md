# XA Transaction Flow Documentation

## Overview

This document provides a detailed walkthrough of XA (eXtended Architecture) transaction flow in the OJP server, from transaction start through prepare, commit, or rollback phases. It includes references to the actual codebase methods involved at each step.

## XA Transaction Architecture

OJP implements an **XA pass-through architecture**:

- **Server role:** Connection pooling and XA operation forwarding
- **Client role:** Transaction lifecycle control and coordination
- **No transaction manager on server:** The server does NOT manage distributed transactions; it only pools connections and forwards XA calls to the underlying database

## XA Transaction Lifecycle

### Phase 1: Connection Establishment

#### Step 1.1: Client Requests XA Connection

**Client Side:**
```java
ConnectionDetails details = ConnectionDetails.newBuilder()
    .setUrl("jdbc:postgresql://localhost:5432/mydb")
    .setUser("user")
    .setPassword("pass")
    .setIsXA(true)  // Enables XA mode
    .setProperties(...)
    .build();

SessionInfo session = statementService.connect(details);
```

**Server Side Processing:**

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/StatementServiceImpl.java`

**Method:** `connect(ConnectionDetails request, StreamObserver<SessionInfo> responseObserver)`

**Lines:** ~196-226

```java
if (connectionDetails.getIsXA()) {
    // Handle XA connection - create Atomikos XA connection pool
    AtomikosXAConnectionPool xaPool = this.xaConnectionPoolMap.get(connHash);
    if (xaPool == null) {
        // Create XADataSource for the database
        String url = UrlParser.parseUrl(connectionDetails.getUrl());
        XADataSource xaDataSource = XADataSourceFactory.createXADataSource(url, connectionDetails);
        
        // Wrap XADataSource with Atomikos connection pool
        xaPool = new AtomikosXAConnectionPool(xaDataSource, connHash, poolConfig);
        this.xaConnectionPoolMap.put(connHash, xaPool);
    }
    
    // Register session and return
    this.sessionManager.registerClientUUID(connHash, connectionDetails.getClientUUID());
    // ... create SessionInfo and return
}
```

**Note:** At this point, no actual XA connection is acquired yet (lazy allocation). The pool is created and ready.

#### Step 1.2: XADataSource Creation

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/XADataSourceFactory.java`

**Method:** `createXADataSource(String url, ConnectionDetails connectionDetails)`

**Lines:** ~35-60 (main method), ~70-90 (PostgreSQL example)

```java
public static XADataSource createXADataSource(String url, ConnectionDetails connectionDetails) 
        throws SQLException {
    String lowerUrl = url.toLowerCase();
    
    if (lowerUrl.contains("postgresql")) {
        return createPostgreSQLXADataSource(url, connectionDetails);
    } else if (lowerUrl.contains("mysql")) {
        return createMySQLXADataSource(url, connectionDetails);
    }
    // ... other databases
}

private static XADataSource createPostgreSQLXADataSource(String url, ConnectionDetails connectionDetails) 
        throws SQLException {
    try {
        Class.forName("org.postgresql.xa.PGXADataSource");
        
        org.postgresql.xa.PGXADataSource xaDS = new org.postgresql.xa.PGXADataSource();
        xaDS.setUrl(url);
        xaDS.setUser(connectionDetails.getUser());
        xaDS.setPassword(connectionDetails.getPassword());
        
        return xaDS;
    } catch (ClassNotFoundException e) {
        throw new SQLException("PostgreSQL JDBC driver not found", e);
    }
}
```

**Note:** This creates the native database driver's XADataSource (e.g., `PGXADataSource` for PostgreSQL).

#### Step 1.3: Atomikos Pool Creation

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/AtomikosXAConnectionPool.java`

**Constructor:** `AtomikosXAConnectionPool(XADataSource xaDataSource, String connectionHash, Properties poolConfig)`

**Lines:** ~44-76

```java
public AtomikosXAConnectionPool(XADataSource xaDataSource, String connectionHash, Properties poolConfig) 
        throws SQLException {
    
    this.rawXADataSource = xaDataSource;
    this.resourceName = "ojp-xa-" + Math.abs(connectionHash.hashCode()) + "-" + resourceCounter.incrementAndGet();
    
    // Create AtomikosDataSourceBean
    this.atomikosDataSource = new AtomikosDataSourceBean();
    atomikosDataSource.setUniqueResourceName(resourceName);
    atomikosDataSource.setXaDataSource(xaDataSource);
    
    // Set pool sizes (from config or defaults)
    int maxPoolSize = getIntProperty(poolConfig, "ojp.connection.pool.maximumPoolSize", 20);
    int minPoolSize = getIntProperty(poolConfig, "ojp.connection.pool.minimumIdle", 5);
    
    atomikosDataSource.setMaxPoolSize(maxPoolSize);
    atomikosDataSource.setMinPoolSize(minPoolSize);
    // ... more configuration
    
    log.info("Created Atomikos XA pool '{}': maxPoolSize={}, minPoolSize={}, ...", 
            resourceName, maxPoolSize, minPoolSize);
}
```

**Note:** `AtomikosDataSourceBean` wraps the raw XADataSource and provides connection pooling.

### Phase 2: XA Connection Acquisition

#### Step 2.1: Client Executes First Statement

**Client Side:**
```java
XAConnection xaConn = xaDataSource.getXAConnection();
Connection conn = xaConn.getConnection();
Statement stmt = conn.createStatement();
stmt.executeQuery("SELECT * FROM test_table");
```

**Server Side Processing:**

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/StatementServiceImpl.java`

**Method:** `executeQuery()` or similar statement execution method

**Note:** The server detects this is an XA session and needs to borrow a connection from the pool.

#### Step 2.2: Borrow XA Connection from Pool

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/AtomikosXAConnectionPool.java`

**Method:** `borrowXAConnection(String sessionId, String branchId)`

**Lines:** ~89-115

```java
public XAConnection borrowXAConnection(String sessionId, String branchId) throws SQLException {
    String leaseKey = sessionId + ":" + branchId;
    
    // Check if already leased for this session/branch
    XAConnection existing = leasedConnections.get(leaseKey);
    if (existing != null) {
        return existing;
    }
    
    // Get XAConnection from raw XADataSource
    try {
        XAConnection xaConnection = rawXADataSource.getXAConnection();
        leasedConnections.put(leaseKey, xaConnection);
        
        log.debug("Leased new XAConnection for session/branch: {} (total leased: {})", 
                leaseKey, leasedConnections.size());
        
        return xaConnection;
    } catch (SQLException e) {
        log.error("Failed to borrow XAConnection: {}", e.getMessage());
        throw e;
    }
}
```

**Note:** Each XA branch gets its own dedicated connection (no sharing across branches). The connection is tracked by session+branch key.

### Phase 3: XA Transaction Start

#### Step 3.1: Client Starts XA Transaction

**Client Side:**
```java
XAResource xaResource = xaConn.getXAResource();
Xid xid = new MyXid(1, "global-tx-123".getBytes(), "branch-1".getBytes());
xaResource.start(xid, XAResource.TMNOFLAGS);
```

**Server Side Processing:**

The `XAResource.start()` call is **passed through** to the database's XAResource:

**Flow:**
1. Client calls `xaResource.start(xid, flags)`
2. OJP JDBC driver forwards call to server via gRPC
3. Server retrieves the XAConnection for this session
4. Server calls `xaConnection.getXAResource().start(xid, flags)` on the database connection
5. Database XAResource starts the XA transaction
6. Result is returned to client

**Note:** There is no transaction management on the server. The server simply forwards the XA operation to the database.

**Implementation Note:** The exact gRPC method for XA operations would be in `StatementServiceImpl` or a dedicated XA service. The key point is that it's a **pass-through** operation.

### Phase 4: Transaction Work (SQL Execution)

#### Step 4.1: Client Executes SQL Operations

**Client Side:**
```java
// Connection is now in XA transaction mode
PreparedStatement ps = conn.prepareStatement("INSERT INTO test_table VALUES (?, ?)");
ps.setInt(1, 1);
ps.setString(2, "Test Data");
ps.executeUpdate();
ps.close();

// More SQL operations...
Statement stmt = conn.createStatement();
stmt.executeUpdate("UPDATE test_table SET name='Updated' WHERE id=1");
stmt.close();
```

**Server Side Processing:**

All SQL operations use the same XAConnection that was borrowed in Phase 2. The connection remains leased to the session/branch throughout the transaction.

**File:** Multiple statement execution methods in `StatementServiceImpl`

**Key Methods:**
- `executeQuery()`
- `executeUpdate()`
- `execute()`
- `executeBatch()`

**Note:** Each SQL operation is executed within the XA transaction context established by `XAResource.start()`. The database maintains the transaction state.

### Phase 5: XA Transaction End

#### Step 5.1: Client Ends XA Transaction Branch

**Client Side:**
```java
xaResource.end(xid, XAResource.TMSUCCESS);
```

**Server Side Processing:**

The `XAResource.end()` call is passed through to the database:

**Flow:**
1. Client calls `xaResource.end(xid, TMSUCCESS)`
2. Server forwards to database XAResource
3. Database XAResource ends the transaction branch
4. Transaction branch is now in "prepared" or "idle" state

**Note:** After `end()`, the XA branch is detached from the connection but not yet committed or rolled back.

### Phase 6: XA Transaction Prepare (2PC Phase 1)

#### Step 6.1: Client Prepares Transaction

**Client Side:**
```java
int prepareResult = xaResource.prepare(xid);
// prepareResult can be:
// - XAResource.XA_OK: Transaction ready to commit
// - XAResource.XA_RDONLY: Read-only optimization, already committed
```

**Server Side Processing:**

The `XAResource.prepare()` call is passed through to the database:

**Flow:**
1. Client calls `xaResource.prepare(xid)`
2. Server forwards to database XAResource
3. Database performs prepare phase:
   - Writes transaction log to disk
   - Locks resources
   - Ensures transaction can be committed or rolled back
4. Database returns prepare result
5. Server forwards result back to client

**Note:** This is the "prepare" phase of two-phase commit (2PC). The database guarantees it can commit or rollback the transaction.

**File Reference:** XA operation forwarding would be in the gRPC service method that handles `prepare()` calls.

### Phase 7: XA Transaction Commit (2PC Phase 2)

#### Step 7.1: Client Commits Transaction

**Client Side:**
```java
if (prepareResult == XAResource.XA_OK) {
    // Two-phase commit
    xaResource.commit(xid, false);  // false = two-phase
} else if (prepareResult == XAResource.XA_RDONLY) {
    // Read-only optimization, no commit needed
}
```

**Server Side Processing:**

The `XAResource.commit()` call is passed through to the database:

**Flow:**
1. Client calls `xaResource.commit(xid, false)`
2. Server forwards to database XAResource
3. Database commits the prepared transaction:
   - Applies changes to database
   - Releases locks
   - Removes transaction from log
4. Database returns success/failure
5. Server forwards result back to client
6. **Server returns XAConnection to pool** (after commit completes)

**Connection Return After Commit:**

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/AtomikosXAConnectionPool.java`

**Method:** `returnXAConnection(String sessionId, String branchId)`

**Lines:** ~125-141

```java
public void returnXAConnection(String sessionId, String branchId) throws SQLException {
    String leaseKey = sessionId + ":" + branchId;
    
    XAConnection xaConnection = leasedConnections.remove(leaseKey);
    if (xaConnection != null) {
        try {
            xaConnection.close(); // Returns to Atomikos pool
            log.debug("Returned XAConnection for session/branch: {} (remaining leased: {})", 
                    leaseKey, leasedConnections.size());
        } catch (SQLException e) {
            log.error("Error returning XAConnection: {}", e.getMessage());
            throw e;
        }
    }
}
```

**Note:** Closing the XAConnection returns it to the Atomikos pool for reuse.

#### Step 7.2: One-Phase Commit Optimization

**Client Side:**
```java
// If only one resource involved, can use one-phase commit
xaResource.commit(xid, true);  // true = one-phase
```

**Server Side Processing:**

Similar to two-phase commit, but database skips the prepare phase and commits directly.

**Note:** One-phase commit is an optimization when only one resource is involved in the distributed transaction.

### Phase 8: XA Transaction Rollback

#### Step 8.1: Client Rolls Back Transaction

**Client Side:**
```java
// Rollback can occur after start, end, or prepare
xaResource.rollback(xid);
```

**Server Side Processing:**

The `XAResource.rollback()` call is passed through to the database:

**Flow:**
1. Client calls `xaResource.rollback(xid)`
2. Server forwards to database XAResource
3. Database rolls back the transaction:
   - Discards changes
   - Releases locks
   - Removes transaction from log
4. Database returns success/failure
5. Server forwards result back to client
6. **Server returns XAConnection to pool** (after rollback completes)

**Note:** Rollback can happen at any point after `start()` - before `end()`, after `end()`, or even after `prepare()`.

### Phase 9: Connection Cleanup

#### Step 9.1: Session Termination

**Client Side:**
```java
xaConn.close();  // Close XA connection
```

**Server Side Processing:**

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/StatementServiceImpl.java`

**Method:** `disconnect()` or session cleanup

The server ensures:
1. Any uncommitted transactions are rolled back
2. XAConnection is returned to pool
3. Session is removed from session manager

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/AtomikosXAConnectionPool.java`

**Method:** `close()` (pool shutdown)

**Lines:** ~179-196

```java
public void close() {
    log.info("Closing Atomikos XA pool '{}'...", resourceName);
    
    // Close any remaining leased connections
    for (var entry : leasedConnections.entrySet()) {
        try {
            entry.getValue().close();
            log.warn("Force-closed leaked XAConnection for: {}", entry.getKey());
        } catch (SQLException e) {
            log.error("Error closing leaked XAConnection: {}", e.getMessage());
        }
    }
    leasedConnections.clear();
    
    // Close Atomikos datasource
    atomikosDataSource.close();
    log.info("Atomikos XA pool '{}' closed", resourceName);
}
```

**Note:** Pool shutdown force-closes any leaked connections and cleans up Atomikos resources.

## XA Transaction Flow Diagram

```
Client                    OJP Server                   Database
  |                           |                            |
  |-- connect(isXA=true) ---->|                            |
  |                           |-- create XADataSource ---->|
  |                           |-- create Atomikos pool     |
  |<------ SessionInfo -------|                            |
  |                           |                            |
  |-- executeQuery() -------->|                            |
  |                           |-- borrowXAConnection() --> |
  |                           |<-- XAConnection ---------- |
  |                           |-- execute SQL ------------>|
  |<------ ResultSet ---------|<-- results ---------------|
  |                           |                            |
  |-- xaResource.start() ---->|                            |
  |                           |-- forward start() -------->|
  |<------ success -----------|<-- success ---------------|
  |                           |                            |
  |-- execute SQL ops ------->|                            |
  |                           |-- forward SQL ------------>|
  |<------ results -----------|<-- results ---------------|
  |                           |                            |
  |-- xaResource.end() ------>|                            |
  |                           |-- forward end() ---------->|
  |<------ success -----------|<-- success ---------------|
  |                           |                            |
  |-- xaResource.prepare() -->|                            |
  |                           |-- forward prepare() ------>|
  |                           |<-- XA_OK ------------------|
  |<------ XA_OK -------------|                            |
  |                           |                            |
  |-- xaResource.commit() --->|                            |
  |                           |-- forward commit() ------->|
  |                           |<-- success ---------------|
  |<------ success -----------|                            |
  |                           |-- returnXAConnection() ----|
  |                           |                            |
  |-- close() --------------->|                            |
  |                           |-- session cleanup          |
  |<------ done --------------|                            |
```

## Key Implementation Files and Methods

### Connection Management

| Phase | File | Method | Lines |
|-------|------|--------|-------|
| Connection request | `StatementServiceImpl.java` | `connect()` | ~196-226 |
| XADataSource creation | `XADataSourceFactory.java` | `createXADataSource()` | ~35-60 |
| Pool creation | `AtomikosXAConnectionPool.java` | Constructor | ~44-76 |
| Connection borrow | `AtomikosXAConnectionPool.java` | `borrowXAConnection()` | ~89-115 |
| Connection return | `AtomikosXAConnectionPool.java` | `returnXAConnection()` | ~125-141 |

### XA Operations (Pass-Through)

| Operation | Server Behavior | Database Processing |
|-----------|-----------------|---------------------|
| `XAResource.start()` | Forward to database XAResource | Begin XA transaction branch |
| `XAResource.end()` | Forward to database XAResource | End XA transaction branch |
| `XAResource.prepare()` | Forward to database XAResource | Prepare transaction (2PC phase 1) |
| `XAResource.commit()` | Forward to database XAResource | Commit transaction (2PC phase 2) |
| `XAResource.rollback()` | Forward to database XAResource | Rollback transaction |
| `XAResource.recover()` | Forward to database XAResource | Recover prepared transactions |

**Note:** All XA operations are **pass-through** - the server does not manage transactions, it only forwards operations to the database.

### Dynamic Pool Sizing

| Phase | File | Method | Lines |
|-------|------|--------|-------|
| Size calculation | `DynamicAtomikosPoolManager.java` | `calculatePerServerSizes()` | ~135-151 |
| Pool creation | `DynamicAtomikosPoolManager.java` | `getOrCreatePool()` | ~85-113 |
| Pool recreation | `DynamicAtomikosPoolManager.java` | `recreatePoolForNewMembership()` | ~170-208 |

## Error Handling and Recovery

### Transaction Timeout

**Client Side:**
```java
xaResource.setTransactionTimeout(300); // 5 minutes
```

**Server Side:**
- Timeout is passed through to database XAResource
- Database enforces the timeout
- If timeout occurs, database automatically rolls back the transaction

### Connection Failure

**Scenario:** Database connection fails during transaction

**Server Behavior:**
1. SQLException is caught
2. Connection is removed from pool
3. Error is returned to client
4. Client's transaction manager handles recovery

### Prepare Failure

**Scenario:** `prepare()` returns error instead of XA_OK

**Server Behavior:**
- Forward error to client
- Client decides whether to retry or rollback
- XAConnection remains leased until explicit rollback or timeout

### Transaction Recovery

**Scenario:** Server crashes during distributed transaction

**Recovery Process:**
1. Database maintains prepared transaction in log
2. Client transaction manager calls `XAResource.recover()` after reconnection
3. Server forwards `recover()` to database
4. Database returns list of prepared XIDs
5. Client decides to commit or rollback each recovered transaction

## Performance Considerations

### Connection Pooling

- **Pool size per server:** Dynamically calculated based on cluster size
- **Connection reuse:** XAConnections are returned to pool after commit/rollback
- **Lazy allocation:** Connections not acquired until first SQL execution

### Pass-Through Design

- **Low overhead:** No transaction state maintained on server
- **Scalability:** Server does not become bottleneck
- **Stateless:** Each XA operation is independent (except connection leasing)

### Pool Recreation

- **Minimal disruption:** Active connections continue during recreation
- **Fast transition:** New pools created in < 1 second typically
- **Graceful cleanup:** Old pools wait for connection returns before closing

## Related Documentation

- [Atomikos Pool Sizing](atomikos-pool-sizing.md) - Dynamic pool size calculations
- [ATOMIKOS_XA_INTEGRATION.md](../ATOMIKOS_XA_INTEGRATION.md) - Atomikos integration details
- [ADDING_DATABASE_XA_SUPPORT.md](../ADDING_DATABASE_XA_SUPPORT.md) - How to add XA support for new databases

## Summary

The OJP XA transaction flow implements a **pass-through architecture** where:

1. ✅ **Server pools XA connections** using Atomikos for efficient resource management
2. ✅ **All XA operations are forwarded** to the database without server-side transaction management
3. ✅ **One connection per XA branch** to ensure transaction isolation
4. ✅ **Dynamic pool sizing** adjusts to cluster membership changes
5. ✅ **Client controls transaction lifecycle** including prepare, commit, and rollback
6. ✅ **Connection pooling** provides performance benefits while maintaining XA semantics

This design keeps the server lightweight and scalable while providing robust XA transaction support for distributed transactions.
