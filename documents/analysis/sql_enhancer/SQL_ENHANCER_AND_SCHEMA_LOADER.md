# SQL Enhancer and Database Schema Loader

> **⚠️ EXPERIMENTAL FEATURE - NOT RECOMMENDED FOR PRODUCTION**
>
> The SQL Enhancer with Apache Calcite is **EXPERIMENTAL** and **NOT YET SUPPORTED** for production use.
>
> - **Status**: Experimental / Not Production Ready
> - **Default**: Disabled by default (opt-in only)
> - **Known Issues**: Substantial type system incompatibilities with PostgreSQL, MySQL, Oracle, SQL Server, and other traditional JDBC databases
> - **Recommendation**: **Do NOT enable in production environments**
>
> Apache Calcite is designed for big data systems (Apache Hive, Drill, Phoenix, Druid, Flink, BigQuery, Elasticsearch, MongoDB) and has significant limitations with traditional relational databases.
>
> **We strongly discourage using this feature in its current state.**

## Overview

The OJP SQL Enhancer provides advanced query optimization capabilities using Apache Calcite with real database schema metadata. By loading actual table and column information from your databases, the SQL enhancer can perform more accurate query analysis, optimization, and dialect translation.

**Note:** Due to type system incompatibilities, many optimization features do not work reliably with traditional JDBC databases.

## Features

### Real Schema Metadata Loading
- **Asynchronous Loading**: Schema metadata loads in the background without blocking query execution
- **Automatic Refresh**: Configurable periodic refresh keeps schema metadata current as your database evolves
- **Thread-Safe Operations**: Multiple connections can safely access and update the schema cache
- **Fallback Support**: Automatically falls back to a generic schema if real schema loading fails
- **Multi-Database Support**: Works with MySQL, PostgreSQL, Oracle, SQL Server, DB2, H2, and other JDBC databases

### Query Optimization Benefits
- **Type-Aware Analysis**: Uses actual column types for better query validation
- **Table Existence Validation**: Knows which tables actually exist in your database
- **Column Reference Checking**: Validates column names against real schema
- **Dialect Translation**: Converts queries between different SQL dialects

## Configuration

### Enabling SQL Enhancer

The SQL Enhancer is **disabled by default** and must be explicitly enabled:

```bash
# Enable SQL enhancer
-Dojp.sql.enhancer.enabled=true
```

Or via environment variable:
```bash
export OJP_SQL_ENHANCER_ENABLED=true
```

### Schema Loader Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `ojp.sql.enhancer.schema.refresh.enabled` | `true` | Enable automatic schema metadata refresh |
| `ojp.sql.enhancer.schema.refresh.interval.hours` | `24` | Hours between automatic schema refreshes |
| `ojp.sql.enhancer.schema.load.timeout.seconds` | `30` | Timeout for schema loading operations (seconds) |
| `ojp.sql.enhancer.schema.fallback.enabled` | `true` | Fall back to generic schema if loading fails |

### Example Configurations

**Basic setup with default settings:**
```bash
java -Dojp.sql.enhancer.enabled=true \
     -jar ojp-server.jar
```

**Production setup with custom refresh interval:**
```bash
java -Dojp.sql.enhancer.enabled=true \
     -Dojp.sql.enhancer.schema.refresh.enabled=true \
     -Dojp.sql.enhancer.schema.refresh.interval.hours=12 \
     -Dojp.sql.enhancer.schema.load.timeout.seconds=60 \
     -jar ojp-server.jar
```

**Disable automatic refresh (manual refresh only):**
```bash
java -Dojp.sql.enhancer.enabled=true \
     -Dojp.sql.enhancer.schema.refresh.enabled=false \
     -jar ojp-server.jar
```

## Database Privileges

For schema loading to work correctly, the database user needs read access to metadata tables. Below are the required privileges for each supported database:

### MySQL / MariaDB

```sql
GRANT SELECT ON information_schema.tables TO 'username'@'host';
GRANT SELECT ON information_schema.columns TO 'username'@'host';
```

### PostgreSQL

```sql
GRANT CONNECT ON DATABASE dbname TO username;
GRANT USAGE ON SCHEMA schemaname TO username;
-- Access to pg_catalog is typically granted by default
```

### Oracle

```sql
-- Option 1: Grant SELECT_CATALOG_ROLE (recommended)
GRANT SELECT_CATALOG_ROLE TO username;

-- Option 2: Grant SELECT ANY DICTIONARY (broader permissions)
GRANT SELECT ANY DICTIONARY TO username;
```

### SQL Server

```sql
-- Option 1: Grant VIEW DEFINITION (recommended)
GRANT VIEW DEFINITION TO username;

-- Option 2: Add to db_datareader role
ALTER ROLE db_datareader ADD MEMBER username;
```

### DB2

```sql
GRANT SELECT ON SYSCAT.TABLES TO username;
GRANT SELECT ON SYSCAT.COLUMNS TO username;
```

### H2

No special privileges required - metadata access is available by default.

## How Schema Loading Works

### Initial Load
1. When OJP Server starts and receives the first database connection, it initiates schema loading
2. The SchemaLoader queries JDBC DatabaseMetaData asynchronously
3. Table and column information is extracted and converted to Apache Calcite types
4. The schema is cached in a thread-safe SchemaCache for fast access
5. Queries can immediately use the cached schema for optimization

### Periodic Refresh
1. After each SQL query enhancement, OJP checks if the refresh interval has passed
2. If refresh is needed, an asynchronous refresh is triggered (non-blocking)
3. A lock mechanism prevents multiple concurrent refreshes
4. The updated schema replaces the old one atomically
5. Query processing continues uninterrupted during refresh

### Fallback Behavior
- If initial schema load fails, OJP falls back to a generic schema with common column types
- Queries continue to work normally with the generic schema
- The next refresh cycle will retry loading the real schema
- Schema loading failures are logged for troubleshooting

## Troubleshooting

### Schema Loading Fails

**Symptom**: Logs show schema loading errors

**Solutions**:
1. **Check database user privileges** - Verify the user has required metadata access (see above)
2. **Verify network connectivity** - Ensure OJP Server can reach the database
3. **Check timeout settings** - Increase `ojp.sql.enhancer.schema.load.timeout.seconds` if you have many tables
4. **Review error messages** - Check server logs for specific error details

**Example log messages:**
```
ERROR SchemaLoader - Failed to load schema: java.sql.SQLException: Access denied
WARN  SchemaCache - Schema refresh failed: timeout
INFO  SchemaLoader - Loaded 250 tables in 1523ms
```

### Schema Not Refreshing

**Symptom**: Changes to database schema are not reflected in query optimization

**Solutions**:
1. **Verify refresh is enabled** - Check `ojp.sql.enhancer.schema.refresh.enabled=true`
2. **Check refresh interval** - The default is 24 hours; reduce for testing
3. **Monitor server logs** - Look for refresh trigger messages
4. **Restart OJP Server** - Forces an immediate schema reload

### Performance Issues

**Symptom**: Schema loading takes too long or times out

**Solutions**:
1. **Increase timeout** - Set `ojp.sql.enhancer.schema.load.timeout.seconds` to a higher value
2. **Reduce refresh frequency** - Increase `ojp.sql.enhancer.schema.refresh.interval.hours`
3. **Check database load** - High database load can slow metadata queries
4. **Review schema size** - Very large schemas (1000+ tables) may need longer timeouts

## Monitoring

### Log Messages

The schema loader emits informative log messages at INFO level:

```
INFO  SchemaLoader - Loading schema metadata for catalog: mydb, schema: public
INFO  SchemaLoader - Loaded 42 tables in 156ms
INFO  SchemaCache - Schema cache updated with 42 tables, loaded at timestamp: 1768117340518
INFO  SqlEnhancerEngine - SQL Enhancer Engine initialized and enabled with dialect: MYSQL with relational algebra conversion and optimization and real schema support with periodic refresh
```

### Success Indicators

When schema loading is working correctly, you should see:
1. Schema load messages on server startup
2. Regular refresh messages (every 24 hours by default)
3. No error or warning messages about schema loading
4. Table count in logs matches your database

### Prometheus Metrics

Schema loading metrics are available through OJP's Prometheus endpoint (port 9159 by default):

- Query enhancement metrics show optimization success rates
- Connection pool metrics show database connectivity
- Error counts indicate schema loading failures

## Best Practices

### Production Deployment

1. **Enable fallback**: Always keep `ojp.sql.enhancer.schema.fallback.enabled=true` in production
2. **Set appropriate timeouts**: Configure timeouts based on your schema size
3. **Monitor logs**: Watch for schema loading failures and adjust configuration
4. **Test privileges**: Verify database user has required metadata access before deployment

### Schema Refresh Intervals

- **Development**: 1-4 hours for rapid schema changes
- **Staging**: 6-12 hours for testing
- **Production**: 24-72 hours for stable schemas
- **Very stable schemas**: Disable automatic refresh and trigger manually when needed

### Large Schemas

For databases with 1000+ tables:
- Increase timeout to 60-120 seconds
- Consider disabling automatic refresh
- Load schema manually during maintenance windows
- Monitor memory usage (schema cache size)

## Example Use Cases

### Dialect Translation
```java
// Automatically translate MySQL query to PostgreSQL syntax
String mysqlQuery = "SELECT * FROM users LIMIT 10";
// SQL Enhancer can convert to: "SELECT * FROM users FETCH FIRST 10 ROWS ONLY"
```

### Query Validation
```java
// Detect invalid table/column references at query time
String invalidQuery = "SELECT nonexistent_column FROM users";
// SQL Enhancer can identify and log invalid references
```

### Type-Aware Optimization
```java
// Use actual column types for better optimization
String query = "SELECT age FROM users WHERE age > '25'";
// SQL Enhancer knows 'age' is INTEGER, can optimize type conversions
```

## Limitations

- Schema loading requires database connectivity and appropriate privileges
- Very large schemas (10,000+ tables) may require increased memory and timeouts
- Schema changes during query execution are not immediately reflected (waits for next refresh)
- Some database-specific features (views, materialized views, functions) may not be fully captured

## Related Documentation

- [OJP Server Configuration](../../configuration/ojp-server-configuration.md) - Complete server configuration reference
- [Connection Pool Configuration](../../configuration/ojp-jdbc-configuration.md) - JDBC driver and pool settings
- [Schema Loader Implementation Plan](../../implementation_plans/SCHEMA_LOADER_IMPLEMENTATION_PLAN.md) - Technical implementation details

## Support

For issues or questions:
- Check server logs for error details
- Review this troubleshooting guide
- Open an issue on GitHub with log excerpts and configuration details
- Join the Discord community for real-time help
