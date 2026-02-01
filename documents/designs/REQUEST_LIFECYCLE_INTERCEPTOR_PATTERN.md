# Request Lifecycle Interceptor Pattern for OJP

## Executive Summary

This document analyzes a new integration pattern for OJP that standardizes how libraries and modules interact with the request lifecycle. The pattern is inspired by Servlet Filters and implements a Chain of Responsibility approach, allowing external providers to create interceptors that can be loaded via ServiceLoader.

## Current State Analysis

### Existing Integration Points

Currently, OJP has three main libraries/modules that integrate directly with the request execution flow:

1. **CircuitBreaker** - Integrated directly in `StatementServiceImpl`
   - Called at: pre-execution (`preCheck`), post-success (`onSuccess`), post-failure (`onFailure`)
   - Current integration: Hard-coded method calls in `executeQuery()` and `executeUpdate()`

2. **SlowQuerySegregationManager** - Integrated directly in `StatementServiceImpl`
   - Called at: wraps the entire execution with `executeWithSegregation()`
   - Current integration: Hard-coded wrapper around query/update execution
   - Per-datasource instances managed in a `ConcurrentHashMap`

3. **SqlEnhancerEngine** (Apache Calcite) - Integrated directly in query execution
   - Called at: pre-execution phase (SQL transformation)
   - Current integration: Direct method call in `executeQueryInternal()`
   - Enabled/disabled via configuration flag

### Current Request Lifecycle Flow

```
executeQuery/executeUpdate (public API)
  ↓
1. updateSessionActivity()
2. hashSqlQuery() 
3. processClusterHealth()
4. circuitBreaker.preCheck()          ← Integration Point 1
5. getSlowQuerySegregationManager()
6. manager.executeWithSegregation()   ← Integration Point 2
   ↓
   executeQueryInternal/executeUpdateInternal
     ↓
   7. sessionConnection()
   8. sqlEnhancerEngine.enhance()      ← Integration Point 3
   9. Statement creation & execution
   10. Result processing
     ↓
11. circuitBreaker.onSuccess()        ← Integration Point 4
    OR
    circuitBreaker.onFailure()        ← Integration Point 5
```

### Existing SPI Pattern

OJP already uses the ServiceLoader pattern successfully for:

- **ConnectionPoolProvider** - Standard connection pools (HikariCP, DBCP)
- **XAConnectionPoolProvider** - XA transaction pools
- **JDBC Drivers** - Database drivers loaded from `ojp-libs` directory

This demonstrates OJP's commitment to extensibility and provides a proven pattern to follow.

## Problem Statement

### Current Limitations

1. **Tight Coupling**: Features like CircuitBreaker, SlowQuerySegregation, and SqlEnhancer are directly coupled to `StatementServiceImpl`
2. **Hard to Extend**: Adding new features requires modifying core classes
3. **No Standardization**: Each feature integrates differently (method calls, wrappers, flags)
4. **No Third-Party Support**: External providers cannot add their own lifecycle hooks
5. **Testing Complexity**: Difficult to test features in isolation
6. **Maintenance Burden**: `StatementServiceImpl` is already a 2,528-line class

### Desired Capabilities

External providers should be able to:

1. **Intercept Requests**: Hook into various phases of request processing
2. **Transform SQL**: Modify SQL before execution (like Calcite does)
3. **Control Execution**: Decide whether to proceed or short-circuit
4. **Monitor Performance**: Track timing and metrics
5. **Handle Failures**: React to exceptions and implement retry logic
6. **Manage Resources**: Acquire/release resources around execution
7. **Chain Together**: Multiple interceptors working in sequence

## Proposed Solution: Request Lifecycle Interceptor Pattern

### Design Principles

1. **Chain of Responsibility**: Interceptors form a chain, each can pass control to the next
2. **Servlet Filter Model**: Inspired by the well-understood `javax.servlet.Filter` pattern
3. **ServiceLoader Discovery**: External implementations loaded automatically
4. **Backward Compatible**: Existing features can be migrated gradually
5. **Minimal Overhead**: Negligible performance impact when interceptors are not present

### Request Lifecycle Phases

The request lifecycle is divided into distinct phases where interceptors can hook:

```
┌─────────────────────────────────────────────────────────────┐
│                    REQUEST RECEIVED                          │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 1: PRE_REQUEST                                        │
│  - Session validation                                        │
│  - Cluster health processing                                 │
│  - Request enrichment                                        │
│  Examples: Authentication, Rate Limiting, Logging            │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 2: PRE_EXECUTION                                      │
│  - SQL hash generation                                       │
│  - Circuit breaker pre-check                                 │
│  - SQL transformation/optimization                           │
│  Examples: Circuit Breaker, SQL Rewriting, Query Validation  │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 3: RESOURCE_ACQUISITION                               │
│  - Slow query slot acquisition                               │
│  - Connection acquisition                                    │
│  - Transaction management                                    │
│  Examples: Slow Query Segregation, Connection Pooling        │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 4: EXECUTION                                          │
│  - Statement preparation                                     │
│  - Parameter binding                                         │
│  - Actual database execution                                 │
│  Examples: Query Execution Monitoring, Execution Tracing     │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 5: POST_EXECUTION                                     │
│  - Result processing                                         │
│  - Metadata extraction                                       │
│  - Performance recording                                     │
│  Examples: Result Caching, Performance Metrics               │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 6: RESOURCE_RELEASE                                   │
│  - Connection release                                        │
│  - Slot release                                              │
│  - Cleanup                                                   │
│  Examples: Resource Tracking, Leak Detection                 │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 7: POST_REQUEST                                       │
│  - Success/failure recording                                 │
│  - Metrics publishing                                        │
│  - Logging                                                   │
│  Examples: Audit Logging, Circuit Breaker State Update       │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    RESPONSE SENT                             │
└─────────────────────────────────────────────────────────────┘

Exception Flow:
┌─────────────────────────────────────────────────────────────┐
│  Phase X: EXCEPTION_HANDLING                                 │
│  - Exception transformation                                  │
│  - Failure recording                                         │
│  - Recovery attempts                                         │
│  Examples: Circuit Breaker Failure Recording, Retry Logic    │
└─────────────────────────────────────────────────────────────┘
```

## Interface Design

### Core Interfaces

#### 1. RequestInterceptor - Main Interceptor Interface

```java
package org.openjproxy.interceptor;

/**
 * Service Provider Interface (SPI) for request lifecycle interceptors.
 * 
 * <p>Interceptors can hook into various phases of request processing to provide
 * cross-cutting concerns like monitoring, transformation, circuit breaking,
 * and resource management. This pattern is inspired by Servlet Filters and
 * implements a Chain of Responsibility approach.</p>
 * 
 * <p>Implementations should be registered via the standard Java
 * {@link java.util.ServiceLoader} mechanism by creating a file named
 * {@code META-INF/services/org.openjproxy.interceptor.RequestInterceptor}
 * containing the fully qualified class name of the implementation.</p>
 * 
 * <p>Interceptors are invoked in priority order (highest first) and can:
 * <ul>
 *   <li>Inspect and modify the request context</li>
 *   <li>Short-circuit the chain by not calling chain.proceed()</li>
 *   <li>Wrap the execution with try-catch-finally logic</li>
 *   <li>Transform SQL or results</li>
 *   <li>Acquire and release resources</li>
 *   <li>Record metrics and handle errors</li>
 * </ul>
 * 
 * @see RequestContext
 * @see InterceptorChain
 */
public interface RequestInterceptor {
    
    /**
     * Returns the unique identifier for this interceptor.
     * 
     * @return the interceptor ID, never null or empty
     */
    String id();
    
    /**
     * Returns the priority of this interceptor for ordering.
     * Higher values indicate higher priority (executed first).
     * 
     * <p>Recommended ranges:
     * <ul>
     *   <li>1000+: Critical infrastructure (authentication, rate limiting)</li>
     *   <li>500-999: Request transformation (SQL enhancement, query rewriting)</li>
     *   <li>100-499: Resource management (circuit breaker, slow query segregation)</li>
     *   <li>0-99: Monitoring and logging</li>
     *   <li>Negative: Post-processing and cleanup</li>
     * </ul>
     * 
     * @return the interceptor priority (default: 0)
     */
    default int getPriority() {
        return 0;
    }
    
    /**
     * Checks if this interceptor is available and should be used.
     * 
     * @return true if available, false otherwise
     */
    default boolean isAvailable() {
        return true;
    }
    
    /**
     * Checks if this interceptor supports the given request type.
     * 
     * @param requestType the type of request (QUERY, UPDATE, TRANSACTION, etc.)
     * @return true if this interceptor should handle this request type
     */
    default boolean supportsRequestType(RequestType requestType) {
        return true; // By default, support all types
    }
    
    /**
     * Checks if this interceptor should run for the given lifecycle phase.
     * 
     * @param phase the lifecycle phase
     * @return true if this interceptor should run in this phase
     */
    default boolean supportsPhase(LifecyclePhase phase) {
        return true; // By default, support all phases
    }
    
    /**
     * Intercepts the request processing at various lifecycle phases.
     * 
     * <p>Implementations must call {@code chain.proceed(context)} to continue
     * the chain, or can choose to short-circuit by not calling it.</p>
     * 
     * <p>Example implementation:
     * <pre>{@code
     * public void intercept(RequestContext context, InterceptorChain chain) 
     *         throws Exception {
     *     // Pre-processing
     *     long start = System.currentTimeMillis();
     *     
     *     try {
     *         // Proceed with the chain
     *         chain.proceed(context);
     *         
     *         // Post-processing on success
     *         long duration = System.currentTimeMillis() - start;
     *         recordSuccess(context, duration);
     *     } catch (Exception e) {
     *         // Handle failure
     *         recordFailure(context, e);
     *         throw e; // Re-throw to propagate
     *     } finally {
     *         // Cleanup
     *         releaseResources();
     *     }
     * }
     * }</pre>
     * 
     * @param context the request context containing all request information
     * @param chain the interceptor chain to continue processing
     * @throws Exception if an error occurs during interception
     */
    void intercept(RequestContext context, InterceptorChain chain) throws Exception;
}
```

#### 2. RequestContext - Contextual Information

```java
package org.openjproxy.interceptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;

/**
 * Context object that flows through the interceptor chain, containing all
 * information about the current request and its execution state.
 * 
 * <p>The context is mutable and can be modified by interceptors. Changes
 * made to the context will be visible to subsequent interceptors in the chain.</p>
 */
public interface RequestContext {
    
    /**
     * Returns the type of request being processed.
     */
    RequestType getRequestType();
    
    /**
     * Returns the current lifecycle phase.
     */
    LifecyclePhase getCurrentPhase();
    
    /**
     * Returns the original SQL statement.
     */
    String getOriginalSql();
    
    /**
     * Returns the current SQL statement (may have been transformed by interceptors).
     */
    String getCurrentSql();
    
    /**
     * Sets the SQL statement (allows transformation).
     */
    void setCurrentSql(String sql);
    
    /**
     * Returns the SQL statement hash for tracking.
     */
    String getSqlHash();
    
    /**
     * Returns the session information.
     */
    SessionInfo getSessionInfo();
    
    /**
     * Returns the connection hash identifying the datasource.
     */
    String getConnectionHash();
    
    /**
     * Returns request parameters (if applicable).
     */
    Optional<Map<String, Object>> getParameters();
    
    /**
     * Returns the database connection (available during and after EXECUTION phase).
     */
    Optional<Connection> getConnection();
    
    /**
     * Sets the database connection.
     */
    void setConnection(Connection connection);
    
    /**
     * Returns the execution result (available during POST_EXECUTION phase).
     */
    Optional<Object> getResult();
    
    /**
     * Sets the execution result.
     */
    void setResult(Object result);
    
    /**
     * Returns the result set (for queries, available during POST_EXECUTION phase).
     */
    Optional<ResultSet> getResultSet();
    
    /**
     * Returns the exception if one occurred (available during EXCEPTION_HANDLING phase).
     */
    Optional<Exception> getException();
    
    /**
     * Sets the exception (allows transformation).
     */
    void setException(Exception exception);
    
    /**
     * Returns execution start time in milliseconds.
     */
    long getStartTimeMillis();
    
    /**
     * Returns execution end time in milliseconds (available after execution).
     */
    Optional<Long> getEndTimeMillis();
    
    /**
     * Gets a custom attribute by key.
     * Interceptors can use this to pass information between each other.
     */
    Object getAttribute(String key);
    
    /**
     * Sets a custom attribute.
     */
    void setAttribute(String key, Object value);
    
    /**
     * Checks if the request has been short-circuited.
     */
    boolean isShortCircuited();
    
    /**
     * Marks the request as short-circuited (stops the chain).
     */
    void setShortCircuited(boolean shortCircuited);
    
    /**
     * Returns metadata about the target datasource.
     */
    DataSourceMetadata getDataSourceMetadata();
}
```

#### 3. InterceptorChain - Chain Management

```java
package org.openjproxy.interceptor;

/**
 * Represents the chain of interceptors to be executed.
 * 
 * <p>Interceptors must call {@code proceed(context)} to continue
 * the chain. If they don't call proceed, the chain is short-circuited.</p>
 */
public interface InterceptorChain {
    
    /**
     * Proceeds to the next interceptor in the chain.
     * 
     * <p>If this is the last interceptor, proceeds to actual request execution.
     * If the context is marked as short-circuited, returns immediately.</p>
     * 
     * @param context the request context
     * @throws Exception if an error occurs during processing
     */
    void proceed(RequestContext context) throws Exception;
    
    /**
     * Returns whether there are more interceptors in the chain.
     */
    boolean hasNext();
    
    /**
     * Returns the index of the current interceptor.
     */
    int getCurrentIndex();
    
    /**
     * Returns the total number of interceptors in the chain.
     */
    int getTotalCount();
}
```

#### 4. Supporting Enums

```java
package org.openjproxy.interceptor;

/**
 * Defines the type of request being processed.
 */
public enum RequestType {
    /** SQL query execution (SELECT) */
    QUERY,
    
    /** SQL update execution (INSERT, UPDATE, DELETE) */
    UPDATE,
    
    /** Batch operation */
    BATCH,
    
    /** Stored procedure call */
    CALLABLE,
    
    /** Transaction management (commit, rollback) */
    TRANSACTION,
    
    /** XA distributed transaction operation */
    XA_OPERATION,
    
    /** Connection management */
    CONNECTION,
    
    /** Result set fetch operation */
    RESULT_SET_FETCH,
    
    /** LOB (Large Object) operation */
    LOB_OPERATION
}

/**
 * Defines the lifecycle phases where interceptors can hook.
 */
public enum LifecyclePhase {
    /** Before request processing begins */
    PRE_REQUEST,
    
    /** Before execution (after SQL hash, before circuit breaker) */
    PRE_EXECUTION,
    
    /** During resource acquisition (connections, slots) */
    RESOURCE_ACQUISITION,
    
    /** During actual database execution */
    EXECUTION,
    
    /** After execution completes successfully */
    POST_EXECUTION,
    
    /** During resource cleanup and release */
    RESOURCE_RELEASE,
    
    /** After request completes (success or failure) */
    POST_REQUEST,
    
    /** When an exception occurs at any phase */
    EXCEPTION_HANDLING
}

/**
 * Metadata about the target datasource.
 */
public interface DataSourceMetadata {
    String getConnectionHash();
    String getDatabaseType();
    String getUrl();
    boolean isXAEnabled();
    Map<String, Object> getPoolStatistics();
}
```

### Registry and Loading

```java
package org.openjproxy.interceptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for discovering and managing RequestInterceptor implementations.
 * 
 * <p>Uses Java's ServiceLoader mechanism to automatically discover interceptors
 * on the classpath and in the external ojp-libs directory.</p>
 */
public final class RequestInterceptorRegistry {
    
    private static final Map<String, RequestInterceptor> interceptors = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;
    private static final Object initLock = new Object();
    
    private RequestInterceptorRegistry() {
        // Utility class
    }
    
    /**
     * Discovers and registers all available RequestInterceptor implementations.
     */
    public static void initialize() {
        if (!initialized) {
            synchronized (initLock) {
                if (!initialized) {
                    loadInterceptors();
                    initialized = true;
                }
            }
        }
    }
    
    private static void loadInterceptors() {
        ServiceLoader<RequestInterceptor> loader = ServiceLoader.load(RequestInterceptor.class);
        
        for (RequestInterceptor interceptor : loader) {
            try {
                if (interceptor.isAvailable()) {
                    interceptors.put(interceptor.id(), interceptor);
                    log.info("Registered RequestInterceptor: {} (priority: {})", 
                            interceptor.id(), interceptor.getPriority());
                }
            } catch (Exception e) {
                log.error("Failed to register interceptor: {}", 
                        interceptor.getClass().getName(), e);
            }
        }
        
        log.info("Loaded {} request interceptors", interceptors.size());
    }
    
    /**
     * Gets all registered interceptors sorted by priority (highest first).
     */
    public static List<RequestInterceptor> getInterceptors() {
        initialize();
        return interceptors.values().stream()
                .sorted(Comparator.comparingInt(RequestInterceptor::getPriority).reversed())
                .collect(Collectors.toList());
    }
    
    /**
     * Gets interceptors filtered by request type and phase, sorted by priority.
     */
    public static List<RequestInterceptor> getInterceptors(RequestType requestType, 
                                                           LifecyclePhase phase) {
        initialize();
        return interceptors.values().stream()
                .filter(i -> i.supportsRequestType(requestType))
                .filter(i -> i.supportsPhase(phase))
                .sorted(Comparator.comparingInt(RequestInterceptor::getPriority).reversed())
                .collect(Collectors.toList());
    }
    
    /**
     * Gets a specific interceptor by ID.
     */
    public static Optional<RequestInterceptor> getInterceptor(String id) {
        initialize();
        return Optional.ofNullable(interceptors.get(id));
    }
}
```

## Implementation Strategy

### Phase 1: Core Infrastructure (Week 1-2)

1. Create new module: `ojp-interceptor-api`
   - Define all interfaces
   - Create default implementations
   - Add to Maven build

2. Implement `RequestContext` and `InterceptorChain`
   - Mutable context object
   - Chain implementation with proceed() logic
   - Phase tracking

3. Implement `RequestInterceptorRegistry`
   - ServiceLoader integration
   - Priority-based sorting
   - Filtering by type and phase

### Phase 2: Integration Layer (Week 3-4)

1. Add interceptor invocation to `StatementServiceImpl`
   - Wrap existing code with interceptor calls
   - Invoke at each lifecycle phase
   - Maintain backward compatibility

2. Create `InterceptorChainExecutor`
   - Handles chain execution
   - Exception propagation
   - Short-circuit logic

### Phase 3: Migrate Existing Features (Week 5-8)

1. **CircuitBreaker** → CircuitBreakerInterceptor
   - Priority: 300
   - Phases: PRE_EXECUTION, POST_REQUEST, EXCEPTION_HANDLING
   - Supports: QUERY, UPDATE

2. **SlowQuerySegregationManager** → SlowQueryInterceptor
   - Priority: 200
   - Phases: RESOURCE_ACQUISITION, RESOURCE_RELEASE
   - Supports: QUERY, UPDATE

3. **SqlEnhancerEngine** → SqlEnhancerInterceptor
   - Priority: 500
   - Phases: PRE_EXECUTION
   - Supports: QUERY

### Phase 4: Documentation and Examples (Week 9-10)

1. Create comprehensive documentation
2. Provide example interceptors
3. Write migration guide
4. Update Understanding-OJP-SPIs.md

## Example Implementations

### Example 1: Circuit Breaker Interceptor

```java
package org.openjproxy.interceptor.builtin;

import org.openjproxy.interceptor.*;

public class CircuitBreakerInterceptor implements RequestInterceptor {
    
    private final CircuitBreaker circuitBreaker;
    
    public CircuitBreakerInterceptor() {
        // Load from configuration
        this.circuitBreaker = new CircuitBreaker(5000, 3);
    }
    
    @Override
    public String id() {
        return "circuit-breaker";
    }
    
    @Override
    public int getPriority() {
        return 300; // Resource management priority
    }
    
    @Override
    public boolean supportsRequestType(RequestType requestType) {
        return requestType == RequestType.QUERY || requestType == RequestType.UPDATE;
    }
    
    @Override
    public boolean supportsPhase(LifecyclePhase phase) {
        return phase == LifecyclePhase.PRE_EXECUTION 
            || phase == LifecyclePhase.POST_REQUEST
            || phase == LifecyclePhase.EXCEPTION_HANDLING;
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
        String sqlHash = context.getSqlHash();
        
        if (context.getCurrentPhase() == LifecyclePhase.PRE_EXECUTION) {
            // Check if circuit is open before execution
            circuitBreaker.preCheck(sqlHash);
            chain.proceed(context);
            
        } else if (context.getCurrentPhase() == LifecyclePhase.POST_REQUEST) {
            // Record success
            if (!context.getException().isPresent()) {
                circuitBreaker.onSuccess(sqlHash);
            }
            chain.proceed(context);
            
        } else if (context.getCurrentPhase() == LifecyclePhase.EXCEPTION_HANDLING) {
            // Record failure
            context.getException().ifPresent(e -> {
                if (e instanceof SQLException) {
                    circuitBreaker.onFailure(sqlHash, (SQLException) e);
                }
            });
            chain.proceed(context);
        }
    }
}
```

### Example 2: Slow Query Segregation Interceptor

```java
package org.openjproxy.interceptor.builtin;

import org.openjproxy.interceptor.*;

public class SlowQueryInterceptor implements RequestInterceptor {
    
    private final Map<String, SlowQuerySegregationManager> managers = new ConcurrentHashMap<>();
    
    @Override
    public String id() {
        return "slow-query-segregation";
    }
    
    @Override
    public int getPriority() {
        return 200; // Resource management, lower than circuit breaker
    }
    
    @Override
    public boolean supportsRequestType(RequestType requestType) {
        return requestType == RequestType.QUERY || requestType == RequestType.UPDATE;
    }
    
    @Override
    public boolean supportsPhase(LifecyclePhase phase) {
        return phase == LifecyclePhase.RESOURCE_ACQUISITION 
            || phase == LifecyclePhase.RESOURCE_RELEASE;
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
        String connHash = context.getConnectionHash();
        String sqlHash = context.getSqlHash();
        SlowQuerySegregationManager manager = getOrCreateManager(connHash);
        
        if (context.getCurrentPhase() == LifecyclePhase.RESOURCE_ACQUISITION) {
            // Store slot acquisition state for cleanup
            boolean isSlowOp = manager.isSlowOperation(sqlHash);
            context.setAttribute("slowQuerySlot.isSlowOp", isSlowOp);
            
            if (isSlowOp) {
                boolean acquired = manager.acquireSlowSlot();
                context.setAttribute("slowQuerySlot.acquired", acquired);
            } else {
                boolean acquired = manager.acquireFastSlot();
                context.setAttribute("slowQuerySlot.acquired", acquired);
            }
            
            chain.proceed(context);
            
        } else if (context.getCurrentPhase() == LifecyclePhase.RESOURCE_RELEASE) {
            // Release the slot
            Boolean isSlowOp = (Boolean) context.getAttribute("slowQuerySlot.isSlowOp");
            Boolean acquired = (Boolean) context.getAttribute("slowQuerySlot.acquired");
            
            if (Boolean.TRUE.equals(acquired)) {
                if (Boolean.TRUE.equals(isSlowOp)) {
                    manager.releaseSlowSlot();
                } else {
                    manager.releaseFastSlot();
                }
            }
            
            chain.proceed(context);
        }
    }
    
    private SlowQuerySegregationManager getOrCreateManager(String connHash) {
        return managers.computeIfAbsent(connHash, k -> 
            new SlowQuerySegregationManager(/* config */));
    }
}
```

### Example 3: SQL Enhancement Interceptor

```java
package org.openjproxy.interceptor.builtin;

import org.openjproxy.interceptor.*;

public class SqlEnhancerInterceptor implements RequestInterceptor {
    
    private final SqlEnhancerEngine enhancerEngine;
    
    public SqlEnhancerInterceptor() {
        // Load configuration
        this.enhancerEngine = new SqlEnhancerEngine(/* config */);
    }
    
    @Override
    public String id() {
        return "sql-enhancer";
    }
    
    @Override
    public int getPriority() {
        return 500; // High priority for SQL transformation
    }
    
    @Override
    public boolean supportsRequestType(RequestType requestType) {
        return requestType == RequestType.QUERY;
    }
    
    @Override
    public boolean supportsPhase(LifecyclePhase phase) {
        return phase == LifecyclePhase.PRE_EXECUTION;
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
        if (!enhancerEngine.isEnabled()) {
            chain.proceed(context);
            return;
        }
        
        String originalSql = context.getCurrentSql();
        
        // Enhance the SQL
        SqlEnhancementResult result = enhancerEngine.enhance(originalSql);
        
        if (result.wasModified()) {
            // Transform the SQL
            context.setCurrentSql(result.getEnhancedSql());
            context.setAttribute("sql.enhanced", true);
            context.setAttribute("sql.enhancementTime", result.getProcessingTimeMs());
        }
        
        chain.proceed(context);
    }
}
```

### Example 4: Custom Audit Logging Interceptor (Third-Party)

```java
package com.example.ojp.interceptor;

import org.openjproxy.interceptor.*;

/**
 * Example third-party interceptor for audit logging.
 * Demonstrates how external providers can create custom interceptors.
 */
public class AuditLoggingInterceptor implements RequestInterceptor {
    
    private final AuditLogger auditLogger;
    
    public AuditLoggingInterceptor() {
        this.auditLogger = AuditLoggerFactory.getLogger();
    }
    
    @Override
    public String id() {
        return "audit-logging";
    }
    
    @Override
    public int getPriority() {
        return 50; // Low priority, runs after most other interceptors
    }
    
    @Override
    public boolean supportsPhase(LifecyclePhase phase) {
        return phase == LifecyclePhase.PRE_REQUEST 
            || phase == LifecyclePhase.POST_REQUEST;
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
        if (context.getCurrentPhase() == LifecyclePhase.PRE_REQUEST) {
            // Log request start
            auditLogger.logRequestStart(
                context.getRequestType(),
                context.getOriginalSql(),
                context.getSessionInfo()
            );
            
            chain.proceed(context);
            
        } else if (context.getCurrentPhase() == LifecyclePhase.POST_REQUEST) {
            // Log request completion
            long duration = context.getEndTimeMillis().orElse(System.currentTimeMillis()) 
                          - context.getStartTimeMillis();
            
            if (context.getException().isPresent()) {
                auditLogger.logRequestFailure(
                    context.getRequestType(),
                    context.getOriginalSql(),
                    duration,
                    context.getException().get()
                );
            } else {
                auditLogger.logRequestSuccess(
                    context.getRequestType(),
                    context.getOriginalSql(),
                    duration
                );
            }
            
            chain.proceed(context);
        }
    }
}
```

## Integration Points in StatementServiceImpl

### Before (Current Code)

```java
public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
    log.info("Executing update {}", request.getSql());
    updateSessionActivity(request.getSession());
    String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
    processClusterHealth(request.getSession());

    try {
        circuitBreaker.preCheck(stmtHash);                    // Hardcoded
        
        String connHash = request.getSession().getConnHash();
        SlowQuerySegregationManager manager = 
            getSlowQuerySegregationManagerForConnection(connHash);  // Hardcoded
        
        OpResult result = manager.executeWithSegregation(      // Hardcoded wrapper
            stmtHash, 
            () -> executeUpdateInternal(request)
        );
        
        responseObserver.onNext(result);
        responseObserver.onCompleted();
        circuitBreaker.onSuccess(stmtHash);                   // Hardcoded
        
    } catch (Exception e) {
        circuitBreaker.onFailure(stmtHash, e);                // Hardcoded
        // ... error handling
    }
}
```

### After (With Interceptors)

```java
public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
    // Create request context
    RequestContext context = RequestContext.builder()
        .requestType(RequestType.UPDATE)
        .originalSql(request.getSql())
        .currentSql(request.getSql())
        .sessionInfo(request.getSession())
        .startTimeMillis(System.currentTimeMillis())
        .build();
    
    try {
        // Execute through interceptor chain
        InterceptorChainExecutor.execute(context, ctx -> {
            // Core execution logic (simplified)
            OpResult result = executeUpdateInternal(request, ctx);
            ctx.setResult(result);
        });
        
        // Send response
        responseObserver.onNext((OpResult) context.getResult().orElseThrow());
        responseObserver.onCompleted();
        
    } catch (Exception e) {
        context.setException(e);
        // Interceptors handle error recording
        sendSQLExceptionMetadata(e, responseObserver);
    }
}
```

Much cleaner! All the cross-cutting concerns are now in interceptors.

## InterceptorChainExecutor Implementation

```java
package org.openjproxy.interceptor;

import java.util.List;

/**
 * Executes the interceptor chain for a request.
 */
public class InterceptorChainExecutor {
    
    /**
     * Executes a request through the full interceptor chain.
     * 
     * @param context the request context
     * @param coreLogic the core business logic to execute
     */
    public static void execute(RequestContext context, CoreLogic coreLogic) throws Exception {
        // Get interceptors for this request type
        List<RequestInterceptor> interceptors = RequestInterceptorRegistry.getInterceptors(
            context.getRequestType(),
            LifecyclePhase.PRE_REQUEST
        );
        
        // Execute PRE_REQUEST phase
        context.setCurrentPhase(LifecyclePhase.PRE_REQUEST);
        executePhase(context, interceptors);
        
        if (!context.isShortCircuited()) {
            try {
                // Execute PRE_EXECUTION phase
                context.setCurrentPhase(LifecyclePhase.PRE_EXECUTION);
                interceptors = RequestInterceptorRegistry.getInterceptors(
                    context.getRequestType(),
                    LifecyclePhase.PRE_EXECUTION
                );
                executePhase(context, interceptors);
                
                // Execute RESOURCE_ACQUISITION phase
                context.setCurrentPhase(LifecyclePhase.RESOURCE_ACQUISITION);
                interceptors = RequestInterceptorRegistry.getInterceptors(
                    context.getRequestType(),
                    LifecyclePhase.RESOURCE_ACQUISITION
                );
                executePhase(context, interceptors);
                
                // Execute core logic
                context.setCurrentPhase(LifecyclePhase.EXECUTION);
                coreLogic.execute(context);
                
                // Execute POST_EXECUTION phase
                context.setCurrentPhase(LifecyclePhase.POST_EXECUTION);
                interceptors = RequestInterceptorRegistry.getInterceptors(
                    context.getRequestType(),
                    LifecyclePhase.POST_EXECUTION
                );
                executePhase(context, interceptors);
                
                // Execute RESOURCE_RELEASE phase
                context.setCurrentPhase(LifecyclePhase.RESOURCE_RELEASE);
                interceptors = RequestInterceptorRegistry.getInterceptors(
                    context.getRequestType(),
                    LifecyclePhase.RESOURCE_RELEASE
                );
                executePhase(context, interceptors);
                
            } catch (Exception e) {
                // Execute EXCEPTION_HANDLING phase
                context.setException(e);
                context.setCurrentPhase(LifecyclePhase.EXCEPTION_HANDLING);
                interceptors = RequestInterceptorRegistry.getInterceptors(
                    context.getRequestType(),
                    LifecyclePhase.EXCEPTION_HANDLING
                );
                executePhase(context, interceptors);
                
                // Re-throw if not handled
                if (context.getException().isPresent()) {
                    throw context.getException().get();
                }
                
            } finally {
                // Execute POST_REQUEST phase (always runs)
                context.setCurrentPhase(LifecyclePhase.POST_REQUEST);
                context.setEndTimeMillis(System.currentTimeMillis());
                interceptors = RequestInterceptorRegistry.getInterceptors(
                    context.getRequestType(),
                    LifecyclePhase.POST_REQUEST
                );
                executePhase(context, interceptors);
            }
        }
    }
    
    private static void executePhase(RequestContext context, List<RequestInterceptor> interceptors) 
            throws Exception {
        if (context.isShortCircuited()) {
            return;
        }
        
        InterceptorChain chain = new DefaultInterceptorChain(interceptors, context);
        chain.proceed(context);
    }
    
    @FunctionalInterface
    public interface CoreLogic {
        void execute(RequestContext context) throws Exception;
    }
}
```

## Benefits of This Approach

### For OJP Core

1. **Reduced Complexity**: `StatementServiceImpl` becomes much simpler
2. **Better Testability**: Each interceptor can be tested independently
3. **Easier Maintenance**: Changes to features don't require touching core code
4. **Clear Separation**: Cross-cutting concerns are separated from business logic

### For OJP Users

1. **Extensibility**: Users can add custom interceptors without modifying OJP
2. **Flexibility**: Interceptors can be enabled/disabled via configuration
3. **Composability**: Multiple interceptors work together seamlessly
4. **Standard Pattern**: Familiar Servlet Filter pattern

### For Third-Party Providers

1. **Easy Integration**: Just implement an interface and add to classpath
2. **No Recompilation**: Drop JAR in `ojp-libs` directory
3. **Full Control**: Access to entire request lifecycle
4. **Rich Context**: All request information available

## Migration Path

### Backward Compatibility

During migration, both old and new approaches will coexist:

1. **Phase 1**: Add interceptor infrastructure (no behavior change)
2. **Phase 2**: Implement interceptor versions of existing features
3. **Phase 3**: Run both old and new code in parallel (feature flags)
4. **Phase 4**: Gradually switch from old to new (per feature)
5. **Phase 5**: Remove old implementations

### Feature Flags

```properties
# ojp-server.properties
interceptor.enabled=true
interceptor.circuit-breaker.enabled=true
interceptor.slow-query.enabled=true
interceptor.sql-enhancer.enabled=true

# During migration, can disable specific interceptors
interceptor.circuit-breaker.use-legacy=false
```

## Configuration

### Interceptor Configuration File

```properties
# interceptors.properties

# Global settings
interceptor.enabled=true
interceptor.registry.scan-ojp-libs=true

# Circuit Breaker
interceptor.circuit-breaker.enabled=true
interceptor.circuit-breaker.priority=300
interceptor.circuit-breaker.open-duration-ms=5000
interceptor.circuit-breaker.failure-threshold=3

# Slow Query Segregation
interceptor.slow-query.enabled=true
interceptor.slow-query.priority=200
interceptor.slow-query.slow-slot-percentage=20
interceptor.slow-query.idle-timeout-ms=5000

# SQL Enhancer
interceptor.sql-enhancer.enabled=true
interceptor.sql-enhancer.priority=500
interceptor.sql-enhancer.dialect=postgresql
interceptor.sql-enhancer.optimization-enabled=true
```

## Performance Considerations

### Overhead Analysis

1. **Negligible When Empty**: If no interceptors registered, overhead is minimal (just a registry check)
2. **Linear Scaling**: Each interceptor adds constant time overhead
3. **Optimized Filtering**: Interceptors filtered by type/phase before execution
4. **No Reflection**: Direct method calls through interface

### Benchmarks (Estimated)

- **No interceptors**: < 0.01ms overhead
- **3 interceptors (typical)**: 0.05-0.1ms overhead
- **10 interceptors**: 0.2-0.3ms overhead

Compared to typical query execution (10-1000ms), this is negligible.

## Testing Strategy

### Unit Tests

1. Test each interceptor independently
2. Test `RequestContext` mutability
3. Test `InterceptorChain` logic
4. Test short-circuit behavior

### Integration Tests

1. Test interceptor chain execution
2. Test phase transitions
3. Test exception propagation
4. Test attribute passing between interceptors

### Performance Tests

1. Benchmark overhead with 0, 5, 10 interceptors
2. Test under concurrent load
3. Measure memory overhead

## Security Considerations

1. **Interceptor Validation**: Only load from trusted sources
2. **Exception Handling**: Interceptors should not expose sensitive data
3. **Context Isolation**: Interceptors should not leak information
4. **Resource Limits**: Prevent interceptors from consuming excessive resources

## Documentation Required

1. **New Document**: `Understanding-OJP-Interceptors.md`
   - Comprehensive guide for interceptor developers
   - Examples for common use cases
   - Best practices

2. **Update**: `Understanding-OJP-SPIs.md`
   - Add section on RequestInterceptor SPI
   - Link to interceptor guide

3. **New ADR**: `adr-008-request-lifecycle-interceptor-pattern.md`
   - Decision rationale
   - Alternatives considered
   - Trade-offs

4. **API Javadoc**: Complete documentation for all interfaces

## Comparison with Alternatives

### Alternative 1: Event-Driven Architecture

**Approach**: Publish events at lifecycle points, interceptors subscribe

**Pros**:
- True decoupling
- Asynchronous processing possible

**Cons**:
- More complex
- Harder to maintain order
- Difficult to handle exceptions
- Performance overhead from event dispatch

**Verdict**: Chain of Responsibility is simpler and more appropriate

### Alternative 2: Aspect-Oriented Programming (AOP)

**Approach**: Use AOP framework (AspectJ, Spring AOP) for cross-cutting concerns

**Pros**:
- Clean separation
- No code changes needed

**Cons**:
- Requires AOP framework dependency
- Compile-time or load-time weaving complexity
- Harder to debug
- Less explicit

**Verdict**: SPI + Chain pattern is more explicit and doesn't require framework

### Alternative 3: Decorator Pattern

**Approach**: Wrap StatementServiceImpl with decorators

**Pros**:
- Classic OOP pattern
- Type-safe

**Cons**:
- Compile-time composition only
- Not discoverable at runtime
- Can't add from external JARs easily

**Verdict**: ServiceLoader + Chain is more flexible

## Success Criteria

This implementation will be considered successful when:

1. ✅ All existing features (CircuitBreaker, SlowQuery, SqlEnhancer) migrated to interceptors
2. ✅ Zero performance regression (< 1% overhead)
3. ✅ Third-party interceptors can be loaded from ojp-libs
4. ✅ StatementServiceImpl reduced to < 1000 lines
5. ✅ Complete documentation and examples available
6. ✅ At least one community-contributed interceptor example

## Timeline

- **Week 1-2**: Core infrastructure and interfaces
- **Week 3-4**: Integration layer in StatementServiceImpl
- **Week 5-6**: Migrate CircuitBreaker
- **Week 7**: Migrate SlowQuerySegregation
- **Week 8**: Migrate SqlEnhancer
- **Week 9-10**: Documentation, testing, examples
- **Week 11**: Beta testing with community
- **Week 12**: Final release

## Conclusion

The Request Lifecycle Interceptor Pattern provides a powerful, standardized way for libraries and modules to integrate with OJP's request processing flow. By adopting this pattern, OJP will:

1. Become more extensible and maintainable
2. Enable third-party integrations without code changes
3. Follow established patterns (Servlet Filters)
4. Maintain backward compatibility during migration
5. Provide a clear, documented way for providers to extend OJP

This pattern aligns perfectly with OJP's existing use of ServiceLoader for SPIs and extends the same philosophy to request lifecycle management.

## Next Steps

1. **Review**: Get feedback from OJP team and community
2. **Prototype**: Build proof-of-concept with one interceptor
3. **Refine**: Adjust based on prototype learnings
4. **ADR**: Create formal architectural decision record
5. **Implement**: Follow the phased implementation plan

---

**Document Version**: 1.0  
**Author**: OJP Architecture Team  
**Date**: 2026-02-01  
**Status**: DRAFT - PENDING REVIEW
