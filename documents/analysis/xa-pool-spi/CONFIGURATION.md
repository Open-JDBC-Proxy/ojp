# XA Pool SPI Configuration Reference

## Overview

This document provides a complete reference for all configuration options available in the XA Pool SPI.

## Standard Configuration

These configuration properties are supported by all XA pool providers:

### Core Pool Settings

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `ojp.xa.connection.pool.enabled` | boolean | `true` | Enable/disable XA connection pooling |
| `ojp.xa.maxPoolSize` | int | `10` | Maximum number of connections in pool |
| `ojp.xa.minIdle` | int | `2` | Minimum number of idle connections to maintain |
| `ojp.xa.maxWaitMillis` | long | `30000` | Maximum time to wait for available connection (ms) |

### Timeout Settings

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `ojp.xa.idleTimeoutMinutes` | int | `10` | Maximum idle time before connection eviction (minutes) |
| `ojp.xa.maxLifetimeMinutes` | int | `30` | Maximum lifetime of connection (minutes) |
| `ojp.xa.connectionTimeoutSeconds` | int | `60` | Timeout for establishing new connection (seconds) |

### Validation Settings

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `ojp.xa.validateOnBorrow` | boolean | `true` | Validate connection health when borrowed from pool |
| `ojp.xa.validationQuery` | String | `null` | Custom SQL query for connection validation |
| `ojp.xa.validationTimeoutSeconds` | int | `5` | Timeout for validation query (seconds) |

## Provider-Specific Configuration

### CommonsPool2XAProvider (Default)

CommonsPool2XAProvider uses Apache Commons Pool 2 and supports these additional options:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `ojp.xa.commonspool.testOnReturn` | boolean | `false` | Test connection health when returned to pool |
| `ojp.xa.commonspool.testWhileIdle` | boolean | `true` | Test idle connections periodically |
| `ojp.xa.commonspool.timeBetweenEvictionRunsMillis` | long | `60000` | Frequency of eviction runs (ms) |
| `ojp.xa.commonspool.numTestsPerEvictionRun` | int | `3` | Number of connections to test per eviction run |
| `ojp.xa.commonspool.blockWhenExhausted` | boolean | `true` | Block when pool exhausted vs throw exception |
| `ojp.xa.commonspool.fairness` | boolean | `false` | Use fair queueing for waiting threads |
| `ojp.xa.commonspool.jmxEnabled` | boolean | `false` | Enable JMX monitoring |

### OracleUCPXAProvider

Oracle UCP provider supports these Oracle-specific options:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `ojp.xa.oracle.ucp.enableFCF` | boolean | `true` | Enable Fast Connection Failover |
| `ojp.xa.oracle.ucp.statementCacheSize` | int | `50` | Number of statements to cache |
| `ojp.xa.oracle.ucp.validateOnBorrow` | boolean | `true` | Validate connections on borrow |
| `ojp.xa.oracle.ucp.onsConfiguration` | String | `null` | ONS configuration for RAC (e.g., "nodes=host1:6200,host2:6200") |
| `ojp.xa.oracle.ucp.connectionAffinity` | boolean | `false` | Enable connection affinity for RAC |
| `ojp.xa.oracle.ucp.connectionLabeling` | boolean | `false` | Enable connection labeling |
| `ojp.xa.oracle.ucp.queryTimeout` | int | `30` | Query timeout in seconds |
| `ojp.xa.oracle.ucp.jmxEnabled` | boolean | `false` | Enable JMX monitoring |

## Database-Specific XADataSource Classes

The XA Pool SPI automatically detects and configures the appropriate XADataSource class based on the JDBC URL:

| Database | JDBC URL Pattern | XADataSource Class |
|----------|------------------|-------------------|
| PostgreSQL | `jdbc:postgresql:` | `org.postgresql.xa.PGXADataSource` |
| SQL Server | `jdbc:sqlserver:` | `com.microsoft.sqlserver.jdbc.SQLServerXADataSource` |
| DB2 | `jdbc:db2:` | `com.ibm.db2.jcc.DB2XADataSource` |
| MySQL | `jdbc:mysql:` | `com.mysql.cj.jdbc.MysqlXADataSource` |
| MariaDB | `jdbc:mariadb:` | `org.mariadb.jdbc.MariaDbDataSource` |
| Oracle | `jdbc:oracle:` | `oracle.jdbc.xa.client.OracleXADataSource` |

## Configuration Examples

### Basic Configuration

Minimal configuration for PostgreSQL:

```properties
ojp.xa.connection.pool.enabled=true
ojp.xa.maxPoolSize=10
ojp.xa.minIdle=2
```

### Production Configuration

Recommended settings for production environments:

```properties
# Enable XA pooling
ojp.xa.connection.pool.enabled=true

# Pool sizing (adjust based on workload)
ojp.xa.maxPoolSize=50
ojp.xa.minIdle=10
ojp.xa.maxWaitMillis=30000

# Timeouts
ojp.xa.idleTimeoutMinutes=10
ojp.xa.maxLifetimeMinutes=30
ojp.xa.connectionTimeoutSeconds=60

# Validation
ojp.xa.validateOnBorrow=true
ojp.xa.validationTimeoutSeconds=5

# Commons Pool 2 tuning
ojp.xa.commonspool.testWhileIdle=true
ojp.xa.commonspool.timeBetweenEvictionRunsMillis=60000
ojp.xa.commonspool.numTestsPerEvictionRun=5
ojp.xa.commonspool.jmxEnabled=true
```

### Oracle RAC Configuration

Configuration for Oracle RAC with Fast Connection Failover:

```properties
# Enable XA pooling
ojp.xa.connection.pool.enabled=true

# Pool sizing
ojp.xa.maxPoolSize=20
ojp.xa.minIdle=5

# Oracle UCP settings
ojp.xa.oracle.ucp.enableFCF=true
ojp.xa.oracle.ucp.onsConfiguration=nodes=rac1:6200,rac2:6200
ojp.xa.oracle.ucp.connectionAffinity=true
ojp.xa.oracle.ucp.statementCacheSize=100
ojp.xa.oracle.ucp.validateOnBorrow=true
ojp.xa.oracle.ucp.jmxEnabled=true
```

### High-Throughput Configuration

Optimized for high-throughput OLTP workloads:

```properties
ojp.xa.connection.pool.enabled=true

# Larger pool
ojp.xa.maxPoolSize=100
ojp.xa.minIdle=20

# Faster timeouts
ojp.xa.maxWaitMillis=10000
ojp.xa.connectionTimeoutSeconds=30

# Aggressive eviction
ojp.xa.idleTimeoutMinutes=5
ojp.xa.maxLifetimeMinutes=15

# Fair queueing
ojp.xa.commonspool.fairness=true
```

### Development Configuration

Simplified settings for development:

```properties
ojp.xa.connection.pool.enabled=true

# Small pool
ojp.xa.maxPoolSize=5
ojp.xa.minIdle=1

# Relaxed timeouts
ojp.xa.maxWaitMillis=60000
ojp.xa.idleTimeoutMinutes=30

# Less aggressive validation
ojp.xa.validateOnBorrow=false
ojp.xa.commonspool.testWhileIdle=false
```

## Environment Variables

All configuration properties can be set via environment variables using uppercase with underscores:

```bash
# Property: ojp.xa.connection.pool.enabled
export OJP_XA_CONNECTION_POOL_ENABLED=true

# Property: ojp.xa.maxPoolSize
export OJP_XA_MAXPOOLSIZE=20

# Property: ojp.xa.oracle.ucp.enableFCF
export OJP_XA_ORACLE_UCP_ENABLEFCF=true
```

## Environment-Specific Configuration

**Recommended Approach (Since PR #298):** Use environment-specific properties files to configure XA pools for different environments without requiring custom code.

Create environment-specific properties files:
- `ojp-dev.properties` - Development environment configuration
- `ojp-staging.properties` - Staging environment configuration
- `ojp-prod.properties` - Production environment configuration

Example `ojp-prod.properties`:
```properties
# XA Pool Configuration for Production
ojp.xa.connection.pool.enabled=true
ojp.xa.connection.pool.maxTotal=40
ojp.xa.connection.pool.minIdle=10
ojp.xa.connection.pool.maxWaitMillis=30000

# Oracle UCP specific settings
ojp.xa.oracle.ucp.enableFCF=true
ojp.xa.oracle.ucp.statementCacheSize=100
```

Set the environment using:
```bash
# System property
-Dojp.environment=prod

# Or environment variable
export OJP_ENVIRONMENT=prod
```

**For complete details, see:**
- **[OJP JDBC Configuration](../../configuration/ojp-jdbc-configuration.md)** - Environment-specific configuration guide
- **[Example Configuration Files](../../configuration/)** - Sample ojp-dev.properties, ojp-staging.properties, ojp-prod.properties

## Programmatic Configuration (Advanced)

**Note:** The programmatic API shown below is for advanced scenarios. For environment-specific configuration, use the properties file approach described above.

Configuration can also be set programmatically when needed:

```java
Map<String, String> config = new HashMap<>();
config.put("xa.pooling.enabled", "true");
config.put("xa.maxPoolSize", "10");
config.put("xa.minIdle", "2");
config.put("xa.maxWaitMillis", "30000");

// For provider-specific settings
config.put("xa.oracle.ucp.enableFCF", "true");
config.put("xa.oracle.ucp.statementCacheSize", "100");
```

## Configuration Validation

The XA Pool SPI validates configuration on startup:

### Validation Rules

1. **maxPoolSize** must be > 0
2. **minIdle** must be >= 0
3. **minIdle** must be <= **maxPoolSize**
4. **maxWaitMillis** must be > 0
5. **idleTimeoutMinutes** must be > 0
6. **maxLifetimeMinutes** must be > 0
7. **connectionTimeoutSeconds** must be > 0

### Validation Errors

Invalid configuration will result in startup failure with detailed error message:

```
java.lang.IllegalArgumentException: Invalid XA pool configuration: 
  minIdle (15) cannot exceed maxPoolSize (10)
```

## Performance Tuning Guidelines

### Pool Sizing

**Formula**: `maxPoolSize = (Concurrent Threads × 1.2) + Buffer`

Example for 40 concurrent threads:
```properties
ojp.xa.maxPoolSize=50  # 40 * 1.2 = 48, round to 50
ojp.xa.minIdle=10      # 20% of max
```

### Timeout Tuning

| Scenario | maxWaitMillis | connectionTimeout | idleTimeout |
|----------|---------------|-------------------|-------------|
| Low Latency | 5000 | 30 | 5 |
| Standard | 30000 | 60 | 10 |
| High Latency | 60000 | 120 | 20 |

### Validation Tuning

**When to validate:**
- **Production**: Enable validation on borrow
- **Development**: Disable for faster startup
- **Testing**: Enable full validation

```properties
# Production
ojp.xa.validateOnBorrow=true
ojp.xa.commonspool.testWhileIdle=true

# Development
ojp.xa.validateOnBorrow=false
ojp.xa.commonspool.testWhileIdle=false
```

## Monitoring Configuration

### Enable JMX Monitoring

```properties
ojp.xa.commonspool.jmxEnabled=true
```

Access via JMX:
- **ObjectName**: `org.apache.commons.pool2:type=GenericObjectPool,name=XAConnectionPool`
- **Attributes**: NumActive, NumIdle, MaxBorrowWaitTimeMillis, etc.

### Enable Logging

```xml
<!-- logback.xml -->
<logger name="org.openjproxy.xa.pool" level="DEBUG"/>
<logger name="org.apache.commons.pool2" level="DEBUG"/>
```

## Migration from Pass-Through

To migrate from pass-through XA to pooled XA:

### Step 1: Enable Pooling
```properties
ojp.xa.connection.pool.enabled=true
```

### Step 2: Configure Pool
```properties
ojp.xa.maxPoolSize=10
ojp.xa.minIdle=2
```

### Step 3: Monitor Performance
- Check connection acquisition times
- Monitor pool utilization
- Verify transaction success rates

### Step 4: Rollback If Needed
```properties
ojp.xa.connection.pool.enabled=false
```

## Troubleshooting Configuration Issues

### Pool Exhaustion

**Symptom**: `Pool exhausted - no sessions available`

**Solutions**:
1. Increase maxPoolSize
2. Increase maxWaitMillis
3. Check for connection leaks
4. Reduce connection lifetime

### Slow Connection Acquisition

**Symptom**: High latency on connection borrow

**Solutions**:
1. Increase minIdle
2. Disable validation on borrow
3. Reduce validation timeout
4. Check network latency

### Memory Issues

**Symptom**: OutOfMemoryError with large pools

**Solutions**:
1. Reduce maxPoolSize
2. Reduce statement cache size
3. Enable connection lifetime limits
4. Monitor heap usage

## Best Practices

1. **Start Conservative**: Begin with small pool sizes and increase as needed
2. **Monitor Metrics**: Enable JMX and monitor pool utilization
3. **Test Validation**: Ensure validation queries are fast (<100ms)
4. **Set Timeouts**: Always configure appropriate timeouts
5. **Use Affinity**: Enable connection affinity for Oracle RAC
6. **Cache Statements**: Enable statement caching for Oracle
7. **Document Changes**: Track configuration changes and their impact

## References

- [Apache Commons Pool 2 Documentation](https://commons.apache.org/proper/commons-pool/apidocs/org/apache/commons/pool2/impl/GenericObjectPoolConfig.html)
- [Oracle UCP Configuration](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjucp/optimizing-ucp-behavior.html)
- [XA Specification](https://pubs.opengroup.org/onlinepubs/009680699/toc.pdf)

## Next Steps

- Review [Implementation Guide](./IMPLEMENTATION_GUIDE.md) for provider development
- See [Oracle UCP Example](./ORACLE_UCP_EXAMPLE.md) for Oracle-specific features
- Check [Troubleshooting Guide](./TROUBLESHOOTING.md) for common issues
