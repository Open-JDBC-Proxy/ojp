# StatementServiceImpl Action Pattern Refactoring Guide

## Overview

This guide explains how to refactor `StatementServiceImpl` using the Action pattern. The `connect()` method has been implemented as a reference - use it as a template for refactoring the remaining 20 public methods.

**Reference Implementation**: [PR #214](https://github.com/Open-J-Proxy/ojp/pull/214)

## Why Refactor?

`StatementServiceImpl` is a 2,528-line God class with 21 public methods. The Action pattern splits this into:
- **StatementServiceImpl**: Thin orchestrator (~400 lines)
- **35+ Action classes**: Focused, testable units (~75 lines each)
- **ActionContext**: Centralized shared state holder

## Architecture Diagrams

### Class Diagram

```mermaid
classDiagram
    class StatementServiceImpl {
        -ActionContext actionContext
        +connect(ConnectionDetails, StreamObserver)
        +executeUpdate(StatementRequest, StreamObserver)
        +executeQuery(StatementRequest, StreamObserver)
        +startTransaction(SessionInfo, StreamObserver)
        +commitTransaction(SessionInfo, StreamObserver)
        +rollbackTransaction(SessionInfo, StreamObserver)
        +xaStart(XaStartRequest, StreamObserver)
        +createLob(StreamObserver) StreamObserver
        ...20+ more methods
    }

    class ActionContext {
        -Map~String,DataSource~ datasourceMap
        -Map~String,XADataSource~ xaDataSourceMap
        -Map~String,XATransactionRegistry~ xaRegistries
        -Map~String,UnpooledConnectionDetails~ unpooledConnectionDetailsMap
        -Map~String,DbName~ dbNameMap
        -Map~String,SlowQuerySegregationManager~ slowQuerySegregationManagers
        -XAConnectionPoolProvider xaPoolProvider
        -MultinodeXaCoordinator xaCoordinator
        -ClusterHealthTracker clusterHealthTracker
        -SessionManager sessionManager
        -CircuitBreaker circuitBreaker
        -ServerConfiguration serverConfiguration
        +getDatasourceMap() Map
        +getXaDataSourceMap() Map
        +getSessionManager() SessionManager
        ...getters for all fields
    }

    class Action~TRequest,TResponse~ {
        <<interface>>
        +execute(ActionContext, TRequest, StreamObserver~TResponse~) void
    }

    class StreamingAction~TRequest,TResponse~ {
        <<interface>>
        +execute(StreamObserver~TResponse~) StreamObserver~TRequest~
    }

    class InitAction {
        <<interface>>
        +execute() void
    }

    class ValueAction~TRequest,TResult~ {
        <<interface>>
        +execute(TRequest) TResult
    }

    class ConnectAction {
        -static INSTANCE ConnectAction
        +getInstance() ConnectAction
        +execute(ActionContext, ConnectionDetails, StreamObserver~SessionInfo~) void
        -handleXAConnection()
        -handleRegularConnection()
    }

    class ExecuteUpdateAction {
        -static INSTANCE ExecuteUpdateAction
        +getInstance() ExecuteUpdateAction
        +execute(ActionContext, StatementRequest, StreamObserver~OpResult~) void
    }

    class ExecuteQueryAction {
        -static INSTANCE ExecuteQueryAction
        +getInstance() ExecuteQueryAction
        +execute(ActionContext, StatementRequest, StreamObserver~OpResult~) void
    }

    class StartTransactionAction {
        -static INSTANCE StartTransactionAction
        +getInstance() StartTransactionAction
        +execute(ActionContext, SessionInfo, StreamObserver~SessionInfo~) void
    }

    class CreateLobAction {
        -static INSTANCE CreateLobAction
        +getInstance() CreateLobAction
        +execute(StreamObserver~LobReference~) StreamObserver~LobDataBlock~
    }

    class HandleXAConnectionWithPoolingAction {
        -static INSTANCE HandleXAConnectionWithPoolingAction
        +getInstance() HandleXAConnectionWithPoolingAction
        +execute(ActionContext, ConnectionDetails, StreamObserver~SessionInfo~) void
    }

    StatementServiceImpl --> ActionContext : uses
    StatementServiceImpl ..> ConnectAction : delegates
    StatementServiceImpl ..> ExecuteUpdateAction : delegates
    StatementServiceImpl ..> ExecuteQueryAction : delegates
    StatementServiceImpl ..> StartTransactionAction : delegates
    StatementServiceImpl ..> CreateLobAction : delegates
    
    ConnectAction ..|> Action : implements
    ExecuteUpdateAction ..|> Action : implements
    ExecuteQueryAction ..|> Action : implements
    StartTransactionAction ..|> Action : implements
    CreateLobAction ..|> StreamingAction : implements
    HandleXAConnectionWithPoolingAction ..|> Action : implements
    
    ConnectAction --> ActionContext : receives as param
    ExecuteUpdateAction --> ActionContext : receives as param
    ExecuteQueryAction --> ActionContext : receives as param
    StartTransactionAction --> ActionContext : receives as param
    CreateLobAction --> ActionContext : receives as param
    HandleXAConnectionWithPoolingAction --> ActionContext : receives as param
    
    ConnectAction --> HandleXAConnectionWithPoolingAction : uses
```

### Connect Method Flow (Reference Implementation)

```mermaid
sequenceDiagram
    participant Client
    participant StatementServiceImpl
    participant ConnectAction
    participant HandleXAConnectionAction
    participant ActionContext
    participant SessionManager
    participant DataSourceMap

    Client->>StatementServiceImpl: connect(ConnectionDetails)
    StatementServiceImpl->>ConnectAction: new ConnectAction(actionContext).execute()
    
    ConnectAction->>ActionContext: Generate connection hash
    ConnectAction->>ConnectAction: Check if XA or regular connection
    
    alt XA Connection
        ConnectAction->>HandleXAConnectionAction: delegate to XA handler
        HandleXAConnectionAction->>ActionContext: Get XA datasource map
        HandleXAConnectionAction->>ActionContext: Get XA pool provider
        HandleXAConnectionAction->>SessionManager: Create XA session
        HandleXAConnectionAction->>ConnectAction: return
    else Regular Connection
        ConnectAction->>ActionContext: Get datasource map
        ConnectAction->>DataSourceMap: Check for existing datasource
        alt Datasource exists
            ConnectAction->>SessionManager: Create session from datasource
        else Datasource doesn't exist
            ConnectAction->>ConnectAction: Create new datasource/unpooled config
            ConnectAction->>ActionContext: Store datasource or unpooled config
            ConnectAction->>SessionManager: Create session
        end
    end
    
    ConnectAction->>StatementServiceImpl: Complete
    StatementServiceImpl->>Client: SessionInfo response
```

## How It Works

### Action Interfaces

All actions MUST be implemented as singletons and MUST implement the appropriate action interface.

#### 1. Action<TRequest, TResponse>
For standard RPC methods (20 of 21 methods).

```java
public interface Action<TRequest, TResponse> {
    void execute(ActionContext context, TRequest request, StreamObserver<TResponse> responseObserver);
}
```

**Implementation Pattern:**
```java
@Slf4j
public class ConnectAction implements Action<ConnectionDetails, SessionInfo> {
    private static final ConnectAction INSTANCE = new ConnectAction();
    
    private ConnectAction() {
        // Private constructor prevents external instantiation
    }
    
    public static ConnectAction getInstance() {
        return INSTANCE;
    }
    
    @Override
    public void execute(ActionContext context, ConnectionDetails request, 
                       StreamObserver<SessionInfo> responseObserver) {
        // Action logic - stateless, all state via context parameter
    }
}
```

**Examples**: connect, executeUpdate, executeQuery, transactions, XA operations, etc.

#### 2. StreamingAction<TRequest, TResponse>
For bidirectional streaming (1 method: createLob).

```java
public interface StreamingAction<TRequest, TResponse> {
    StreamObserver<TRequest> execute(StreamObserver<TResponse> responseObserver);
}
```

**Implementation Pattern:**
```java
@Slf4j
public class CreateLobAction implements StreamingAction<LobDataBlock, LobReference> {
    private static final CreateLobAction INSTANCE = new CreateLobAction();
    
    private CreateLobAction() {
        // Private constructor prevents external instantiation
    }
    
    public static CreateLobAction getInstance() {
        return INSTANCE;
    }
    
    @Override
    public StreamObserver<LobDataBlock> execute(StreamObserver<LobReference> responseObserver) {
        // Streaming action logic - stateless, returns StreamObserver for client streaming
        return new StreamObserver<LobDataBlock>() {
            // Implementation...
        };
    }
}
```

**Examples**: createLob

#### 3. InitAction
For initialization operations that don't take request/response parameters.

```java
public interface InitAction {
    void execute();
}
```

**Implementation Pattern:**
```java
@Slf4j
public class InitializeXAPoolProviderAction implements InitAction {
    private static final InitializeXAPoolProviderAction INSTANCE = new InitializeXAPoolProviderAction();
    
    private InitializeXAPoolProviderAction() {
        // Private constructor prevents external instantiation
    }
    
    public static InitializeXAPoolProviderAction getInstance() {
        return INSTANCE;
    }
    
    @Override
    public void execute() {
        // Initialization logic - stateless
    }
}
```

**Examples**: initializeXAPoolProvider

#### 4. ValueAction<TRequest, TResult>
For internal helper operations that return a value directly (not via StreamObserver).

```java
public interface ValueAction<TRequest, TResult> {
    TResult execute(TRequest request) throws Exception;
}
```

**Implementation Pattern:**
```java
@Slf4j
public class ExecuteUpdateInternalAction implements ValueAction<StatementRequest, OpResult> {
    private static final ExecuteUpdateInternalAction INSTANCE = new ExecuteUpdateInternalAction();
    
    private ExecuteUpdateInternalAction() {
        // Private constructor prevents external instantiation
    }
    
    public static ExecuteUpdateInternalAction getInstance() {
        return INSTANCE;
    }
    
    @Override
    public OpResult execute(StatementRequest request) throws Exception {
        // Internal action logic - stateless, returns value directly
        return OpResult.newBuilder().build();
    }
}
```

**Examples**: executeUpdateInternal, findLobContext, sessionConnection

### ActionContext

Holds all shared state (maps, services) used by actions. Thread-safe with ConcurrentHashMap.

**Key fields**:
- `datasourceMap`, `xaDataSourceMap` - Database connections
- `sessionManager` - Session/connection management  
- `circuitBreaker` - Failure protection
- `serverConfiguration` - Server config

Actions access via `context.getDatasourceMap()`, `context.getSessionManager()`, etc.

## Implementation Pattern

### Before: God Class (144 lines inline)
```java
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {
    public void connect(ConnectionDetails details, StreamObserver<SessionInfo> observer) {
        // 144 lines of logic: health checks, hashing, XA branching,
        // multinode coordination, pool creation, session management...
    }
}
```

### After: Action Pattern (3 lines + focused action)

**⚠️ IMPORTANT: All actions MUST be implemented as singletons for thread-safety and memory efficiency.**

Actions are stateless, implement the Action interface, and receive the ActionContext as a parameter.

```java
// StatementServiceImpl - thin delegator
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {
    private final ActionContext actionContext;
    
    public void connect(ConnectionDetails details, StreamObserver<SessionInfo> observer) {
        ConnectAction.getInstance().execute(actionContext, details, observer);
    }
}

// ConnectAction - focused logic (~150 lines) - SINGLETON PATTERN
@Slf4j
public class ConnectAction implements Action<ConnectionDetails, SessionInfo> {
    private static final ConnectAction INSTANCE = new ConnectAction();
    
    private ConnectAction() {
        // Private constructor prevents external instantiation
    }
    
    public static ConnectAction getInstance() {
        return INSTANCE;
    }
    
    @Override
    public void execute(ActionContext context, ConnectionDetails request, 
                       StreamObserver<SessionInfo> responseObserver) {
        // Connection handling logic - stateless, all state via context parameter
        // Access: context.getDatasourceMap(), context.getSessionManager(), etc.
        // Delegate to helper actions: HandleXAConnectionAction.getInstance().execute(context, ...)
    }
}
```

### Why Singletons?

All action classes MUST be implemented as singletons for the following reasons:

1. **Thread-Safety**: Actions implement the Action interface and are stateless - they receive all necessary state via parameters (ActionContext, request). This makes them inherently thread-safe and reusable across concurrent requests.

2. **Memory Efficiency**: Creating new action instances for every request would generate millions of short-lived objects per day in production. Singletons eliminate this overhead.

3. **Performance**: Singleton pattern avoids object allocation and garbage collection overhead on the hot path.

4. **Consistency**: All actions follow the same pattern, making the codebase easier to understand and maintain.

**Note**: Actions do NOT store ActionContext as an instance field. Instead, they receive it as a parameter to their execute() method via the Action interface, ensuring they remain truly stateless and thread-safe.

## Quick Start for Contributors

### 1. Study the Reference
Review [PR #214](https://github.com/Open-J-Proxy/ojp/pull/214) - especially `ConnectAction` and how it uses ActionContext.

### 2. Pick a Method to Refactor
Choose any of the 20 remaining public methods in `StatementServiceImpl`:
- **Simple**: `startTransaction`, `commitTransaction`, `rollbackTransaction`, `terminateSession`, XA operations (xaEnd, xaForget, etc.)
- **Medium**: `executeUpdate`, `executeQuery`, `fetchNextRows`, `callResource`, `xaStart`, `xaPrepare`
- **Complex**: `createLob`, `readLob`

### 3. Implementation Steps
1. **Create action class** in appropriate package (e.g., `org.openjproxy.grpc.server.action.transaction`)
2. **Implement Action interface**: `public class YourAction implements Action<RequestType, ResponseType>`
3. **Implement singleton pattern**:
   - Add private constructor
   - Add `private static final YourAction INSTANCE = new YourAction();`
   - Add `public static YourAction getInstance() { return INSTANCE; }`
4. **Implement execute method**: `@Override public void execute(ActionContext context, RequestType request, StreamObserver<ResponseType> observer)`
5. **Copy method logic** from `StatementServiceImpl` to action's `execute()` method
6. **Use context parameter** instead of instance fields - e.g., `context.getDatasourceMap()`, `context.getSessionManager()`
7. **Update StatementServiceImpl** to delegate: `YourAction.getInstance().execute(actionContext, request, observer);`
8. **Test** - ensure compilation and existing tests pass
9. **Submit PR** with clear description

### Common Patterns

#### Simple Delegation (Singleton Pattern)
```java
// In StatementServiceImpl
public void methodName(Request request, StreamObserver<Response> observer) {
    MethodNameAction.getInstance().execute(actionContext, request, observer);
}

// In Action class (Singleton implementing Action interface)
@Slf4j
public class MethodNameAction implements Action<Request, Response> {
    private static final MethodNameAction INSTANCE = new MethodNameAction();
    
    private MethodNameAction() {}
    
    public static MethodNameAction getInstance() {
        return INSTANCE;
    }
    
    @Override
    public void execute(ActionContext context, Request request, StreamObserver<Response> observer) {
        // Action logic here - stateless, all state via context parameter
    }
}
```

#### Accessing Shared State
```java
// In Action class - context is passed as parameter
public void execute(ActionContext context, Request request, StreamObserver<Response> observer) {
    DataSource ds = context.getDatasourceMap().get(connHash);
    SessionManager sessionManager = context.getSessionManager();
}
```

#### Delegating to Other Actions
```java
// Singleton actions delegate to other singleton actions
HandleXAConnectionAction.getInstance().execute(context, connectionDetails, observer);
CreateSlowQuerySegregationManagerAction.getInstance().execute(context, connHash, maxPoolSize);
```

#### Error Handling
```java
try {
    // Action logic
    responseObserver.onNext(response);
    responseObserver.onCompleted();
} catch (SQLException e) {
    log.error("Error in action", e);
    sendSQLExceptionMetadata(e, responseObserver);
}
```

## Package Structure

Actions are organized by functionality:

```
org.openjproxy.grpc.server.action/
├── connection/        ConnectAction ✅ (reference implementation)
├── transaction/       Start/Commit/Rollback transactions
├── statement/         ExecuteUpdate, ExecuteQuery, FetchNextRows
├── xa/                XA transaction operations (10 methods)
├── lob/               CreateLob, ReadLob (streaming)
├── session/           TerminateSession
├── resource/          CallResource
└── util/              ProcessClusterHealth ✅, helpers
```

## Benefits


- ✅ **Testability**: Each action independently testable
- ✅ **Maintainability**: ~75 line focused classes vs 2,528 line God class  
- ✅ **Code Review**: Smaller, focused PRs
- ✅ **Parallel Development**: Multiple contributors can work simultaneously
- ✅ **Debugging**: Easier to trace specific operations

---

**Get started**: Pick a method from `StatementServiceImpl`, study ConnectAction in [PR #214](https://github.com/Open-J-Proxy/ojp/pull/214), and submit your PR!
