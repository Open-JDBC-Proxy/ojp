# Narayana XA Connection Pool Implementation Plan

## Overview

This document outlines the implementation plan for Narayana-only XA connection pooling in OJP, replacing the current direct XADataSource approach and removing server-binding logic.

## Implementation Status

**Status:** In Progress  
**Date Started:** December 17, 2024

## Objectives

1. Create `ojp-datasource-narayana` module with Narayana XA connection pool provider
2. Replace server-side direct XADataSource creation with Narayana pooled XADataSource
3. Remove XA connection-to-server binding (use round-robin load balancing like non-XA)
4. Preserve xaStart retry logic across servers
5. Replace max transaction limit with Narayana pool-based limits
6. Make XA pooling behave like non-XA pooling as much as possible

## Key Changes Required

### 1. Server-Side Changes

#### 1.1 Create ojp-datasource-narayana Module
- [ ] Create module structure
- [ ] Add Narayana dependencies
- [ ] Implement `NarayanaXAConnectionPoolProvider`
- [ ] Create ServiceLoader registration
- [ ] Add configuration mapping
- [ ] Unit tests

#### 1.2 Modify StatementServiceImpl
- [ ] Remove direct XADataSource creation
- [ ] Use XAConnectionPoolProvider registry
- [ ] Configure Narayana pools using PoolConfig
- [ ] Remove XA-specific datasource map (use provider)
- [ ] Apply connection pool configuration from client

#### 1.3 Remove Max Transaction Limit
- [ ] Identify code enforcing max transaction limit
- [ ] Replace with Narayana pool limits
- [ ] Update documentation

### 2. Client-Side Changes

#### 2.1 Remove Server Binding Logic
Files to modify:
- `OjpXAConnection.java` - Remove `boundServerAddress` tracking
- `OjpXAResource.java` - Remove server-specific routing
- `XAConnectionRedistributor.java` - May need removal or modification
- `ConnectionTracker.java` - Update for non-bound XA connections

Changes:
- [ ] Remove `boundServerAddress` field from OjpXAConnection
- [ ] Remove server tracking in sessionInfo
- [ ] Update connection creation to not bind to specific server
- [ ] Remove ServerHealthListener logic for XA binding

#### 2.2 Update XA Retry Logic
- [ ] Modify xaStart to retry on different servers
- [ ] Track connection state before xaStart (to detect alterations)
- [ ] Only retry if no alterations detected
- [ ] Use same round-robin logic as non-XA statements

Connection alterations to track:
- [ ] setAutoCommit calls
- [ ] SQL statement execution
- [ ] Transaction isolation changes
- [ ] Read-only mode changes
- [ ] Catalog/schema changes

#### 2.3 Update Load Balancing
- [ ] Use existing round-robin from MultinodeStatementService
- [ ] Remove XA-specific server selection
- [ ] Ensure XA connections participate in health checks

### 3. Configuration Changes

#### 3.1 Pool Configuration
- [ ] Use same PoolConfig as non-XA pools
- [ ] Map client pool properties to Narayana
- [ ] Support datasource-specific configuration
- [ ] Multinode pool division for XA

#### 3.2 Transaction Manager Configuration
- [ ] Narayana transaction manager initialization
- [ ] Transaction logging configuration
- [ ] Recovery manager setup
- [ ] Resource naming strategy

## Concerns & Questions

### 1. Transaction State Tracking
**Concern:** How to reliably detect if connection was altered before xaStart?

**Proposed Solution:**
- Add `connectionAltered` flag to OjpXALogicalConnection
- Track operations: setAutoCommit, executeQuery, executeUpdate, setTransactionIsolation, setReadOnly, setCatalog, setSchema
- Reset flag after successful xaStart
- Prevent retry if flag is true

### 2. Session Invalidation
**Concern:** Current code binds XA sessions to specific servers. Removing this means XA operations could hit different servers.

**Analysis:**
- With Narayana pool on server-side, each server has its own XA pool
- XA operations must stay on same server within a transaction
- Solution: Session affinity via SessionInfo (already tracked)
- XA operations route to server via sessionUUID, not connection binding

**Decision:** Session-level binding is sufficient. Remove connection-level binding.

### 3. Recovery Implications
**Concern:** Narayana recovery needs stable resource names. With round-robin, prepared transactions could be on any server.

**Analysis:**
- Each OJP server runs its own Narayana instance
- Each server's Narayana manages recovery for its own XA resources
- Prepared transactions are server-local
- If server fails, prepared transactions are lost (acceptable for OJP proxy use case)
- For true distributed recovery, would need shared transaction log (out of scope)

**Decision:** Document limitation. Each server recovers its own transactions only.

### 4. Pool Configuration Parity
**Concern:** Should Narayana pool use same configuration as non-XA pools?

**Decision:** YES
- Use same PoolConfig class
- Same property names (ojp.connection.pool.*)
- Same multinode pool division logic
- Separate provider implementation

### 5. Backward Compatibility
**Concern:** Breaking changes for existing XA users

**Mitigation:**
- Document breaking changes clearly
- Provide migration guide
- Update version to 0.4.0 (breaking change)
- Keep XA API the same (javax.sql.XAConnection)

## Implementation Phases

### Phase 1: Narayana Module (Week 1)
1. Create ojp-datasource-narayana module structure
2. Add Narayana dependencies
3. Implement NarayanaXAConnectionPoolProvider
4. Basic unit tests with H2 XA

### Phase 2: Server Integration (Week 2)
1. Modify StatementServiceImpl to use XA pool provider
2. Remove direct XADataSource creation
3. Configure Narayana pools from client properties
4. Integration tests

### Phase 3: Client Refactoring (Week 3)
1. Remove server binding from OjpXAConnection
2. Add connection alteration tracking
3. Update retry logic
4. Remove XAConnectionRedistributor (or adapt)

### Phase 4: Testing & Documentation (Week 4)
1. Multinode XA testing
2. Failover testing
3. Performance testing
4. Documentation updates
5. Migration guide

## Technical Details

### Narayana Dependencies

```xml
<dependency>
    <groupId>org.jboss.narayana.jta</groupId>
    <artifactId>narayana-jta</artifactId>
    <version>7.0.2.Final</version>
</dependency>
<dependency>
    <groupId>org.jboss</groupId>
    <artifactId>jboss-transaction-spi</artifactId>
    <version>8.0.0.Final</version>
</dependency>
```

### XAConnectionPoolProvider Interface

```java
package org.openjproxy.datasource;

import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Map;

public interface XAConnectionPoolProvider extends ConnectionPoolProvider {
    
    /**
     * Creates an XA-aware pooled DataSource.
     */
    XADataSource createXADataSource(PoolConfig config) throws SQLException;
    
    /**
     * Closes an XA DataSource and releases resources.
     */
    void closeXADataSource(XADataSource xaDataSource) throws Exception;
    
    /**
     * Gets statistics for an XA DataSource.
     */
    Map<String, Object> getXAStatistics(XADataSource xaDataSource);
}
```

### Connection Alteration Tracking

```java
public class OjpXALogicalConnection extends Connection {
    private volatile boolean connectionAltered = false;
    
    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        this.connectionAltered = true;
        super.setAutoCommit(autoCommit);
    }
    
    // Similar for all mutation methods...
    
    public boolean isConnectionAltered() {
        return connectionAltered;
    }
    
    public void resetAlterationFlag() {
        this.connectionAltered = false;
    }
}
```

### XAStart Retry Logic

```java
public void start(Xid xid, int flags) throws XAException {
    int retries = 0;
    int maxRetries = 3;
    
    while (retries < maxRetries) {
        try {
            // Check if connection was altered
            if (connection instanceof OjpXALogicalConnection) {
                OjpXALogicalConnection logicalConn = (OjpXALogicalConnection) connection;
                if (logicalConn.isConnectionAltered() && retries > 0) {
                    throw new XAException("Cannot retry xaStart - connection was altered");
                }
            }
            
            // Attempt xaStart
            statementService.xaStart(sessionInfo, xid, flags);
            return; // Success
            
        } catch (Exception e) {
            retries++;
            if (retries >= maxRetries) {
                throw new XAException("xaStart failed after " + retries + " retries: " + e.getMessage());
            }
            log.warn("xaStart failed on attempt {}, retrying on different server", retries);
            // Session will automatically retry on next available server
        }
    }
}
```

## Breaking Changes

1. **Server binding removed**: XA connections no longer bind to specific servers
2. **Max transaction limit removed**: Replaced by Narayana pool limits
3. **XAConnectionRedistributor**: May be removed or significantly changed
4. **Configuration**: XA pool configuration now follows same pattern as non-XA

## Migration Guide (To Be Written)

For users upgrading from previous version:
1. Update pool configuration to use unified properties
2. Review transaction limits (now pool-based)
3. Test multinode failover behavior (changed)
4. Update monitoring (new metrics from Narayana)

## Open Questions

1. **Q:** Should we keep XAConnectionRedistributor for rebalancing?
   **A:** TBD - May not be needed if using round-robin

2. **Q:** How to handle transaction recovery UI/monitoring?
   **A:** TBD - Narayana provides JMX beans

3. **Q:** Performance impact of Narayana vs direct XADataSource?
   **A:** Need to benchmark

4. **Q:** Should XA and non-XA share same connection pool provider?
   **A:** NO - XADataSource is different from DataSource interface

## Success Criteria

- [ ] XA connections use Narayana pooling
- [ ] XA load balancing works like non-XA (round-robin)
- [ ] xaStart retry works across servers
- [ ] Connection alteration detection works
- [ ] Multinode pool coordination works for XA
- [ ] All XA tests pass
- [ ] Performance acceptable (< 10% overhead vs direct)
- [ ] Documentation complete

## Next Steps

1. Create ojp-datasource-narayana module
2. Implement NarayanaXAConnectionPoolProvider
3. Add to parent pom
4. Update server to use XA pool provider
5. Remove server binding from client
6. Test and validate

---

**Document Version:** 1.0  
**Last Updated:** December 17, 2024  
**Status:** Planning Complete, Implementation Starting
