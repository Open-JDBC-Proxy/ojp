# Quick Start: OJP Request Interceptors

Get started with OJP Request Interceptors in 5 minutes!

## What Are Interceptors?

Interceptors let you hook into OJP's request processing lifecycle to:
- Transform SQL (optimize, validate, translate dialects)
- Apply policies (security, rate limiting)
- Collect metrics
- Add custom business logic

## Quick Start: Using SQL Enhancer

The easiest way to get started is with the built-in SQL Enhancer interceptor.

### Step 1: Get the Interceptor

**Download** (or build):
```bash
# If building from source
cd ojp-sql-enhancer-interceptor
mvn clean package

# Get the shaded JAR
ls target/*-shaded.jar
```

### Step 2: Deploy It

```bash
# Create libs directory
mkdir -p ojp-libs

# Copy the interceptor JAR
cp ojp-sql-enhancer-interceptor-*-shaded.jar ojp-libs/
```

### Step 3: Configure OJP

```bash
# Enable interceptor framework
export OJP_INTERCEPTOR_ENABLED=true

# Enable SQL Enhancer
export OJP_SQL_ENHANCER_ENABLED=true
export OJP_SQL_ENHANCER_MODE=VALIDATE

# Start OJP
java -jar ojp-server-0.3.2.jar
```

### Step 4: Verify It Works

Look for these log messages:

```
INFO  Loading external library JAR: ojp-sql-enhancer-interceptor-0.3.2-snapshot-shaded.jar
INFO  Discovered RequestInterceptor: sql-enhancer (priority: 600)
INFO  SQL Enhancer Interceptor enabled with mode: VALIDATE
```

### Step 5: Test It

Connect your application and run a query. The SQL will be validated:

```sql
-- This will be validated against database schema
SELECT * FROM users WHERE id = 1;

-- Invalid queries will be caught
SELECT * FROM nonexistent_table;  -- Error!
```

## Quick Start: Creating Your Own Interceptor

Want to create a custom interceptor? Here's a simple example.

### Step 1: Create the Class

`src/main/java/com/example/HelloInterceptor.java`:

```java
package com.example;

import org.openjproxy.interceptor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;

public class HelloInterceptor implements RequestInterceptor {
    
    private static final Logger log = LoggerFactory.getLogger(HelloInterceptor.class);
    
    @Override
    public String id() {
        return "hello-interceptor";
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
        // Log the SQL
        log.info("Intercepted SQL: {}", context.getSql());
        
        // Add a comment to the SQL
        String sql = context.getSql();
        String commented = "/* Hello from interceptor! */ " + sql;
        context.setSql(commented);
        
        // Continue to next interceptor
        chain.proceed();
    }
}
```

### Step 2: Register with ServiceLoader

Create `src/main/resources/META-INF/services/org.openjproxy.interceptor.RequestInterceptor`:

```
com.example.HelloInterceptor
```

### Step 3: Create POM

`pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>hello-interceptor</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>

    <dependencies>
        <!-- OJP Interceptor API -->
        <dependency>
            <groupId>org.openjproxy</groupId>
            <artifactId>ojp-interceptor-api</artifactId>
            <version>0.3.2-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
        
        <!-- Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>2.0.9</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

### Step 4: Build and Deploy

```bash
# Build
mvn clean package

# Deploy
cp target/hello-interceptor-1.0.0.jar /path/to/ojp-libs/

# Enable and start
export OJP_INTERCEPTOR_ENABLED=true
java -jar ojp-server-0.3.2.jar
```

### Step 5: See It in Action

Logs will show:

```
INFO  Discovered RequestInterceptor: hello-interceptor (priority: 700)
INFO  Intercepted SQL: SELECT * FROM users
```

Your SQL will have the comment added:

```sql
/* Hello from interceptor! */ SELECT * FROM users
```

## Common Use Cases

### Use Case 1: SQL Validation

Validate SQL before execution:

```java
@Override
public void intercept(RequestContext context, InterceptorChain chain) 
        throws Exception {
    String sql = context.getSql();
    
    if (sql.toUpperCase().contains("DROP TABLE")) {
        log.error("Blocked dangerous SQL: {}", sql);
        context.shortCircuit();  // Stop processing
        throw new SQLException("DROP TABLE not allowed");
    }
    
    chain.proceed();
}
```

### Use Case 2: Query Metrics

Collect query execution time:

```java
@Override
public Set<LifecyclePhase> getSupportedPhases() {
    return Set.of(
        LifecyclePhase.PRE_EXECUTION,
        LifecyclePhase.POST_EXECUTION
    );
}

@Override
public void intercept(RequestContext context, InterceptorChain chain) 
        throws Exception {
    if (context.getCurrentPhase() == LifecyclePhase.PRE_EXECUTION) {
        context.setAttribute("startTime", System.nanoTime());
    }
    
    chain.proceed();
    
    if (context.getCurrentPhase() == LifecyclePhase.POST_EXECUTION) {
        long startTime = (Long) context.getAttribute("startTime");
        long duration = System.nanoTime() - startTime;
        log.info("Query took {} ms", duration / 1_000_000);
    }
}
```

### Use Case 3: SQL Transformation

Transform SQL dialects:

```java
@Override
public void intercept(RequestContext context, InterceptorChain chain) 
        throws Exception {
    String sql = context.getSql();
    
    // Convert MySQL LIMIT to Oracle ROWNUM
    if (sql.contains("LIMIT")) {
        sql = sql.replaceAll(
            "LIMIT (\\d+)",
            "AND ROWNUM <= $1"
        );
        context.setSql(sql);
    }
    
    chain.proceed();
}
```

### Use Case 4: Request Logging

Log all requests with metadata:

```java
@Override
public Set<LifecyclePhase> getSupportedPhases() {
    return Set.of(LifecyclePhase.POST_REQUEST);
}

@Override
public void intercept(RequestContext context, InterceptorChain chain) 
        throws Exception {
    chain.proceed();
    
    // Log after request completes
    log.info("Request completed: type={}, sql={}, duration={}ms",
        context.getRequestType(),
        context.getSql(),
        context.getAttribute("duration")
    );
}
```

## Configuration

### Enable/Disable Interceptors

```bash
# Enable interceptor framework
export OJP_INTERCEPTOR_ENABLED=true

# Or via JVM property
java -Dojp.interceptor.enabled=true -jar ojp-server.jar
```

### Interceptor-Specific Configuration

Each interceptor can read its own configuration:

```java
public class MyInterceptor implements RequestInterceptor {
    
    private final boolean enabled = Boolean.parseBoolean(
        System.getProperty("my.interceptor.enabled", "false")
    );
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) 
            throws Exception {
        if (!enabled) {
            chain.proceed();  // Skip if disabled
            return;
        }
        
        // Your logic here
        chain.proceed();
    }
}
```

Then configure:

```bash
export MY_INTERCEPTOR_ENABLED=true
```

## Priority Guidelines

Choose appropriate priority for your interceptor:

| Range | Purpose | Example |
|-------|---------|---------|
| **1000+** | Infrastructure | Authentication (1500) |
| **500-999** | Transformation | SQL Enhancer (600), Dialect Translator (700) |
| **100-499** | Resource Management | Circuit Breaker (200), Connection Pool (300) |
| **0-99** | Monitoring | Metrics (50), Logging (10) |

**Rule**: Higher priority = executes earlier

## Lifecycle Phases

Choose appropriate phase(s):

| Phase | When to Use |
|-------|-------------|
| **PRE_REQUEST** | Session validation, early checks |
| **PRE_EXECUTION** | SQL transformation, validation |
| **RESOURCE_ACQUISITION** | Connection monitoring |
| **EXECUTION** | Core logic (rarely intercepted) |
| **POST_EXECUTION** | Result processing |
| **RESOURCE_RELEASE** | Cleanup |
| **POST_REQUEST** | Metrics, logging |
| **EXCEPTION_HANDLING** | Error handling |

## Best Practices

### ✅ DO:
- Always call `chain.proceed()`
- Handle exceptions gracefully
- Use appropriate priority
- Filter by request type and phase
- Log interceptor activity
- Test thoroughly

### ❌ DON'T:
- Forget to call `chain.proceed()` (chain stops!)
- Throw exceptions for non-critical errors
- Do heavy processing (keep it fast)
- Depend on execution order (use priorities)
- Modify context without documenting it

## Troubleshooting

### Interceptor Not Loading

```bash
# Check if JAR is in ojp-libs/
ls ojp-libs/*.jar

# Check if ServiceLoader file exists
unzip -l your-interceptor.jar | grep META-INF/services

# Enable debug logging
java --log-level=DEBUG -jar ojp-server.jar
```

### Interceptor Not Executing

```java
// Check phase support
@Override
public Set<LifecyclePhase> getSupportedPhases() {
    return Set.of(LifecyclePhase.PRE_EXECUTION);  // Must match phase
}

// Check request type support
@Override
public Set<RequestType> getSupportedRequestTypes() {
    return Set.of(RequestType.QUERY);  // Must match request type
}
```

### Chain Stops

```java
// Make sure you call proceed()!
@Override
public void intercept(RequestContext context, InterceptorChain chain) 
        throws Exception {
    // Your logic
    doSomething();
    
    // MUST call this!
    chain.proceed();
}
```

## Next Steps

- Read [Understanding OJP Interceptors](Understanding-OJP-Interceptors.md) for comprehensive guide
- See [SQL Enhancer source code](../ojp-sql-enhancer-interceptor) for production example
- Check [Request Lifecycle Pattern Design](designs/REQUEST_LIFECYCLE_INTERCEPTOR_PATTERN.md) for architecture
- Join [GitHub Discussions](https://github.com/Open-J-Proxy/ojp/discussions) to share your interceptors

## Examples Repository

Coming soon: A repository of community interceptors!

Meanwhile, check out:
- SQL Enhancer Interceptor (included)
- Examples in Understanding OJP Interceptors guide

---

**Need Help?**
- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- GitHub Discussions: https://github.com/Open-J-Proxy/ojp/discussions
- Documentation: docs/Understanding-OJP-Interceptors.md

Happy intercepting! 🚀
