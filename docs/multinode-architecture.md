# OJP Multinode Architecture

## Overview
This document describes the multinode functionality in Open-J-Proxy (OJP), detailing the flow of operations from client to server, session management, load balancing, and datasource handling.

## Architecture Components

### Client Side

#### 1. Driver (org.openjproxy.jdbc.Driver)
Entry point for JDBC connections.

**Key Methods:**
- `connect(String url, Properties info)` - Detects multinode URLs and creates appropriate service

**Flow:**
```java
// 1. Parse JDBC URL
// Example: jdbc:ojp[server1:port1,server2:port2]_postgresql://db:5432/defaultdb

// 2. Detect multinode configuration
if (url contains multiple servers in brackets) {
    // Create MultinodeStatementService
} else {
    // Create single-node StatementServiceGrpcClient
}

// 3. Call connect() on the statement service
SessionInfo sessionInfo = statementService.connect(connectionDetails);

// 4. Create Connection object with session info
return new Connection(sessionInfo, statementService);
```

#### 2. MultinodeStatementService
Implements the StatementService interface for multinode setups.

**Initialization:**
```java
public MultinodeStatementService(String url, List<String> serverEndpoints) {
    // 1. Create MultinodeConnectionManager with all server endpoints
    this.connectionManager = new MultinodeConnectionManager(endpoints);
    
    // 2. Initialize gRPC channels to ALL servers upfront
    // (done in MultinodeConnectionManager constructor)
}
```

**Key Methods:**
- `connect()` - Delegates to MultinodeConnectionManager
- `executeUpdate()`, `executeQuery()`, etc. - Use executeWithSessionStickiness()

**Operation Flow:**
```java
// All operations follow this pattern:
public ResultType someOperation(SessionInfo session, params) {
    return executeWithSessionStickiness(session, (client) -> {
        return client.someOperation(session, params);
    });
}
```

#### 3. MultinodeConnectionManager
Manages connections to multiple OJP servers with load balancing and session stickiness.

**Initialization (Constructor):**
```java
public MultinodeConnectionManager(List<ServerEndpoint> endpoints) {
    // 1. Store all server endpoints
    this.serverEndpoints = endpoints;
    
    // 2. Initialize gRPC channels and stubs to ALL servers
    for (ServerEndpoint endpoint : serverEndpoints) {
        ManagedChannel channel = GrpcChannelFactory.createChannel(endpoint);
        StatementServiceBlockingStub blockingStub = StatementServiceGrpc.newBlockingStub(channel);
        StatementServiceStub asyncStub = StatementServiceGrpc.newStub(channel);
        channelMap.put(endpoint, new ChannelAndStub(channel, blockingStub, asyncStub));
    }
    // All servers are now ready for use
}
```

**connect() Method - Session Establishment:**
```java
public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
    // 1. SELECT A HEALTHY SERVER (Round-Robin)
    ServerEndpoint selectedServer = selectHealthyServer();
    // Uses atomic counter for round-robin: servers[counter++ % servers.length]
    
    // 2. GET PRE-INITIALIZED CHANNEL/STUB
    ChannelAndStub channelAndStub = channelMap.get(selectedServer);
    
    // 3. MAKE gRPC CONNECT CALL TO SELECTED SERVER
    SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);
    
    // 4. SESSION BINDING (if sessionUUID is present)
    if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
        // Bind session to this specific server for session stickiness
        sessionToServerMap.put(sessionInfo.getSessionUUID(), selectedServer);
        log.info("Session {} bound to server {}", sessionUUID, selectedServer);
    } else {
        // No session ID - operations will use round-robin
        log.info("No sessionUUID present, session not bound to specific server");
    }
    
    return sessionInfo;
}
```

**affinityServer() Method - Server Selection for Operations:**
```java
public ServerEndpoint affinityServer(String sessionKey) throws SQLException {
    if (sessionKey == null || sessionKey.isEmpty()) {
        // NO SESSION KEY: Use round-robin load balancing
        return selectHealthyServer();
    } else {
        // SESSION KEY PROVIDED: Use session stickiness
        ServerEndpoint boundServer = sessionToServerMap.get(sessionKey);
        
        if (boundServer == null) {
            // Session not bound yet - use round-robin
            // (Server will create session on-demand if needed)
            return selectHealthyServer();
        }
        
        if (!boundServer.isHealthy()) {
            // Bound server is unhealthy - throw exception
            // We enforce session stickiness - session cannot be moved
            throw new SQLException("Server for session " + sessionKey + " is unavailable");
        }
        
        return boundServer;
    }
}
```

**getChannelAndStub() Method:**
```java
public ChannelAndStub getChannelAndStub(String sessionKey) throws SQLException {
    // 1. Get server based on session affinity
    ServerEndpoint server = affinityServer(sessionKey);
    
    // 2. Return pre-initialized channel/stub for that server
    return channelMap.get(server);
}
```

**executeWithSessionStickiness() Pattern (in MultinodeStatementService):**
```java
private <T> T executeWithSessionStickiness(SessionInfo session, 
                                          Function<StatementServiceGrpcClient, T> operation) {
    // 1. Extract session key
    String sessionKey = session.getSessionUUID();
    
    // 2. Get appropriate server (sticky or round-robin)
    ChannelAndStub channelAndStub = connectionManager.getChannelAndStub(sessionKey);
    
    // 3. Get or create client for this server
    StatementServiceGrpcClient client = getClient(serverEndpoint);
    
    // 4. Execute operation on selected client/server
    return operation.apply(client);
}
```

### Server Side (org.openjproxy.grpc.server.StatementServiceImpl)

#### Session and Datasource Management

**connect() Method - Server Side:**
```java
public SessionInfo connect(ConnectionDetails connectionDetails) {
    // 1. Generate connection hash from connection parameters
    String connHash = generateConnectionHash(connectionDetails);
    
    // 2. Get or create datasource for these connection parameters
    HikariDataSource dataSource = getOrCreateDataSource(connHash, connectionDetails);
    
    // 3. Create session info
    SessionInfo sessionInfo = SessionInfo.newBuilder()
        .setConnHash(connHash)  // Always populated
        .setSessionUUID(uuid)   // May be empty if no explicit session created
        .setClientUUID(clientId)
        .build();
    
    // 4. Store session mapping
    sessionToDataSourceMap.put(connHash, dataSource);
    
    return sessionInfo;
}
```

**Datasource Creation:**
```java
private HikariDataSource getOrCreateDataSource(String connHash, ConnectionDetails details) {
    // 1. Check if datasource already exists for this connection hash
    HikariDataSource existingDs = dataSourceCache.get(connHash);
    if (existingDs != null) {
        return existingDs;
    }
    
    // 2. Create new HikariCP datasource
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(details.getJdbcUrl());
    config.setUsername(details.getUsername());
    config.setPassword(details.getPassword());
    config.setMaximumPoolSize(30);
    // ... other pool settings
    
    HikariDataSource newDs = new HikariDataSource(config);
    
    // 3. Cache datasource
    dataSourceCache.put(connHash, newDs);
    
    return newDs;
}
```

**executeUpdate() - Server Side:**
```java
public ExecuteUpdateResponse executeUpdate(ExecuteUpdateRequest request) {
    // 1. Extract session info from request
    SessionInfo sessionInfo = request.getSessionInfo();
    String connHash = sessionInfo.getConnHash();
    
    // 2. Retrieve datasource for this connection
    HikariDataSource dataSource = dataSourceCache.get(connHash);
    if (dataSource == null) {
        throw new SQLException("No datasource found for connection hash: " + connHash);
    }
    
    // 3. Get connection from pool
    Connection conn = dataSource.getConnection();
    
    // 4. Execute SQL
    Statement stmt = conn.createStatement();
    int rowCount = stmt.executeUpdate(request.getSql());
    
    // 5. Return response
    return ExecuteUpdateResponse.newBuilder()
        .setRowCount(rowCount)
        .build();
}
```

## Complete Flow Diagrams

### Initial Connection Flow

```
Client                  MultinodeStatementService    MultinodeConnectionManager      OJP Server 1    OJP Server 2
  |                              |                            |                           |               |
  |--DriverManager.getConnection()-->|                        |                           |               |
  |                              |                            |                           |               |
  |                              |--new MultinodeConnectionManager(endpoints)-->          |               |
  |                              |                            |                           |               |
  |                              |                            |--initializeConnections()--|               |
  |                              |                            |                           |               |
  |                              |                            |--createChannel(server1)-->|               |
  |                              |                            |<--channel1---------------|               |
  |                              |                            |                           |               |
  |                              |                            |--createChannel(server2)------------------>|
  |                              |                            |<--channel2---------------------------------|
  |                              |                            |                           |               |
  |                              |<--connectionManager--------|                           |               |
  |                              |                            |                           |               |
  |                              |--connect(connDetails)----->|                           |               |
  |                              |                            |                           |               |
  |                              |                            |--selectHealthyServer()    |               |
  |                              |                            |  (Round-robin: returns    |               |
  |                              |                            |   server1 on first call)  |               |
  |                              |                            |                           |               |
  |                              |                            |--connect(connDetails)---->|               |
  |                              |                            |                           |               |
  |                              |                            |                           | (Server creates/gets datasource)
  |                              |                            |                           | (Generates connHash)
  |                              |                            |                           | (May or may not create sessionUUID)
  |                              |                            |                           |               |
  |                              |                            |<--SessionInfo-------------|               |
  |                              |                            |   (sessionUUID: empty,    |               |
  |                              |                            |    connHash: "abc123")    |               |
  |                              |                            |                           |               |
  |                              |                            |--sessionToServerMap.put() |               |
  |                              |                            |  (Only if sessionUUID     |               |
  |                              |                            |   is present)             |               |
  |                              |                            |                           |               |
  |                              |<--SessionInfo--------------|                           |               |
  |<--Connection-----------------|                            |                           |               |
```

### Operation with Session Stickiness (sessionUUID present)

```
Client                  MultinodeStatementService    MultinodeConnectionManager      OJP Server 1    OJP Server 2
  |                              |                            |                           |               |
  |--stmt.executeUpdate(sql)--->|                            |                           |               |
  |                              |                            |                           |               |
  |                              |--executeWithSessionStickiness(session)-->             |               |
  |                              |                            |                           |               |
  |                              |                            |--affinityServer(sessionUUID)              |
  |                              |                            |                           |               |
  |                              |                            |--sessionToServerMap.get(sessionUUID)      |
  |                              |                            |  (Returns server1)        |               |
  |                              |                            |                           |               |
  |                              |                            |--getChannelAndStub(server1)               |
  |                              |                            |<--channelAndStub----------|               |
  |                              |                            |                           |               |
  |                              |<--channelAndStub-----------|                           |               |
  |                              |                            |                           |               |
  |                              |--client.executeUpdate()-------------------------------->|               |
  |                              |                            |                           |               |
  |                              |                            |                           | (Retrieves datasource by connHash)
  |                              |                            |                           | (Executes SQL)
  |                              |                            |                           |               |
  |                              |<--ExecuteUpdateResponse---------------------------------|               |
  |<--rowCount-------------------|                            |                           |               |
```

### Operation without Session (Round-Robin)

```
Client                  MultinodeStatementService    MultinodeConnectionManager      OJP Server 1    OJP Server 2
  |                              |                            |                           |               |
  |--stmt.executeUpdate(sql)--->|                            |                           |               |
  |   (sessionUUID is empty)     |                            |                           |               |
  |                              |                            |                           |               |
  |                              |--executeWithSessionStickiness(session)-->             |               |
  |                              |                            |                           |               |
  |                              |                            |--affinityServer(null or empty)            |
  |                              |                            |                           |               |
  |                              |                            |--selectHealthyServer()    |               |
  |                              |                            |  (Round-robin: selects    |               |
  |                              |                            |   server2 this time)      |               |
  |                              |                            |                           |               |
  |                              |                            |--getChannelAndStub(server2)               |
  |                              |                            |<--channelAndStub-------------------------|
  |                              |                            |                           |               |
  |                              |<--channelAndStub-----------|                           |               |
  |                              |                            |                           |               |
  |                              |--client.executeUpdate()------------------------------------>         |
  |                              |                            |                           |               |
  |                              |                            |                           |   (Server creates datasource on-demand)
  |                              |                            |                           |   (May create sessionUUID if needed)
  |                              |                            |                           |   (Executes SQL)
  |                              |                            |                           |               |
  |                              |<--ExecuteUpdateResponse--------------------------------------------|
  |<--rowCount-------------------|                            |                           |               |
```

## Key Decision Points

### When is a Session Bound to a Server?
- **In MultinodeConnectionManager.connect()** after successful connection
- **Only if** `sessionInfo.getSessionUUID()` is not null and not empty
- Binding is stored in `sessionToServerMap<sessionUUID, ServerEndpoint>`

### When is Round-Robin Used?
- **During connect()**: Always uses round-robin to select initial server
- **During operations**: When `sessionKey` is null/empty in `affinityServer()` method
- **When session not bound**: If sessionUUID exists but isn't in `sessionToServerMap`

### When is Session Stickiness Used?
- **During operations**: When `sessionKey` is provided to `affinityServer()`
- **AND** the session is found in `sessionToServerMap`
- **AND** the bound server is healthy

### When are Datasources Created?
- **Server-side** in `StatementServiceImpl.connect()` or first operation
- **Keyed by connHash** (hash of connection parameters)
- **Cached** in `dataSourceCache` for reuse
- **Shared** across all clients with same connection parameters

### When are Datasources Retrieved?
- **Every operation** looks up datasource by `connHash` from `SessionInfo`
- If not found, operation fails with "No datasource found" error
- Datasources are per-server (each server has its own datasource cache)

## Session UUID vs Connection Hash

| Field | Purpose | When Present | Used For |
|-------|---------|--------------|----------|
| sessionUUID | Explicit session identifier | When server creates an explicit session (transactions, cursors) | Session stickiness - binds operations to specific server |
| connHash | Connection parameters hash | Always (generated from JDBC URL, username, etc.) | Datasource lookup - finds the correct connection pool on server |

## Error Handling

### Server Failure During Operation
```java
try {
    return operation.apply(client);
} catch (StatusRuntimeException e) {
    // Mark server as unhealthy
    connectionManager.handleServerFailure(server, e);
    
    // If this was a session-bound operation, fail
    // (we don't move sessions to other servers)
    if (sessionKey != null) {
        throw new SQLException("Session server failed: " + e.getMessage());
    }
    
    // If no session, could retry on another server
    // (not currently implemented)
}
```

### No Healthy Servers
```java
ServerEndpoint selectHealthyServer() {
    List<ServerEndpoint> healthy = serverEndpoints.stream()
        .filter(ServerEndpoint::isHealthy)
        .collect(Collectors.toList());
    
    if (healthy.isEmpty()) {
        return null;  // Caller handles retry logic
    }
    
    int index = roundRobinCounter.getAndIncrement() % healthy.size();
    return healthy.get(index);
}
```

## Configuration

### Multinode URL Format
```
jdbc:ojp[server1:port1,server2:port2,server3:port3]_targetdb://dbhost:dbport/database
```

### Properties
- `multinode.retry.attempts`: Number of retry attempts (default: 3, -1 for infinite)
- `multinode.retry.delay.ms`: Delay between retries in milliseconds (default: 1000)
- `multinode.health.check.interval.ms`: Interval for health checks (default: 10000)

## Threading and Concurrency

- **MultinodeConnectionManager**: Thread-safe using `ConcurrentHashMap` for all maps
- **Round-robin counter**: Uses `AtomicInteger` for thread-safe increments
- **gRPC channels**: Thread-safe by design
- **SessionInfo**: Immutable protobuf objects

## Performance Considerations

1. **Channel Reuse**: gRPC channels are created once and reused for all operations
2. **Connection Pooling**: Each server maintains its own HikariCP pool
3. **No Connection Migration**: Sessions stay on their original server (no overhead of moving state)
4. **Lazy Datasource Creation**: Datasources created on-demand, not all upfront
5. **Lock-Free Round-Robin**: Uses atomic counter, no synchronization needed

## Future Enhancements

1. **Automatic Failover**: Retry operations on different server if no session is bound
2. **Health Checks**: Periodic background health checks of servers
3. **Load-Based Routing**: Select server based on load, not just round-robin
4. **Session Migration**: Move sessions to different servers on failure (complex)
5. **Consistent Hashing**: Better distribution and minimal disruption on server changes
