# Atomikos XA Connection Pooling

## Overview

OJP now supports Atomikos XA connection pooling for distributed transactions. When XA mode is enabled via `jdbc.xa.enabled=true`, the server uses Atomikos instead of HikariCP for connection pooling.

## Features

- **Automatic XA Datasource Creation**: When `isXA=true` is set in connection details, an `AtomikosDataSourceBean` is automatically created and configured
- **Lazy Connection Allocation**: Connections are allocated from the pool only when executing statements (matching HikariCP behavior)
- **Configuration Mapping**: Existing HikariCP configuration properties are automatically mapped to Atomikos equivalents
- **Timeout Conversion**: Timeout values in milliseconds are automatically converted to seconds for Atomikos
- **Transaction Manager Lifecycle**: Atomikos transaction manager is automatically initialized on first XA datasource creation and shut down with the server

## Configuration

### Basic XA Connection

```java
Properties props = new Properties();
props.setProperty("jdbc.xa.enabled", "true");
// Connection will use Atomikos for XA transactions
```

### Pool Configuration

The following Hikari configuration properties are automatically mapped to Atomikos:

| Hikari Property | Default (ms) | Atomikos Property | Converted (s) |
|----------------|--------------|-------------------|---------------|
| `ojp.connection.pool.maximumPoolSize` | 20 | `maxPoolSize` | (no conversion) |
| `ojp.connection.pool.minimumIdle` | 5 | `minPoolSize` | (no conversion) |
| `ojp.connection.pool.connectionTimeout` | 10000 | `borrowConnectionTimeout` | 10 |
| `ojp.connection.pool.idleTimeout` | 600000 | `maxIdleTime` | 600 |
| `ojp.connection.pool.maxLifetime` | 1800000 | `maxLifetime` | 1800 |

Example with custom pool settings:

```java
Properties props = new Properties();
props.setProperty("jdbc.xa.enabled", "true");
props.setProperty("ojp.connection.pool.maximumPoolSize", "25");
props.setProperty("ojp.connection.pool.minimumIdle", "8");
props.setProperty("ojp.connection.pool.connectionTimeout", "30000"); // 30 seconds
```

### Atomikos Logging Configuration

Control Atomikos transaction logging:

```java
Properties props = new Properties();
props.setProperty("jdbc.xa.enabled", "true");
props.setProperty("jdbc.atomikos.logging.enabled", "true");
props.setProperty("jdbc.atomikos.logging.dir", "/var/log/atomikos");
```

Default: Logging is disabled and logs are stored in `./atomikos-logs`

## Supported Databases

XA transactions are currently supported for:
- **PostgreSQL**: Uses `org.postgresql.xa.PGXADataSource`
- **MySQL**: Uses `com.mysql.cj.jdbc.MysqlXADataSource`

## Architecture

### Components

1. **AtomikosDataSourceFactory**: Creates and configures `AtomikosDataSourceBean` instances
   - Maps Hikari properties to Atomikos
   - Handles timeout conversions (ms to seconds)
   - Sets database-specific test queries

2. **AtomikosLifecycle**: Manages Atomikos transaction manager lifecycle
   - Initializes `UserTransactionServiceImp` on first XA datasource creation
   - Configures transaction logs and logging levels
   - Shuts down transaction manager on server shutdown

3. **StatementServiceImpl**: Modified to support both datasource types
   - Creates `AtomikosDataSourceBean` when `isXA=true`
   - Creates `HikariDataSource` when `isXA=false`
   - Lazy connection allocation in `sessionConnection()` method works for both

### Connection Flow

1. Client requests connection with `isXA=true`
2. Server checks if XA datasource exists for connection hash
3. If not, creates XA datasource:
   - Initializes Atomikos transaction manager (if first XA datasource)
   - Creates underlying database XADataSource
   - Wraps in AtomikosDataSourceBean with configured pool settings
4. No physical connection is allocated yet (lazy allocation)
5. When first statement executes:
   - `sessionConnection()` is called
   - Physical connection is acquired from Atomikos pool
   - Session is created and associated with connection

## Implementation Details

### Datasource Map

The `datasourceMap` in `StatementServiceImpl` now uses `Map<String, Object>` to support both:
- `HikariDataSource` for regular connections
- `AtomikosDataSourceBean` for XA connections

Both implement `javax.sql.DataSource` interface for connection acquisition.

### Lazy Connection Allocation

Connections are allocated lazily in the `sessionConnection()` method:

```java
if (dataSource instanceof DataSource) {
    DataSource ds = (DataSource) dataSource;
    
    if (dataSource instanceof HikariDataSource) {
        // Use ConnectionAcquisitionManager for Hikari
        conn = ConnectionAcquisitionManager.acquireConnection(
            (HikariDataSource) dataSource, sessionInfo.getConnHash());
    } else {
        // Standard getConnection() for Atomikos (provides lazy allocation)
        conn = ds.getConnection();
    }
}
```

### Shutdown Hook

Atomikos transaction manager is automatically shut down when the server terminates:

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    // ... server shutdown ...
    AtomikosLifecycle.shutdown();
}));
```

## Testing

The implementation includes comprehensive tests:

- **AtomikosDataSourceFactoryTest** (5 tests): Configuration mapping and timeout conversions
- **AtomikosLifecycleTest** (5 tests): Transaction manager lifecycle
- **AtomikosXAIntegrationTest** (4 tests): End-to-end XA functionality

All 112 tests pass (14 new tests added).

## Dependencies

```xml
<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-jdbc</artifactId>
    <version>6.0.0</version>
</dependency>

<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-jta</artifactId>
    <version>6.0.0</version>
</dependency>

<dependency>
    <groupId>javax.transaction</groupId>
    <artifactId>jta</artifactId>
    <version>1.1</version>
</dependency>
```

## Notes

- XA mode is opt-in via configuration; default behavior uses HikariCP
- Transaction logs are stored in configured directory (default: `./atomikos-logs`)
- Atomikos uses connection validation with database-specific test queries
- Pool maintenance runs every 60 seconds by default
