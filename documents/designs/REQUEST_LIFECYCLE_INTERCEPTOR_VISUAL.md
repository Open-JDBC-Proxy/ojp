# Request Lifecycle Interceptor Pattern - Visual Reference

## Overview Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      REQUEST LIFECYCLE FLOW                              │
│                  (With Interceptor Integration Points)                   │
└─────────────────────────────────────────────────────────────────────────┘

Client Request
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ PHASE 1: PRE_REQUEST                                                     │
│ • Session validation & activity tracking                                 │
│ • Cluster health processing                                              │
│ • Request enrichment & metadata addition                                 │
│                                                                           │
│ Interceptor Examples:                                                    │
│   • AuthenticationInterceptor (priority: 1000)                           │
│   • RateLimitingInterceptor (priority: 900)                              │
│   • RequestLoggingInterceptor (priority: 100)                            │
└─────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ PHASE 2: PRE_EXECUTION                                                   │
│ • SQL hash generation                                                    │
│ • Circuit breaker pre-check                                              │
│ • SQL transformation & optimization                                      │
│                                                                           │
│ Interceptor Examples:                                                    │
│   • SqlEnhancerInterceptor (priority: 500) ← Apache Calcite             │
│   • CircuitBreakerInterceptor (priority: 300)                            │
│   • QueryValidationInterceptor (priority: 250)                           │
└─────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ PHASE 3: RESOURCE_ACQUISITION                                            │
│ • Slow query slot acquisition                                            │
│ • Connection pool acquisition                                            │
│ • Transaction context setup                                              │
│                                                                           │
│ Interceptor Examples:                                                    │
│   • SlowQueryInterceptor (priority: 200) ← Slot management               │
│   • ConnectionLeaseInterceptor (priority: 150)                           │
│   • ResourceTrackingInterceptor (priority: 100)                          │
└─────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ PHASE 4: EXECUTION                                                       │
│ • Statement preparation                                                  │
│ • Parameter binding                                                      │
│ • ACTUAL DATABASE EXECUTION ★                                            │
│                                                                           │
│ Interceptor Examples:                                                    │
│   • QueryTimeoutInterceptor (priority: 300)                              │
│   • ExecutionMonitorInterceptor (priority: 200)                          │
│   • TracingInterceptor (priority: 100)                                   │
└─────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ PHASE 5: POST_EXECUTION                                                  │
│ • Result set processing                                                  │
│ • Metadata extraction                                                    │
│ • Performance data recording                                             │
│                                                                           │
│ Interceptor Examples:                                                    │
│   • ResultCacheInterceptor (priority: 300)                               │
│   • PerformanceMonitorInterceptor (priority: 200)                        │
│   • ResultTransformInterceptor (priority: 150)                           │
└─────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ PHASE 6: RESOURCE_RELEASE                                                │
│ • Connection release back to pool                                        │
│ • Slow query slot release                                                │
│ • Resource cleanup                                                       │
│                                                                           │
│ Interceptor Examples:                                                    │
│   • SlowQueryInterceptor (priority: 200) ← Slot release                  │
│   • ConnectionReleaseInterceptor (priority: 150)                         │
│   • LeakDetectionInterceptor (priority: 100)                             │
└─────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ PHASE 7: POST_REQUEST                                                    │
│ • Success/failure state recording                                        │
│ • Metrics publishing                                                     │
│ • Audit logging                                                          │
│                                                                           │
│ Interceptor Examples:                                                    │
│   • CircuitBreakerInterceptor (priority: 300) ← Success/failure          │
│   • MetricsPublisherInterceptor (priority: 200)                          │
│   • AuditLoggerInterceptor (priority: 100)                               │
│   • AlertingInterceptor (priority: 50)                                   │
└─────────────────────────────────────────────────────────────────────────┘
     │
     ▼
Response to Client

═══════════════════════════════════════════════════════════════════════════
                          EXCEPTION FLOW
═══════════════════════════════════════════════════════════════════════════

Any Phase Exception
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ PHASE X: EXCEPTION_HANDLING                                              │
│ • Exception transformation                                               │
│ • Failure state recording                                                │
│ • Retry logic (if applicable)                                            │
│ • Error recovery attempts                                                │
│                                                                           │
│ Interceptor Examples:                                                    │
│   • CircuitBreakerInterceptor (priority: 300) ← Failure recording        │
│   • RetryInterceptor (priority: 250)                                     │
│   • ExceptionTransformInterceptor (priority: 200)                        │
│   • ErrorNotificationInterceptor (priority: 100)                         │
└─────────────────────────────────────────────────────────────────────────┘
     │
     ├─── Exception Handled → Continue to POST_REQUEST
     │
     └─── Exception Propagated → Error Response to Client
```

## Interceptor Chain Execution

```
┌──────────────────────────────────────────────────────────────────────────┐
│                   CHAIN OF RESPONSIBILITY                                 │
└──────────────────────────────────────────────────────────────────────────┘

Request arrives
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ RequestInterceptorRegistry                                               │
│ • Discovers interceptors via ServiceLoader                               │
│ • Filters by RequestType and LifecyclePhase                              │
│ • Sorts by priority (highest first)                                      │
└─────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ InterceptorChain.proceed(context)                                        │
└─────────────────────────────────────────────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Interceptor #1 (Highest Priority)                                        │
│ intercept(context, chain) {                                              │
│   // Pre-processing                                                      │
│   doPreWork();                                                           │
│                                                                           │
│   // Proceed to next interceptor                                         │
│   chain.proceed(context);  ───────────┐                                 │
│                                         │                                 │
│   // Post-processing                    │                                 │
│   doPostWork();                         │                                 │
│ }                                       │                                 │
└─────────────────────────────────────────┘                                 │
                                          │                                 │
                                          ▼                                 │
┌─────────────────────────────────────────────────────────────────────────┐
│ Interceptor #2                                                           │
│ intercept(context, chain) {                                              │
│   doPreWork();                                                           │
│   chain.proceed(context);  ───────────┐                                 │
│   doPostWork();                       │                                  │
│ }                                     │                                  │
└───────────────────────────────────────┘                                  │
                                        │                                  │
                                        ▼                                  │
┌─────────────────────────────────────────────────────────────────────────┐
│ Interceptor #3                                                           │
│ intercept(context, chain) {                                              │
│   doPreWork();                                                           │
│   chain.proceed(context);  ───────────┐                                 │
│   doPostWork();                       │                                  │
│ }                                     │                                  │
└───────────────────────────────────────┘                                  │
                                        │                                  │
                                        ▼                                  │
┌─────────────────────────────────────────────────────────────────────────┐
│ Core Business Logic                                                      │
│ • executeUpdateInternal() or                                             │
│ • executeQueryInternal()                                                 │
│ • Actual database operation                                              │
└─────────────────────────────────────────────────────────────────────────┘
                                        │
                                        │ Returns
                                        ▼
                              Interceptor #3 Post-processing
                                        │
                                        ▼
                              Interceptor #2 Post-processing
                                        │
                                        ▼
                              Interceptor #1 Post-processing
                                        │
                                        ▼
                                   Response
```

## Current vs. Proposed Architecture

### Current Architecture (Hard-coded Integration)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      StatementServiceImpl                                │
│                         (2,528 lines)                                    │
│                                                                           │
│  executeUpdate(request) {                                                │
│    updateSessionActivity();                ← Always hardcoded            │
│    hash = hashSql();                       ← Always hardcoded            │
│    circuitBreaker.preCheck(hash);          ← Always hardcoded ❌         │
│    manager = getSlowQuery(conn);           ← Always hardcoded ❌         │
│    result = manager.executeWith(...);      ← Always hardcoded ❌         │
│    circuitBreaker.onSuccess(hash);         ← Always hardcoded ❌         │
│  }                                                                        │
│                                                                           │
│  executeQuery(request) {                                                 │
│    // Similar hard-coded logic                                           │
│    if (enhancerEnabled) {                  ← Configuration flag ❌       │
│      sql = enhancer.enhance(sql);          ← Conditional call ❌         │
│    }                                                                      │
│    // More hard-coded calls...                                           │
│  }                                                                        │
│                                                                           │
└─────────────────────────────────────────────────────────────────────────┘

Problems:
  ❌ Tight coupling
  ❌ Can't add features without modifying core
  ❌ No third-party extensibility
  ❌ Each feature integrates differently
  ❌ Hard to test in isolation
```

### Proposed Architecture (Interceptor Pattern)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      StatementServiceImpl                                │
│                         (<1,000 lines)                                   │
│                                                                           │
│  executeUpdate(request) {                                                │
│    context = RequestContext.builder()                                   │
│      .requestType(UPDATE)                                                │
│      .sql(request.getSql())                                              │
│      .build();                                                           │
│                                                                           │
│    InterceptorChainExecutor.execute(context, ctx -> {                   │
│      result = executeUpdateInternal(request, ctx);                      │
│      ctx.setResult(result);                                              │
│    });                                                                   │
│  }                                                                        │
│                                                                           │
└─────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ delegates to
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│              InterceptorChainExecutor                                    │
│  • Discovers interceptors from registry                                 │
│  • Executes each lifecycle phase                                        │
│  • Manages exception handling                                            │
└─────────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ loads from
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│          RequestInterceptorRegistry (ServiceLoader)                      │
│                                                                           │
│  Built-in interceptors:                                                  │
│    • CircuitBreakerInterceptor       ✅                                  │
│    • SlowQueryInterceptor            ✅                                  │
│    • SqlEnhancerInterceptor          ✅                                  │
│                                                                           │
│  Third-party interceptors:                                               │
│    • AcmeQueryLoggerInterceptor      ✅ (from ojp-libs/)                │
│    • CustomRateLimitInterceptor      ✅ (from ojp-libs/)                │
│    • ... any provider can add more ... ✅                                │
│                                                                           │
└─────────────────────────────────────────────────────────────────────────┘

Benefits:
  ✅ Loose coupling
  ✅ Add features without modifying core
  ✅ Third-party extensibility via ServiceLoader
  ✅ Standardized integration pattern
  ✅ Easy to test interceptors individually
  ✅ Clean separation of concerns
```

## Interface Hierarchy

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       Core Interfaces                                     │
└─────────────────────────────────────────────────────────────────────────┘

RequestInterceptor (SPI)
    ├── id(): String
    ├── getPriority(): int
    ├── isAvailable(): boolean
    ├── supportsRequestType(RequestType): boolean
    ├── supportsPhase(LifecyclePhase): boolean
    └── intercept(RequestContext, InterceptorChain): void
            │
            ├─── Uses ──┐
            │           │
            ▼           ▼
    RequestContext    InterceptorChain
            │               │
            │               └── proceed(RequestContext): void
            │
            ├── getRequestType(): RequestType
            ├── getCurrentPhase(): LifecyclePhase
            ├── getOriginalSql(): String
            ├── getCurrentSql(): String
            ├── setCurrentSql(String): void
            ├── getSqlHash(): String
            ├── getConnection(): Optional<Connection>
            ├── getResult(): Optional<Object>
            ├── getException(): Optional<Exception>
            ├── getAttribute(String): Object
            └── setAttribute(String, Object): void

┌─────────────────────────────────────────────────────────────────────────┐
│                       Supporting Types                                    │
└─────────────────────────────────────────────────────────────────────────┘

RequestType (Enum)
    ├── QUERY
    ├── UPDATE
    ├── BATCH
    ├── CALLABLE
    ├── TRANSACTION
    ├── XA_OPERATION
    ├── CONNECTION
    ├── RESULT_SET_FETCH
    └── LOB_OPERATION

LifecyclePhase (Enum)
    ├── PRE_REQUEST
    ├── PRE_EXECUTION
    ├── RESOURCE_ACQUISITION
    ├── EXECUTION
    ├── POST_EXECUTION
    ├── RESOURCE_RELEASE
    ├── POST_REQUEST
    └── EXCEPTION_HANDLING
```

## Priority Ranges (Recommended)

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Priority Range │ Purpose                    │ Examples                   │
├────────────────┼────────────────────────────┼────────────────────────────┤
│ 1000+          │ Critical Infrastructure    │ • Authentication           │
│                │ Must run first             │ • Rate Limiting            │
│                │                            │ • Security Checks          │
├────────────────┼────────────────────────────┼────────────────────────────┤
│ 500-999        │ Request Transformation     │ • SQL Enhancement (500)    │
│                │ Modify before execution    │ • Query Rewriting          │
│                │                            │ • SQL Validation           │
├────────────────┼────────────────────────────┼────────────────────────────┤
│ 100-499        │ Resource Management        │ • Circuit Breaker (300)    │
│                │ Control execution flow     │ • Slow Query Seg. (200)    │
│                │                            │ • Connection Mgmt (150)    │
├────────────────┼────────────────────────────┼────────────────────────────┤
│ 0-99           │ Monitoring & Logging       │ • Metrics (100)            │
│                │ Observe without changing   │ • Tracing (75)             │
│                │                            │ • Logging (50)             │
├────────────────┼────────────────────────────┼────────────────────────────┤
│ Negative       │ Post-processing            │ • Cleanup (-10)            │
│                │ Final cleanup tasks        │ • Deferred logging (-20)   │
└─────────────────────────────────────────────────────────────────────────┘
```

## Example: Third-Party Integration

### Step 1: Implement RequestInterceptor

```java
package com.acme.ojp;

import org.openjproxy.interceptor.*;

public class QueryLoggerInterceptor implements RequestInterceptor {
    
    @Override
    public String id() {
        return "acme-query-logger";
    }
    
    @Override
    public int getPriority() {
        return 50; // Monitoring priority
    }
    
    @Override
    public boolean supportsPhase(LifecyclePhase phase) {
        return phase == LifecyclePhase.POST_REQUEST;
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) 
            throws Exception {
        long duration = context.getEndTimeMillis().orElse(0L) 
                      - context.getStartTimeMillis();
        
        logger.info("Query: sql={}, duration={}ms", 
            context.getOriginalSql(), duration);
        
        chain.proceed(context);
    }
}
```

### Step 2: Register via ServiceLoader

Create file: `META-INF/services/org.openjproxy.interceptor.RequestInterceptor`

```
com.acme.ojp.QueryLoggerInterceptor
```

### Step 3: Package and Deploy

```bash
# Package into JAR
mvn clean package

# Deploy to OJP
cp target/acme-ojp-interceptor.jar /path/to/ojp-libs/

# Restart OJP Server
# Interceptor automatically discovered and loaded!
```

### Result

```
[INFO] Registered RequestInterceptor: acme-query-logger (priority: 50)
[INFO] Query: sql=SELECT * FROM users, duration=45ms
```

No OJP recompilation needed! ✅

## Performance Characteristics

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Overhead Analysis                                      │
└─────────────────────────────────────────────────────────────────────────┘

Scenario                     Overhead      Impact
─────────────────────────────────────────────────────────────────────────
No interceptors              < 0.01ms      Negligible
3 interceptors (typical)     0.05-0.1ms    Negligible
10 interceptors              0.2-0.3ms     Negligible
                                           
Compared to:
  Fast query (10ms)          ~1%           ✅ Acceptable
  Average query (100ms)      ~0.1%         ✅ Negligible
  Slow query (1000ms)        ~0.01%        ✅ Negligible

Optimization:
  • Interceptors filtered by type/phase before execution
  • No reflection overhead (direct interface calls)
  • Registry caching (loaded once at startup)
  • Short-circuit support (stop chain early if needed)
```

## Module Structure

```
ojp/
├── ojp-interceptor-api/               ← NEW MODULE
│   └── src/main/java/org/openjproxy/interceptor/
│       ├── RequestInterceptor.java    ← SPI Interface
│       ├── RequestContext.java        ← Context object
│       ├── InterceptorChain.java      ← Chain interface
│       ├── RequestType.java           ← Enum
│       ├── LifecyclePhase.java        ← Enum
│       └── RequestInterceptorRegistry.java  ← ServiceLoader registry
│
├── ojp-server/
│   └── src/main/java/org/openjproxy/grpc/server/
│       ├── interceptor/                ← Built-in interceptors
│       │   ├── CircuitBreakerInterceptor.java
│       │   ├── SlowQueryInterceptor.java
│       │   └── SqlEnhancerInterceptor.java
│       │
│       └── StatementServiceImpl.java   ← Uses interceptors
│
└── ojp-libs/                           ← External JARs
    ├── acme-ojp-interceptor.jar       ← Third-party interceptors
    └── custom-interceptor.jar         ← Custom implementations
```

## Migration Timeline

```
Week 1-2   ┃ Core Infrastructure
           ┃ • Create ojp-interceptor-api module
           ┃ • Implement interfaces and registry
           ┃ • Add ServiceLoader support
           ┃
Week 3-4   ┃ Integration Layer
           ┃ • Add interceptor invocation to StatementServiceImpl
           ┃ • Implement InterceptorChainExecutor
           ┃ • Add phase transitions
           ┃
Week 5-6   ┃ Migrate Circuit Breaker
           ┃ • Implement CircuitBreakerInterceptor
           ┃ • Run parallel with legacy code
           ┃ • Switch via feature flag
           ┃
Week 7     ┃ Migrate Slow Query Segregation
           ┃ • Implement SlowQueryInterceptor
           ┃ • Test slot management
           ┃ • Switch via feature flag
           ┃
Week 8     ┃ Migrate SQL Enhancer
           ┃ • Implement SqlEnhancerInterceptor
           ┃ • Test SQL transformation
           ┃ • Switch via feature flag
           ┃
Week 9-10  ┃ Documentation & Examples
           ┃ • Write comprehensive docs
           ┃ • Create example interceptors
           ┃ • Prepare migration guide
           ┃
Week 11    ┃ Beta Testing
           ┃ • Community testing
           ┃ • Gather feedback
           ┃ • Fix issues
           ┃
Week 12    ┃ Final Release
           ┃ • Remove legacy code
           ┃ • Publish documentation
           ┃ • Announce release
```

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-01  
**Status**: DRAFT - PENDING REVIEW
