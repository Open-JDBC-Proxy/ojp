# Understanding OJP Interceptors

## Overview

The **Request Lifecycle Interceptor Pattern** is OJP's extensibility mechanism that allows libraries and modules to hook into the request processing lifecycle. Inspired by Servlet Filters, it provides a standardized way to intercept and modify SQL requests as they flow through the system.

## Table of Contents

- [Core Concepts](#core-concepts)
- [Lifecycle Phases](#lifecycle-phases)
- [Creating an Interceptor](#creating-an-interceptor)
- [Deployment Options](#deployment-options)
- [Priority and Ordering](#priority-and-ordering)
- [Best Practices](#best-practices)
- [Examples](#examples)
- [Troubleshooting](#troubleshooting)

## Core Concepts

### What is an Interceptor?

An interceptor is a component that can observe and modify requests as they flow through OJP's request processing pipeline. Each interceptor implements the `RequestInterceptor` interface and can:

- **Transform SQL**: Modify SQL statements before execution
- **Validate Requests**: Check request validity and reject if needed
- **Collect Metrics**: Gather timing and performance data
- **Enforce Policies**: Apply security, rate limiting, or other policies
- **Enhance Functionality**: Add features like query optimization or caching

### Key Interfaces

```java
// Main interceptor interface
public interface RequestInterceptor {
    String id();                    // Unique identifier
    int getPriority();              // Execution order (higher = earlier)
    Set<LifecyclePhase> getSupportedPhases();
    Set<RequestType> getSupportedRequestTypes();
    void intercept(RequestContext context, InterceptorChain chain) throws Exception;
}

// Mutable context flowing through chain
public interface RequestContext {
    String getSql();
    void setSql(String sql);
    RequestType getRequestType();
    LifecyclePhase getCurrentPhase();
    Object getAttribute(String key);
    void setAttribute(String key, Object value);
    boolean isShortCircuited();
    void shortCircuit();
}

// Chain control
public interface InterceptorChain {
    void proceed() throws Exception;  // Continue to next interceptor
}
```

## Lifecycle Phases

Requests flow through 8 distinct lifecycle phases:

```
PRE_REQUEST
    ↓
PRE_EXECUTION
    ↓
RESOURCE_ACQUISITION
    ↓
EXECUTION (Core Logic)
    ↓
POST_EXECUTION
    ↓
RESOURCE_RELEASE
    ↓
POST_REQUEST
    ↓
EXCEPTION_HANDLING (if errors occur)
```

### Phase Descriptions

| Phase | Purpose | Common Uses |
|-------|---------|-------------|
| **PRE_REQUEST** | Before any processing | Session validation, cluster health checks |
| **PRE_EXECUTION** | Before SQL execution | SQL transformation, circuit breaker, validation |
| **RESOURCE_ACQUISITION** | Connection/resource allocation | Connection pool monitoring, slot reservation |
| **EXECUTION** | Actual database execution | Core execution logic (typically not intercepted) |
| **POST_EXECUTION** | After successful execution | Result transformation, caching |
| **RESOURCE_RELEASE** | Resource cleanup | Connection release, cleanup |
| **POST_REQUEST** | Request finalization | Metrics recording, logging |
| **EXCEPTION_HANDLING** | Error occurred | Error logging, recovery, notifications |

### Phase Guarantees

- **RESOURCE_RELEASE** always executes (even on exceptions)
- **POST_REQUEST** always executes (even on exceptions)
- **EXCEPTION_HANDLING** only executes on errors
- Phases execute in strict order
- Short-circuit skips remaining interceptors (but still runs cleanup phases)

## Creating an Interceptor

### Step 1: Implement the Interface

```java
package com.example.interceptor;

import org.openjproxy.interceptor.*;
import java.util.Set;

public class MyInterceptor implements RequestInterceptor {
    
    @Override
    public String id() {
        return "my-custom-interceptor";
    }
    
    @Override
    public int getPriority() {
        return 500;  // Transformation range: 500-999
    }
    
    @Override
    public Set<LifecyclePhase> getSupportedPhases() {
        return Set.of(LifecyclePhase.PRE_EXECUTION);
    }
    
    @Override
    public Set<RequestType> getSupportedRequestTypes() {
        return Set.of(RequestType.QUERY, RequestType.UPDATE);
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) 
            throws Exception {
        // Your logic here
        String originalSql = context.getSql();
        String modifiedSql = transform(originalSql);
        context.setSql(modifiedSql);
        
        // Continue to next interceptor
        chain.proceed();
    }
    
    private String transform(String sql) {
        // Your transformation logic
        return sql;
    }
}
```

### Step 2: Register with ServiceLoader

Create file: `src/main/resources/META-INF/services/org.openjproxy.interceptor.RequestInterceptor`

Content:
```
com.example.interceptor.MyInterceptor
```

### Step 3: Package and Deploy

```bash
# Build your JAR
mvn clean package

# Copy to ojp-libs directory
cp target/my-interceptor-1.0.0.jar /path/to/ojp-libs/

# OJP will automatically discover and load it
```

## Deployment Options

### Option 1: External Module (Recommended)

Deploy as standalone JAR in `ojp-libs/` directory:

**Pros**:
- No OJP recompilation needed
- Independent update cycle
- Can be added/removed dynamically

**Usage**:
```bash
# Enable interceptor framework
export OJP_INTERCEPTOR_ENABLED=true

# Copy your JAR
cp my-interceptor.jar ./ojp-libs/

# Start OJP (autodiscovery via ServiceLoader)
java -jar ojp-server.jar
```

### Option 2: Embedded Dependency

Add as Maven dependency to ojp-server:

**Pros**:
- Simpler deployment (one JAR)
- Version control via POM

**Usage**:
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>my-interceptor</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Option 3: Custom Build

Fork OJP and add interceptor to source:

**Pros**:
- Full control
- Custom modifications

**Cons**:
- Maintenance burden
- Harder to upgrade OJP

## Priority and Ordering

Interceptors execute in priority order (highest to lowest):

### Priority Ranges

| Range | Purpose | Examples |
|-------|---------|----------|
| **1000+** | Infrastructure | Authentication, session management |
| **500-999** | Transformation | SQL enhancement, dialect translation |
| **100-499** | Resource Management | Circuit breaker, connection pool |
| **0-99** | Monitoring | Metrics, logging |

### Execution Order Example

```
Request arrives
    ↓
Priority 1500: Authentication Interceptor
    ↓
Priority 800: SQL Optimizer Interceptor  
    ↓
Priority 600: SQL Enhancer Interceptor
    ↓
Priority 200: Circuit Breaker Interceptor
    ↓
Priority 50: Metrics Interceptor
    ↓
Core Execution
    ↓
[Same chain in reverse for POST phases]
```

### Same Priority?

- Alphabetical by interceptor ID
- Predictable but avoid if possible
- Use different priorities

## Best Practices

### 1. Always Call chain.proceed()

```java
// ✅ GOOD
@Override
public void intercept(RequestContext context, InterceptorChain chain) 
        throws Exception {
    // Do work
    doSomething(context);
    
    // Continue chain
    chain.proceed();
}

// ❌ BAD - Chain stops here!
@Override
public void intercept(RequestContext context, InterceptorChain chain) 
        throws Exception {
    doSomething(context);
    // Missing chain.proceed()!
}
```

### 2. Handle Exceptions Gracefully

```java
@Override
public void intercept(RequestContext context, InterceptorChain chain) 
        throws Exception {
    try {
        riskyOperation(context);
        chain.proceed();
    } catch (Exception e) {
        // Log but don't fail request
        log.warn("Interceptor failed", e);
        // Continue anyway
        chain.proceed();
    }
}
```

### 3. Use Attributes for Communication

```java
// Interceptor A (early in chain)
context.setAttribute("query.startTime", System.currentTimeMillis());
chain.proceed();

// Interceptor B (later in chain)
Long startTime = (Long) context.getAttribute("query.startTime");
long duration = System.currentTimeMillis() - startTime;
```

### 4. Respect Request Types

```java
@Override
public Set<RequestType> getSupportedRequestTypes() {
    // Only intercept SELECT queries
    return Set.of(RequestType.QUERY);
}
```

### 5. Choose Appropriate Phase

```java
// SQL transformation = PRE_EXECUTION
public Set<LifecyclePhase> getSupportedPhases() {
    return Set.of(LifecyclePhase.PRE_EXECUTION);
}

// Metrics collection = POST_REQUEST  
public Set<LifecyclePhase> getSupportedPhases() {
    return Set.of(LifecyclePhase.POST_REQUEST);
}
```

### 6. Make IDs Unique and Descriptive

```java
@Override
public String id() {
    return "com.mycompany.sql-optimizer-v2";  // ✅ Good
    // return "interceptor";  // ❌ Too generic
}
```

### 7. Document Configuration

```java
public class MyInterceptor implements RequestInterceptor {
    // Document what properties control behavior
    private final boolean enabled = 
        Boolean.parseBoolean(System.getProperty("my.interceptor.enabled", "false"));
    
    private final int cacheSize = 
        Integer.parseInt(System.getProperty("my.interceptor.cache.size", "1000"));
}
```

### 8. Test Thoroughly

```java
@Test
public void testInterceptorWithValidInput() {
    MyInterceptor interceptor = new MyInterceptor();
    RequestContext context = DefaultRequestContext.builder()
        .sql("SELECT * FROM users")
        .requestType(RequestType.QUERY)
        .build();
    
    InterceptorChain chain = mock(InterceptorChain.class);
    
    interceptor.intercept(context, chain);
    
    verify(chain).proceed();  // Ensure chain continues
    assertNotNull(context.getSql());  // Verify transformation
}
```

## Examples

### Example 1: SQL Comment Injector

Adds comments to SQL for tracking:

```java
public class SqlCommentInterceptor implements RequestInterceptor {
    
    @Override
    public String id() {
        return "sql-comment-injector";
    }
    
    @Override
    public int getPriority() {
        return 700;  // Transformation range
    }
    
    @Override
    public Set<LifecyclePhase> getSupportedPhases() {
        return Set.of(LifecyclePhase.PRE_EXECUTION);
    }
    
    @Override
    public Set<RequestType> getSupportedRequestTypes() {
        return Set.of(RequestType.QUERY, RequestType.UPDATE);
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) 
            throws Exception {
        String sql = context.getSql();
        String comment = String.format(
            "/* ojp:%s, phase:%s */", 
            context.getAttribute("request.id"),
            context.getCurrentPhase()
        );
        context.setSql(comment + " " + sql);
        chain.proceed();
    }
}
```

### Example 2: Query Metrics Collector

Collects query execution metrics:

```java
public class MetricsInterceptor implements RequestInterceptor {
    
    private final MetricsRegistry registry = new MetricsRegistry();
    
    @Override
    public String id() {
        return "query-metrics-collector";
    }
    
    @Override
    public int getPriority() {
        return 50;  // Monitoring range
    }
    
    @Override
    public Set<LifecyclePhase> getSupportedPhases() {
        return Set.of(
            LifecyclePhase.PRE_EXECUTION,
            LifecyclePhase.POST_EXECUTION
        );
    }
    
    @Override
    public Set<RequestType> getSupportedRequestTypes() {
        return Set.of(RequestType.QUERY, RequestType.UPDATE);
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) 
            throws Exception {
        
        if (context.getCurrentPhase() == LifecyclePhase.PRE_EXECUTION) {
            // Record start time
            context.setAttribute("metrics.startTime", System.nanoTime());
        }
        
        chain.proceed();
        
        if (context.getCurrentPhase() == LifecyclePhase.POST_EXECUTION) {
            // Calculate duration
            long startTime = (Long) context.getAttribute("metrics.startTime");
            long duration = System.nanoTime() - startTime;
            
            // Record metric
            registry.recordQuery(
                context.getRequestType(),
                duration,
                context.getSql().length()
            );
        }
    }
}
```

### Example 3: SQL Sanitizer

Validates and sanitizes SQL:

```java
public class SqlSanitizerInterceptor implements RequestInterceptor {
    
    private static final Pattern DANGEROUS_PATTERNS = Pattern.compile(
        ".*;\\s*(DROP|DELETE|TRUNCATE)\\s+.*", 
        Pattern.CASE_INSENSITIVE
    );
    
    @Override
    public String id() {
        return "sql-sanitizer";
    }
    
    @Override
    public int getPriority() {
        return 1200;  // Infrastructure (run early)
    }
    
    @Override
    public Set<LifecyclePhase> getSupportedPhases() {
        return Set.of(LifecyclePhase.PRE_REQUEST);
    }
    
    @Override
    public Set<RequestType> getSupportedRequestTypes() {
        return Set.of(
            RequestType.QUERY, 
            RequestType.UPDATE,
            RequestType.BATCH
        );
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) 
            throws Exception {
        String sql = context.getSql();
        
        // Check for dangerous patterns
        if (DANGEROUS_PATTERNS.matcher(sql).matches()) {
            log.warn("Blocked potentially dangerous SQL: {}", sql);
            context.shortCircuit();  // Stop processing
            context.setAttribute("error", "SQL contains prohibited operations");
            return;  // Don't call chain.proceed()
        }
        
        // Continue if safe
        chain.proceed();
    }
}
```

### Example 4: Caching Interceptor

Caches query results:

```java
public class QueryCacheInterceptor implements RequestInterceptor {
    
    private final Cache<String, Object> cache = CacheBuilder.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build();
    
    @Override
    public String id() {
        return "query-cache";
    }
    
    @Override
    public int getPriority() {
        return 800;  // Transformation range
    }
    
    @Override
    public Set<LifecyclePhase> getSupportedPhases() {
        return Set.of(
            LifecyclePhase.PRE_EXECUTION,
            LifecyclePhase.POST_EXECUTION
        );
    }
    
    @Override
    public Set<RequestType> getSupportedRequestTypes() {
        return Set.of(RequestType.QUERY);  // Only SELECT
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) 
            throws Exception {
        
        if (context.getCurrentPhase() == LifecyclePhase.PRE_EXECUTION) {
            // Check cache
            String cacheKey = context.getSql();
            Object cachedResult = cache.getIfPresent(cacheKey);
            
            if (cachedResult != null) {
                log.debug("Cache hit for query: {}", cacheKey);
                context.setResult(cachedResult);
                context.shortCircuit();  // Skip execution
                return;
            }
        }
        
        chain.proceed();
        
        if (context.getCurrentPhase() == LifecyclePhase.POST_EXECUTION) {
            // Store result in cache
            String cacheKey = context.getSql();
            Object result = context.getResult();
            if (result != null) {
                cache.put(cacheKey, result);
            }
        }
    }
}
```

## Troubleshooting

### Interceptor Not Loading

**Problem**: Your interceptor isn't being discovered

**Solutions**:
1. Check ServiceLoader registration file exists:
   ```
   src/main/resources/META-INF/services/org.openjproxy.interceptor.RequestInterceptor
   ```

2. Verify file contains correct fully-qualified class name:
   ```
   com.example.MyInterceptor
   ```

3. Ensure JAR is in `ojp-libs/` directory

4. Verify interceptor framework is enabled:
   ```bash
   export OJP_INTERCEPTOR_ENABLED=true
   ```

5. Check logs for discovery messages:
   ```
   INFO  Discovered RequestInterceptor: my-interceptor (priority: 500)
   ```

### Interceptor Not Executing

**Problem**: Interceptor loads but doesn't execute

**Solutions**:
1. Check phase support matches execution phase
2. Verify request type filter includes your requests
3. Ensure higher-priority interceptor isn't short-circuiting

### Chain Stops Unexpectedly

**Problem**: Requests fail or hang

**Solutions**:
1. Verify all interceptors call `chain.proceed()`
2. Check for exceptions being swallowed
3. Look for short-circuit calls:
   ```java
   context.shortCircuit();  // Stops chain
   ```

### Performance Issues

**Problem**: Requests are slower with interceptors

**Solutions**:
1. Profile interceptor code
2. Reduce interceptor count
3. Move heavy work to async/background
4. Use caching where appropriate
5. Check if too many phases are supported

### Configuration Not Working

**Problem**: Configuration properties not being read

**Solutions**:
1. Verify system properties are set:
   ```bash
   java -Dmy.interceptor.enabled=true -jar ojp-server.jar
   ```

2. Check environment variable format:
   ```bash
   export MY_INTERCEPTOR_ENABLED=true
   ```

3. Use ServerConfiguration pattern:
   ```java
   String value = System.getProperty("my.prop", "default");
   ```

## Performance Considerations

### Overhead

- **No interceptors**: < 0.01ms overhead
- **1-3 interceptors**: 0.05-0.1ms overhead
- **5-10 interceptors**: 0.2-0.3ms overhead
- **Target**: Keep total overhead < 1%

### Optimization Tips

1. **Filter aggressively**: Use phase and type filters
2. **Cache results**: Cache expensive computations
3. **Fail fast**: Quick validation before heavy work
4. **Minimize object creation**: Reuse objects
5. **Use efficient data structures**: HashMap vs List
6. **Profile regularly**: Measure actual impact

## Related Documentation

- [Request Lifecycle Interceptor Pattern Design](designs/REQUEST_LIFECYCLE_INTERCEPTOR_PATTERN.md)
- [ADR-008: Request Lifecycle Interceptor Pattern](ADRs/adr-008-request-lifecycle-interceptor-pattern.md)
- [SQL Enhancer Interceptor](../ojp-sql-enhancer-interceptor/README.md)
- [SQL Enhancer Deployment Guide](guides/SQL_ENHANCER_DEPLOYMENT.md)
- [SQL Enhancer Migration Guide](guides/SQL_ENHANCER_MIGRATION.md)
- [Understanding OJP SPIs](Understanding-OJP-SPIs.md)
- [Drivers and Libraries](configuration/DRIVERS_AND_LIBS.md)

## FAQ

**Q: Can interceptors modify results?**  
A: Yes, in POST_EXECUTION phase. Access via `context.getResult()` and modify via `context.setResult()`.

**Q: Can I have multiple interceptors from same developer?**  
A: Yes, create multiple classes and register each in ServiceLoader file.

**Q: What happens if an interceptor throws an exception?**  
A: The exception propagates and EXCEPTION_HANDLING phase interceptors are invoked.

**Q: Can interceptors be disabled at runtime?**  
A: Not currently. Restart required to add/remove interceptors. Use feature flags within your interceptor.

**Q: Do interceptors work with all databases?**  
A: Yes, interceptors are database-agnostic. They operate on the SQL/request level.

**Q: Can I access database metadata in interceptors?**  
A: Yes, via `context.getDataSourceMetadata()` (if available in context).

**Q: How do I debug interceptor issues?**  
A: Enable debug logging: `--log-level=DEBUG` and watch for interceptor execution logs.

**Q: Can interceptors be written in languages other than Java?**  
A: Currently only Java/JVM languages supported due to ServiceLoader requirement.

## Conclusion

The Request Lifecycle Interceptor Pattern provides a powerful and standardized way to extend OJP. By following these guidelines and best practices, you can create robust interceptors that integrate seamlessly with OJP's architecture.

For more examples and advanced patterns, see the `ojp-sql-enhancer-interceptor` module source code.

---

*Last Updated: 2026-02-21*  
*Version: 1.0*  
*Related Version: OJP 0.3.2+*
