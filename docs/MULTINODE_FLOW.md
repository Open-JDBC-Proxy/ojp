# Multinode Architecture Flow Documentation

This document describes the exact flow of method calls and data in the Open-J-Proxy multinode implementation, covering session establishment, server affinity, round-robin selection, and datasource management.

## Table of Contents
1. [Client-Side Flow](#client-side-flow)
2. [Server-Side Flow](#server-side-flow)
3. [Session Establishment and Binding](#session-establishment-and-binding)
4. [Server Selection (Affinity vs Round-Robin)](#server-selection-affinity-vs-round-robin)
5. [Datasource Creation and Retrieval](#datasource-creation-and-retrieval)
6. [TargetServer Field and Session Stickiness](#targetserver-field-and-session-stickiness)

---

## TargetServer Field and Session Stickiness

### Overview

The `targetServer` field in `SessionInfo` enables explicit server identification and binding for session stickiness in multinode deployments. This field ensures that once a session is established on a specific server, all subsequent operations for that session are routed to the same server.

### Format

The `targetServer` field uses the format: `host:port`

Example: `server1:10591`

### Binding Lifecycle

#### 1. Session Creation and Binding

When a client connects to a multinode cluster:

1. **Server Populates targetServer**: Each server that receives a `connect()` request populates the `targetServer` field in the `SessionInfo` response with its own `host:port`.

2. **Client Reads and Binds**: Upon receiving the response, the client reads the `targetServer` field and binds the session:
   ```java
   if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
       String targetServer = sessionInfo.getTargetServer();
       if (targetServer != null && !targetServer.isEmpty()) {
           connectionManager.bindSession(sessionInfo.getSessionUUID(), targetServer);
       }
   }
   ```

3. **Binding Storage**: The binding is stored in a `ConcurrentHashMap<String, ServerEndpoint>` keyed by `sessionUUID`.

#### 2. Session-Bound Request Routing

When executing operations with a bound session:

1. **Client Looks Up Binding**: Before making an RPC, the client checks if the session is bound:
   ```java
   String targetServer = connectionManager.getBoundTargetServer(sessionUUID);
   ```

2. **Route to Bound Server**: If a binding exists, the RPC is routed to that specific server. If no binding exists, round-robin selection is used.

3. **Server Echoes targetServer**: The server includes the `targetServer` field in all `SessionInfo` responses, maintaining the binding throughout the session lifecycle.

#### 3. Session Termination and Unbinding

When a session is terminated:

1. **Client Calls terminateSession**: The client sends a `terminateSession` RPC to the server(s) that received the original `connect()` call.

2. **Unbinding**: The client removes the session binding:
   ```java
   connectionManager.unbindSession(sessionUUID);
   ```

3. **Cleanup**: The session-to-server mapping is removed from the connection manager's internal map.

### Backward Compatibility

The `targetServer` field is optional and backward compatible:

- **New clients with old servers**: Clients handle missing `targetServer` gracefully by falling back to existing session binding mechanisms.
- **Old clients with new servers**: Servers populate `targetServer`, but older clients simply ignore it.

### Error Handling

- **Bound Server Unavailable**: If a session is bound to a server that becomes unavailable, the client throws an exception rather than silently failing over to another server. This enforces strict session stickiness and prevents data consistency issues.

- **Invalid targetServer**: If the client receives a `targetServer` that doesn't match any configured endpoint, the binding is not created and the session uses round-robin routing.

### Thread Safety

All session binding operations (`bindSession`, `getBoundTargetServer`, `unbindSession`) are thread-safe, using `ConcurrentHashMap` for internal storage.

---

## Client-Side Flow

### 1. Initial Connection Setup

When a client creates a JDBC connection using a multinode URL:

```
jdbc:ojp[server1:port1,server2:port2,...]_dbtype://dbhost:dbport/dbname
```

**Method Call Sequence:**

1. **`Driver.connect(String url, Properties info)`** (ojp-jdbc-driver)
   - Parses the URL using `UrlParser` and `MultinodeUrlParser`
   - Detects multinode URL pattern (comma-separated endpoints in brackets)
   - Creates or retrieves cached `MultinodeStatementService`

2. **`MultinodeStatementService` constructor**
   - Creates `MultinodeConnectionManager` with parsed server endpoints
   - Initializes `ConcurrentHashMap<ServerEndpoint, StatementServiceGrpcClient>` for client mapping

3. **`MultinodeConnectionManager` constructor**
   - Stores list of server endpoints
   - Calls `initializeConnections()` to set up gRPC infrastructure

4. **`MultinodeConnectionManager.initializeConnections()`**
   ```java
   // For EACH server in the endpoint list:
   for (ServerEndpoint endpoint : serverEndpoints) {
       createChannelAndStub(endpoint);  // Creates gRPC channel + stubs
   }
   ```
   - Creates `ManagedChannel` for each server via `GrpcChannelFactory`
   - Creates blocking and async stubs for each server
   - Stores in `channelMap`: `Map<ServerEndpoint, ChannelAndStub>`
   - Marks server as healthy/unhealthy based on success

5. **`Driver.connect()` continues - Session Establishment**
   - Calls `statementService.connect(connectionDetails)`
   - Returns `org.openjproxy.jdbc.Connection` object with session info

### 2. Session Establishment

**`MultinodeStatementService.connect(ConnectionDetails details)`**

```java
// Delegates to MultinodeConnectionManager
SessionInfo sessionInfo = connectionManager.connect(details);
return sessionInfo;
```

**`MultinodeConnectionManager.connect(ConnectionDetails details)`**

```java
// Try to connect to all servers (to ensure datasources are created on all nodes)
for (ServerEndpoint server : serverEndpoints) {
    // Get pre-initialized channel and stub for this server
    ChannelAndStub channelAndStub = channelMap.get(server);
    
    // Make gRPC call to establish session
    SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);
    
    // NEW: Session binding using targetServer field
    if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
        String targetServer = sessionInfo.getTargetServer();
        if (targetServer != null && !targetServer.isEmpty()) {
            // Use the server-returned targetServer as authoritative for binding
            bindSession(sessionInfo.getSessionUUID(), targetServer);
            log.info("Session {} bound to target server {} (from response)", 
                    sessionInfo.getSessionUUID(), targetServer);
        } else {
            // Fallback: bind using current server endpoint if targetServer not provided
            String serverAddress = server.getHost() + ":" + server.getPort();
            sessionToServerMap.put(sessionInfo.getSessionUUID(), server);
            log.info("Session {} bound to server {} (fallback, no targetServer in response)", 
                    sessionInfo.getSessionUUID(), serverAddress);
        }
    } else {
        // No sessionUUID - no binding, operations will use round-robin
        log.info("No sessionUUID present, session not bound to specific server");
    }
}

return primarySessionInfo;
```

**Key Points:** 
- Session binding now uses the `targetServer` field from the server response as authoritative.
- The server populates `targetServer` with its `host:port` in all SessionInfo responses.
- Client reads `targetServer` and calls `bindSession(sessionUUID, targetServer)` to establish the binding.
- If `targetServer` is missing, falls back to direct ServerEndpoint binding for backward compatibility.

### 3. Executing Operations (Query/Update)

**`Statement.executeQuery()` or `Statement.executeUpdate()`**

```java
// Client JDBC Statement calls MultinodeStatementService
MultinodeStatementService.executeQuery(sessionInfo, sql);
```

**`MultinodeStatementService.executeQuery(SessionInfo sessionInfo, String sql)`**

```java
// Uses helper method with session stickiness
return executeWithSessionStickiness(sessionInfo, 
    (client) -> client.executeQuery(sessionInfo, sql, fetchSize));
```

**`MultinodeStatementService.executeWithSessionStickiness(SessionInfo, operation)`**

```java
// Extract session key (sessionUUID if present, else null)
String sessionKey = null;
if (sessionInfo != null && sessionInfo.getSessionUUID() != null 
    && !sessionInfo.getSessionUUID().isEmpty()) {
    sessionKey = sessionInfo.getSessionUUID();
}

// SERVER SELECTION: This is where affinity is determined
ServerEndpoint server = connectionManager.affinityServer(sessionKey);

// Get client for selected server
StatementServiceGrpcClient client = getClient(server);

// Execute operation
return operation.apply(client);
```

---

## Server Selection (Affinity vs Round-Robin)

### `MultinodeConnectionManager.affinityServer(String sessionKey)`

**This is the core routing logic:**

```java
public ServerEndpoint affinityServer(String sessionKey) throws SQLException {
    if (sessionKey == null || sessionKey.isEmpty()) {
        // NO SESSION: Use round-robin
        return selectServer();  // Round-robin selection
    }
    
    // SESSION EXISTS: Use session stickiness
    ServerEndpoint server = sessionToServerMap.get(sessionKey);
    
    if (server == null) {
        // Session not found (shouldn't happen in normal flow)
        throw new SQLException("No server found for session: " + sessionKey);
    }
    
    if (!server.isHealthy()) {
        // Session's server is down - throw exception
        throw new SQLException("Server for session " + sessionKey + " is unavailable");
    }
    
    return server;
}
```

**Round-Robin Selection (`selectServer()`):**

```java
public ServerEndpoint selectServer() throws SQLException {
    ServerEndpoint server = selectHealthyServer();
    if (server == null) {
        throw new SQLException("No healthy servers available");
    }
    return server;
}

private ServerEndpoint selectHealthyServer() {
    List<ServerEndpoint> healthyServers = serverEndpoints.stream()
        .filter(ServerEndpoint::isHealthy)
        .collect(Collectors.toList());
    
    if (healthyServers.isEmpty()) {
        return null;
    }
    
    // Round-robin: increment counter and mod by number of healthy servers
    int index = roundRobinCounter.getAndIncrement() % healthyServers.size();
    return healthyServers.get(index);
}
```

**Summary:**
- **With sessionUUID**: Operation goes to the specific server where session was created (sticky)
- **Without sessionUUID**: Operation goes to next server in round-robin rotation
- **Server down**: Exception thrown if trying to use session on unavailable server

---

## Server-Side Flow

### 1. Session Creation (connect request)

**`StatementServiceImpl.connect(ConnectRequest request)`** (ojp-server)

```java
@Override
public void connect(ConnectRequest request, StreamObserver<SessionInfo> responseObserver) {
    try {
        ConnectionDetails details = request.getConnectionDetails();
        
        // Create connection hash from connection parameters
        String connHash = ConnectionHashGenerator.generate(
            details.getJdbcUrl(),
            details.getUsername(),
            details.getOjpProperties()
        );
        
        // CREATE OR RETRIEVE DATASOURCE for this connection hash
        HikariDataSource dataSource = dataSourceManager.getOrCreateDataSource(
            connHash, 
            details
        );
        
        // Get a connection from the pool
        Connection connection = dataSource.getConnection();
        
        // MAY create a session UUID (implementation dependent)
        String sessionUUID = sessionManager.createSession(connection);
        
        // Build response
        SessionInfo sessionInfo = SessionInfo.newBuilder()
            .setSessionUUID(sessionUUID != null ? sessionUUID : "")
            .setConnHash(connHash)
            .setClientUUID(details.getClientUUID())
            .build();
        
        responseObserver.onNext(sessionInfo);
        responseObserver.onCompleted();
        
    } catch (Exception e) {
        responseObserver.onError(e);
    }
}
```

**Key Points:**
- **connHash**: Identifies the datasource/connection pool (based on JDBC URL + credentials)
- **sessionUUID**: Identifies a specific session (may or may not be created depending on server logic)
- Server maintains a pool of database connections per unique `connHash`

### 2. Executing Operations (executeQuery/executeUpdate)

**`StatementServiceImpl.executeQuery(StatementRequest request)`**

```java
@Override
public void executeQuery(StatementRequest request, StreamObserver<QueryResult> responseObserver) {
    try {
        SessionInfo sessionInfo = request.getSessionInfo();
        String sql = request.getSql();
        
        // RETRIEVE CONNECTION based on session info
        Connection connection;
        
        if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
            // Has session UUID - retrieve specific session connection
            connection = sessionManager.getConnection(sessionInfo.getSessionUUID());
        } else {
            // No session UUID - get connection from pool using connHash
            String connHash = sessionInfo.getConnHash();
            HikariDataSource dataSource = dataSourceManager.getDataSource(connHash);
            
            if (dataSource == null) {
                throw new SQLException("No datasource found for connection hash: " + connHash);
            }
            
            connection = dataSource.getConnection();
            
            // MAY create session on-the-fly if needed
            String newSessionUUID = sessionManager.createSession(connection);
            // (Would need to update sessionInfo in response to inform client)
        }
        
        // Execute SQL
        PreparedStatement stmt = connection.prepareStatement(sql);
        ResultSet rs = stmt.executeQuery();
        
        // Build and send response
        QueryResult result = buildQueryResult(rs);
        responseObserver.onNext(result);
        responseObserver.onCompleted();
        
    } catch (Exception e) {
        responseObserver.onError(e);
    }
}
```

---

## Datasource Creation and Retrieval

### Server-Side DataSource Management

**`DataSourceManager.getOrCreateDataSource(String connHash, ConnectionDetails details)`**

```java
public HikariDataSource getOrCreateDataSource(String connHash, ConnectionDetails details) {
    // Thread-safe retrieval or creation
    return dataSourceCache.computeIfAbsent(connHash, key -> {
        // Create new HikariCP datasource
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(details.getJdbcUrl());
        config.setUsername(details.getUsername());
        config.setPassword(details.getPassword());
        
        // Apply OJP properties (pool size, timeouts, etc.)
        applyOjpProperties(config, details.getOjpProperties());
        
        HikariDataSource dataSource = new HikariDataSource(config);
        
        log.info("Created datasource for connHash: {}", connHash);
        return dataSource;
    });
}
```

**Connection Hash Generation:**

The `connHash` uniquely identifies a datasource and is generated from:
- JDBC URL (including database host, port, database name)
- Username
- OJP-specific properties (if any)

**Example:** Two clients connecting to the same database with same credentials will share the same datasource (connection pool).

### DataSource Lifecycle

1. **Creation**: On first `connect()` call with new connection parameters
2. **Reuse**: Subsequent connections with same parameters retrieve existing datasource
3. **Pooling**: Each datasource manages a HikariCP connection pool
4. **Cleanup**: DataSources are closed when OJP server shuts down

---

## Complete Flow Example

### Scenario: Two Operations - First with Session, Second without

**Client Code:**
```java
// Connect
Connection conn = DriverManager.getConnection(
    "jdbc:ojp[server1:10591,server2:10592]_postgresql://dbhost:5432/mydb",
    "user", "password"
);

// First operation
Statement stmt = conn.createStatement();
stmt.executeUpdate("CREATE TABLE test (id INT)");  // May establish session

// Second operation  
stmt.executeQuery("SELECT * FROM test");  // Uses session if established
```

**Flow:**

1. **Connection Establishment:**
   - Client: `Driver.connect()` → `MultinodeStatementService.connect()` → `MultinodeConnectionManager.connect()`
   - Client: Round-robin selects `server1`
   - Client: gRPC call `server1.connect(connectionDetails)`
   - Server1: Creates datasource with connHash `abc123`
   - Server1: Gets connection from pool
   - Server1: May create sessionUUID `session-uuid-1` (or not, depending on implementation)
   - Server1: Returns `SessionInfo{sessionUUID="session-uuid-1", connHash="abc123"}`
   - Client: If sessionUUID present, binds to `server1`: `sessionToServerMap.put("session-uuid-1", server1)`

2. **First Operation (CREATE TABLE):**
   - Client: `stmt.executeUpdate()` → `MultinodeStatementService.executeUpdate(sessionInfo, sql)`
   - Client: Calls `affinityServer("session-uuid-1")` → returns `server1` (session sticky)
   - Client: gRPC call `server1.executeUpdate(sessionInfo, sql)`
   - Server1: Looks up connection by sessionUUID `session-uuid-1`
   - Server1: Executes SQL on connection
   - Server1: Returns result

3. **Second Operation (SELECT):**
   - Client: `stmt.executeQuery()` → `MultinodeStatementService.executeQuery(sessionInfo, sql)`
   - Client: Calls `affinityServer("session-uuid-1")` → returns `server1` (same server)
   - Client: gRPC call `server1.executeQuery(sessionInfo, sql)`
   - Server1: Looks up connection by sessionUUID
   - Server1: Executes SQL and returns results

**If No SessionUUID:**

If server didn't create a sessionUUID (returned empty):

1. **Connection:** Session NOT bound to any server
2. **First Operation:** `affinityServer(null)` → round-robin selects `server1`
3. **Second Operation:** `affinityServer(null)` → round-robin selects `server2` (different!)

This is where connHash was previously used as a fallback, but that has been removed per PR review feedback. Now operations without a session can legitimately go to different servers.

---

## Summary

### Session Binding
- **Bound**: When `sessionUUID` is present in `SessionInfo`
  - Client stores `sessionToServerMap.put(sessionUUID, server)`
  - All operations use `affinityServer(sessionUUID)` → returns same server
  
- **Unbound**: When `sessionUUID` is empty/null
  - No entry in `sessionToServerMap`
  - All operations use `affinityServer(null)` → returns round-robin server
  - Server can create session on-the-fly if needed

### DataSource Management
- **Server-side only**: Managed by `DataSourceManager`
- **Key**: `connHash` (derived from JDBC URL + credentials)
- **Pooled**: Each datasource is a HikariCP connection pool
- **Shared**: Multiple clients with same connection parameters share same pool

### Round-Robin
- **Trigger**: Operations without session, or new connections
- **Method**: `selectHealthyServer()` uses `AtomicInteger` counter
- **Health**: Only selects from healthy servers (automatically skips failed servers)
