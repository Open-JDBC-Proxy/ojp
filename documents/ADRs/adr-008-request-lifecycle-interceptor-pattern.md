# ADR 008: Request Lifecycle Interceptor Pattern for Extensible Integration

In the context of the OJP project,  
facing the need to standardize how libraries and modules integrate with request lifecycle phases while avoiding tight coupling and enabling third-party extensibility,  

we decided for adopting the **Request Lifecycle Interceptor Pattern** using a Chain of Responsibility approach inspired by Servlet Filters, with ServiceLoader-based discovery  
and neglected hard-coded integration of features like Circuit Breaker, Slow Query Segregation, and SQL Enhancement directly into `StatementServiceImpl`,  

to achieve a pluggable architecture where external providers can create interceptors that hook into various phases of request processing (PRE_REQUEST, PRE_EXECUTION, RESOURCE_ACQUISITION, EXECUTION, POST_EXECUTION, RESOURCE_RELEASE, POST_REQUEST, EXCEPTION_HANDLING),  
accepting the complexity of chain management and phase coordination,  

because this pattern enables powerful extensibility, reduces coupling in the core request handling code, allows third-party providers to add functionality without modifying OJP, and follows the established ServiceLoader pattern already used successfully for ConnectionPoolProvider and XAConnectionPoolProvider.

## Context

OJP Server acts as a proxy between applications and databases, processing SQL queries and updates through `StatementServiceImpl`. Currently, three major features are tightly integrated into the request processing flow:

1. **CircuitBreaker** - Prevents repeatedly executing failing queries
   - Integrated via direct method calls (`preCheck`, `onSuccess`, `onFailure`)
   - Hard-coded at specific points in `executeQuery()` and `executeUpdate()`

2. **SlowQuerySegregationManager** - Prevents connection starvation from slow queries
   - Integrated via wrapper pattern (`executeWithSegregation()`)
   - Per-datasource instances managed in a `ConcurrentHashMap`

3. **SqlEnhancerEngine** (Apache Calcite) - Optimizes and transforms SQL
   - Integrated via direct method call in `executeQueryInternal()`
   - Enabled/disabled via configuration flag

### Problems with Current Approach

1. **Tight Coupling**: Features are hard-coded into `StatementServiceImpl`, which is already 2,528 lines
2. **Difficult Extension**: Adding new features requires modifying core classes
3. **No Standardization**: Each feature integrates differently (method calls vs wrappers vs flags)
4. **No Third-Party Support**: External providers cannot add lifecycle hooks
5. **Testing Complexity**: Features cannot be easily tested in isolation
6. **Maintenance Burden**: Changes to features require touching core request handling code

### Requirements

External providers should be able to:

1. Hook into various phases of request processing
2. Transform SQL before execution
3. Control whether to proceed or short-circuit the chain
4. Monitor performance and record metrics
5. Handle failures and implement retry logic
6. Acquire and release resources around execution
7. Work seamlessly with other interceptors in a chain

## Decision

We will implement a **Request Lifecycle Interceptor Pattern** using:

1. **Chain of Responsibility**: Interceptors form a chain, each can pass control to the next
2. **Servlet Filter Model**: Inspired by `javax.servlet.Filter` API pattern
3. **ServiceLoader Discovery**: External implementations loaded automatically via SPI
4. **Phased Lifecycle**: Eight distinct phases where interceptors can hook
5. **Priority-Based Ordering**: Interceptors execute in priority order (highest first)

### Core Components

#### 1. RequestInterceptor Interface (SPI)

```java
public interface RequestInterceptor {
    String id();
    int getPriority();
    boolean isAvailable();
    boolean supportsRequestType(RequestType requestType);
    boolean supportsPhase(LifecyclePhase phase);
    void intercept(RequestContext context, InterceptorChain chain) throws Exception;
}
```

#### 2. Lifecycle Phases

1. **PRE_REQUEST** - Session validation, cluster health, request enrichment
2. **PRE_EXECUTION** - SQL hash, circuit breaker check, SQL transformation
3. **RESOURCE_ACQUISITION** - Connection/slot acquisition
4. **EXECUTION** - Actual database execution
5. **POST_EXECUTION** - Result processing, metadata extraction
6. **RESOURCE_RELEASE** - Connection/slot release, cleanup
7. **POST_REQUEST** - Success/failure recording, metrics publishing
8. **EXCEPTION_HANDLING** - Exception transformation, failure recording

#### 3. RequestContext

Mutable context object flowing through the chain containing:
- Request type, SQL (original and current), session info
- Connection, result, exception
- Timing information, custom attributes
- Short-circuit flag

#### 4. InterceptorChain

Manages chain execution with `proceed(context)` method, allowing interceptors to:
- Execute logic before proceeding
- Call `chain.proceed(context)` to continue
- Execute logic after proceeding (in finally block)
- Short-circuit by not calling proceed

#### 5. RequestInterceptorRegistry

Discovers interceptors via ServiceLoader, manages registration, provides filtering by request type and phase, sorts by priority.

### Integration Approach

#### Before (Current):

```java
public void executeUpdate(StatementRequest request, ...) {
    circuitBreaker.preCheck(stmtHash);  // Hard-coded
    SlowQuerySegregationManager manager = getManager(connHash);  // Hard-coded
    OpResult result = manager.executeWithSegregation(  // Hard-coded wrapper
        stmtHash, () -> executeUpdateInternal(request)
    );
    circuitBreaker.onSuccess(stmtHash);  // Hard-coded
}
```

#### After (With Interceptors):

```java
public void executeUpdate(StatementRequest request, ...) {
    RequestContext context = RequestContext.builder()
        .requestType(RequestType.UPDATE)
        .originalSql(request.getSql())
        .build();
    
    InterceptorChainExecutor.execute(context, ctx -> {
        OpResult result = executeUpdateInternal(request, ctx);
        ctx.setResult(result);
    });
}
```

All cross-cutting concerns move to interceptors, core code becomes cleaner.

### Migration Strategy

1. **Phase 1**: Implement interceptor infrastructure (no behavior change)
2. **Phase 2**: Implement interceptor versions of existing features
3. **Phase 3**: Run old and new code in parallel with feature flags
4. **Phase 4**: Switch from old to new gradually
5. **Phase 5**: Remove legacy implementations

Full backward compatibility maintained during migration.

## Consequences

### Positive

1. **Pluggable Architecture**: Features completely decoupled from core request handling
2. **Third-Party Extensibility**: External providers can add interceptors via JAR in `ojp-libs`
3. **Standardized Integration**: Single, well-defined pattern for all lifecycle hooks
4. **Better Testability**: Each interceptor can be tested independently
5. **Reduced Complexity**: `StatementServiceImpl` becomes much simpler (target < 1000 lines)
6. **Composability**: Multiple interceptors work seamlessly together
7. **Clear Lifecycle**: Eight distinct phases make integration points explicit
8. **Familiar Pattern**: Servlet Filter model is well-understood by Java developers
9. **Consistent with SPIs**: Follows established pattern used for ConnectionPoolProvider
10. **No Recompilation**: Interceptors can be added/removed without rebuilding OJP

### Negative

1. **Implementation Complexity**: Chain management, phase coordination adds complexity
2. **Performance Overhead**: Each interceptor adds small constant time overhead (~0.05ms)
3. **Learning Curve**: Developers need to understand lifecycle phases and chain mechanics
4. **Debugging Complexity**: Tracing through interceptor chain may be harder than direct calls
5. **Migration Effort**: Existing features need to be refactored to interceptors
6. **Documentation Burden**: Comprehensive docs needed for third-party developers

### Mitigations

1. **Clear Documentation**: Comprehensive guide with examples (`Understanding-OJP-Interceptors.md`)
2. **Reference Implementations**: Built-in interceptors serve as examples
3. **Performance Testing**: Benchmark overhead, ensure < 1% impact
4. **Phased Migration**: Gradual rollout with feature flags
5. **Helpful Logging**: Log interceptor chain execution at DEBUG level
6. **Testing Support**: Provide test utilities for interceptor developers

## Alternatives Considered

### 1. Event-Driven Architecture

**Approach**: Publish events at lifecycle points, interceptors subscribe via event bus

**Pros**:
- True decoupling
- Asynchronous processing possible
- Dynamic subscription/unsubscription

**Cons**:
- More complex infrastructure
- Harder to maintain execution order
- Difficult to handle exceptions consistently
- Performance overhead from event dispatch
- Harder to short-circuit request flow

**Verdict**: **REJECTED** - Chain of Responsibility is simpler and more appropriate for synchronous request processing

### 2. Aspect-Oriented Programming (AOP)

**Approach**: Use AspectJ or Spring AOP to apply cross-cutting concerns

**Pros**:
- Clean separation via aspects
- No code changes in core classes
- Mature tooling

**Cons**:
- Requires AOP framework dependency (violates minimal dependencies principle)
- Compile-time or load-time weaving complexity
- Harder to debug (bytecode modification)
- Less explicit control flow
- Difficult for third-party providers to add aspects

**Verdict**: **REJECTED** - SPI + Chain pattern is more explicit and doesn't require framework dependency

### 3. Decorator Pattern

**Approach**: Wrap `StatementServiceImpl` with decorator classes

**Pros**:
- Classic OOP pattern
- Type-safe at compile time
- Easy to understand

**Cons**:
- Requires compile-time composition
- Not discoverable at runtime via ServiceLoader
- Can't add decorators from external JARs easily
- Each decorator needs to implement entire service interface

**Verdict**: **REJECTED** - Not flexible enough for runtime discovery and third-party extensions

### 4. Plugin Framework (OSGi, JPMs)

**Approach**: Use heavyweight plugin framework

**Pros**:
- Full module isolation
- Sophisticated dependency management
- Hot-swapping capabilities

**Cons**:
- Too heavyweight for the use case
- Adds significant complexity
- Steep learning curve
- Not consistent with OJP's lightweight approach

**Verdict**: **REJECTED** - ServiceLoader provides sufficient modularity without the complexity

### 5. Status Quo (Keep Current Approach)

**Approach**: Continue hard-coding integrations into `StatementServiceImpl`

**Pros**:
- No implementation effort
- No migration needed
- Simple and direct

**Cons**:
- Cannot support third-party extensions
- `StatementServiceImpl` continues to grow
- Each feature integrates differently
- Tight coupling persists

**Verdict**: **REJECTED** - Does not meet extensibility requirements

## Implementation Plan

### Timeline (12 weeks)

- **Week 1-2**: Core infrastructure (interfaces, registry, chain)
- **Week 3-4**: Integration layer in `StatementServiceImpl`
- **Week 5-6**: Migrate CircuitBreaker to interceptor
- **Week 7**: Migrate SlowQuerySegregation to interceptor
- **Week 8**: Migrate SqlEnhancer to interceptor
- **Week 9-10**: Documentation, examples, testing
- **Week 11**: Beta testing with community
- **Week 12**: Final release

### Success Criteria

1. ✅ All existing features migrated to interceptors
2. ✅ Performance overhead < 1% (measured via benchmarks)
3. ✅ Third-party interceptors loadable from `ojp-libs`
4. ✅ `StatementServiceImpl` reduced to < 1000 lines
5. ✅ Complete documentation available
6. ✅ At least one community-contributed example

### New Module Structure

```
ojp-interceptor-api/
  src/main/java/org/openjproxy/interceptor/
    RequestInterceptor.java
    RequestContext.java
    InterceptorChain.java
    RequestType.java
    LifecyclePhase.java
    DataSourceMetadata.java
    RequestInterceptorRegistry.java
```

Built-in interceptors will reside in `ojp-server`:

```
ojp-server/
  src/main/java/org/openjproxy/grpc/server/interceptor/
    CircuitBreakerInterceptor.java
    SlowQueryInterceptor.java
    SqlEnhancerInterceptor.java
```

## Related Decisions

- **ADR-006**: Adopt SPI Pattern for Extensibility - This decision extends the SPI philosophy to request lifecycle
- **ADR-003**: Use HikariCP as Connection Pool - ConnectionPoolProvider SPI demonstrated ServiceLoader success
- **STATEMENTSERVICE_ACTION_PATTERN_MIGRATION**: Refactoring StatementServiceImpl - Interceptors further simplify this class

## References

- [Request Lifecycle Interceptor Pattern - Full Design](../designs/REQUEST_LIFECYCLE_INTERCEPTOR_PATTERN.md)
- [Understanding OJP SPIs](../Understanding-OJP-SPIs.md)
- [Java ServiceLoader Documentation](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)
- [Servlet Filter Specification](https://jakarta.ee/specifications/servlet/5.0/jakarta-servlet-spec-5.0.html#filters)
- [Chain of Responsibility Pattern](https://en.wikipedia.org/wiki/Chain-of-responsibility_pattern)

## Appendix: Example Interceptor

```java
package com.example.ojp.interceptor;

import org.openjproxy.interceptor.*;

/**
 * Example third-party query timeout interceptor.
 * Demonstrates ServiceLoader-based integration.
 */
public class QueryTimeoutInterceptor implements RequestInterceptor {
    
    private final long timeoutMs;
    
    public QueryTimeoutInterceptor() {
        this.timeoutMs = Long.parseLong(
            System.getProperty("interceptor.timeout.ms", "30000")
        );
    }
    
    @Override
    public String id() {
        return "query-timeout";
    }
    
    @Override
    public int getPriority() {
        return 100;
    }
    
    @Override
    public boolean supportsRequestType(RequestType requestType) {
        return requestType == RequestType.QUERY;
    }
    
    @Override
    public boolean supportsPhase(LifecyclePhase phase) {
        return phase == LifecyclePhase.EXECUTION;
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) 
            throws Exception {
        // Set query timeout on connection
        context.getConnection().ifPresent(conn -> {
            try {
                Statement stmt = conn.createStatement();
                stmt.setQueryTimeout((int) (timeoutMs / 1000));
            } catch (SQLException e) {
                log.warn("Failed to set query timeout", e);
            }
        });
        
        chain.proceed(context);
    }
}
```

**META-INF/services/org.openjproxy.interceptor.RequestInterceptor**:
```
com.example.ojp.interceptor.QueryTimeoutInterceptor
```

Deploy by placing JAR in `ojp-libs/` directory. No OJP recompilation needed.

---

| Status        | PROPOSED         |  
|---------------|------------------| 
| Proposer(s)   | OJP Architecture Team | 
| Proposal date | 2026-02-01       | 
| Approver(s)   | Pending Review   |
| Approval date | Pending          |
