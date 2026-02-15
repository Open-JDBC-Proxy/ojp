# Request Lifecycle Interceptor Pattern - Implementation Plan

## Overview

This document provides a phased implementation plan for the Request Lifecycle Interceptor Pattern in OJP. Each phase is designed to be completed in a single Copilot session with clear objectives, deliverables, and validation criteria.

## Current State

- **Documentation Complete**: Full design spec, ADR, and visual guides created
- **Main Branch Status**: Merged with latest changes including enhanced Calcite integration
- **Target**: Implement the pattern and migrate SqlEnhancerEngine as the first interceptor

## Goals

1. Implement the core interceptor infrastructure
2. Migrate Apache Calcite integration from hard-coded to interceptor-based
3. Enable Calcite to be deployed as an external module in `ojp-libs/`
4. Maintain full backward compatibility throughout

## Implementation Phases

### Phase 1: Core Interceptor Infrastructure (Session 1)

**Objective**: Create the foundational interceptor API and registry

**Deliverables**:
1. New module: `ojp-interceptor-api`
   - `RequestInterceptor` interface
   - `RequestContext` implementation
   - `InterceptorChain` implementation
   - `LifecyclePhase` enum
   - `RequestType` enum
   - `RequestInterceptorRegistry` with ServiceLoader support

2. Maven configuration
   - Add module to parent POM
   - Set up dependencies
   - Configure ServiceLoader directory structure

3. Unit tests
   - Registry discovery tests
   - Context manipulation tests
   - Chain execution tests
   - Priority ordering tests

**Validation**:
- [ ] All classes compile without errors
- [ ] Unit tests pass (coverage >80%)
- [ ] ServiceLoader discovery works correctly
- [ ] Maven build succeeds

**Estimated Time**: 3-4 hours

**Key Files**:
```
ojp-interceptor-api/
├── pom.xml
└── src/main/java/org/openjproxy/interceptor/
    ├── RequestInterceptor.java
    ├── RequestContext.java
    ├── InterceptorChain.java
    ├── LifecyclePhase.java
    ├── RequestType.java
    ├── DataSourceMetadata.java
    └── RequestInterceptorRegistry.java
```

---

### Phase 2: Integration Layer in StatementServiceImpl (Session 2)

**Objective**: Add interceptor invocation points without breaking existing functionality

**Deliverables**:
1. `InterceptorChainExecutor` class
   - Handles phase transitions
   - Manages exception propagation
   - Coordinates interceptor chain

2. `DefaultRequestContext` implementation
   - Mutable context object
   - Attribute storage
   - Phase tracking

3. Integration into `StatementServiceImpl`
   - Add interceptor invocation wrapper
   - Keep existing code paths active
   - Feature flag for gradual rollout

4. Configuration support
   - Add `interceptor.enabled` property
   - Add phase-specific enable/disable flags

5. Integration tests
   - Empty chain (no interceptors) tests
   - Verify existing functionality unchanged

**Validation**:
- [ ] Existing tests pass without modification
- [ ] No performance degradation (< 1% overhead)
- [ ] Feature flag works correctly
- [ ] Chain invocation traced in logs

**Estimated Time**: 4-5 hours

**Key Changes**:
- `ojp-server/pom.xml` - Add dependency on `ojp-interceptor-api`
- `StatementServiceImpl.java` - Add interceptor integration
- `ServerConfiguration.java` - Add interceptor configuration
- New: `InterceptorChainExecutor.java`
- New: `DefaultRequestContext.java`

---

### Phase 3: SQL Enhancer Interceptor Implementation (Session 3)

**Objective**: Migrate SqlEnhancerEngine to be an interceptor

**Deliverables**:
1. New module: `ojp-sql-enhancer-interceptor`
   - `SqlEnhancerInterceptor` implementation
   - ServiceLoader registration
   - Configuration support
   - All Calcite dependencies

2. Refactor `SqlEnhancerEngine`
   - Extract into separate module
   - Make standalone (no StatementServiceImpl dependency)
   - Keep all existing functionality

3. Feature flag migration
   - Support both old and new paths
   - `interceptor.sql-enhancer.use-legacy=false` to switch

4. Integration tests
   - Migrate `PostgresSqlEnhancerIntegrationTest`
   - Verify identical behavior
   - Test with interceptor enabled/disabled

**Validation**:
- [ ] All SQL enhancer tests pass
- [ ] Interceptor version produces same results as legacy
- [ ] Can switch between legacy and interceptor via flag
- [ ] No regression in Calcite functionality

**Estimated Time**: 5-6 hours

**Module Structure**:
```
ojp-sql-enhancer-interceptor/
├── pom.xml (includes Calcite dependencies)
└── src/main/java/org/openjproxy/interceptor/sql/
    ├── SqlEnhancerInterceptor.java
    └── SqlEnhancerEngine.java (moved from ojp-server)
└── src/main/resources/
    └── META-INF/services/
        └── org.openjproxy.interceptor.RequestInterceptor
```

---

### Phase 4: External Deployment Support (Session 4)

**Objective**: Enable SqlEnhancerInterceptor to be loaded from `ojp-libs/`

**Deliverables**:
1. Build shaded JAR
   - Include all Calcite dependencies
   - Proper ServiceLoader metadata
   - Minimal package conflicts

2. Update DriverLoader
   - Ensure interceptors are discovered from `ojp-libs/`
   - Test ClassLoader isolation

3. Documentation
   - Update `DRIVERS_AND_LIBS.md`
   - Add deployment guide for SQL Enhancer
   - Create troubleshooting section

4. Integration tests
   - Test loading from `ojp-libs/`
   - Verify ClassLoader isolation
   - Test enable/disable scenarios

**Validation**:
- [ ] JAR loads correctly from `ojp-libs/`
- [ ] ServiceLoader discovers interceptor
- [ ] No ClassLoader conflicts
- [ ] Documentation complete

**Estimated Time**: 3-4 hours

**Key Files**:
- `ojp-sql-enhancer-interceptor/pom.xml` - Add maven-shade-plugin
- `documents/configuration/DRIVERS_AND_LIBS.md` - Update
- New: `documents/guides/SQL_ENHANCER_DEPLOYMENT.md`

---

### Phase 5: Legacy Code Removal (Session 5)

**Objective**: Remove hard-coded SqlEnhancer integration

**Deliverables**:
1. Remove from `StatementServiceImpl`
   - Delete `sqlEnhancerEngine` field
   - Remove `createSqlEnhancerEngine()` method
   - Remove direct enhancement calls
   - Clean up imports

2. Remove from `ServerConfiguration`
   - Remove SQL enhancer specific properties
   - Keep interceptor configuration only

3. Update dependencies
   - Remove Calcite from `ojp-server/pom.xml`
   - Verify clean build

4. Update tests
   - Remove legacy-specific tests
   - Keep interceptor tests only

5. Documentation updates
   - Update configuration docs
   - Mark legacy properties as deprecated
   - Update migration guide

**Validation**:
- [ ] Build succeeds without Calcite in ojp-server
- [ ] All tests pass
- [ ] SQL enhancer works only via interceptor
- [ ] Documentation updated

**Estimated Time**: 2-3 hours

---

### Phase 6: Testing and Documentation (Session 6)

**Objective**: Comprehensive testing and documentation finalization

**Deliverables**:
1. Performance testing
   - Benchmark overhead with 0, 1, 3, 5 interceptors
   - Verify < 1% impact
   - Document results

2. Integration testing
   - Test all databases (PostgreSQL, MySQL, Oracle, etc.)
   - Test multinode scenarios
   - Test enable/disable via configuration

3. Documentation
   - Complete `Understanding-OJP-Interceptors.md`
   - Add examples for third-party developers
   - Update `Understanding-OJP-SPIs.md`
   - Create video/GIF demonstrations

4. Migration guide
   - Step-by-step upgrade instructions
   - Configuration changes needed
   - Troubleshooting common issues

**Validation**:
- [ ] Performance tests show < 1% overhead
- [ ] All integration tests pass
- [ ] Documentation reviewed and approved
- [ ] Ready for production use

**Estimated Time**: 4-5 hours

---

## Total Timeline

- **Phase 1**: 3-4 hours (Core infrastructure)
- **Phase 2**: 4-5 hours (Integration layer)
- **Phase 3**: 5-6 hours (SQL Enhancer interceptor)
- **Phase 4**: 3-4 hours (External deployment)
- **Phase 5**: 2-3 hours (Legacy removal)
- **Phase 6**: 4-5 hours (Testing & docs)

**Total**: 21-27 hours (6 Copilot sessions)

## Success Criteria

### Functional Requirements
- [x] Request Lifecycle Interceptor Pattern analysis complete
- [ ] Core interceptor API implemented
- [ ] SqlEnhancerEngine migrated to interceptor
- [ ] Can load SQL Enhancer from `ojp-libs/`
- [ ] All existing tests pass
- [ ] No breaking changes for users

### Non-Functional Requirements
- [ ] Performance overhead < 1%
- [ ] Code coverage > 80%
- [ ] Documentation complete
- [ ] Backward compatible

### Quality Gates
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] No SonarQube critical issues
- [ ] Maven build succeeds
- [ ] Code review approved

## Phase 1 Detailed Plan (Current Session)

### Step 1: Create ojp-interceptor-api Module

**1.1 Create Module Structure**
```bash
mkdir -p ojp-interceptor-api/src/main/java/org/openjproxy/interceptor
mkdir -p ojp-interceptor-api/src/test/java/org/openjproxy/interceptor
```

**1.2 Create pom.xml**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.openjproxy</groupId>
        <artifactId>ojp-parent</artifactId>
        <version>0.3.2-snapshot</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>ojp-interceptor-api</artifactId>
    <version>0.3.2-snapshot</version>
    <name>OJP Interceptor API</name>
    <description>Request Lifecycle Interceptor SPI for OJP</description>

    <dependencies>
        <!-- Minimal dependencies - only what's needed for the API -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        
        <!-- For SessionInfo and gRPC types -->
        <dependency>
            <groupId>org.openjproxy</groupId>
            <artifactId>ojp-grpc-commons</artifactId>
            <version>0.3.2-snapshot</version>
        </dependency>

        <!-- Test dependencies -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

**1.3 Add to Parent POM**
Add to `<modules>` section in root `pom.xml`:
```xml
<module>ojp-interceptor-api</module>
```

### Step 2: Implement Core Interfaces

**2.1 LifecyclePhase.java**
```java
package org.openjproxy.interceptor;

public enum LifecyclePhase {
    PRE_REQUEST,
    PRE_EXECUTION,
    RESOURCE_ACQUISITION,
    EXECUTION,
    POST_EXECUTION,
    RESOURCE_RELEASE,
    POST_REQUEST,
    EXCEPTION_HANDLING
}
```

**2.2 RequestType.java**
```java
package org.openjproxy.interceptor;

public enum RequestType {
    QUERY,
    UPDATE,
    BATCH,
    CALLABLE,
    TRANSACTION,
    XA_OPERATION,
    CONNECTION,
    RESULT_SET_FETCH,
    LOB_OPERATION
}
```

**2.3 RequestInterceptor.java**
```java
package org.openjproxy.interceptor;

public interface RequestInterceptor {
    String id();
    
    default int getPriority() {
        return 0;
    }
    
    default boolean isAvailable() {
        return true;
    }
    
    default boolean supportsRequestType(RequestType requestType) {
        return true;
    }
    
    default boolean supportsPhase(LifecyclePhase phase) {
        return true;
    }
    
    void intercept(RequestContext context, InterceptorChain chain) throws Exception;
}
```

**2.4 RequestContext.java** (interface)
**2.5 DefaultRequestContext.java** (implementation)
**2.6 InterceptorChain.java** (interface)
**2.7 DefaultInterceptorChain.java** (implementation)
**2.8 RequestInterceptorRegistry.java**

### Step 3: Write Tests

**3.1 RequestInterceptorRegistryTest.java**
- Test ServiceLoader discovery
- Test priority ordering
- Test filtering by type/phase
- Test empty registry handling

**3.2 DefaultRequestContextTest.java**
- Test attribute storage
- Test immutable vs mutable fields
- Test phase transitions

**3.3 DefaultInterceptorChainTest.java**
- Test chain progression
- Test short-circuit
- Test exception propagation

### Step 4: Build and Validate

**4.1 Compile**
```bash
mvn clean compile -pl ojp-interceptor-api
```

**4.2 Test**
```bash
mvn test -pl ojp-interceptor-api
```

**4.3 Verify**
- Check test coverage
- Ensure no compilation warnings
- Verify ServiceLoader metadata

## Next Steps After Phase 1

Once Phase 1 is complete and validated:
1. Create PR for review
2. Merge to main
3. Plan Phase 2 session
4. Continue with integration layer

## Notes

- Each phase should be merged before starting the next
- Feature flags allow gradual rollout
- Backward compatibility is critical
- Test coverage must remain high (>80%)
- Document all configuration changes

---

**Document Version**: 1.0  
**Created**: 2026-02-15  
**Status**: READY FOR IMPLEMENTATION  
**Next Session**: Phase 1 - Core Interceptor Infrastructure
