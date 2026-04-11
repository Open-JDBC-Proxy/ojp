# OJP Server Complete Configuration Guide

The OJP Server supports comprehensive configuration through both JVM system properties and environment variables. This document covers all available configuration options including server settings, connection pools, slow query segregation, and client-side configuration.

> **⚠️ Important JVM Property:**  
> Always start the server with `-Duser.timezone=UTC`. The OJP Server handles date/time values from multiple databases and client timezones. Running the JVM in UTC ensures consistent and predictable temporal conversions. Omitting this setting can cause incorrect date/time handling when clients and databases use different timezones.

## Server Configuration

The server supports configuration through both JVM system properties and environment variables. JVM system properties take precedence over environment variables when both are specified.

### Core Server Settings

| Property                             | Environment Variable                 | Type    | Default   | Description                                            | Since   |
|--------------------------------------|--------------------------------------|---------|-----------|--------------------------------------------------------|---------|
| `ojp.server.port`                    | `OJP_SERVER_PORT`                    | int     | 1059      | gRPC server port                                       | 0.2.0-beta |
| `ojp.prometheus.port`                | `OJP_PROMETHEUS_PORT`                | int     | 9159      | Prometheus metrics HTTP server port                    | 0.2.0-beta |
| `ojp.server.threadPoolSize`          | `OJP_SERVER_THREADPOOLSIZE`          | int     | 200       | gRPC server thread pool size                           | 0.2.0-beta |
| `ojp.server.maxRequestSize`          | `OJP_SERVER_MAXREQUESTSIZE`          | int     | 4194304   | Maximum request size in bytes (4MB)                    | 0.2.0-beta |
| `ojp.server.connectionIdleTimeout`   | `OJP_SERVER_CONNECTIONIDLETIMEOUT`   | long    | 30000     | Connection idle timeout in milliseconds                | 0.2.0-beta |

### Logging Settings

OJP Server uses Logback for logging with fully configurable options. All logging properties can be set via system properties or environment variables.

| Property                           | Environment Variable               | Type    | Default                            | Description                                   | Since      |
|------------------------------------|------------------------------------|---------|------------------------------------|-----------------------------------------------|------------|
| `ojp.server.logLevel`              | `OJP_SERVER_LOGLEVEL`              | string  | INFO                               | Root log level (TRACE, DEBUG, INFO, WARN, ERROR) | 0.2.0-beta    |
| `ojp.server.log.file`              | `OJP_SERVER_LOG_FILE`              | string  | logs/ojp-server.log                | Log file location                            | 0.4.0-beta |
| `ojp.server.log.fileNamePattern`   | `OJP_SERVER_LOG_FILENAMEPATTERN`   | string  | logs/ojp-server.%d{yyyy-MM-dd}.log | Rolling file pattern (daily rollover)       | 0.4.0-beta |
| `ojp.server.log.maxHistory`        | `OJP_SERVER_LOG_MAXHISTORY`        | int     | 30                                 | Number of days to keep log files            | 0.4.0-beta |
| `ojp.server.log.totalSizeCap`      | `OJP_SERVER_LOG_TOTALSIZECAP`      | string  | 1GB                                | Total size cap for all log files            | 0.4.0-beta |
| `ojp.server.log.pattern`           | `OJP_SERVER_LOG_PATTERN`           | string  | %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n | Log message pattern | 0.4.0-beta |

#### Logging Configuration Examples

**Basic logging configuration:**
```bash
# Set log level to DEBUG
-Dojp.server.logLevel=DEBUG

# Change log file location
-Dojp.server.log.file=/var/log/ojp/server.log

# Keep 60 days of logs
-Dojp.server.log.maxHistory=60

# Set total size cap to 5GB
-Dojp.server.log.totalSizeCap=5GB
```

**Production logging setup:**
```bash
java -Duser.timezone=UTC \
     -Dojp.server.logLevel=INFO \
     -Dojp.server.log.file=/var/log/ojp/server.log \
     -Dojp.server.log.maxHistory=90 \
     -Dojp.server.log.totalSizeCap=10GB \
     -jar ojp-server.jar
```

### Security Settings

| Property                      | Environment Variable          | Type    | Default   | Description                                              | Since |
|-------------------------------|-------------------------------|---------|-----------|----------------------------------------------------------|-------|
| `ojp.server.allowedIps`       | `OJP_SERVER_ALLOWEDIPS`       | string  | 0.0.0.0/0 | IP whitelist for gRPC server (comma-separated)          | 0.2.0-beta |
| `ojp.prometheus.allowedIps`   | `OJP_PROMETHEUS_ALLOWEDIPS`   | string  | 0.0.0.0/0 | IP whitelist for Prometheus endpoint (comma-separated)  | 0.2.0-beta |

#### SSL/TLS Certificate Path Placeholders

OJP Server supports property placeholders in JDBC URLs to enable server-side SSL/TLS certificate configuration. This allows certificate paths to be configured on the server rather than hardcoded in client connection URLs.

**How it works:**
1. Client configures URL with placeholders: `jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}`
2. Server resolves placeholders from JVM properties or environment variables
3. Placeholders are replaced with actual file paths before connecting to the database

**Configuration example:**
```bash
# JVM properties
java -Duser.timezone=UTC \
  -jar ojp-server.jar \
  -Dojp.server.sslrootcert=/etc/ojp/certs/ca-cert.pem \
  -Dojp.server.sslcert=/etc/ojp/certs/client-cert.pem

# Environment variables
export OJP_SERVER_SSLROOTCERT=/etc/ojp/certs/ca-cert.pem
export OJP_SERVER_SSLCERT=/etc/ojp/certs/client-cert.pem
```

**Common SSL/TLS properties for different databases:**

| Database   | Common Properties                                                          |
|------------|---------------------------------------------------------------------------|
| PostgreSQL | `ojp.server.sslrootcert`, `ojp.server.sslcert`, `ojp.server.sslkey`     |
| MySQL      | `ojp.server.mysql.truststore`, `ojp.server.mysql.keystore`              |
| Oracle     | `ojp.server.oracle.wallet.location`                                      |
| SQL Server | `ojp.server.sqlserver.truststore`, `ojp.server.sqlserver.keystore`      |
| DB2        | `ojp.server.db2.truststore`, `ojp.server.db2.keystore`                  |

For detailed configuration examples for each database, see [SSL/TLS Certificate Configuration Guide](ssl-tls-certificate-placeholders.md).


### OpenTelemetry Settings

| Property                      | Environment Variable          | Type    | Default | Description                                    | Since |
|-------------------------------|-------------------------------|---------|---------|------------------------------------------------|-------|
| `ojp.telemetry.enabled`   | `OJP_TELEMETRY_ENABLED`   | boolean | true    | Master switch: Enable/disable OpenTelemetry infrastructure (Prometheus server, MeterProvider, TracerProvider)  | 0.2.0-beta |
| `ojp.opentelemetry.endpoint`  | `OJP_OPENTELEMETRY_ENDPOINT`  | string  | ""      | OpenTelemetry exporter endpoint (empty = default) | 0.2.0-beta |

### Tracing Settings

Distributed tracing is **disabled by default**. When enabled, OJP emits OpenTelemetry spans for all gRPC calls to a Zipkin or OTLP-compatible backend (e.g. Jaeger, Grafana Tempo).

| Property                    | Environment Variable        | Type    | Default                                   | Description                                                   | Since          |
|-----------------------------|-----------------------------|---------|-------------------------------------------|---------------------------------------------------------------|----------------|
| `ojp.tracing.enabled`       | `OJP_TRACING_ENABLED`       | boolean | false                                     | Enable/disable distributed tracing (disabled by default)      | 0.4.0-beta |
| `ojp.tracing.exporter`      | `OJP_TRACING_EXPORTER`      | string  | zipkin                                    | Trace exporter type: `zipkin` or `otlp`                      | 0.4.0-beta |
| `ojp.tracing.endpoint`      | `OJP_TRACING_ENDPOINT`      | string  | http://localhost:9411/api/v2/spans        | Exporter endpoint URL                                         | 0.4.0-beta |
| `ojp.tracing.serviceName`   | `OJP_TRACING_SERVICENAME`   | string  | ojp-server                                | Service name attached to all spans                           | 0.4.0-beta |
| `ojp.tracing.sampleRate`    | `OJP_TRACING_SAMPLERATE`    | double  | 1.0                                       | Fraction of requests to sample (0.0–1.0)                     | 0.4.0-beta |

#### Tracing Configuration Examples

**Enable Zipkin tracing:**
```bash
java -Duser.timezone=UTC \
  -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.endpoint=http://zipkin:9411/api/v2/spans \
  -Dojp.tracing.serviceName=my-ojp-server
```

**Enable OTLP tracing (Jaeger / Grafana Tempo / OpenTelemetry Collector):**
```bash
java -Duser.timezone=UTC \
  -jar ojp-server.jar \
  -Dojp.tracing.enabled=true \
  -Dojp.tracing.exporter=otlp \
  -Dojp.tracing.endpoint=http://jaeger:4317 \
  -Dojp.tracing.sampleRate=0.1
```

For full integration examples including Docker Compose setups, see the **[Telemetry Documentation](../telemetry/README.md)**.

### Circuit Breaker Settings

| Property                             | Environment Variable                 | Type | Default | Description                                       | Since |
|--------------------------------------|--------------------------------------|------|---------|---------------------------------------------------|-------|
| `ojp.server.circuitBreakerTimeout`   | `OJP_SERVER_CIRCUITBREAKERTIMEOUT`   | long | 60000   | Circuit breaker timeout once open in milliseconds | 0.2.0-beta |
| `ojp.server.circuitBreakerThreshold` | `OJP_SERVER_CIRCUITBREAKERTHRESHOLD` | int  | 3       | Circuit breaker failure threshold                 | 0.2.0-beta |

### Slow Query Segregation Settings

| Property                                           | Environment Variable                               | Type    | Default  | Description                                      | Since |
|----------------------------------------------------|----------------------------------------------------|---------|----------|--------------------------------------------------|-------|
| `ojp.server.slowQuerySegregation.enabled`         | `OJP_SERVER_SLOWQUERYSEGREGATION_ENABLED`         | boolean | false    | Enable/disable slow query segregation feature   | 0.2.0-beta |
| `ojp.server.slowQuerySegregation.slowSlotPercentage` | `OJP_SERVER_SLOWQUERYSEGREGATION_SLOWSLOTPERCENTAGE` | int     | 20       | Percentage of slots for slow operations (0-100) | 0.2.0-beta |
| `ojp.server.slowQuerySegregation.idleTimeout`     | `OJP_SERVER_SLOWQUERYSEGREGATION_IDLETIMEOUT`     | long    | 10000    | Idle timeout for slot borrowing (milliseconds)  | 0.2.0-beta |
| `ojp.server.slowQuerySegregation.slowSlotTimeout` | `OJP_SERVER_SLOWQUERYSEGREGATION_SLOWSLOTTIMEOUT` | long    | 120000   | Timeout for acquiring slow operation slots (ms) | 0.2.0-beta |
| `ojp.server.slowQuerySegregation.fastSlotTimeout` | `OJP_SERVER_SLOWQUERYSEGREGATION_FASTSLOTTIMEOUT` | long    | 60000    | Timeout for acquiring fast operation slots (ms) | 0.2.0-beta |

### SQL Enhancer and Schema Loader Settings

> **⚠️ EXPERIMENTAL FEATURE - NOT RECOMMENDED FOR PRODUCTION**
>
> The SQL Enhancer with Apache Calcite is **EXPERIMENTAL** and **NOT YET SUPPORTED** for production use.
>
> - **Default**: Disabled (false)
> - **Known Issues**: Substantial type system incompatibilities with PostgreSQL, MySQL, Oracle, SQL Server, and other traditional JDBC databases
> - **Recommendation**: **Do NOT enable in production environments**
>
> Apache Calcite is designed for big data systems (Apache Hive, Drill, Phoenix, Druid, Flink, BigQuery, Elasticsearch, MongoDB) and has significant limitations with traditional relational databases. Early testing revealed critical type mapping issues that prevent reliable query optimization.
>
> **We strongly discourage using this feature in its current state.**

The SQL Enhancer provides query optimization using Apache Calcite with real database schema metadata for accurate query analysis.

| Property                                           | Environment Variable                               | Type    | Default  | Description                                      |
|----------------------------------------------------|----------------------------------------------------|---------|----------|--------------------------------------------------|
| `ojp.sql.enhancer.enabled`                        | `OJP_SQL_ENHANCER_ENABLED`                        | boolean | false    | Enable/disable SQL query enhancement (**NOT RECOMMENDED**) |
| `ojp.sql.enhancer.schema.refresh.enabled`         | `OJP_SQL_ENHANCER_SCHEMA_REFRESH_ENABLED`         | boolean | true     | Enable automatic schema metadata refresh        |
| `ojp.sql.enhancer.schema.refresh.interval.hours`  | `OJP_SQL_ENHANCER_SCHEMA_REFRESH_INTERVAL_HOURS`  | long    | 24       | Hours between automatic schema refreshes         |
| `ojp.sql.enhancer.schema.load.timeout.seconds`    | `OJP_SQL_ENHANCER_SCHEMA_LOAD_TIMEOUT_SECONDS`    | long    | 30       | Timeout for schema loading operations (seconds) |
| `ojp.sql.enhancer.schema.fallback.enabled`        | `OJP_SQL_ENHANCER_SCHEMA_FALLBACK_ENABLED`        | boolean | true     | Fall back to generic schema if loading fails    |

#### SQL Enhancer Configuration Examples

> **⚠️ WARNING**: These examples are for testing/experimental purposes only. Do not use in production.

**Enable SQL enhancement with schema loading (NOT RECOMMENDED):**
```bash
# Enable SQL enhancer (EXPERIMENTAL - NOT FOR PRODUCTION)
-Dojp.sql.enhancer.enabled=true

# Configure schema refresh (default 24 hours)
-Dojp.sql.enhancer.schema.refresh.enabled=true
-Dojp.sql.enhancer.schema.refresh.interval.hours=24

# Set schema load timeout to 60 seconds
-Dojp.sql.enhancer.schema.load.timeout.seconds=60
```

**Testing/Development setup (NOT FOR PRODUCTION):**
```bash
# WARNING: For testing/development only
java -Duser.timezone=UTC \
     -Dojp.sql.enhancer.enabled=true \
     -Dojp.sql.enhancer.schema.refresh.enabled=true \
     -Dojp.sql.enhancer.schema.refresh.interval.hours=12 \
     -Dojp.sql.enhancer.schema.load.timeout.seconds=30 \
     -jar ojp-server.jar
```

#### Database Schema Integration

> **⚠️ EXPERIMENTAL**: Schema integration has known limitations with traditional JDBC databases.

The SQL Enhancer can load real database schema metadata to improve query optimization accuracy. Schema metadata is loaded asynchronously from database connections and cached for performance.

**Features:**
- **Asynchronous Loading**: Schema metadata loads in the background without blocking queries
- **Automatic Refresh**: Configurable periodic refresh keeps schema metadata current
- **Thread-Safe**: Multiple connections can safely access and update schema cache
- **Fallback Support**: Falls back to generic schema if real schema is unavailable
- **Multi-Database**: Designed for MySQL, PostgreSQL, Oracle, SQL Server (but has known limitations)

**Required Database Privileges:**

For schema loading to work, the database user needs read access to metadata tables:

- **MySQL/MariaDB:**
  ```sql
  GRANT SELECT ON information_schema.tables TO 'user'@'host';
  GRANT SELECT ON information_schema.columns TO 'user'@'host';
  ```

- **PostgreSQL:**
  ```sql
  GRANT CONNECT ON DATABASE dbname TO user;
  GRANT USAGE ON SCHEMA schemaname TO user;
  -- Access to pg_catalog is typically granted by default
  ```

- **Oracle:**
  ```sql
  GRANT SELECT_CATALOG_ROLE TO user;
  -- OR --
  GRANT SELECT ANY DICTIONARY TO user;
  ```

- **SQL Server:**
  ```sql
  GRANT VIEW DEFINITION TO user;
  -- OR --
  ALTER ROLE db_datareader ADD MEMBER user;
  ```

- **DB2:**
  ```sql
  GRANT SELECT ON SYSCAT.TABLES TO user;
  GRANT SELECT ON SYSCAT.COLUMNS TO user;
  ```

**Troubleshooting Schema Loading:**

If schema loading fails, check the following:

1. **Verify database user has required privileges** (see above)
2. **Check timeout settings** - increase if your database has many tables
3. **Review server logs** for error messages about schema loading
4. **Ensure fallback is enabled** - allows queries to work even if schema load fails
5. **Test with a simple query** to verify schema is loading correctly

The server logs will show messages like:
```
INFO  SchemaLoader - Loading schema metadata for catalog: null, schema: null
INFO  SchemaLoader - Loaded 42 tables in 156ms
INFO  SchemaCache - Schema cache updated with 42 tables
```

## Client-Side Configuration

For JDBC driver and client-side connection pool configuration, see:

- **[OJP JDBC Configuration](ojp-jdbc-configuration.md)** - JDBC driver setup and client connection pool settings

## Configuration Methods

### 1. JVM System Properties

Set configuration using JVM system properties when starting the server:

```bash
java -Duser.timezone=UTC \
     -Dojp.server.port=8080 \
     -Dojp.prometheus.port=9091 \
     -Dojp.telemetry.enabled=false \
     -Dojp.server.threadPoolSize=100 \
     -Dojp.server.circuitBreakerTimeout=120000 \
     -Dojp.server.circuitBreakerThreshold=3 \
     -Dojp.server.slowQuerySegregation.enabled=true \
     -Dojp.server.slowQuerySegregation.slowSlotPercentage=25 \
     -Dojp.server.allowedIps="192.168.1.0/24,10.0.0.1" \
     -jar ojp-server.jar
```

### 2. Environment Variables

Set configuration using environment variables:

```bash
export OJP_SERVER_PORT=8080
export OJP_PROMETHEUS_PORT=9091
export OJP_OPENTELEMETRY_ENABLED=false
export OJP_SERVER_THREADPOOLSIZE=100
export OJP_SERVER_CIRCUITBREAKERTIMEOUT=120000
export OJP_SERVER_CIRCUITBREAKERTHRESHOLD=3
export OJP_SERVER_SLOWQUERYSEGREGATION_ENABLED=true
export OJP_SERVER_SLOWQUERYSEGREGATION_SLOWSLOTPERCENTAGE=25
export OJP_SERVER_ALLOWEDIPS="192.168.1.0/24,10.0.0.1"
java -Duser.timezone=UTC -jar ojp-server.jar
```

### 3. Docker Environment Variables

```bash
docker run -e OJP_SERVER_PORT=8080 \
           -e OJP_PROMETHEUS_PORT=9091 \
           -e OJP_OPENTELEMETRY_ENABLED=false \
           -e OJP_SERVER_CIRCUITBREAKERTIMEOUT=120000 \
           -e OJP_SERVER_SLOWQUERYSEGREGATION_ENABLED=true \
           -e OJP_SERVER_ALLOWEDIPS="192.168.1.0/24,10.0.0.1" \
           -p 8080:8080 \
           -p 9091:9091 \
           rrobetti/ojp:latest
```

### 4. Docker with JVM Parameters (JAVA_TOOL_OPTIONS)

Pass JVM parameters (heap size, system properties, GC options) using the `JAVA_TOOL_OPTIONS` environment variable:

```bash
docker run -e JAVA_TOOL_OPTIONS="-Xmx4g -Xms2g -Dfile.encoding=UTF-8 -Duser.timezone=UTC" \
           -e OJP_SERVER_PORT=1059 \
           -e OJP_SERVER_LOGLEVEL=INFO \
           -p 1059:1059 \
           -p 9159:9159 \
           rrobetti/ojp:latest
```

The JVM automatically recognizes and applies parameters set in `JAVA_TOOL_OPTIONS`. This allows you to configure:
- **Memory settings**: `-Xmx4g`, `-Xms2g`, `-XX:MaxMetaspaceSize=512m`
- **System properties**: `-Dfile.encoding=UTF-8`, `-Duser.timezone=UTC`
- **Garbage collection**: `-XX:+UseG1GC`, `-XX:MaxGCPauseMillis=200`
- **Debugging**: `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`

For comprehensive Docker deployment examples and best practices, see **[Docker Deployment Guide](DOCKER_DEPLOYMENT.md)**.

## IP Whitelist Configuration

The server supports IP-based access control for both the gRPC server and Prometheus endpoints.

### Supported Formats

- **Individual IP addresses**: `192.168.1.1`
- **CIDR ranges**: `192.168.1.0/24`, `10.0.0.0/8`
- **Wildcard (allow all)**: `0.0.0.0/0` or `*`
- **Multiple rules**: `192.168.1.1,10.0.0.0/8,172.16.0.1`

### Examples

```bash
# Allow only specific IPs
-Dojp.server.allowedIps="192.168.1.100,192.168.1.101"

# Allow a subnet range
-Dojp.server.allowedIps="192.168.1.0/24"

# Allow multiple subnets and specific IPs
-Dojp.server.allowedIps="192.168.1.0/24,10.0.0.0/8,127.0.0.1"

# Allow all (default)
-Dojp.server.allowedIps="0.0.0.0/0"
```

### Separate Prometheus Whitelist

You can configure different IP restrictions for the Prometheus metrics endpoint:

```bash
# Allow gRPC from internal network, Prometheus from monitoring subnet only
-Dojp.server.allowedIps="10.0.0.0/8" \
-Dojp.prometheus.allowedIps="192.168.100.0/24"
```

## Slow Query Segregation Feature

The Slow Query Segregation feature monitors all database operations and classifies them as "slow" or "fast" based on their execution time, then manages the number of concurrently executing operations to prevent slow operations from blocking the system.

### How It Works

1. **Operation Monitoring**: Every SQL operation is tracked using a hash of the SQL statement
2. **Execution Time Tracking**: Execution times are recorded and averaged using a weighted formula: `new_average = ((stored_average * 4) + new_measurement) / 5`
3. **Classification**: An operation is classified as "slow" if its average execution time is **2x or greater** than the overall average execution time
4. **Slot Management**: The total number of concurrent operations is limited by the HikariCP connection pool maximum size
5. **Slot Borrowing**: If one pool (slow/fast) is idle for a configurable time, the other pool can borrow its slots

### Configuration

```properties
# Enable/disable the feature
ojp.server.slowQuerySegregation.enabled=false

# Percentage of slots for slow operations (0-100)
ojp.server.slowQuerySegregation.slowSlotPercentage=20

# Idle timeout for slot borrowing (milliseconds)
ojp.server.slowQuerySegregation.idleTimeout=10000

# Timeout for acquiring slow operation slots (milliseconds)
ojp.server.slowQuerySegregation.slowSlotTimeout=120000

# Timeout for acquiring fast operation slots (milliseconds)
ojp.server.slowQuerySegregation.fastSlotTimeout=60000
```

### Benefits

- **Per-datasource isolation**: Each datasource maintains independent slow/fast lanes based on actual pool sizes
- **Enhanced resource protection**: Smart borrowing preserves at least one slot per pool and requires prior activity
- **Prevents resource starvation**: Fast operations aren't blocked by slow ones within each datasource
- **Adaptive learning**: Automatically discovers and adapts to slow operations per datasource
- **Efficient resource utilization**: Smart slot borrowing maximizes connection pool usage while maintaining safety

## Configuration Examples

### Development Environment

```bash
java -Duser.timezone=UTC \
     -Dojp.server.port=1059 \
     -Dojp.prometheus.port=9159 \
     -Dojp.server.logLevel=DEBUG \
     -Dojp.server.log.file=logs/ojp-dev.log \
     -Dojp.server.allowedIps="0.0.0.0/0" \
     -Dojp.server.slowQuerySegregation.enabled=true \
     -jar ojp-server.jar
```

### Production Environment

```bash
java -Duser.timezone=UTC \
     -Dojp.server.port=1059 \
     -Dojp.prometheus.port=9159 \
     -Dojp.server.logLevel=INFO \
     -Dojp.server.log.file=/var/log/ojp/server.log \
     -Dojp.server.log.maxHistory=90 \
     -Dojp.server.log.totalSizeCap=10GB \
     -Dojp.server.threadPoolSize=300 \
     -Dojp.server.circuitBreakerTimeout=60000 \
     -Dojp.server.slowQuerySegregation.enabled=true \
     -Dojp.server.slowQuerySegregation.slowSlotPercentage=25 \
     -Dojp.server.slowQuerySegregation.slowSlotTimeout=180000 \
     -Dojp.server.allowedIps="10.0.0.0/8,172.16.0.0/12" \
     -Dojp.prometheus.allowedIps="192.168.100.0/24" \
     -jar ojp-server.jar
```

### High-Throughput Environment

```bash
java -Duser.timezone=UTC \
     -Dojp.server.port=1059 \
     -Dojp.server.threadPoolSize=500 \
     -Dojp.server.maxRequestSize=16777216 \
     -Dojp.server.connectionIdleTimeout=60000 \
     -Dojp.server.circuitBreakerTimeout=90000 \
     -Dojp.server.slowQuerySegregation.enabled=true \
     -Dojp.server.slowQuerySegregation.slowSlotPercentage=30 \
     -Dojp.server.slowQuerySegregation.idleTimeout=5000 \
     -jar ojp-server.jar
```

### Kubernetes ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ojp-server-config
data:
  OJP_SERVER_PORT: "1059"
  OJP_PROMETHEUS_PORT: "9159"
  OJP_SERVER_THREADPOOLSIZE: "200"
  OJP_SERVER_LOGLEVEL: "INFO"
  OJP_SERVER_LOG_FILE: "/var/log/ojp/server.log"
  OJP_SERVER_LOG_MAXHISTORY: "60"
  OJP_SERVER_LOG_TOTALSIZECAP: "5GB"
  OJP_SERVER_CIRCUITBREAKERTIMEOUT: "60000"
  OJP_SERVER_CIRCUITBREAKERTHRESHOLD: "3"
  OJP_SERVER_SLOWQUERYSEGREGATION_ENABLED: "true"
  OJP_SERVER_SLOWQUERYSEGREGATION_SLOWSLOTPERCENTAGE: "20"
  OJP_SERVER_SLOWQUERYSEGREGATION_IDLETIMEOUT: "10000"
  OJP_SERVER_SLOWQUERYSEGREGATION_SLOWSLOTTIMEOUT: "120000"
  OJP_SERVER_SLOWQUERYSEGREGATION_FASTSLOTTIMEOUT: "60000"
  OJP_SERVER_ALLOWEDIPS: "10.244.0.0/16"
  OJP_PROMETHEUS_ALLOWEDIPS: "10.244.0.0/16"
  OJP_OPENTELEMETRY_ENABLED: "true"
```

## Configuration Validation

- **Invalid values**: Fall back to defaults with warning logs
- **Invalid IP rules**: Server startup fails with error
- **Port conflicts**: Standard socket binding errors apply
- **Type mismatches**: Automatic fallback to defaults

## Troubleshooting

### Common Issues

1. **Server won't start**: Check IP whitelist configuration and port availability
2. **Can't connect**: Verify client IP is in the allowed list
3. **Metrics unavailable**: Check Prometheus port and IP whitelist
4. **Performance issues**: Adjust thread pool size, connection timeouts, and slow query segregation settings
5. **Slow queries blocking fast ones**: Enable slow query segregation and tune slot percentages

### Debugging Configuration

Enable debug logging to see configuration loading:

```bash
-Dojp.server.logLevel=DEBUG
```

Configuration summary is logged at startup:

```
INFO org.openjproxy.grpc.server.ServerConfiguration - OJP Server Configuration:
INFO org.openjproxy.grpc.server.ServerConfiguration -   Server Port: 1059
INFO org.openjproxy.grpc.server.ServerConfiguration -   Prometheus Port: 9159
INFO org.openjproxy.grpc.server.ServerConfiguration -   OpenTelemetry Enabled: true
INFO org.openjproxy.grpc.server.ServerConfiguration -   Slow Query Segregation Enabled: false
INFO org.openjproxy.grpc.server.ServerConfiguration -   Slow Query Slot Percentage: 20%
...
```

### Performance Tuning Tips

1. **Thread Pool**: Start with 200 threads, increase for high-concurrency environments
2. **Circuit Breaker**: Adjust timeout based on your slowest acceptable query
3. **Slow Query Segregation**: 
   - Increase slow slot percentage if you have many legitimate slow queries
   - Decrease idle timeout for more aggressive slot borrowing
   - Increase timeouts in environments with occasional very slow queries
4. **Connection Pools**: Configure client-side pool sizes based on application requirements
5. **Request Size**: Increase for applications that handle large result sets

## Related Documentation

- **[Slow Query Segregation Documentation](../designs/SLOW_QUERY_SEGREGATION.md)** - Detailed guide to the slow query segregation feature
- **[Example Configuration Properties](ojp-server-example.properties)** - Complete example configuration file with all settings