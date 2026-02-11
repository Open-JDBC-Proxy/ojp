# Read/Write Splitting - Sequence Diagrams

This document provides sequence diagrams illustrating how read/write splitting works in various scenarios.

## Scenario 1: Simple Read Query Routing to Replica

```
┌─────────┐    ┌──────────┐    ┌──────────────┐    ┌────────────┐    ┌─────────┐
│JDBC App │    │OJP Driver│    │ OJP Server   │    │ReadWrite   │    │ Replica │
│         │    │          │    │StatementSvc  │    │Router      │    │ Pool    │
└────┬────┘    └────┬─────┘    └──────┬───────┘    └─────┬──────┘    └────┬────┘
     │              │                  │                  │                │
     │executeQuery("SELECT * FROM users")                │                │
     │─────────────>│                  │                  │                │
     │              │                  │                  │                │
     │              │ gRPC             │                  │                │
     │              │ StatementRequest │                  │                │
     │              │─────────────────>│                  │                │
     │              │                  │                  │                │
     │              │                  │ selectDataSource(│                │
     │              │                  │   session,       │                │
     │              │                  │   "SELECT...",   │                │
     │              │                  │   primary,       │                │
     │              │                  │   replicas)      │                │
     │              │                  │─────────────────>│                │
     │              │                  │                  │                │
     │              │                  │                  │1. Check transaction
     │              │                  │                  │   → Not in txn  │
     │              │                  │                  │                │
     │              │                  │                  │2. Check sticky  │
     │              │                  │                  │   → Not sticky  │
     │              │                  │                  │                │
     │              │                  │                  │3. Classify SQL  │
     │              │                  │                  │   → READ        │
     │              │                  │                  │                │
     │              │                  │                  │4. Select replica│
     │              │                  │<──────replica────│   (round robin)│
     │              │                  │                  │                │
     │              │                  │ getConnection()  │                │
     │              │                  │─────────────────────────────────>│
     │              │                  │<──────connection──────────────────│
     │              │                  │                  │                │
     │              │                  │ prepareStatement │                │
     │              │                  │ + execute        │                │
     │              │                  │─────────────────────────────────>│
     │              │                  │<──────ResultSet───────────────────│
     │              │                  │                  │                │
     │              │<───gRPC Response─│                  │                │
     │<─ResultSet───│                  │                  │                │
     │              │                  │                  │                │
```

## Scenario 2: Write Query Routing to Primary with Sticky Session

```
┌─────────┐    ┌──────────┐    ┌──────────────┐    ┌────────────┐    ┌─────────┐
│JDBC App │    │OJP Driver│    │ OJP Server   │    │ReadWrite   │    │ Primary │
│         │    │          │    │StatementSvc  │    │Router      │    │ Pool    │
└────┬────┘    └────┬─────┘    └──────┬───────┘    └─────┬──────┘    └────┬────┘
     │              │                  │                  │                │
     │executeUpdate("UPDATE users SET ...")               │                │
     │─────────────>│                  │                  │                │
     │              │                  │                  │                │
     │              │ gRPC             │                  │                │
     │              │ StatementRequest │                  │                │
     │              │─────────────────>│                  │                │
     │              │                  │                  │                │
     │              │                  │ selectDataSource(│                │
     │              │                  │   session,       │                │
     │              │                  │   "UPDATE...",   │                │
     │              │                  │   primary,       │                │
     │              │                  │   replicas)      │                │
     │              │                  │─────────────────>│                │
     │              │                  │                  │                │
     │              │                  │                  │1. Check transaction
     │              │                  │                  │   → Not in txn  │
     │              │                  │                  │                │
     │              │                  │                  │2. Classify SQL  │
     │              │                  │                  │   → WRITE       │
     │              │                  │                  │                │
     │              │                  │                  │3. Mark write    │
     │              │                  │                  │   occurred      │
     │              │                  │                  │   (sticky=5sec) │
     │              │                  │<─────primary─────│                │
     │              │                  │                  │                │
     │              │                  │ getConnection()  │                │
     │              │                  │─────────────────────────────────>│
     │              │                  │<──────connection──────────────────│
     │              │                  │                  │                │
     │              │                  │ prepareStatement │                │
     │              │                  │ + execute        │                │
     │              │                  │─────────────────────────────────>│
     │              │                  │<──────rowCount────────────────────│
     │              │                  │                  │                │
     │              │<───gRPC Response─│                  │                │
     │<─rowCount────│                  │                  │                │
     │              │                  │                  │                │
     │              │                  │                  │                │
     │executeQuery("SELECT * FROM users") -- Next query within 5 seconds   │
     │─────────────>│                  │                  │                │
     │              │─────────────────>│                  │                │
     │              │                  │─────────────────>│                │
     │              │                  │                  │1. Check sticky  │
     │              │                  │                  │   → In sticky!  │
     │              │                  │<─────primary─────│                │
     │              │                  │                  │                │
     │              │                  │ Execute on PRIMARY (read-your-writes)
     │              │                  │─────────────────────────────────>│
     │              │                  │<──────ResultSet───────────────────│
```

## Scenario 3: Transaction Pinning to Primary

```
┌─────────┐    ┌──────────┐    ┌──────────────┐    ┌────────────┐    ┌─────────┐
│JDBC App │    │OJP Driver│    │ OJP Server   │    │ReadWrite   │    │ Primary │
│         │    │          │    │StatementSvc  │    │Router      │    │ Pool    │
└────┬────┘    └────┬─────┘    └──────┬───────┘    └─────┬──────┘    └────┬────┘
     │              │                  │                  │                │
     │setAutoCommit(false) -- Begin transaction           │                │
     │─────────────>│                  │                  │                │
     │              │─────────────────>│                  │                │
     │              │                  │ session.beginTransaction()        │
     │              │                  │──────────────────X                │
     │              │                  │                  │                │
     │              │                  │                  │                │
     │executeQuery("SELECT * FROM accounts WHERE id=1")   │                │
     │─────────────>│                  │                  │                │
     │              │─────────────────>│                  │                │
     │              │                  │─────────────────>│                │
     │              │                  │                  │1. Check transaction
     │              │                  │                  │   → IN TRANSACTION!
     │              │                  │<─────primary─────│   Always primary│
     │              │                  │                  │                │
     │              │                  │ Execute on PRIMARY                │
     │              │                  │─────────────────────────────────>│
     │              │                  │<──────ResultSet───────────────────│
     │              │                  │                  │                │
     │              │                  │                  │                │
     │executeUpdate("UPDATE accounts SET balance=...") -- Still in txn    │
     │─────────────>│                  │                  │                │
     │              │─────────────────>│                  │                │
     │              │                  │─────────────────>│                │
     │              │                  │                  │1. Check transaction
     │              │                  │                  │   → IN TRANSACTION!
     │              │                  │<─────primary─────│                │
     │              │                  │                  │                │
     │              │                  │ Execute on PRIMARY                │
     │              │                  │─────────────────────────────────>│
     │              │                  │<──────rowCount────────────────────│
     │              │                  │                  │                │
     │              │                  │                  │                │
     │commit() -- End transaction      │                  │                │
     │─────────────>│                  │                  │                │
     │              │─────────────────>│                  │                │
     │              │                  │ COMMIT           │                │
     │              │                  │─────────────────────────────────>│
     │              │                  │                  │                │
     │              │                  │ session.endTransaction()          │
     │              │                  │──────────────────X                │
     │              │                  │                  │                │
     │              │                  │                  │                │
     │executeQuery("SELECT * FROM ...") -- After commit, reads go to replica
     │─────────────>│                  │                  │                │
     │              │─────────────────>│                  │                │
     │              │                  │─────────────────>│                │
     │              │                  │                  │1. Check transaction
     │              │                  │                  │   → Not in txn  │
     │              │                  │                  │2. Classify SQL  │
     │              │                  │                  │   → READ        │
     │              │                  │<─────replica─────│                │
```

## Scenario 4: Replica Failover to Primary

```
┌─────────┐    ┌──────────┐    ┌──────────────┐    ┌────────────┐    ┌─────────┐   ┌─────────┐
│JDBC App │    │OJP Driver│    │ OJP Server   │    │ReadWrite   │    │ Replica │   │ Primary │
│         │    │          │    │StatementSvc  │    │Router      │    │ Pool    │   │ Pool    │
└────┬────┘    └────┬─────┘    └──────┬───────┘    └─────┬──────┘    └────┬────┘   └────┬────┘
     │              │                  │                  │                │           │
     │executeQuery("SELECT * FROM users")                │                │           │
     │─────────────>│                  │                  │                │           │
     │              │─────────────────>│                  │                │           │
     │              │                  │─────────────────>│                │           │
     │              │                  │                  │1. Classify SQL │           │
     │              │                  │                  │   → READ       │           │
     │              │                  │                  │                │           │
     │              │                  │                  │2. Select replica           │
     │              │                  │                  │   (round robin)│           │
     │              │                  │                  │                │           │
     │              │                  │ getConnection()  │                │           │
     │              │                  │─────────────────────────────────>│           │
     │              │                  │<─────SQLException (replica down)─│           │
     │              │                  │                  │                │           │
     │              │                  │                  │3. Catch exception          │
     │              │                  │                  │   Log warning  │           │
     │              │                  │                  │   Fallback to primary      │
     │              │                  │                  │                │           │
     │              │                  │ getConnection()  │                │           │
     │              │                  │──────────────────────────────────────────────>│
     │              │                  │<──────connection──────────────────────────────│
     │              │                  │                  │                │           │
     │              │                  │ Execute on PRIMARY (failover)    │           │
     │              │                  │──────────────────────────────────────────────>│
     │              │                  │<──────ResultSet───────────────────────────────│
     │              │                  │                  │                │           │
     │              │<───gRPC Response─│                  │                │           │
     │<─ResultSet───│                  │                  │                │           │
     │              │                  │                  │                │           │
     │              │              (Circuit breaker opens for replica)    │           │
     │              │              (Future reads continue on primary until             │
     │              │               replica recovers)     │                │           │
```

## Scenario 5: SELECT FOR UPDATE - Detected as Write

```
┌─────────┐    ┌──────────┐    ┌──────────────┐    ┌────────────┐    ┌─────────┐
│JDBC App │    │OJP Driver│    │ OJP Server   │    │ReadWrite   │    │ Primary │
│         │    │          │    │StatementSvc  │    │Router      │    │ Pool    │
└────┬────┘    └────┬─────┘    └──────┬───────┘    └─────┬──────┘    └────┬────┘
     │              │                  │                  │                │
     │executeQuery("SELECT * FROM accounts WHERE id=1 FOR UPDATE")        │
     │─────────────>│                  │                  │                │
     │              │─────────────────>│                  │                │
     │              │                  │─────────────────>│                │
     │              │                  │                  │1. Classify SQL │
     │              │                  │                  │   → Starts with│
     │              │                  │                  │      SELECT    │
     │              │                  │                  │   → Contains   │
     │              │                  │                  │      FOR UPDATE│
     │              │                  │                  │   → WRITE!     │
     │              │                  │<─────primary─────│   (needs lock) │
     │              │                  │                  │                │
     │              │                  │ Execute on PRIMARY (locking query)│
     │              │                  │─────────────────────────────────>│
     │              │                  │<──────ResultSet───────────────────│
     │              │                  │                  │                │
     │              │<───gRPC Response─│                  │                │
     │<─ResultSet───│                  │                  │                │
```

## Key Decision Points in Router Logic

```
┌─────────────────────────────────────┐
│   selectDataSource(session, sql)    │
└─────────────────┬───────────────────┘
                  │
                  ▼
         ┌────────────────┐
         │ In Transaction? │
         └────────┬────────┘
                  │
        ┌─────────┴─────────┐
       YES                 NO
        │                   │
        ▼                   ▼
   ┌─────────┐    ┌─────────────────┐
   │ PRIMARY │    │  In Sticky      │
   │         │    │  Session?       │
   └─────────┘    └────────┬─────────┘
                           │
                 ┌─────────┴─────────┐
                YES                 NO
                 │                   │
                 ▼                   ▼
            ┌─────────┐    ┌─────────────────┐
            │ PRIMARY │    │  Classify SQL   │
            │         │    │                 │
            └─────────┘    └────────┬─────────┘
                                    │
                          ┌─────────┴─────────┐
                        READ              WRITE/UNKNOWN
                          │                    │
                          ▼                    ▼
                   ┌──────────────┐       ┌─────────┐
                   │ Select       │       │ PRIMARY │
                   │ Replica      │       │ + Mark  │
                   │ (Round Robin)│       │ Sticky  │
                   └──────┬───────┘       └─────────┘
                          │
                          │
                   ┌──────┴───────┐
                   │              │
            Replica Available   No Replicas
                   │              │
                   ▼              ▼
              ┌─────────┐    ┌─────────┐
              │ REPLICA │    │ PRIMARY │
              │         │    │(fallback)
              └─────────┘    └─────────┘
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
