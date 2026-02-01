# Request Lifecycle Interceptor Pattern - Executive Summary

## Overview

A new integration pattern for OJP that standardizes how libraries and modules interact with request lifecycle phases, inspired by Servlet Filters and implementing a Chain of Responsibility pattern with ServiceLoader-based discovery.

## The Problem

Currently, features like Circuit Breaker, Slow Query Segregation, and SQL Enhancement are **tightly coupled** to `StatementServiceImpl`:

```java
// Current approach - hard-coded integrations
public void executeUpdate(StatementRequest request, ...) {
    circuitBreaker.preCheck(stmtHash);              // Hard-coded ❌
    manager = getSlowQueryManager(connHash);        // Hard-coded ❌
    result = manager.executeWithSegregation(...);   // Hard-coded wrapper ❌
    circuitBreaker.onSuccess(stmtHash);             // Hard-coded ❌
}
```

**Issues:**
- Can't add features without modifying core code
- Each feature integrates differently
- Third-party providers can't extend OJP
- Testing is complex
- `StatementServiceImpl` is 2,528 lines and growing

## The Solution

**Request Lifecycle Interceptor Pattern** - A standardized way to hook into request processing:

```java
// New approach - clean and extensible
public void executeUpdate(StatementRequest request, ...) {
    RequestContext context = RequestContext.builder()
        .requestType(RequestType.UPDATE)
        .originalSql(request.getSql())
        .build();
    
    // All cross-cutting concerns handled by interceptors
    InterceptorChainExecutor.execute(context, ctx -> {
        OpResult result = executeUpdateInternal(request, ctx);
        ctx.setResult(result);
    });
}
```

## Key Concepts

### 1. Eight Lifecycle Phases

Interceptors can hook into any phase:

1. **PRE_REQUEST** - Session validation, request enrichment
2. **PRE_EXECUTION** - Circuit breaker, SQL transformation
3. **RESOURCE_ACQUISITION** - Connection/slot acquisition
4. **EXECUTION** - Database execution
5. **POST_EXECUTION** - Result processing
6. **RESOURCE_RELEASE** - Cleanup
7. **POST_REQUEST** - Metrics, logging
8. **EXCEPTION_HANDLING** - Error handling

### 2. RequestInterceptor Interface

```java
public interface RequestInterceptor {
    String id();
    int getPriority();  // Higher = runs first
    void intercept(RequestContext context, InterceptorChain chain) throws Exception;
}
```

### 3. Chain of Responsibility

Interceptors form a chain, each can:
- Execute logic before proceeding
- Call `chain.proceed(context)` to continue
- Execute logic after (in try-finally)
- Short-circuit by not calling proceed

### 4. ServiceLoader Discovery

Interceptors are discovered automatically:

```
META-INF/services/org.openjproxy.interceptor.RequestInterceptor
com.example.MyInterceptor
```

Drop JAR in `ojp-libs/` → Interceptor loads automatically ✅

## Example: Circuit Breaker Interceptor

```java
public class CircuitBreakerInterceptor implements RequestInterceptor {
    
    @Override
    public String id() {
        return "circuit-breaker";
    }
    
    @Override
    public int getPriority() {
        return 300; // High priority
    }
    
    @Override
    public boolean supportsPhase(LifecyclePhase phase) {
        return phase == PRE_EXECUTION || phase == POST_REQUEST;
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) 
            throws Exception {
        
        if (context.getCurrentPhase() == PRE_EXECUTION) {
            // Check before execution
            circuitBreaker.preCheck(context.getSqlHash());
            chain.proceed(context);
            
        } else if (context.getCurrentPhase() == POST_REQUEST) {
            // Record success/failure
            if (context.getException().isPresent()) {
                circuitBreaker.onFailure(context.getSqlHash(), ...);
            } else {
                circuitBreaker.onSuccess(context.getSqlHash());
            }
            chain.proceed(context);
        }
    }
}
```

## Example: Third-Party Query Logging Interceptor

```java
package com.acme.ojp;

public class QueryLoggerInterceptor implements RequestInterceptor {
    
    @Override
    public String id() {
        return "acme-query-logger";
    }
    
    @Override
    public int getPriority() {
        return 50; // Low priority, runs late
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) 
            throws Exception {
        
        if (context.getCurrentPhase() == POST_REQUEST) {
            long duration = context.getEndTimeMillis().orElse(0L) 
                          - context.getStartTimeMillis();
            
            logger.info("Query executed: sql={}, duration={}ms, success={}", 
                context.getOriginalSql(), 
                duration,
                !context.getException().isPresent());
        }
        
        chain.proceed(context);
    }
}
```

**Deploy**: Just drop JAR in `ojp-libs/` - no OJP recompilation needed! 🚀

## Benefits

### For OJP Core
- ✅ **Simpler code**: `StatementServiceImpl` reduces from 2,528 → <1,000 lines
- ✅ **Better testability**: Each interceptor tested independently
- ✅ **Easier maintenance**: Features don't touch core code
- ✅ **Clear separation**: Business logic separate from cross-cutting concerns

### For OJP Users
- ✅ **Extensibility**: Add custom interceptors without modifying OJP
- ✅ **Flexibility**: Enable/disable interceptors via configuration
- ✅ **Composability**: Multiple interceptors work together seamlessly
- ✅ **Familiar pattern**: Servlet Filter model is well-understood

### For Third-Party Providers
- ✅ **Easy integration**: Implement interface, drop in `ojp-libs/`
- ✅ **No recompilation**: OJP doesn't need to be rebuilt
- ✅ **Full control**: Access entire request lifecycle
- ✅ **Rich context**: All request information available

## Request Flow Example

```
Client Request
    ↓
┌────────────────────────────────────────────────┐
│ PRE_REQUEST Phase                              │
│ • Authentication Interceptor (priority: 1000)  │
│ • Rate Limiting Interceptor (priority: 900)    │
└────────────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────────────┐
│ PRE_EXECUTION Phase                            │
│ • SQL Enhancer Interceptor (priority: 500)     │
│   [SQL: SELECT * FROM users]                   │
│   [Enhanced: SELECT id, name FROM users]       │
│ • Circuit Breaker Interceptor (priority: 300)  │
│   [Checks if query is failing repeatedly]      │
└────────────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────────────┐
│ RESOURCE_ACQUISITION Phase                     │
│ • Slow Query Interceptor (priority: 200)       │
│   [Acquires appropriate slot]                  │
└────────────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────────────┐
│ EXECUTION Phase                                │
│ • Core OJP Logic                               │
│   [Actual database execution]                  │
└────────────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────────────┐
│ POST_EXECUTION Phase                           │
│ • Result Cache Interceptor (priority: 150)     │
│   [Caches result for future queries]           │
└────────────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────────────┐
│ RESOURCE_RELEASE Phase                         │
│ • Slow Query Interceptor (priority: 200)       │
│   [Releases slot]                              │
└────────────────────────────────────────────────┘
    ↓
┌────────────────────────────────────────────────┐
│ POST_REQUEST Phase                             │
│ • Metrics Interceptor (priority: 100)          │
│   [Records timing metrics]                     │
│ • Circuit Breaker Interceptor (priority: 300)  │
│   [Records success]                            │
│ • Query Logger Interceptor (priority: 50)      │
│   [Logs query execution]                       │
└────────────────────────────────────────────────┘
    ↓
Response to Client
```

## Performance

**Overhead Analysis:**
- No interceptors: < 0.01ms
- 3 interceptors (typical): 0.05-0.1ms
- 10 interceptors: 0.2-0.3ms

**Compared to typical query execution (10-1000ms): Negligible ✅**

## Migration Path

### Phase 1: Add Infrastructure
- Implement interfaces
- Add registry
- **No behavior change**

### Phase 2: Create Interceptors
- CircuitBreakerInterceptor
- SlowQueryInterceptor
- SqlEnhancerInterceptor

### Phase 3: Parallel Execution
- Run old and new code together
- Feature flags control which is active

### Phase 4: Switch Over
- Gradually enable interceptors
- Disable legacy code

### Phase 5: Cleanup
- Remove old implementations
- **Fully migrated ✅**

## Configuration

```properties
# Enable interceptor system
interceptor.enabled=true

# Circuit Breaker
interceptor.circuit-breaker.enabled=true
interceptor.circuit-breaker.priority=300
interceptor.circuit-breaker.failure-threshold=3

# Slow Query
interceptor.slow-query.enabled=true
interceptor.slow-query.priority=200

# SQL Enhancer
interceptor.sql-enhancer.enabled=true
interceptor.sql-enhancer.priority=500
```

## Success Criteria

1. ✅ All existing features migrated to interceptors
2. ✅ Performance overhead < 1%
3. ✅ Third-party interceptors loadable from `ojp-libs`
4. ✅ `StatementServiceImpl` < 1,000 lines
5. ✅ Complete documentation available
6. ✅ Community-contributed example exists

## Timeline

- **Week 1-2**: Core infrastructure
- **Week 3-4**: Integration layer
- **Week 5-8**: Migrate existing features
- **Week 9-10**: Documentation & examples
- **Week 11-12**: Testing & release

## Comparison with Servlet Filters

| Aspect | Servlet Filter | OJP Interceptor |
|--------|---------------|-----------------|
| Pattern | Chain of Responsibility | Chain of Responsibility |
| Discovery | web.xml or @WebFilter | ServiceLoader |
| Method | `doFilter(request, response, chain)` | `intercept(context, chain)` |
| Proceed | `chain.doFilter(request, response)` | `chain.proceed(context)` |
| Context | ServletRequest/Response | RequestContext |
| Phases | Single (filter) | Eight (lifecycle) |

**Key Insight**: OJP interceptors are like Servlet Filters, but with **multiple lifecycle phases** for finer-grained control.

## Related Documents

- 📄 [Full Design Document](REQUEST_LIFECYCLE_INTERCEPTOR_PATTERN.md) - Complete technical specification
- 📄 [ADR-008](../ADRs/adr-008-request-lifecycle-interceptor-pattern.md) - Architectural decision record
- 📄 [Understanding OJP SPIs](../Understanding-OJP-SPIs.md) - Existing SPI documentation

## Questions?

**Q: Will this break existing code?**  
A: No, fully backward compatible during migration.

**Q: What's the performance impact?**  
A: < 1% overhead, negligible compared to database execution time.

**Q: Can I add my own interceptor?**  
A: Yes! Implement `RequestInterceptor`, add to JAR, drop in `ojp-libs/`.

**Q: How is this different from events?**  
A: Chain pattern is synchronous, ordered, and can short-circuit. Better for request processing.

**Q: Do I need to rebuild OJP?**  
A: No, interceptors are loaded dynamically via ServiceLoader.

---

**Status**: DRAFT - PENDING REVIEW  
**Version**: 1.0  
**Date**: 2026-02-01  
**Contact**: OJP Architecture Team
