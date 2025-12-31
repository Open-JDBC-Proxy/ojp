# TestContainers Migration Guide

**Purpose:** Step-by-step guide for migrating integration tests from CSV-based configuration to TestContainers.

## Table of Contents
- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Migration Steps](#migration-steps)
- [Database-Specific Guides](#database-specific-guides)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

## Overview

This guide provides instructions for migrating OJP integration tests from external database connections (using CSV configuration files) to TestContainers. The SQL Server tests serve as the reference implementation.

### Benefits of Migration
- ✅ No external database setup required
- ✅ Consistent test environment across all developers
- ✅ Faster test execution with container reuse
- ✅ Better CI/CD integration
- ✅ Automatic cleanup and isolation

### Reference Implementation
See the SQL Server implementation as the gold standard:
- `SQLServerTestContainer.java` - Container management
- `SQLServerConnectionProvider.java` - JUnit integration
- `SQLServerBinaryStreamIntegrationTest.java` - Example test usage

## Prerequisites

### Required Tools
1. **Docker** - Must be installed and running
   ```bash
   docker --version
   # Ensure Docker daemon is running
   docker ps
   ```

2. **Maven** - For building and testing
   ```bash
   mvn --version
   ```

3. **Java 11+** - Required for running tests
   ```bash
   java -version
   ```

### Required Knowledge
- Basic understanding of JUnit 5
- Familiarity with Maven project structure
- Basic Docker concepts
- Understanding of JDBC connections

## Migration Steps

### Step 1: Add TestContainers Dependencies

Add the appropriate TestContainers module to `ojp-jdbc-driver/pom.xml`:

```xml
<!-- TestContainers for [DATABASE_NAME] -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>[container-module]</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>

<!-- Database JDBC Driver (if not already present) -->
<dependency>
    <groupId>[jdbc-driver-group]</groupId>
    <artifactId>[jdbc-driver-artifact]</artifactId>
    <version>[jdbc-driver-version]</version>
    <scope>test</scope>
</dependency>
```

**Available TestContainers Modules:**
| Database | Artifact ID | JDBC Driver Group | JDBC Driver Artifact |
|----------|------------|-------------------|---------------------|
| PostgreSQL | `postgresql` | `org.postgresql` | `postgresql` |
| MySQL | `mysql` | `com.mysql` | `mysql-connector-j` |
| MariaDB | `mariadb` | `org.mariadb.jdbc` | `mariadb-java-client` |
| Oracle | `oracle-xe` | `com.oracle.database.jdbc` | `ojdbc11` |
| CockroachDB | `cockroachdb` | `org.postgresql` | `postgresql` |

### Step 2: Create Container Manager Class

Create a singleton container manager in `ojp-jdbc-driver/src/test/java/openjproxy/jdbc/testutil/`:

```java
package openjproxy.jdbc.testutil;

import org.testcontainers.containers.[DatabaseContainer];
import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton [Database] test container for all integration tests.
 * Ensures all tests share the same container instance for efficiency.
 */
public class [Database]TestContainer {
    
    // Container image
    private static final String [DATABASE]_IMAGE = "[image:tag]";
    
    private static [DatabaseContainer]<?> container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    private static ReentrantLock initLock = new ReentrantLock();
    
    /**
     * Gets or creates the shared test container instance.
     * The container is automatically started on first access.
     */
    public static [DatabaseContainer]<?> getInstance() {
        // Fast-path: if container already created and running, return it
        [DatabaseContainer]<?> local = container;
        if (local != null && local.isRunning()) {
            return local;
        }

        initLock.lock();
        try {
            if (container == null) {
                container = new [DatabaseContainer]<>([DATABASE]_IMAGE);
                // Add any database-specific configuration here
            }

            if (!isStarted) {
                container.start();
                isStarted = true;

                // Post-start initialization if needed
                try {
                    // Database-specific setup (e.g., create users, databases, install extensions)
                    performPostStartSetup();
                } catch (Exception e) {
                    System.err.println("[[Database]TestContainer] Warning: Post-start setup failed: " + e.getMessage());
                }

                // Add shutdown hook
                if (!shutdownHookRegistered) {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (container != null && container.isRunning()) {
                            container.stop();
                        }
                    }));
                    shutdownHookRegistered = true;
                }
            }

            return container;
        } finally {
            initLock.unlock();
        }
    }
    
    /**
     * Database-specific post-start initialization.
     */
    private static void performPostStartSetup() throws Exception {
        // Example: Create test database, users, install extensions
        // See SQLServerTestContainer for XA setup example
    }
    
    /**
     * Gets the JDBC URL for connecting to the test container.
     */
    public static String getJdbcUrl() {
        return getInstance().getJdbcUrl();
    }
    
    /**
     * Gets the username for connecting to the test container.
     */
    public static String getUsername() {
        return getInstance().getUsername();
    }
    
    /**
     * Gets the password for connecting to the test container.
     */
    public static String getPassword() {
        return getInstance().getPassword();
    }
    
    /**
     * Checks if tests are enabled via system property.
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("enable[Database]Tests", "false"));
    }
}
```

### Step 3: Create Connection Provider

Create a JUnit ArgumentsProvider in the same package:

```java
package openjproxy.jdbc.testutil;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import java.util.stream.Stream;

/**
 * Custom ArgumentsProvider for [Database] integration tests.
 * Provides connection details from TestContainers when tests are enabled.
 */
public class [Database]ConnectionProvider implements ArgumentsProvider {
    
    private static final String JDBC_PREFIX = "jdbc:";
    private static final String OJP_PROXY_HOST = System.getProperty("ojp.proxy.host", "localhost");
    private static final String OJP_PROXY_PORT = System.getProperty("ojp.proxy.port", "1059");
    private static final String OJP_PROXY_ADDRESS = OJP_PROXY_HOST + ":" + OJP_PROXY_PORT;
    
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        if (![Database]TestContainer.isEnabled()) {
            return Stream.empty();
        }

        ConnectionProps props = getConnectionProps();
        return Stream.of(
            Arguments.of(props.driverClass, props.ojpUrl, props.username, props.password)
        );
    }

    private static @NotNull ConnectionProps getConnectionProps() {
        // Initialize and start the TestContainer
        [Database]TestContainer.getInstance();

        // Get connection details
        String containerJdbcUrl = [Database]TestContainer.getJdbcUrl();
        String username = [Database]TestContainer.getUsername();
        String password = [Database]TestContainer.getPassword();

        // Build OJP JDBC URL
        String driverClass = "org.openjproxy.jdbc.Driver";
        String urlWithoutPrefix = containerJdbcUrl.startsWith(JDBC_PREFIX)
            ? containerJdbcUrl.substring(JDBC_PREFIX.length())
            : containerJdbcUrl;

        // Add any database-specific URL parameters here
        
        String ojpUrl = JDBC_PREFIX + "ojp[" + OJP_PROXY_ADDRESS + "]_" + urlWithoutPrefix;

        return new ConnectionProps(username, password, driverClass, ojpUrl);
    }

    private static class ConnectionProps {
        final String username;
        final String password;
        final String driverClass;
        final String ojpUrl;

        ConnectionProps(String username, String password, String driverClass, String ojpUrl) {
            this.username = username;
            this.password = password;
            this.driverClass = driverClass;
            this.ojpUrl = ojpUrl;
        }
    }
}
```

### Step 4: Update Test Classes

Update each integration test class to use the new provider:

**Before (CSV-based):**
```java
@ParameterizedTest
@CsvFileSource(resources = "/database_connections.csv")
public void testMethod(String driverClass, String url, String user, String pwd) {
    // Test code...
}
```

**After (TestContainers-based):**
```java
import openjproxy.jdbc.testutil.[Database]ConnectionProvider;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.provider.ArgumentsSource;

@EnabledIf("openjproxy.jdbc.testutil.[Database]TestContainer#isEnabled")
public class [Database]SomeIntegrationTest {

    @ParameterizedTest
    @ArgumentsSource([Database]ConnectionProvider.class)
    public void testMethod(String driverClass, String url, String user, String pwd) {
        // Test code remains the same...
    }
}
```

### Step 5: Update Test Execution

Run tests with the database test flag enabled:

```bash
# Single database tests
mvn test -pl ojp-jdbc-driver -Denable[Database]Tests=true -Dtest="[Database]*"

# All integration tests
mvn test -pl ojp-jdbc-driver -Denable[Database]Tests=true
```

## Database-Specific Guides

### PostgreSQL Migration

**Container Image:** `postgres:17-alpine`

**Dependencies:**
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
```

**Container Setup:**
```java
import org.testcontainers.containers.PostgreSQLContainer;

container = new PostgreSQLContainer<>("postgres:17-alpine")
    .withDatabaseName("testdb")
    .withUsername("testuser")
    .withPassword("testpass");
```

**Tests to Migrate:**
- `PostgresMultipleTypesIntegrationTest.java`
- `PostgresXAIntegrationTest.java`
- `BinaryStreamIntegrationTest.java` (postgres-specific tests)
- `ReadMultipleBlocksOfDataIntegrationTest.java` (postgres-specific tests)

### MySQL Migration

**Container Image:** `mysql:8.4`

**Dependencies:**
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
```

**Container Setup:**
```java
import org.testcontainers.containers.MySQLContainer;

container = new MySQLContainer<>("mysql:8.4")
    .withDatabaseName("testdb")
    .withUsername("testuser")
    .withPassword("testpass");
```

**Tests to Migrate:**
- `MySQLMultipleTypesIntegrationTest.java`
- `MySQLSpecificFeaturesIntegrationTest.java`
- `BlobIntegrationTest.java` (mysql-specific tests)

### MariaDB Migration

**Container Image:** `mariadb:11`

**Dependencies:**
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mariadb</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
```

**Container Setup:**
```java
import org.testcontainers.containers.MariaDBContainer;

container = new MariaDBContainer<>("mariadb:11")
    .withDatabaseName("testdb")
    .withUsername("testuser")
    .withPassword("testpass");
```

**Tests to Migrate:**
- Tests using `mysql_mariadb_connection.csv` (MariaDB-specific runs)

### CockroachDB Migration

**Container Image:** `cockroachdb/cockroach:latest-v24.3`

**Dependencies:**
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>cockroachdb</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
```

**Container Setup:**
```java
import org.testcontainers.containers.CockroachContainer;

container = new CockroachContainer("cockroachdb/cockroach:latest-v24.3")
    .withDatabaseName("testdb")
    .withUsername("testuser")
    .withPassword("testpass");
```

**Tests to Migrate:**
- `CockroachDBBinaryStreamIntegrationTest.java`
- `CockroachDBBlobIntegrationTest.java`
- `CockroachDBMultipleTypesIntegrationTest.java`
- `CockroachDBReadMultipleBlocksOfDataIntegrationTest.java`

### Oracle Migration

**Container Image:** `gvenzl/oracle-free:23-slim-faststart`

**Dependencies:**
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>oracle-free</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
```

**Container Setup:**
```java
import org.testcontainers.containers.OracleContainer;

container = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
    .withDatabaseName("FREEPDB1")
    .withUsername("testuser")
    .withPassword("testpass");
```

**Special Considerations:**
- Oracle containers are larger and slower to start
- Consider using shared containers across test classes
- May require accepting Oracle license terms

**Tests to Migrate:**
- `OracleBinaryStreamIntegrationTest.java`
- `OracleBlobIntegrationTest.java`
- `OracleMultipleTypesIntegrationTest.java`
- `OracleReadMultipleBlocksOfDataIntegrationTest.java`
- `OracleXAIntegrationTest.java`

## Best Practices

### 1. Use Singleton Containers
Share a single container instance across all tests for a database to improve performance:
```java
private static [DatabaseContainer]<?> container;  // Shared instance
```

### 2. Implement Proper Locking
Use locks to prevent race conditions during container initialization:
```java
private static ReentrantLock initLock = new ReentrantLock();
```

### 3. Add Shutdown Hooks
Ensure containers are properly stopped:
```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    if (container != null && container.isRunning()) {
        container.stop();
    }
}));
```

### 4. Use Conditional Test Execution
Enable/disable tests based on system properties:
```java
@EnabledIf("openjproxy.jdbc.testutil.[Database]TestContainer#isEnabled")
```

### 5. Handle Post-Start Initialization
Perform database-specific setup after container starts:
```java
private static void performPostStartSetup() throws Exception {
    // Create users, databases, install extensions, etc.
}
```

### 6. Use Fast-Start Images
Choose container images optimized for testing:
- PostgreSQL: `postgres:17-alpine`
- MySQL: `mysql:8.4`
- Oracle: `gvenzl/oracle-free:23-slim-faststart`

### 7. Clean Up Between Tests
Use `@BeforeEach` or transaction rollback to ensure test isolation:
```java
@BeforeEach
void setUp() {
    // Clean up tables or use transactions
}
```

### 8. Configure Timeouts
Set appropriate timeouts for container startup:
```java
container.withStartupTimeout(Duration.ofMinutes(5));
```

### 9. Add Logging
Include helpful logging for debugging:
```java
System.out.println("Testing with container URL: " + container.getJdbcUrl());
```

### 10. Document Container Configuration
Add JavaDoc comments explaining container setup and requirements.

## Advanced Topics

### XA Transaction Support

For XA-enabled tests (like `SqlServerXAIntegrationTest`), you need to install XA components:

```java
private static void installXaSupport() throws Exception {
    // Example for SQL Server
    String[] cmd = {
        "/opt/mssql-tools18/bin/sqlcmd",
        "-S", "localhost",
        "-U", getInstance().getUsername(),
        "-P", getInstance().getPassword(),
        "-d", "master",
        "-C",
        "-Q", "EXEC sp_sqljdbc_xa_install;"
    };
    getInstance().execInContainer(cmd);
}
```

### Custom Network Configuration

For multi-container tests:
```java
Network network = Network.newNetwork();
container.withNetwork(network);
```

### Database Initialization Scripts

Run SQL scripts on container startup:
```java
container.withInitScript("init.sql");
```

### Reusable Containers

For faster test execution across multiple Maven modules:
```java
container.withReuse(true);
```

## Troubleshooting

### Issue: Container fails to start

**Symptom:** Test fails with "Could not start container"

**Solutions:**
1. Check Docker is running: `docker ps`
2. Check Docker has enough resources (memory, disk)
3. Try pulling image manually: `docker pull [image:tag]`
4. Check firewall/network settings
5. Enable TestContainers debugging: `-Dtestcontainers.debug=true`

### Issue: Tests are slow

**Symptom:** Tests take a long time to execute

**Solutions:**
1. Use singleton containers (share across tests)
2. Use fast-start container images (alpine, slim variants)
3. Use container reuse: `container.withReuse(true)`
4. Run tests in parallel: `mvn -T 4 test`
5. Reduce startup timeouts appropriately

### Issue: Port conflicts

**Symptom:** "Port already in use" error

**Solutions:**
1. TestContainers uses random ports by default
2. Ensure you're not hardcoding ports
3. Stop conflicting containers: `docker ps` and `docker stop [id]`

### Issue: OJP server not running

**Symptom:** "Connection refused" to localhost:1059

**Solutions:**
1. Start OJP server before running tests:
   ```bash
   java -jar ojp-server/target/ojp-server-*-shaded.jar &
   ```
2. Check server logs
3. Verify port 1059 is available

### Issue: Database initialization fails

**Symptom:** Post-start setup throws exceptions

**Solutions:**
1. Add appropriate delays: `Thread.sleep(1000)`
2. Check container logs: `container.getLogs()`
3. Verify JDBC driver compatibility
4. Check database-specific requirements (e.g., Oracle license acceptance)

### Issue: Tests work locally but fail in CI

**Symptom:** Tests pass on developer machine but fail in GitHub Actions

**Solutions:**
1. Ensure Docker is available in CI environment
2. Check CI has sufficient resources
3. Use matrix builds for testing multiple databases
4. Add container health checks
5. Increase timeouts in CI environment

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Database Integration Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    
    strategy:
      matrix:
        database: [postgresql, mysql, mariadb, cockroachdb]
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'
      
      - name: Build OJP Server
        run: mvn clean package -pl ojp-server -DskipTests
      
      - name: Start OJP Server
        run: |
          java -jar ojp-server/target/ojp-server-*-shaded.jar &
          sleep 10
      
      - name: Run ${{ matrix.database }} Integration Tests
        run: |
          mvn test -pl ojp-jdbc-driver \
            -Denable${{ matrix.database }}Tests=true \
            -Dtest="${{ matrix.database }}*"
      
      - name: Upload Test Results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results-${{ matrix.database }}
          path: ojp-jdbc-driver/target/surefire-reports/
```

## Migration Checklist

Use this checklist when migrating a test class:

- [ ] Add TestContainers dependency to `pom.xml`
- [ ] Add JDBC driver dependency (if not present)
- [ ] Create `[Database]TestContainer.java` class
- [ ] Create `[Database]ConnectionProvider.java` class
- [ ] Implement singleton pattern with proper locking
- [ ] Add shutdown hook for cleanup
- [ ] Implement post-start initialization if needed
- [ ] Update test class annotations:
  - [ ] Add `@EnabledIf` annotation
  - [ ] Replace `@CsvFileSource` with `@ArgumentsSource`
  - [ ] Update import statements
- [ ] Test locally with container
- [ ] Update CI/CD pipeline if needed
- [ ] Document any database-specific requirements
- [ ] Remove or deprecate CSV configuration files
- [ ] Update relevant documentation

## Additional Resources

- [TestContainers Documentation](https://www.testcontainers.org/)
- [TestContainers Java Modules](https://www.testcontainers.org/modules/)
- [SQL Server TestContainers Guide](../SQLSERVER_TESTCONTAINER_GUIDE.md)
- [Integration Tests Analysis](./INTEGRATION_TESTS_ANALYSIS.md)
- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Docker Documentation](https://docs.docker.com/)

## Support

If you encounter issues during migration:
1. Check this guide's troubleshooting section
2. Review the SQL Server implementation as reference
3. Check TestContainers documentation
4. Review existing GitHub issues
5. Create a new issue with detailed error messages and logs
