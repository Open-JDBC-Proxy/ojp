# Narayana XA Pool Implementation - Current Status

## Document Information
**Date:** December 17, 2024  
**Status:** Phase 1 Complete, Phases 2-4 In Progress  
**Commit:** 7ea3519

## Summary

Implementation of Narayana-only XA connection pooling is underway per @rrobetti's request. Phase 1 (core infrastructure) is complete. Phases 2-4 require continued work.

## What's Been Completed ✅

### Phase 1: Core Infrastructure (100% Complete)

1. **SPI Design & Implementation**
   - Created `XAConnectionPoolProvider` interface extending `ConnectionPoolProvider`
   - Created `XAConnectionPoolProviderRegistry` for ServiceLoader discovery
   - Designed to work alongside existing non-XA pool providers

2. **Narayana Module Created**
   - `ojp-datasource-narayana` module with full structure
   - Maven dependencies configured (Narayana 7.0.2.Final)
   - Module added to parent pom and compiles successfully

3. **Provider Implementation**
   - `NarayanaXAConnectionPoolProvider` - Main provider class
   - `NarayanaPooledXADataSource` - Pooled XA datasource with:
     - Concurrent connection pool (ConcurrentLinkedQueue)
     - Atomic counters for active/idle/total connections
     - Thread-safe operations with ReentrantLock
     - Connection validation and lifecycle management
     - Dynamic resizing support
   - `PooledXAConnectionWrapper` - Pool-aware XA connection wrapper
   - ServiceLoader registration file

4. **Database Support**
   - PostgreSQL XA (via reflection)
   - MySQL XA (via reflection)
   - H2 XA (via reflection)
   - Extensible for Oracle/SQL Server

5. **Narayana Integration**
   - Transaction manager initialization
   - Recovery manager setup
   - XAResourceRecoveryHelper implementation
   - Configurable transaction logging

6. **Documentation**
   - Comprehensive implementation plan (NARAYANA_XA_POOL_IMPLEMENTATION_PLAN.md)
   - Technical design decisions documented
   - Concerns and questions identified

## What Remains To Be Done 🚧

### Phase 2: Server Integration (0% Complete)

**Critical for functionality - XA pooling won't work without this**

1. **Modify ojp-server dependencies**
   - Add `ojp-datasource-narayana` dependency to server pom.xml

2. **Update StatementServiceImpl**
   - Replace direct `XADataSource` creation with `XAConnectionPoolProviderRegistry`
   - Remove `xaDataSourceMap` (Map<String, XADataSource>)
   - Use XA pool provider to create pooled XA datasources
   - Configure pool from client ConnectionDetails properties
   - Map PoolConfig from client properties

3. **Remove max transaction limit code**
   - Identify where transaction limits are enforced
   - Replace with Narayana pool-based limits
   - Update related documentation

**Files to modify:**
- `ojp-server/pom.xml`
- `ojp-server/.../StatementServiceImpl.java`
- `ojp-server/.../SessionManagerImpl.java` (possibly)

### Phase 3: Client Refactoring (0% Complete)

**Critical for round-robin load balancing**

1. **Remove Server Binding**
   - Remove `boundServerAddress` field from `OjpXAConnection`
   - Remove server tracking logic
   - Remove `ServerHealthListener` implementation from XA connection
   - Update session creation to not bind to specific server

2. **Connection Alteration Tracking**
   - Add `connectionAltered` flag to `OjpXALogicalConnection`
   - Track these operations as "alterations":
     - `setAutoCommit()`
     - `executeQuery()`, `executeUpdate()`, `execute()`
     - `setTransactionIsolation()`
     - `setReadOnly()`
     - `setCatalog()`, `setSchema()`
   - Reset flag after successful `xaStart`

3. **Update XA Retry Logic**
   - Modify `OjpXAResource.start()` to retry on different servers
   - Check `connectionAltered` flag before retry
   - Throw exception if connection was altered
   - Use round-robin server selection (like non-XA)

4. **Update or Remove XAConnectionRedistributor**
   - Current implementation assumes server binding
   - Either remove completely OR
   - Adapt to work with round-robin (may not be needed)

5. **Update ConnectionTracker**
   - Remove XA connection-to-server binding tracking
   - Update for non-bound XA connections

**Files to modify:**
- `ojp-jdbc-driver/.../OjpXAConnection.java`
- `ojp-jdbc-driver/.../OjpXALogicalConnection.java`
- `ojp-jdbc-driver/.../OjpXAResource.java`
- `ojp-jdbc-driver/.../XAConnectionRedistributor.java` (remove or refactor)
- `ojp-jdbc-driver/.../ConnectionTracker.java`

### Phase 4: Testing & Validation (0% Complete)

1. **Unit Tests**
   - `NarayanaXAConnectionPoolProviderTest`
   - Pool behavior tests (acquire, release, resize)
   - Connection validation tests
   - Concurrency tests

2. **Integration Tests**
   - PostgreSQL XA with Narayana pool
   - Round-robin load balancing verification
   - Failover testing (xaStart retry)
   - Connection alteration detection tests

3. **Multinode Tests**
   - Pool coordination across servers
   - Health check integration
   - Server recovery scenarios

4. **Performance Tests**
   - Benchmark vs direct XADataSource
   - Measure pooling overhead
   - Concurrent transaction throughput

5. **Documentation Updates**
   - Update XA_SUPPORT.md
   - Update configuration documentation
   - Create migration guide from old XA approach
   - Update examples

## Critical Path Items

To get a **minimally working** implementation:

1. **Must Do (Phase 2):**
   - Add narayana dependency to server
   - Modify StatementServiceImpl to use XA pool provider
   - This enables pooled XA connections

2. **Must Do (Phase 3):**
   - Remove server binding from OjpXAConnection
   - Update round-robin logic
   - This enables load balancing

3. **Should Do (Phase 3):**
   - Add connection alteration tracking
   - Update retry logic
   - This enables safe retries

4. **Should Do (Phase 4):**
   - Basic integration test
   - Verify pooling works
   - Verify load balancing works

## Estimated Effort Remaining

- **Phase 2 (Server Integration):** 2-3 days
- **Phase 3 (Client Refactoring):** 3-4 days
- **Phase 4 (Testing & Docs):** 2-3 days

**Total:** 7-10 days of focused development

## Technical Risks & Concerns

### 1. Transaction Recovery ⚠️
**Status:** Placeholder implementation  
**Risk:** Medium  
**Details:** `getXAResources()` returns empty array. Need to enumerate active XA resources for proper recovery.  
**Mitigation:** Each server recovers its own transactions (documented limitation).

### 2. Connection State Tracking ⚠️
**Status:** Not implemented  
**Risk:** Medium  
**Details:** Must reliably detect if connection was used before xaStart retry.  
**Mitigation:** Add comprehensive tracking in OjpXALogicalConnection.

### 3. Session Routing 🤔
**Status:** Needs verification  
**Risk:** Low  
**Question:** How does sessionUUID ensure XA operations hit the same server?  
**Answer:** StatementService routes by session, which is server-bound. Connection-level binding not needed.

### 4. Breaking Changes 🔴
**Status:** Confirmed  
**Impact:** High  
**Changes:**
- XA connections no longer bind to specific servers
- Load balancing behavior changes
- Max transaction limit removed
- Configuration may change

**Recommendation:** Bump version to 0.4.0

### 5. Performance 📊
**Status:** Unknown  
**Risk:** Low  
**Details:** Haven't benchmarked pooling overhead.  
**Mitigation:** Expect pooling to improve performance (connection reuse).

## Dependencies

### Build Dependencies Added
- Narayana JTA 7.0.2.Final
- JBoss Transaction SPI 8.0.0.Final
- Jakarta Transaction API 2.0.1

### Runtime Dependencies
- All of above
- Database-specific JDBC drivers (reflection-based, not compile-time)

## Open Questions

1. **Q:** Should we support Atomikos as well, or Narayana-only as requested?
   **A:** User requested Narayana-only. Can add Atomikos later if needed.

2. **Q:** How to handle connection pooling for multinode scenarios?
   **A:** Each OJP server has its own Narayana instance and pool. Use same division logic as non-XA pools.

3. **Q:** What about XA transaction timeout configuration?
   **A:** Use standard PoolConfig timeout fields. May need XA-specific timeout later.

4. **Q:** Should pool statistics be exposed via JMX?
   **A:** Future enhancement. Basic getXAStatistics() is sufficient for now.

5. **Q:** How to test without actual XA transaction manager?
   **A:** Narayana provides its own TM. Can test with standard JDBC XA API.

## Next Actions

### Immediate (This Session if Time Permits)
1. Start Phase 2: Add narayana dependency to ojp-server pom.xml
2. Begin modifying StatementServiceImpl

### Short Term (Next Session)
1. Complete Phase 2: Server integration
2. Start Phase 3: Client refactoring
3. Remove server binding logic

### Medium Term (Follow-up Sessions)
1. Complete Phase 3: Client refactoring
2. Add connection alteration tracking
3. Update retry logic

### Long Term
1. Phase 4: Comprehensive testing
2. Documentation updates
3. Migration guide
4. Performance validation

## Success Criteria

- [ ] XA connections use Narayana pooling (not direct XADataSource)
- [ ] Round-robin load balancing works for XA (like non-XA)
- [ ] xaStart retry works across servers
- [ ] Connection alteration detection prevents invalid retries
- [ ] Multinode pool coordination works
- [ ] All existing XA tests pass
- [ ] New tests demonstrate pooling and load balancing
- [ ] Documentation updated

## Conclusion

**Phase 1 is complete.** The Narayana XA connection pool provider module is implemented, compiling, and ready for integration. However, **significant work remains** in Phases 2-4 to actually use this module in the server and client, and to remove the server-binding logic as requested.

The implementation is on track but requires continued effort across multiple sessions to complete all requested functionality.

---

**For Questions:** See NARAYANA_XA_POOL_IMPLEMENTATION_PLAN.md for detailed technical design  
**Status Updates:** This document will be updated as phases progress
