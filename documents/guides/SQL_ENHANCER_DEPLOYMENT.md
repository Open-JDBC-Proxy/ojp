# SQL Enhancer Interceptor Deployment Guide

This guide explains how to deploy the SQL Enhancer Interceptor as an external module in OJP.

## Overview

The SQL Enhancer Interceptor provides SQL validation, optimization, and dialect translation using Apache Calcite. It can be deployed:

1. **Embedded** - Included as a Maven dependency (development/testing)
2. **External** - Loaded dynamically from `ojp-libs/` directory (production)
3. **Hybrid** - Combined with other interceptors for powerful SQL processing

## External Deployment (Recommended for Production)

### Step 1: Build the Shaded JAR

```bash
cd ojp-sql-enhancer-interceptor
mvn clean package
```

This creates two JARs:
- `ojp-sql-enhancer-interceptor-0.3.2-snapshot.jar` - Regular JAR (requires dependencies)
- `ojp-sql-enhancer-interceptor-0.3.2-snapshot-shaded.jar` - Fat JAR with all dependencies

### Step 2: Deploy to ojp-libs Directory

#### Option A: Runnable JAR Deployment

```bash
# Copy shaded JAR to ojp-libs directory
cp ojp-sql-enhancer-interceptor/target/ojp-sql-enhancer-interceptor-0.3.2-snapshot-shaded.jar ./ojp-libs/

# Verify file permissions
chmod 644 ./ojp-libs/ojp-sql-enhancer-interceptor-*.jar

# Start OJP Server
java -jar ojp-server/target/ojp-server-0.3.2-snapshot-shaded.jar
```

#### Option B: Docker Deployment with Volume Mount

```bash
# Create ojp-libs directory
mkdir -p ./ojp-libs

# Copy shaded JAR
cp ojp-sql-enhancer-interceptor/target/ojp-sql-enhancer-interceptor-0.3.2-snapshot-shaded.jar ./ojp-libs/

# Run Docker with volume mount
docker run -d \
  -p 1059:1059 \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  --name ojp \
  rrobetti/ojp:0.3.2-snapshot
```

#### Option C: Custom Docker Image

```bash
# Copy shaded JAR to ojp-libs for building
mkdir -p ./ojp-libs
cp ojp-sql-enhancer-interceptor/target/ojp-sql-enhancer-interceptor-0.3.2-snapshot-shaded.jar ./ojp-libs/

# Build custom image with interceptor embedded
docker build -f Dockerfile.proprietary -t my-company/ojp-with-sql-enhancer:1.0.0 .

# Run custom image
docker run -d -p 1059:1059 my-company/ojp-with-sql-enhancer:1.0.0
```

### Step 3: Enable the Interceptor

The SQL Enhancer Interceptor is **disabled by default** to ensure backward compatibility. Enable it via configuration:

**JVM System Property:**
```bash
java -Dojp.sql.enhancer.enabled=true \
     -Dojp.interceptor.enabled=true \
     -jar ojp-server-0.3.2-snapshot-shaded.jar
```

**Environment Variables:**
```bash
export OJP_SQL_ENHANCER_ENABLED=true
export OJP_INTERCEPTOR_ENABLED=true
docker run -d \
  -e OJP_SQL_ENHANCER_ENABLED=true \
  -e OJP_INTERCEPTOR_ENABLED=true \
  -p 1059:1059 \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  rrobetti/ojp:0.3.2-snapshot
```

**Docker Compose:**
```yaml
version: '3.8'
services:
  ojp:
    image: rrobetti/ojp:0.3.2-snapshot
    ports:
      - "1059:1059"
    volumes:
      - ./ojp-libs:/opt/ojp/ojp-libs
    environment:
      - OJP_SQL_ENHANCER_ENABLED=true
      - OJP_INTERCEPTOR_ENABLED=true
      - OJP_SQL_ENHANCER_MODE=OPTIMIZE
      - OJP_SQL_ENHANCER_DIALECT=POSTGRESQL
```

### Step 4: Verify Deployment

Check the server logs for successful loading:

```
INFO  Loading external library JAR: ojp-sql-enhancer-interceptor-0.3.2-snapshot-shaded.jar
INFO  Successfully loaded 1 external library JAR(s) from: /opt/ojp/ojp-libs
INFO  Discovered RequestInterceptor: sql-enhancer (priority: 600)
INFO  SQL Enhancer Interceptor enabled with mode: VALIDATE
```

## Configuration Options

### Enhancement Modes

Configure via `ojp.sql.enhancer.mode`:

- **VALIDATE** (default) - Validate SQL syntax only, no modifications
- **OPTIMIZE** - Optimize queries using Calcite rules
- **TRANSLATE** - Translate SQL between dialects
- **ANALYZE** - Analyze queries and log statistics (no modifications)

### SQL Dialects

Configure source dialect via `ojp.sql.enhancer.dialect`:

- **GENERIC** (default) - Standard SQL
- **POSTGRESQL** - PostgreSQL-specific syntax
- **MYSQL** - MySQL/MariaDB-specific syntax
- **ORACLE** - Oracle Database syntax
- **H2** - H2 Database syntax

### Optimization Settings

```bash
# Enable optimization with async mode
OJP_SQL_ENHANCER_MODE=OPTIMIZE
OJP_SQL_ENHANCER_OPTIMIZATION_MODE=ASYNC

# Enable caching for repeated queries
OJP_SQL_ENHANCER_CACHE_ENABLED=true
OJP_SQL_ENHANCER_CACHE_SIZE=1000

# Log optimization details
OJP_SQL_ENHANCER_LOG_OPTIMIZATIONS=true
```

### Full Configuration Example

```bash
# Enable interceptor framework
export OJP_INTERCEPTOR_ENABLED=true

# SQL Enhancer settings
export OJP_SQL_ENHANCER_ENABLED=true
export OJP_SQL_ENHANCER_MODE=OPTIMIZE
export OJP_SQL_ENHANCER_DIALECT=POSTGRESQL
export OJP_SQL_ENHANCER_OPTIMIZATION_MODE=ASYNC
export OJP_SQL_ENHANCER_CACHE_ENABLED=true
export OJP_SQL_ENHANCER_CACHE_SIZE=1000
export OJP_SQL_ENHANCER_LOG_OPTIMIZATIONS=true

# Schema loading settings
export OJP_SQL_ENHANCER_SCHEMA_REFRESH_ENABLED=true
export OJP_SQL_ENHANCER_SCHEMA_REFRESH_INTERVAL_HOURS=24
```

## Kubernetes Deployment

### Using ConfigMap for JAR

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ojp-interceptors
binaryData:
  ojp-sql-enhancer-interceptor-0.3.2-snapshot-shaded.jar: |
    <base64-encoded-jar-content>
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ojp-server
spec:
  template:
    spec:
      containers:
      - name: ojp
        image: rrobetti/ojp:0.3.2-snapshot
        env:
        - name: OJP_INTERCEPTOR_ENABLED
          value: "true"
        - name: OJP_SQL_ENHANCER_ENABLED
          value: "true"
        - name: OJP_SQL_ENHANCER_MODE
          value: "OPTIMIZE"
        volumeMounts:
        - name: interceptors
          mountPath: /opt/ojp/ojp-libs
      volumes:
      - name: interceptors
        configMap:
          name: ojp-interceptors
```

### Using Init Container (Download from Artifact Repository)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ojp-server
spec:
  template:
    spec:
      initContainers:
      - name: download-interceptor
        image: curlimages/curl:latest
        command:
        - sh
        - -c
        - |
          curl -o /ojp-libs/ojp-sql-enhancer-interceptor-0.3.2-snapshot-shaded.jar \
            $INTERCEPTOR_URL
        env:
        - name: INTERCEPTOR_URL
          value: "https://artifacts.company.com/ojp/ojp-sql-enhancer-interceptor-0.3.2-snapshot-shaded.jar"
        volumeMounts:
        - name: interceptors
          mountPath: /ojp-libs
      containers:
      - name: ojp
        image: rrobetti/ojp:0.3.2-snapshot
        env:
        - name: OJP_INTERCEPTOR_ENABLED
          value: "true"
        - name: OJP_SQL_ENHANCER_ENABLED
          value: "true"
        volumeMounts:
        - name: interceptors
          mountPath: /opt/ojp/ojp-libs
      volumes:
      - name: interceptors
        emptyDir: {}
```

## Embedded Deployment (Development/Testing)

For development or when you want the interceptor always available:

### Maven Dependency

Add to `ojp-server/pom.xml`:

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-sql-enhancer-interceptor</artifactId>
    <version>0.3.2-snapshot</version>
</dependency>
```

**Note**: This approach embeds the interceptor in the server JAR, making it always available but less flexible for updates.

## Combining with Other Interceptors

The SQL Enhancer can work alongside other interceptors:

```bash
# Place multiple interceptor JARs in ojp-libs/
./ojp-libs/
  ├── ojp-sql-enhancer-interceptor-0.3.2-snapshot-shaded.jar
  ├── custom-circuit-breaker-interceptor-1.0.0.jar
  └── custom-logging-interceptor-1.0.0.jar
```

Interceptors execute in priority order:
- **1000+**: Infrastructure interceptors (auth, routing)
- **500-999**: Transformation interceptors (SQL Enhancer: 600)
- **100-499**: Resource management interceptors
- **0-99**: Monitoring/logging interceptors

## Performance Considerations

### Caching

Enable caching to improve performance for repeated queries:

```bash
export OJP_SQL_ENHANCER_CACHE_ENABLED=true
export OJP_SQL_ENHANCER_CACHE_SIZE=1000
```

**Performance Impact:**
- **Cached queries**: <0.1ms overhead
- **New queries**: 1-5ms overhead (depends on complexity)
- **Overall impact**: <1% for typical workloads

### Async Optimization

For expensive optimizations, use async mode:

```bash
export OJP_SQL_ENHANCER_OPTIMIZATION_MODE=ASYNC
```

This optimizes queries in the background without blocking request execution.

### Schema Refresh

Control schema metadata refresh to balance accuracy vs. performance:

```bash
# Refresh schema every 24 hours
export OJP_SQL_ENHANCER_SCHEMA_REFRESH_INTERVAL_HOURS=24

# Disable automatic refresh for static schemas
export OJP_SQL_ENHANCER_SCHEMA_REFRESH_ENABLED=false
```

## Troubleshooting

### Interceptor Not Loaded

**Problem**: SQL Enhancer JAR in ojp-libs/ but not detected.

**Solutions**:
1. Check file permissions: `chmod 644 ojp-libs/*.jar`
2. Verify `.jar` extension (case-insensitive)
3. Check logs for loading errors
4. Ensure using shaded JAR (includes all dependencies)
5. Verify ServiceLoader metadata: `jar -tf ojp-sql-enhancer-*.jar | grep META-INF/services`

### Interceptor Disabled

**Problem**: Interceptor loaded but not executing.

**Solutions**:
1. Enable interceptor framework: `OJP_INTERCEPTOR_ENABLED=true`
2. Enable SQL enhancer: `OJP_SQL_ENHANCER_ENABLED=true`
3. Check logs for configuration messages
4. Verify request types (only QUERY and UPDATE supported)

### ClassLoader Conflicts

**Problem**: Version conflicts with server dependencies.

**Solutions**:
1. Use shaded JAR (dependencies are relocated)
2. Check for duplicate dependencies in ojp-libs/
3. Review relocation patterns in pom.xml
4. Use `mvn dependency:tree` to analyze conflicts

### Performance Issues

**Problem**: Queries slower with interceptor enabled.

**Solutions**:
1. Enable caching: `OJP_SQL_ENHANCER_CACHE_ENABLED=true`
2. Use async optimization: `OJP_SQL_ENHANCER_OPTIMIZATION_MODE=ASYNC`
3. Disable optimization if not needed: `OJP_SQL_ENHANCER_MODE=VALIDATE`
4. Review optimization timeout: `OJP_SQL_ENHANCER_OPTIMIZATION_TIMEOUT=5000`
5. Monitor with: `OJP_SQL_ENHANCER_LOG_OPTIMIZATIONS=true`

### Schema Loading Errors

**Problem**: Cannot load database schema metadata.

**Solutions**:
1. Enable fallback mode: `OJP_SQL_ENHANCER_SCHEMA_FALLBACK_ENABLED=true`
2. Increase timeout: `OJP_SQL_ENHANCER_SCHEMA_LOAD_TIMEOUT_SECONDS=60`
3. Check database permissions (requires SELECT on metadata tables)
4. Verify JDBC connection is valid

## Migration from Hard-Coded Integration

If upgrading from a version with hard-coded SQL enhancement:

### Step 1: Deploy Interceptor

Follow external deployment steps above to add the interceptor JAR.

### Step 2: Enable Both (Temporary)

```bash
# Keep legacy enabled temporarily
export OJP_SQL_ENHANCER_ENABLED=true

# Enable interceptor framework
export OJP_INTERCEPTOR_ENABLED=true
```

Both will run in parallel - verify identical behavior in logs.

### Step 3: Switch to Interceptor Only

Once validated, the legacy integration can be disabled (future version).

## Best Practices

1. **Use Shaded JAR**: Always deploy the `-shaded.jar` to avoid dependency conflicts
2. **Version Control**: Track interceptor version alongside OJP version
3. **Test First**: Validate in dev/staging before production
4. **Monitor Performance**: Use logging to track enhancement times
5. **Cache Management**: Tune cache size based on query patterns
6. **Schema Refresh**: Balance freshness vs. performance for your use case

## CI/CD Integration

### GitLab CI Example

```yaml
deploy-ojp-with-interceptors:
  stage: deploy
  script:
    - mkdir -p ojp-libs
    - mvn clean package -pl ojp-sql-enhancer-interceptor
    - cp ojp-sql-enhancer-interceptor/target/*-shaded.jar ojp-libs/
    - docker build -f Dockerfile.proprietary -t $CI_REGISTRY_IMAGE:$CI_COMMIT_SHA .
    - docker push $CI_REGISTRY_IMAGE:$CI_COMMIT_SHA
```

### GitHub Actions Example

```yaml
name: Deploy OJP with Interceptors
on: [push]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Build interceptor
        run: |
          mvn clean package -pl ojp-sql-enhancer-interceptor
          mkdir -p ojp-libs
          cp ojp-sql-enhancer-interceptor/target/*-shaded.jar ojp-libs/
      - name: Build Docker image
        run: docker build -f Dockerfile.proprietary -t ojp:latest .
```

## Support

For issues or questions about SQL Enhancer deployment:
- **GitHub Issues**: [Open an issue](https://github.com/Open-J-Proxy/ojp/issues)
- **Discord**: [Join our community](https://discord.gg/J5DdHpaUzu)
- **Documentation**: 
  - [SQL Enhancer README](../../ojp-sql-enhancer-interceptor/README.md)
  - [Request Lifecycle Interceptor Pattern](../designs/REQUEST_LIFECYCLE_INTERCEPTOR_PATTERN.md)
  - [Drivers and External Libraries](../configuration/DRIVERS_AND_LIBS.md)
