# OJP SQL Enhancer Interceptor

SQL Enhancement Interceptor for Open-J-Proxy using Apache Calcite for SQL parsing, validation, and optimization.

## Overview

The SQL Enhancer Interceptor provides SQL enhancement capabilities through the OJP Request Lifecycle Interceptor Pattern. It integrates Apache Calcite to:

- **Validate** SQL syntax and semantics
- **Optimize** queries using rule-based transformations
- **Translate** between SQL dialects
- **Cache** enhancement results for fast repeated queries

## Features

- **Graceful Degradation**: Never fails requests - falls back to original SQL on errors
- **Priority-Based**: Executes at priority 600 (transformation range)
- **Phase-Specific**: Runs during PRE_EXECUTION phase only
- **Type-Filtered**: Supports QUERY and UPDATE request types
- **ServiceLoader Discoverable**: Automatically loaded from `ojp-libs/` directory
- **Highly Configurable**: Multiple enhancement modes and options

## Usage

### 1. As a Dependency (Embedded)

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-sql-enhancer-interceptor</artifactId>
    <version>0.3.2-snapshot</version>
</dependency>
```

Configure via system properties:

```java
System.setProperty("ojp.sql.enhancer.enabled", "true");
System.setProperty("ojp.sql.enhancer.mode", "OPTIMIZE");
System.setProperty("ojp.sql.enhancer.dialect", "POSTGRESQL");
```

### 2. As an External Module (ojp-libs/)

1. Build the shaded JAR (future implementation):
   ```bash
   mvn clean package -pl ojp-sql-enhancer-interceptor
   ```

2. Copy the JAR to your `ojp-libs/` directory:
   ```bash
   cp ojp-sql-enhancer-interceptor/target/ojp-sql-enhancer-interceptor-*-shaded.jar /path/to/ojp-libs/
   ```

3. The interceptor will be automatically discovered via ServiceLoader

### 3. Programmatic Usage

```java
// Create engine with custom configuration
SqlEnhancerEngine engine = new SqlEnhancerEngine(
    true,                      // enabled
    "POSTGRESQL",              // dialect
    "",                        // target dialect (empty = no translation)
    true,                      // enable conversion
    true,                      // enable optimization
    OptimizationMode.SYNC,     // optimization mode
    Arrays.asList("FilterProject", "ProjectMerge"),  // enabled rules
    schemaCache,               // optional schema cache
    schemaLoader,              // optional schema loader
    dataSource,                // optional datasource
    "mydb",                    // catalog name
    "public",                  // schema name
    24                         // schema refresh interval (hours)
);

// Create interceptor
SqlEnhancerInterceptor interceptor = new SqlEnhancerInterceptor(engine, true);

// Register with the interceptor registry
RequestInterceptorRegistry.registerInterceptor(interceptor);
```

## Configuration

### Enhancement Modes

The SQL enhancer supports three modes (via `ojp.sql.enhancer.mode`):

- **VALIDATE** (default): Parse and validate SQL syntax only
- **OPTIMIZE**: Enable query optimization with Calcite rules
- **TRANSLATE**: Translate between SQL dialects

### Optimization Modes

Control how optimization is executed (when OPTIMIZE mode is enabled):

- **DISABLED**: No optimization
- **SYNC**: Synchronous optimization (blocks request)
- **ASYNC**: Asynchronous optimization (non-blocking, future enhancement)

### SQL Dialects

Supported dialects (via `ojp.sql.enhancer.dialect`):

- **GENERIC** (default): Generic SQL dialect
- **POSTGRESQL**: PostgreSQL-specific dialect
- **MYSQL**: MySQL-specific dialect
- **ORACLE**: Oracle-specific dialect
- **H2**: H2-specific dialect

### Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.sql.enhancer.enabled` | `false` | Enable SQL enhancement |
| `ojp.sql.enhancer.mode` | `VALIDATE` | Enhancement mode (VALIDATE, OPTIMIZE, TRANSLATE) |
| `ojp.sql.enhancer.dialect` | `GENERIC` | Source SQL dialect |
| `ojp.sql.enhancer.target.dialect` | (empty) | Target dialect for translation |
| `ojp.sql.enhancer.optimization.mode` | `SYNC` | Optimization execution mode |
| `ojp.sql.enhancer.cache.size` | `1000` | Enhancement result cache size |

## Architecture

### Integration with Request Lifecycle

```
PRE_REQUEST
    ↓
PRE_EXECUTION  ← SQL Enhancer Interceptor executes here
    ↓           (transforms SQL before execution)
RESOURCE_ACQUISITION
    ↓
EXECUTION
    ↓
POST_EXECUTION
    ↓
RESOURCE_RELEASE
    ↓
POST_REQUEST
```

### Enhancement Flow

```
Original SQL → Parse → Validate → Optimize → Enhanced SQL
                  ↓        ↓          ↓
                Cache ← Result ← Result
```

## Examples

### Example 1: Basic Validation

```java
// Enable basic validation
System.setProperty("ojp.sql.enhancer.enabled", "true");
System.setProperty("ojp.sql.enhancer.mode", "VALIDATE");

// SQL will be validated but not optimized
String sql = "SELECT * FROM users WHERE id = 1";
// → Parses successfully, passes through unchanged
```

### Example 2: Query Optimization

```java
// Enable optimization
System.setProperty("ojp.sql.enhancer.enabled", "true");
System.setProperty("ojp.sql.enhancer.mode", "OPTIMIZE");

// Redundant conditions will be optimized
String sql = "SELECT * FROM users WHERE 1=1 AND id = 5";
// → Optimized to: "SELECT * FROM users WHERE id = 5"
```

### Example 3: Dialect Translation

```java
// Enable dialect translation
System.setProperty("ojp.sql.enhancer.enabled", "true");
System.setProperty("ojp.sql.enhancer.mode", "TRANSLATE");
System.setProperty("ojp.sql.enhancer.dialect", "POSTGRESQL");
System.setProperty("ojp.sql.enhancer.target.dialect", "MYSQL");

// PostgreSQL-specific syntax will be translated to MySQL
String sql = "SELECT * FROM users LIMIT 10 OFFSET 20";
// → Translated to MySQL-compatible syntax
```

## Performance

- **Caching**: Enhancement results are cached for repeated queries
- **Overhead**: < 0.1ms for cached queries, 1-5ms for new queries
- **Impact**: < 1% on typical query execution time

## Testing

Run all tests:

```bash
mvn test -pl ojp-sql-enhancer-interceptor
```

Run specific test:

```bash
mvn test -pl ojp-sql-enhancer-interceptor -Dtest=SqlEnhancerIntegrationTest
```

## Dependencies

- **Apache Calcite 1.41.0**: SQL parser, validator, and optimizer
- **OJP Interceptor API**: Core interceptor interfaces
- **Lombok**: Boilerplate reduction
- **SLF4J**: Logging API

## Troubleshooting

### Interceptor Not Loading

1. Check that `ojp.sql.enhancer.enabled=true` is set
2. Verify the JAR is in the classpath or `ojp-libs/` directory
3. Check logs for ServiceLoader discovery messages

### SQL Not Being Enhanced

1. Verify the request type is QUERY or UPDATE
2. Check that enhancement mode is appropriate
3. Look for error messages in logs (enhancement failures are logged but don't fail requests)

### Performance Issues

1. Check cache hit rate in logs
2. Consider using ASYNC optimization mode (when available)
3. Disable optimization for simple queries

## Future Enhancements

- [ ] Asynchronous optimization support
- [ ] Schema-aware optimization
- [ ] Custom optimization rule sets
- [ ] Metrics and monitoring integration
- [ ] Shaded JAR for external deployment

## License

This module is part of the Open-J-Proxy project.
