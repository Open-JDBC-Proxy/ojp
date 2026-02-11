# Read/Write Splitting - Sequence Diagrams

This document provides sequence diagrams illustrating how read/write splitting works in various scenarios.

## Scenario 1: Simple Read Query Routing to Replica

```mermaid
sequenceDiagram
    participant App as JDBC App
    participant Driver as OJP Driver
    participant Server as OJP Server<br/>StatementSvc
    participant Router as ReadWrite<br/>Router
    participant Replica as Replica<br/>Pool

    App->>Driver: executeQuery("SELECT * FROM users")
    Driver->>Server: gRPC StatementRequest
    Server->>Router: selectDataSource(session, "SELECT...", primary, replicas)
    
    Note over Router: 1. Check transaction<br/>→ Not in txn
    Note over Router: 2. Check sticky<br/>→ Not sticky
    Note over Router: 3. Classify SQL<br/>→ READ
    Note over Router: 4. Select replica<br/>(round robin)
    
    Router-->>Server: replica
    Server->>Replica: getConnection()
    Replica-->>Server: connection
    Server->>Replica: prepareStatement + execute
    Replica-->>Server: ResultSet
    Server-->>Driver: gRPC Response
    Driver-->>App: ResultSet
```

## Scenario 2: Write Query Routing to Primary with Sticky Session

```mermaid
sequenceDiagram
    participant App as JDBC App
    participant Driver as OJP Driver
    participant Server as OJP Server<br/>StatementSvc
    participant Router as ReadWrite<br/>Router
    participant Primary as Primary<br/>Pool

    App->>Driver: executeUpdate("UPDATE users SET ...")
    Driver->>Server: gRPC StatementRequest
    Server->>Router: selectDataSource(session, "UPDATE...", primary, replicas)
    
    Note over Router: 1. Check transaction<br/>→ Not in txn
    Note over Router: 2. Classify SQL<br/>→ WRITE
    Note over Router: 3. Mark write occurred<br/>(sticky=5sec)
    
    Router-->>Server: primary
    Server->>Primary: getConnection()
    Primary-->>Server: connection
    Server->>Primary: prepareStatement + execute
    Primary-->>Server: rowCount
    Server-->>Driver: gRPC Response
    Driver-->>App: rowCount

    Note over App,Primary: Next query within 5 seconds

    App->>Driver: executeQuery("SELECT * FROM users")
    Driver->>Server: gRPC StatementRequest
    Server->>Router: selectDataSource(session, "SELECT...", primary, replicas)
    
    Note over Router: 1. Check sticky<br/>→ In sticky!
    
    Router-->>Server: primary
    Note over Server,Primary: Execute on PRIMARY (read-your-writes)
    Server->>Primary: Execute
    Primary-->>Server: ResultSet
    Server-->>Driver: gRPC Response
    Driver-->>App: ResultSet
```

## Scenario 3: Transaction Pinning to Primary

```mermaid
sequenceDiagram
    participant App as JDBC App
    participant Driver as OJP Driver
    participant Server as OJP Server<br/>StatementSvc
    participant Router as ReadWrite<br/>Router
    participant Primary as Primary<br/>Pool

    App->>Driver: setAutoCommit(false)
    Driver->>Server: Begin transaction
    Note over Server: session.beginTransaction()

    App->>Driver: executeQuery("SELECT * FROM accounts WHERE id=1")
    Driver->>Server: gRPC StatementRequest
    Server->>Router: selectDataSource(session, "SELECT...", primary, replicas)
    
    Note over Router: 1. Check transaction<br/>→ IN TRANSACTION!<br/>Always primary
    
    Router-->>Server: primary
    Note over Server,Primary: Execute on PRIMARY
    Server->>Primary: Execute
    Primary-->>Server: ResultSet

    App->>Driver: executeUpdate("UPDATE accounts SET balance=...")
    Driver->>Server: gRPC StatementRequest
    Server->>Router: selectDataSource(session, "UPDATE...", primary, replicas)
    
    Note over Router: 1. Check transaction<br/>→ IN TRANSACTION!
    
    Router-->>Server: primary
    Server->>Primary: Execute
    Primary-->>Server: rowCount

    App->>Driver: commit()
    Driver->>Server: COMMIT
    Server->>Primary: COMMIT
    Note over Server: session.endTransaction()

    Note over App,Primary: After commit, reads can go to replica

    App->>Driver: executeQuery("SELECT * FROM ...")
    Driver->>Server: gRPC StatementRequest
    Server->>Router: selectDataSource(session, "SELECT...", primary, replicas)
    
    Note over Router: 1. Check transaction<br/>→ Not in txn<br/>2. Classify SQL<br/>→ READ
    
    Router-->>Server: replica
```

## Scenario 4: Replica Failover to Primary

```mermaid
sequenceDiagram
    participant App as JDBC App
    participant Driver as OJP Driver
    participant Server as OJP Server<br/>StatementSvc
    participant Router as ReadWrite<br/>Router
    participant Replica as Replica<br/>Pool
    participant Primary as Primary<br/>Pool

    App->>Driver: executeQuery("SELECT * FROM users")
    Driver->>Server: gRPC StatementRequest
    Server->>Router: selectDataSource(session, "SELECT...", primary, replicas)
    
    Note over Router: 1. Classify SQL<br/>→ READ
    Note over Router: 2. Select replica<br/>(round robin)
    
    Server->>Replica: getConnection()
    Replica-->>Server: SQLException (replica down)
    
    Note over Router: 3. Catch exception<br/>Log warning<br/>Fallback to primary
    
    Server->>Primary: getConnection()
    Primary-->>Server: connection
    
    Note over Server,Primary: Execute on PRIMARY (failover)
    Server->>Primary: Execute
    Primary-->>Server: ResultSet
    Server-->>Driver: gRPC Response
    Driver-->>App: ResultSet
    
    Note over Replica,Primary: Circuit breaker opens for replica<br/>Future reads continue on primary<br/>until replica recovers
```

## Scenario 5: SELECT FOR UPDATE - Detected as Write

```mermaid
sequenceDiagram
    participant App as JDBC App
    participant Driver as OJP Driver
    participant Server as OJP Server<br/>StatementSvc
    participant Router as ReadWrite<br/>Router
    participant Primary as Primary<br/>Pool

    App->>Driver: executeQuery("SELECT * FROM accounts WHERE id=1 FOR UPDATE")
    Driver->>Server: gRPC StatementRequest
    Server->>Router: selectDataSource(session, "SELECT...FOR UPDATE", primary, replicas)
    
    Note over Router: 1. Classify SQL<br/>→ Starts with SELECT<br/>→ Contains FOR UPDATE<br/>→ WRITE! (needs lock)
    
    Router-->>Server: primary
    
    Note over Server,Primary: Execute on PRIMARY (locking query)
    Server->>Primary: Execute
    Primary-->>Server: ResultSet
    Server-->>Driver: gRPC Response
    Driver-->>App: ResultSet
```

## Key Decision Points in Router Logic

```mermaid
flowchart TD
    Start([selectDataSource<br/>session, sql])
    Start --> CheckTxn{In Transaction?}
    
    CheckTxn -->|YES| Primary1[PRIMARY]
    CheckTxn -->|NO| CheckSticky{In Sticky<br/>Session?}
    
    CheckSticky -->|YES| Primary2[PRIMARY]
    CheckSticky -->|NO| ClassifySQL[Classify SQL]
    
    ClassifySQL --> SQLType{SQL Type?}
    
    SQLType -->|READ| SelectReplica[Select Replica<br/>Round Robin]
    SQLType -->|WRITE/UNKNOWN| MarkSticky[PRIMARY<br/>+ Mark Sticky]
    
    SelectReplica --> ReplicaCheck{Replica<br/>Available?}
    
    ReplicaCheck -->|YES| Replica[REPLICA]
    ReplicaCheck -->|NO| Primary3[PRIMARY<br/>fallback]
    
    style Primary1 fill:#ff9999
    style Primary2 fill:#ff9999
    style Primary3 fill:#ff9999
    style MarkSticky fill:#ff9999
    style Replica fill:#99ccff
```

## Summary

These sequence diagrams illustrate the key behaviors of the read/write splitting feature:

1. **Read Routing**: Normal SELECT queries go to replicas using round-robin selection
2. **Write Routing**: All writes go to primary and trigger sticky session
3. **Transaction Handling**: All queries within a transaction use the primary datasource
4. **Failover**: Replica failures automatically fall back to primary
5. **Lock Detection**: SELECT FOR UPDATE and similar locking queries route to primary

The routing logic ensures:
- **Consistency**: Writes always go to primary
- **Performance**: Reads distributed across replicas
- **Safety**: Unknown queries and failures default to primary
- **Transaction Integrity**: All transaction operations use single datasource
