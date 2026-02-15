# Database Schema Loader - Implementation Plan

> **⚠️ EXPERIMENTAL FEATURE - NOT RECOMMENDED FOR PRODUCTION**
>
> **IMPORTANT:** This implementation plan describes the Schema Loader for the SQL Enhancer with Apache Calcite, which is now **EXPERIMENTAL** and **NOT YET SUPPORTED** for production use.
>
> - **Status**: Experimental / Not Production Ready
> - **Default**: Disabled by default (SQL enhancer must be explicitly enabled)
> - **Known Issues**: Substantial type system incompatibilities with PostgreSQL, MySQL, Oracle, SQL Server, and other traditional JDBC databases
> - **Recommendation**: **Do NOT enable in production environments**
>
> Apache Calcite is designed for big data systems (Apache Hive, Drill, Phoenix, Druid, Flink, BigQuery, Elasticsearch, MongoDB) and has significant limitations with traditional relational databases.
>
> **We strongly discourage using this feature in its current state.**
>
> This document remains for reference purposes.

## Overview
Implement an asynchronous database schema loader that dynamically loads table and column metadata from actual database connections, replacing the current static GenericTable approach with real schema information for accurate SQL query optimization.

**Note:** Implementation complete but feature has known limitations with traditional JDBC databases.

## Objectives
- Load real database schema metadata at connection pool initialization
- Support asynchronous, non-blocking schema loading
- Implement configurable automatic schema refresh
- Provide fallback to generic schema when metadata is unavailable
- Ensure thread-safe schema updates with proper locking

## Architecture

### 1. Core Components

#### 1.1 SchemaLoader Class
**Location:** `ojp-server/src/main/java/org/openjproxy/grpc/server/sql/SchemaLoader.java`

**Responsibilities:**
- Asynchronously load database schema metadata via JDBC `DatabaseMetaData`
- Convert JDBC metadata to Calcite `RelDataType` structures
- Build and cache schema mappings (table → columns → types)
- Handle errors gracefully with logging
- Support multiple database dialects (MySQL, PostgreSQL, Oracle, SQL Server)

**Key Methods:**
```java
public class SchemaLoader {
    // Asynchronously load schema from DataSource
    public CompletableFuture<SchemaMetadata> loadSchemaAsync(DataSource dataSource, String catalogName, String schemaName);
    
    // Synchronously load schema (for testing/fallback)
    public SchemaMetadata loadSchema(Connection connection, String catalogName, String schemaName);
    
    // Convert JDBC types to Calcite types
    private RelDataType buildTableType(String tableName, List<ColumnMetadata> columns);
    
    // Handle dialect-specific metadata quirks
    private SchemaMetadata loadSchemaForDialect(DatabaseDialect dialect, Connection connection);
}
```

**Data Structures:**
```java
public class SchemaMetadata {
    private final Map<String, TableMetadata> tables;
    private final long loadTimestamp;
    private final String catalogName;
    private final String schemaName;
}

public class TableMetadata {
    private final String tableName;
    private final List<ColumnMetadata> columns;
    private final RelDataType relDataType;
}

public class ColumnMetadata {
    private final String columnName;
    private final int jdbcType;
    private final String typeName;
    private final boolean nullable;
    private final int precision;
    private final int scale;
}
```

#### 1.2 SchemaCache Class
**Location:** `ojp-server/src/main/java/org/openjproxy/grpc/server/sql/SchemaCache.java`

**Responsibilities:**
- Store loaded schema metadata in memory
- Track last schema refresh timestamp
- Provide thread-safe access to schema
- Support fallback to generic schema

**Key Methods:**
```java
public class SchemaCache {
    private volatile SchemaMetadata currentSchema;
    private volatile long lastRefreshTimestamp;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    
    // Get current schema or fallback to generic
    public SchemaMetadata getSchema(boolean fallbackToGeneric);
    
    // Update schema (thread-safe)
    public void updateSchema(SchemaMetadata newSchema);
    
    // Check if refresh is needed
    public boolean needsRefresh(long refreshIntervalMillis);
    
    // Try to acquire refresh lock
    public boolean tryAcquireRefreshLock();
    
    // Release refresh lock
    public void releaseRefreshLock();
}
```

#### 1.3 CalciteSchemaFactory Class
**Location:** `ojp-server/src/main/java/org/openjproxy/grpc/server/sql/CalciteSchemaFactory.java`

**Responsibilities:**
- Build Calcite `Schema` objects from `SchemaMetadata`
- Create dynamic table implementations
- Handle schema composition (real + fallback)

**Key Methods:**
```java
public class CalciteSchemaFactory {
    // Create Calcite schema from metadata
    public Schema createSchema(SchemaMetadata metadata);
    
    // Create generic fallback schema
    public Schema createGenericSchema();
    
    // Create hybrid schema (real + fallback)
    public Schema createHybridSchema(SchemaMetadata realSchema, Schema fallbackSchema);
}
```

### 2. Integration Points

#### 2.1 DataSourceManager Integration
**Location:** `ojp-server/src/main/java/org/openjproxy/grpc/server/DataSourceManager.java`

**Changes:**
- Add `SchemaCache` instance per data source
- Trigger async schema loading after creating connection pool
- Don't block `connect()` method on schema loading

**Modified Methods:**
```java
public void connect(DatabaseConfiguration config) {
    // ... existing connection pool creation ...
    
    // Initialize schema cache
    SchemaCache schemaCache = new SchemaCache();
    schemaCaches.put(config.getName(), schemaCache);
    
    // Trigger async schema loading (non-blocking)
    schemaLoader.loadSchemaAsync(dataSource, config.getCatalog(), config.getSchema())
        .thenAccept(schema -> {
            schemaCache.updateSchema(schema);
            logger.info("Schema loaded for datasource: {}", config.getName());
        })
        .exceptionally(ex -> {
            logger.error("Failed to load schema for datasource: {}", config.getName(), ex);
            return null;
        });
}
```

#### 2.2 RelationalAlgebraConverter Integration
**Location:** `ojp-server/src/main/java/org/openjproxy/grpc/server/sql/RelationalAlgebraConverter.java`

**Changes:**
- Accept `SchemaCache` in constructor
- Use real schema from cache or fallback to generic
- Remove hardcoded `DynamicSchema` and `GenericTable`

**Modified Methods:**
```java
public RelationalAlgebraConverter(SqlDialect targetDialect, SchemaCache schemaCache) {
    this.targetDialect = targetDialect;
    this.schemaCache = schemaCache;
}

private FrameworkConfig createFrameworkConfig() {
    // Get schema from cache (with fallback)
    SchemaMetadata metadata = schemaCache.getSchema(true); // true = fallback to generic
    Schema schema = calciteSchemaFactory.createSchema(metadata);
    
    // ... rest of config ...
}
```

#### 2.3 SqlEnhancerEngine Integration
**Location:** `ojp-server/src/main/java/org/openjproxy/grpc/server/sql/SqlEnhancerEngine.java`

**Changes:**
- Check if schema refresh is needed after enhancement
- Trigger async refresh if needed (with lock)
- Pass `SchemaCache` to `RelationalAlgebraConverter`

**Modified Methods:**
```java
public SqlEnhancementResult enhance(String sql) {
    // ... existing enhancement logic ...
    
    // Check if schema refresh is needed (after enhancement to minimize overhead)
    if (optimizationEnabled && schemaCache.needsRefresh(schemaRefreshIntervalMillis)) {
        if (schemaCache.tryAcquireRefreshLock()) {
            try {
                // Trigger async refresh
                schemaLoader.loadSchemaAsync(dataSource, catalog, schemaName)
                    .thenAccept(schema -> {
                        schemaCache.updateSchema(schema);
                        logger.info("Schema refreshed");
                    })
                    .exceptionally(ex -> {
                        logger.warn("Schema refresh failed", ex);
                        return null;
                    })
                    .whenComplete((result, ex) -> schemaCache.releaseRefreshLock());
            } catch (Exception e) {
                schemaCache.releaseRefreshLock();
                logger.warn("Failed to start schema refresh", e);
            }
        }
    }
    
    return result;
}
```

### 3. Configuration Properties

Add to `ServerConfiguration.java`:

```properties
# Schema loader configuration
ojp.sql.enhancer.schema.refresh.enabled=true
ojp.sql.enhancer.schema.refresh.interval.hours=24
ojp.sql.enhancer.schema.load.timeout.seconds=30
ojp.sql.enhancer.schema.fallback.enabled=true
```

**Property Details:**
- `schema.refresh.enabled` - Enable/disable automatic schema refresh (default: true)
- `schema.refresh.interval.hours` - Hours between schema refreshes (default: 24)
- `schema.load.timeout.seconds` - Timeout for schema loading operation (default: 30)
- `schema.fallback.enabled` - Fallback to generic schema on load failure (default: true)

### 4. Database-Specific Considerations

#### 4.1 Required Privileges

**MySQL/MariaDB:**
```sql
GRANT SELECT ON information_schema.tables TO 'user'@'host';
GRANT SELECT ON information_schema.columns TO 'user'@'host';
```

**PostgreSQL:**
```sql
GRANT CONNECT ON DATABASE dbname TO user;
GRANT USAGE ON SCHEMA schemaname TO user;
-- Access to pg_catalog is typically granted by default
```

**Oracle:**
```sql
GRANT SELECT_CATALOG_ROLE TO user;
-- OR --
GRANT SELECT ANY DICTIONARY TO user;
```

**SQL Server:**
```sql
GRANT VIEW DEFINITION TO user;
-- OR --
ALTER ROLE db_datareader ADD MEMBER user;
```

#### 4.2 Dialect-Specific Metadata Queries

Different databases require different approaches:

**MySQL/MariaDB:**
- Use `DatabaseMetaData.getTables()` and `DatabaseMetaData.getColumns()`
- Filter by `TABLE_TYPE = 'TABLE'`

**PostgreSQL:**
- Query `pg_catalog.pg_tables` for additional metadata
- Handle array types and custom types

**Oracle:**
- Query `ALL_TABLES` and `ALL_TAB_COLUMNS`
- Handle NUMBER type precision/scale correctly

**SQL Server:**
- Query `INFORMATION_SCHEMA` views
- Handle identity columns and computed columns

### 5. Error Handling & Fallback Strategy

#### 5.1 Error Scenarios

1. **Initial schema load fails:**
   - Log error with details
   - Fall back to generic schema
   - Allow query optimization to proceed
   - Retry on next refresh cycle

2. **Schema refresh fails:**
   - Log warning
   - Keep using existing schema
   - Retry on next refresh cycle

3. **Connection timeout:**
   - Respect configured timeout
   - Fall back to generic schema
   - Don't block query execution

4. **Insufficient privileges:**
   - Log error with required privileges
   - Fall back to generic schema
   - Document required privileges clearly

#### 5.2 Fallback Behavior

```java
public SchemaMetadata getSchema(boolean fallbackToGeneric) {
    SchemaMetadata current = currentSchema;
    
    if (current == null && fallbackToGeneric) {
        logger.debug("No real schema available, using generic schema");
        return GenericSchemaFactory.createGenericSchema();
    }
    
    return current;
}
```

### 6. Testing Strategy

#### 6.1 Unit Tests

**SchemaLoaderTest.java:**
- Test schema loading from mock `DatabaseMetaData`
- Test type conversion (JDBC → Calcite)
- Test error handling
- Test dialect-specific logic

**SchemaCacheTest.java:**
- Test thread-safe schema updates
- Test refresh timing logic
- Test lock acquisition/release
- Test fallback behavior

**CalciteSchemaFactoryTest.java:**
- Test Calcite schema creation
- Test table/column mapping
- Test hybrid schema composition

#### 6.2 Integration Tests

**SchemaLoaderIntegrationTest.java:**
- Test with real database connections (H2 for tests)
- Test schema loading from actual metadata
- Test schema refresh trigger
- Test fallback scenarios

**SqlEnhancerEngineWithRealSchemaTest.java:**
- Test optimization with real schema metadata
- Test queries with actual table/column names
- Test schema refresh doesn't disrupt ongoing queries
- Test fallback when schema unavailable

### 7. Performance Considerations

#### 7.1 Initial Load

- **Timing:** 100-500ms typical for moderate schemas (100-1000 tables)
- **Approach:** Async loading prevents blocking
- **Optimization:** Load only necessary metadata (exclude system tables)

#### 7.2 Memory Usage

- **Estimate:** ~1-5 MB for typical schemas (1000 tables, 10 columns avg)
- **Optimization:** Use efficient data structures
- **Monitoring:** Log schema size and memory usage

#### 7.3 Refresh Overhead

- **Frequency:** Default 24 hours minimizes overhead
- **Lock mechanism:** `AtomicBoolean` prevents concurrent refreshes
- **Impact:** Negligible (<1ms overhead per query for refresh check)

### 8. Documentation Updates

#### 8.1 README Updates
- Add section on database schema integration
- Document required database privileges
- Explain schema refresh behavior

#### 8.2 Configuration Guide
- Add schema configuration properties
- Provide examples for different databases
- Explain fallback behavior

#### 8.3 Troubleshooting Guide
- Document common schema loading issues
- Provide solutions for privilege problems
- Explain how to verify schema loading

### 9. Migration Path

#### 9.1 Backward Compatibility

- Keep generic schema as fallback
- Schema loading is optional (disabled if no DataSource)
- Existing tests continue to work with generic schema

#### 9.2 Phased Rollout

1. **Phase 1:** Implement core schema loading (this task)
2. **Phase 2:** Add monitoring and metrics
3. **Phase 3:** Optimize for large schemas (lazy loading, filtering)
4. **Phase 4:** Add schema change detection and incremental updates

### 10. Implementation Checklist

- [ ] Implement `SchemaLoader` class with JDBC metadata querying
- [ ] Implement `SchemaCache` class with thread-safe access
- [ ] Implement `CalciteSchemaFactory` for Calcite integration
- [ ] Integrate with `DataSourceManager` for async loading on connect
- [ ] Integrate with `RelationalAlgebraConverter` for schema usage
- [ ] Integrate with `SqlEnhancerEngine` for refresh triggers
- [ ] Add configuration properties to `ServerConfiguration`
- [ ] Add required privileges documentation
- [ ] Implement unit tests for all new classes
- [ ] Implement integration tests with real databases
- [ ] Update user documentation
- [ ] Add logging for troubleshooting
- [ ] Test with multiple database types (MySQL, PostgreSQL, Oracle, SQL Server)
- [ ] Verify backward compatibility
- [ ] Performance testing with large schemas

### 11. Estimated Effort

- **Implementation:** 16-24 hours
- **Testing:** 8-12 hours
- **Documentation:** 4-6 hours
- **Code Review & Iteration:** 4-8 hours
- **Total:** 32-50 hours (4-6 days)

### 12. Success Criteria

1. Schema successfully loads from all supported databases
2. Queries optimize using real schema metadata
3. Fallback to generic schema works correctly
4. Schema refresh works without disrupting queries
5. No performance regression in query execution
6. All tests pass
7. Documentation is complete and clear
8. Code review approved

### 13. Future Enhancements

- Schema change detection (invalidate cache on DDL)
- Incremental schema updates (only changed tables)
- Schema filtering (include/exclude patterns)
- Cross-database schema federation
- JMX metrics for schema loading
- Schema caching to disk for faster startup

## References

- Apache Calcite Schema Documentation: https://calcite.apache.org/docs/adapter.html
- JDBC DatabaseMetaData API: https://docs.oracle.com/javase/8/docs/api/java/sql/DatabaseMetaData.html
- Current Implementation: `RelationalAlgebraConverter.java` lines 71-200
