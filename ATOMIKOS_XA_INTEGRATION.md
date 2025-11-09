# Atomikos XA Integration Guide

## Overview

This document describes the Atomikos XA connection pooling integration in OJP server, which provides distributed transaction support for XA-capable databases.

**IMPORTANT**: Atomikos is used **ONLY for connection pooling**, NOT for transaction management. The OJP server remains an XA pass-through proxy - clients control transaction lifecycle via XAResource.

## Architecture

### Key Components

1. **AtomikosXAConnectionPool** - Manages XA connection pooling using AtomikosDataSourceBean
2. **XADataSourceFactory** - Creates native JDBC driver XADataSource instances (e.g., PGXADataSource for PostgreSQL)
3. **StatementServiceImpl** - Modified to use AtomikosXAConnectionPool instead of direct XADataSource
4. **ConnectionHashGenerator** - Generates unique hashes for connections including datasource name
5. **DataSourceConfigurationManager** - Manages datasource-specific configurations

### XA Pass-Through Architecture

The system uses Atomikos connection pooling infrastructure while maintaining XA pass-through semantics:

**Server Responsibilities:**
- Pool XA connections using AtomikosDataSourceBean (sizing, validation, health checks)
- Lease one XAConnection per client XA branch/session (no sharing across branches)
- Forward XA operations (start/prepare/commit/rollback) from client to database XAResource
- Return XAConnection to pool on branch end/commit/rollback

**Client Responsibilities:**
- Control transaction lifecycle (start, prepare, commit, rollback)
- Coordinate distributed transactions across multiple resources
- Run transaction manager (if needed for 2PC coordination)

### Named DataSource Architecture

The system supports multiple named datasources, each with its own connection pool and configuration:

**JDBC Driver Side:**
- Parses datasource name from URL: `jdbc:ojp[host:port(dataSourceName)]_database:...`
- Loads datasource-specific properties from `ojp.properties` using pattern `{dataSourceName}.ojp.connection.pool.*`
- Sends datasource name to server as `ojp.datasource.name` property
- Falls back to unprefixed properties for "default" datasource

**Server Side:**
- `ConnectionHashGenerator` includes datasource name in connection hash calculation
- Ensures separate pools for same connection string but different datasource names
- `DataSourceConfigurationManager` extracts and caches datasource-specific configurations
- Both HikariCP (non-XA) and Atomikos (XA) pools use the same datasource name infrastructure

**Benefits:**
- Multiple isolated connection pools for the same database
- Different configurations per datasource (e.g., primary with high pool size, readonly with low pool size)
- Clear separation of concerns (web traffic vs batch jobs vs analytics)
- Works transparently for both XA and non-XA connections

## Configuration

### Server Configuration Properties

**BREAKING CHANGE (v0.2.1+):** The properties `ojp.xa.maxTransactions` and `ojp.xa.startTimeoutMillis` have been **REMOVED**.

XA connection pooling now uses the same properties as regular connection pools.

| Client Property | Atomikos Property | Conversion |
|----------------|------------------|------------|
| ojp.connection.pool.maximumPoolSize | setMaxPoolSize | Direct mapping |
| ojp.connection.pool.minimumIdle | setMinPoolSize | Direct mapping |
| ojp.connection.pool.connectionTimeout | setBorrowConnectionTimeout | ms → seconds (min 1) |
| ojp.connection.pool.idleTimeout | setMaxIdleTime | ms → seconds (min 1) |
| ojp.connection.pool.validationQuery | setTestQuery | Direct mapping |

### Time Conversion

All timeout configurations are kept in milliseconds in configuration files and automatically converted to seconds for Atomikos:

```java
seconds = Math.max(1, Math.round(milliseconds / 1000.0))
```

Minimum value is 1 second, even for values less than 1000ms.

## Usage Examples

### Named DataSource Support

OJP supports multiple named datasources, allowing different connection pools with different configurations. The datasource name is specified in parentheses within the OJP URL section.

**URL Format:**
```
jdbc:ojp[host:port(dataSourceName)]_database:connectionString
```

**Example URLs:**
```java
// Primary datasource
jdbc:ojp[localhost:1059(primary)]_postgresql://localhost/mydb

// Secondary (read-only) datasource  
jdbc:ojp[localhost:1059(secondary)]_postgresql://localhost/mydb

// Default datasource (no name specified)
jdbc:ojp[localhost:1059]_postgresql://localhost/mydb
```

**Configuration in ojp.properties:**
```properties
# Primary datasource configuration
primary.ojp.connection.pool.maximumPoolSize=30
primary.ojp.connection.pool.minimumIdle=10
primary.ojp.connection.pool.connectionTimeout=15000

# Secondary datasource configuration
secondary.ojp.connection.pool.maximumPoolSize=10
secondary.ojp.connection.pool.minimumIdle=3
secondary.ojp.connection.pool.connectionTimeout=5000

# Default datasource configuration (used when no name specified)
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
```

Each named datasource creates a separate connection pool on the server with its own configuration. This applies to both HikariCP (non-XA) and Atomikos (XA) connection pools.

### Client-Side XA Connection

```java
import com.openjproxy.grpc.ConnectionDetails;
import org.openjproxy.grpc.SerializationHandler;
import java.util.Properties;

// Configure connection pool properties for named datasource
Properties props = new Properties();
props.setProperty("ojp.datasource.name", "primary");  // Named datasource
props.setProperty("ojp.connection.pool.maximumPoolSize", "20");
props.setProperty("ojp.connection.pool.minimumIdle", "5");
props.setProperty("ojp.connection.pool.connectionTimeout", "10000");
props.setProperty("ojp.connection.pool.idleTimeout", "600000");
props.setProperty("ojp.connection.pool.maxLifetime", "1800000");

// Create XA connection details
ConnectionDetails details = ConnectionDetails.newBuilder()
    .setUrl("jdbc:postgresql://localhost:5432/mydb")
    .setUser("myuser")
    .setPassword("mypassword")
    .setClientUUID(UUID.randomUUID().toString())
    .setIsXA(true)  // Enable XA transactions
    .setProperties(ByteString.copyFrom(SerializationHandler.serialize(props)))
    .build();

// Connect using the XA-enabled connection
SessionInfo session = statementService.connect(details);
```

**Using JDBC Driver with Named DataSource:**
```java
// The JDBC driver automatically extracts the datasource name from the URL
// and loads the corresponding configuration from ojp.properties
String url = "jdbc:ojp[localhost:1059(primary)]_postgresql://localhost:5432/mydb";
Connection conn = DriverManager.getConnection(url, "user", "password");

// This will:
// 1. Parse "primary" as the datasource name
// 2. Load primary.ojp.connection.pool.* properties from ojp.properties
// 3. Send these properties to the server
// 4. Create a separate "primary" connection pool on the server
```

### Server Configuration

Start the server with Atomikos logging enabled:

```bash
java -Dojp.jdbc.atomikos.logging.enabled=true \
     -Dojp.jdbc.atomikos.logging.dir=/var/log/atomikos \
     -jar ojp-server.jar
```

Or with Docker:

```bash
docker run -p 1059:1059 \
  -e OJP_JDBC_ATOMIKOS_LOGGING_ENABLED=true \
  -e OJP_JDBC_ATOMIKOS_LOGGING_DIR=/var/log/atomikos \
  -v /var/log/atomikos:/var/log/atomikos \
  rrobetti/ojp:latest
```

## Supported Databases

The current implementation supports XA transactions for:
- PostgreSQL (via org.postgresql.xa.PGXADataSource)
- MySQL (via com.mysql.cj.jdbc.MysqlXADataSource)
- H2 (via org.h2.jdbcx.JdbcDataSource) - primarily for testing

Additional database support can be added by extending the `createXADataSource()` method in `StatementServiceImpl`.

## Implementation Details

### Atomikos Lifecycle

1. **Startup**: AtomikosLifecycle.start() is called when GrpcServer starts
   - Initializes UserTransactionServiceImp with configuration
   - Sets up transaction logging based on configuration
   - If logging is disabled, uses temp directory with minimal I/O

2. **Shutdown**: AtomikosLifecycle.stop() is called when GrpcServer shuts down
   - Gracefully shuts down UserTransactionService
   - Cleans up resources

### Connection Acquisition Flow

```
Client Request (isXA=true, dataSourceName="primary")
    ↓
JDBC Driver parses URL: jdbc:ojp[localhost:1059(primary)]_postgres:mydb
    ↓
Extracts dataSourceName="primary" from URL
    ↓
Loads primary.ojp.connection.pool.* from ojp.properties
    ↓
Sends to server with ojp.datasource.name=primary
    ↓
StatementServiceImpl.connect()
    ↓
ConnectionHashGenerator includes dataSourceName in hash
    ↓
Creates XADataSource for database
    ↓
AtomikosDataSourceFactory.createAtomikosDataSource()
    ↓
DataSourceConfigurationManager extracts "primary" config
    ↓
Wraps XADataSource in AtomikosDataSourceBean with "primary" config
    ↓
Stores in datasourceXaMap with unique hash (includes dataSourceName)
    ↓
Returns SessionInfo (no connection yet - lazy allocation)
    ↓
Client executes statement
    ↓
StatementServiceImpl.sessionConnection()
    ↓
Detects XA session, acquires connection from Atomikos pool
    ↓
Creates session with connection
```

### Transaction Logging

When `ojp.jdbc.atomikos.logging.enabled=true`:
- Transaction logs are written to the specified directory
- Logs enable recovery after crashes
- Directory is automatically created if it doesn't exist

When `ojp.jdbc.atomikos.logging.enabled=false` (default):
- Atomikos uses temp directory with minimal configuration
- No persistent transaction logs
- Checkpoint interval set to very high value to minimize I/O
- Suitable for development and testing

## Testing

### Unit Tests

The `AtomikosIntegrationTest` class provides comprehensive test coverage:

```bash
mvn test -Dtest=AtomikosIntegrationTest -pl ojp-server -am
```

Tests include:
- Atomikos lifecycle (start/stop)
- DataSource creation with configuration
- Connection acquisition
- Milliseconds to seconds conversion
- Default values
- Configuration caching

### Manual Testing

1. Start server with XA support:
```bash
mvn clean install -DskipTests
cd ojp-server
mvn exec:java -Dexec.mainClass="org.openjproxy.grpc.server.GrpcServer" \
  -Dojp.jdbc.atomikos.logging.enabled=true
```

2. Connect with XA-enabled client and verify Atomikos datasource creation in logs

## Performance Considerations

### Connection Pooling

- Atomikos pools are sized independently per datasource
- Pool size configured via standard OJP connection pool properties
- Each XA datasource has its own unique resource name

### Transaction Logging

- When logging is disabled (default), minimal disk I/O
- Enable logging only in production environments requiring crash recovery
- Log directory should be on a reliable filesystem

### Lazy Allocation

- Connections not acquired until first use
- Reduces connection overhead for short-lived sessions
- Pool connections recycled efficiently

## Troubleshooting

### Common Issues

1. **javax.transaction.TransactionManager not found**
   - Ensure javax.transaction-api dependency is present
   - Version 1.3 required

2. **Atomikos fails to start**
   - Check logging directory permissions
   - Verify JTA API is on classpath
   - Review Atomikos configuration in logs

3. **Connection timeout**
   - Increase connectionTimeout property
   - Check database connectivity
   - Verify pool size is adequate

### Debug Logging

Enable Atomikos debug logging:
```bash
-Dcom.atomikos.icatch.console_log_level=DEBUG
```

## Migration from Previous Version

If upgrading from a version without XA support:

1. Existing non-XA connections continue to work unchanged
2. No configuration changes required for existing deployments
3. XA support is opt-in via `isXA=true` flag
4. Server gracefully handles both XA and non-XA connections simultaneously

## Dependencies

Required Maven dependencies (automatically included):

```xml
<!-- Atomikos -->
<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-jta</artifactId>
    <version>5.0.8</version>
</dependency>
<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-jdbc</artifactId>
    <version>5.0.8</version>
</dependency>
<dependency>
    <groupId>javax.transaction</groupId>
    <artifactId>javax.transaction-api</artifactId>
    <version>1.3</version>
</dependency>
```

## Adding XA Support for Other Databases

This section explains how to extend OJP's XA support beyond PostgreSQL to other databases.

### Current Database Support Status

**Fully Supported (Tested):**
- ✅ **PostgreSQL** - Complete XA support with integration tests using `PGXADataSource`

**Infrastructure Ready (Factory Methods Exist, Not Tested):**
- 🟡 **MySQL** - `MysqlXADataSource` factory method implemented
- 🟡 **Oracle** - `OracleXADataSource` factory method implemented with privilege documentation
- 🟡 **SQL Server** - `SQLServerXADataSource` factory method implemented
- 🟡 **DB2** - `DB2XADataSource` factory method implemented
- 🟡 **CockroachDB** - Uses PostgreSQL protocol via `PGXADataSource`

**Not Supported:**
- ❌ **H2, MariaDB** - Limited or no XA support in these databases

### Architecture Overview

XA support in OJP involves three layers:

1. **Server-side XADataSource Factory** (`XADataSourceFactory.java`) - Creates native JDBC driver XADataSource instances
2. **Atomikos Connection Pool** (`AtomikosXAConnectionPool.java`) - Wraps XADataSource for connection pooling
3. **Client-side Tests** (`PostgresXAIntegrationTest.java`) - Validates XA functionality end-to-end

### Step-by-Step Guide to Add a New Database

#### 1. Add XADataSource Factory Method

**File:** `ojp-server/src/main/java/org/openjproxy/grpc/server/xa/XADataSourceFactory.java`

**What to do:**
- Add a URL detection check in the main `createXADataSource()` method
- Implement a private factory method for your database's XADataSource

**Example for MySQL (already exists):**

```java
// In createXADataSource() method, add:
if (lowerUrl.contains("mysql")) {
    return createMySQLXADataSource(url, connectionDetails);
}

// Factory method:
private static XADataSource createMySQLXADataSource(String url, ConnectionDetails connectionDetails) 
        throws SQLException {
    try {
        // Check driver availability
        Class.forName("com.mysql.cj.jdbc.MysqlXADataSource");
        
        com.mysql.cj.jdbc.MysqlXADataSource xaDS = new com.mysql.cj.jdbc.MysqlXADataSource();
        xaDS.setUrl(url);
        xaDS.setUser(connectionDetails.getUser());
        xaDS.setPassword(connectionDetails.getPassword());
        
        log.info("Created MySQL XADataSource for URL: {}", url);
        return xaDS;
        
    } catch (ClassNotFoundException e) {
        throw new SQLException("MySQL JDBC driver not found. Add mysql-connector-j to classpath.", e);
    }
}
```

**Key considerations:**
- Use **reflection** if you want to avoid compile-time dependencies on proprietary drivers
- Parse the JDBC URL to extract connection parameters (host, port, database name)
- Handle driver availability gracefully with `ClassNotFoundException`
- Document any special database privileges required (like Oracle XA privileges)

#### 2. Atomikos Integration (No Changes Needed)

The `AtomikosXAConnectionPool` class is **database-agnostic**. It wraps any `XADataSource` implementation:

```java
// This works for any database
AtomikosXAConnectionPool pool = new AtomikosXAConnectionPool(xaDataSource, connHash, poolConfig);
```

**What Atomikos provides:**
- Connection pooling (maxPoolSize, minPoolSize)
- Connection validation (testQuery)
- Timeout management (borrowConnectionTimeout)
- Connection lifecycle (borrow/return)

**No changes needed** in `AtomikosXAConnectionPool.java` when adding a new database.

#### 3. Create Integration Tests

**File pattern:** `ojp-jdbc-driver/src/test/java/openjproxy/jdbc/{Database}XAIntegrationTest.java`

**Use PostgreSQL test as template:**

```java
@Slf4j
public class MySQLXAIntegrationTest {
    
    private XAConnection xaConnection;
    private Connection connection;
    
    @BeforeAll
    public static void checkTestConfiguration() {
        // Enable/disable tests via system property
        isTestDisabled = Boolean.parseBoolean(
            System.getProperty("disableMySQLTests", "false"));
    }
    
    public void setUp(String driverClass, String url, String user, 
                      String password, boolean isXA) throws SQLException {
        // Create XA DataSource
        OjpXADataSource xaDataSource = new OjpXADataSource();
        xaDataSource.setUrl(url);
        xaDataSource.setUser(user);
        xaDataSource.setPassword(password);
        
        // Get XA Connection
        xaConnection = xaDataSource.getXAConnection(user, password);
        connection = xaConnection.getConnection();
    }
    
    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_xa_connection.csv")
    public void testXATransactionWithCRUD(String driverClass, String url, 
                                         String user, String password, boolean isXA) {
        // Test XA operations: start, prepare, commit, rollback
    }
}
```

**Test CSV file:** `ojp-jdbc-driver/src/test/resources/mysql_xa_connection.csv`

```csv
org.openjproxy.jdbc.Driver,jdbc:ojp[localhost:1059]_mysql://localhost:3306/testdb,user,pass,true
```

#### 4. Key Test Cases to Implement

Based on PostgreSQL tests, implement these scenarios:

1. **testXAConnectionBasics** - Verify XAConnection creation and XAResource availability
2. **testXATransactionWithCRUD** - Test XA start → prepare → commit flow
3. **testXATransactionRollback** - Test XA rollback functionality
4. **testXATransactionTimeout** - Test transaction timeout settings
5. **testXAOnePhaseCommit** - Test one-phase commit optimization

**Sample test structure:**

```java
@Test
public void testXATransactionWithCRUD() throws Exception {
    XAResource xaResource = xaConnection.getXAResource();
    Xid xid = new TestXid(1, "global-tx-1".getBytes(), "branch-1".getBytes());
    
    // Start XA transaction
    xaResource.start(xid, XAResource.TMNOFLAGS);
    
    // Execute SQL
    try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO test_table VALUES (?, ?)")) {
        ps.setInt(1, 1);
        ps.setString(2, "Test");
        ps.executeUpdate();
    }
    
    // End and prepare
    xaResource.end(xid, XAResource.TMSUCCESS);
    int result = xaResource.prepare(xid);
    
    // Commit
    if (result == XAResource.XA_OK) {
        xaResource.commit(xid, false);
    }
}
```

#### 5. Database-Specific Considerations

**Oracle:**
- Requires specific XA privileges (documented in `XADataSourceFactory.java`):
  ```sql
  GRANT SELECT ON sys.dba_pending_transactions TO user;
  GRANT EXECUTE ON sys.dbms_system TO user;
  GRANT FORCE ANY TRANSACTION TO user;
  ```

**MySQL:**
- XA support in MySQL has some limitations with certain storage engines
- Use InnoDB storage engine for XA transactions

**SQL Server:**
- Requires MSDTC (Microsoft Distributed Transaction Coordinator) for full XA support
- May need specific configuration for distributed transactions

**CockroachDB:**
- Uses PostgreSQL wire protocol, so `PGXADataSource` works
- Some XA features may have different behavior

#### 6. Testing Prerequisites

Before running tests:

1. **Database Instance** - Running database accessible from test environment
2. **Test Database** - Create a test database with appropriate schema
3. **User Privileges** - Ensure user has XA-related privileges (database-specific)
4. **OJP Server** - Running OJP server on localhost:1059 (or configured endpoint)
5. **JDBC Driver** - Database JDBC driver with XA support on classpath

#### 7. Code Changes Checklist

- [ ] Add XADataSource factory method in `XADataSourceFactory.java`
- [ ] Add URL detection logic in `createXADataSource()` main method
- [ ] Create integration test class (e.g., `MySQLXAIntegrationTest.java`)
- [ ] Create test CSV file with connection parameters
- [ ] Implement all 5 core test cases
- [ ] Document any database-specific requirements (privileges, configuration)
- [ ] Update `ADDING_DATABASE_XA_SUPPORT.md` with database status
- [ ] Test end-to-end: factory → Atomikos pool → XA operations → commit/rollback

#### 8. Validation

**Verify your implementation works:**

```bash
# Run integration tests
mvn test -pl ojp-jdbc-driver -Dtest="MySQLXAIntegrationTest"

# Check server logs for:
# - "Created MySQL XADataSource for URL: ..."
# - "Created Atomikos XA pool '...': maxPoolSize=..."
# - "Leased new XAConnection for session/branch: ..."
# - "Returned XAConnection for session/branch: ..."
```

**Expected behavior:**
- XADataSource created successfully
- Atomikos pool initialized with configured sizes
- XA transactions execute: start → prepare → commit/rollback
- Connections returned to pool after transaction completion
- No connection leaks (check pool stats in logs)

### Common Issues and Solutions

**Issue: ClassNotFoundException for XADataSource**
- **Solution**: Add database JDBC driver to Maven dependencies or runtime classpath

**Issue: XA privilege errors (Oracle)**
- **Solution**: Grant required XA privileges to database user (see Oracle section above)

**Issue: Connection timeout during tests**
- **Solution**: Increase `ojp.connection.pool.connectionTimeout` in test configuration

**Issue: XA transactions not starting**
- **Solution**: Verify database supports XA transactions and user has required privileges

**Issue: Pool exhaustion during tests**
- **Solution**: Increase `ojp.connection.pool.maximumPoolSize` or ensure connections are returned

### Reference Implementation

**PostgreSQL** serves as the reference implementation:

- **Factory**: `XADataSourceFactory.createPostgreSQLXADataSource()`
- **Tests**: `PostgresXAIntegrationTest.java`
- **CSV**: `postgres_xa_connection.csv`
- **XADataSource**: `org.postgresql.xa.PGXADataSource`

Study these files when implementing support for other databases.

## References

- [Atomikos Documentation](https://www.atomikos.com/Documentation/)
- [JTA Specification](https://jcp.org/en/jsr/detail?id=907)
- [XA Transactions](https://en.wikipedia.org/wiki/X/Open_XA)
