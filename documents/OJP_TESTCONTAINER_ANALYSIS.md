# OJP TestContainer Analysis & Implementation Guide

## Executive Summary

This document provides a comprehensive analysis for creating a TestContainer for Java integration tests that extends `org.testcontainers.containers.GenericContainer`. The goal is to publish a JAR to Maven Central containing this test container, which will run the OJP server internally, making it easier to produce integration tests that include OJP.

## Current State Analysis

### Existing Project Structure

The OJP project is a multi-module Maven project with:

1. **ojp-server** (Java 21) - The gRPC server managing HikariCP connection pools
2. **ojp-jdbc-driver** (Java 11) - JDBC driver implementation that connects to the server
3. **ojp-grpc-commons** (Java 11) - Shared gRPC contracts

**Key Finding**: The project already uses TestContainers for SQL Server integration tests (see `SQLServerTestContainer.java`), demonstrating familiarity with the technology.

### OJP Server Characteristics

From analyzing `GrpcServer.java` and `ServerConfiguration.java`:

- **Main Class**: `org.openjproxy.grpc.server.GrpcServer`
- **Default Port**: 1059 (gRPC)
- **Health Check**: Built-in gRPC health service
- **Configuration**: Environment-based configuration with defaults
- **Docker Image**: Already exists at `rrobetti/ojp:0.3.1-snapshot`
- **Shaded JAR**: Server produces a shaded JAR with all dependencies included

### Current Testing Patterns

Integration tests in `ojp-jdbc-driver` follow this pattern:
- Tests use `@ParameterizedTest` with `@CsvFileSource` 
- Each database requires the OJP server to be running
- Tests connect via OJP JDBC URL format: `jdbc:ojp[localhost:1059]_<database>://...`

## Recommended Implementation Approach

### 1. **Create as a Separate Module within this Repository** ✅

**Recommendation**: Create a new module `ojp-testcontainers` within the existing OJP repository.

**Rationale**:
- ✅ Maintains version synchronization with OJP server
- ✅ Leverages existing CI/CD infrastructure
- ✅ Easier dependency management (can reference ojp-server artifacts)
- ✅ Single source of truth for issues and contributions
- ✅ Follows the existing multi-module pattern (ojp-server, ojp-jdbc-driver, ojp-grpc-commons)
- ✅ Simplifies release process (all artifacts released together)

**Alternative Considered**: Separate repository
- ❌ Harder to keep versions in sync
- ❌ Additional CI/CD setup
- ❌ More complex release coordination
- ⚠️ Only consider if planning to support multiple OJP versions simultaneously

### 1.1. **Licensing Considerations for Database TestContainers** ⚠️

**IMPORTANT**: The published `ojp-testcontainers` module to Maven Central will **only include support for open-source databases** due to licensing restrictions.

**Open-Source Databases** (Can be published to Maven Central):
- ✅ PostgreSQL
- ✅ MySQL
- ✅ MariaDB
- ✅ H2
- ✅ Other OSS databases with compatible licenses

**Proprietary Databases** (Cannot be published to Maven Central):
- ❌ Oracle Database (requires accepting license)
- ❌ Microsoft SQL Server (requires accepting license)
- ❌ IBM DB2 (requires accepting license)
- ❌ Other proprietary databases

**Solution for Proprietary Databases**:
Developers can create their own TestContainer implementations locally by following the patterns and documentation we provide. See [Section 8: Custom TestContainers for Proprietary Databases](#8-custom-testcontainers-for-proprietary-databases) for detailed guidance.

### 2. Module Structure

```
ojp-testcontainers/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── org/openjproxy/testcontainers/
│   │   │       ├── OJPContainer.java
│   │   │       ├── OJPContainerConfig.java
│   │   │       └── DatabaseConfig.java
│   │   └── resources/
│   │       └── META-INF/
│   │           └── MANIFEST.MF
│   └── test/
│       ├── java/
│       │   └── org/openjproxy/testcontainers/
│       │       ├── OJPContainerTest.java
│       │       ├── PostgresIntegrationTest.java
│       │       └── MySQLIntegrationTest.java
│       └── resources/
│           └── test-databases.yml
└── README.md
```

### 3. Implementation Design

#### Core Class: OJPContainer

```java
package org.openjproxy.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * TestContainer for OJP (Open J Proxy) server.
 * Provides an easy way to run OJP server in integration tests.
 * 
 * Example usage:
 * <pre>
 * {@code
 * @Container
 * static OJPContainer ojp = new OJPContainer()
 *     .withDatabaseConfig("mydb", "jdbc:postgresql://postgres:5432/test", "user", "pass");
 * 
 * // In your test
 * String ojpUrl = ojp.getJdbcUrl("mydb");
 * }
 * </pre>
 */
public class OJPContainer extends GenericContainer<OJPContainer> {
    
    private static final String DEFAULT_IMAGE_NAME = "rrobetti/ojp";
    private static final String DEFAULT_TAG = "0.3.1-snapshot";
    private static final int DEFAULT_GRPC_PORT = 1059;
    private static final int DEFAULT_PROMETHEUS_PORT = 9159;
    
    private final Map<String, DatabaseConfig> databases = new HashMap<>();
    private boolean telemetryEnabled = true; // Enabled by default
    
    public OJPContainer() {
        this(DEFAULT_IMAGE_NAME + ":" + DEFAULT_TAG);
    }
    
    public OJPContainer(String dockerImageName) {
        super(DockerImageName.parse(dockerImageName));
        
        // Expose default gRPC port and Prometheus port
        // Both ports will be mapped to random available ports to avoid conflicts
        withExposedPorts(DEFAULT_GRPC_PORT, DEFAULT_PROMETHEUS_PORT);
        
        // Wait for health check
        waitingFor(Wait.forHealthcheck());
    }
    
    /**
     * Configure a database connection in OJP server
     */
    public OJPContainer withDatabaseConfig(String name, String jdbcUrl, 
                                           String username, String password) {
        databases.put(name, new DatabaseConfig(name, jdbcUrl, username, password));
        
        // Set environment variables for OJP server configuration
        withEnv("OJP_DB_" + name.toUpperCase() + "_URL", jdbcUrl);
        withEnv("OJP_DB_" + name.toUpperCase() + "_USERNAME", username);
        withEnv("OJP_DB_" + name.toUpperCase() + "_PASSWORD", password);
        
        return this;
    }
    
    /**
     * Get OJP JDBC URL for connecting through the container
     */
    public String getJdbcUrl(String dbName) {
        DatabaseConfig db = databases.get(dbName);
        if (db == null) {
            throw new IllegalArgumentException("Database not configured: " + dbName);
        }
        
        String host = getHost();
        int port = getMappedPort(DEFAULT_GRPC_PORT);
        
        return "jdbc:ojp[" + host + ":" + port + "]_" + db.getTargetJdbcUrl();
    }
    
    /**
     * Get the gRPC connection string for direct gRPC clients
     */
    public String getGrpcUrl() {
        return getHost() + ":" + getMappedPort(DEFAULT_GRPC_PORT);
    }
    
    /**
     * Enable or disable telemetry/Prometheus metrics.
     * Telemetry is enabled by default.
     */
    public OJPContainer withTelemetryEnabled(boolean enabled) {
        this.telemetryEnabled = enabled;
        withEnv("ojp.opentelemetry.enabled", String.valueOf(enabled));
        return this;
    }
    
    /**
     * Get the Prometheus metrics endpoint URL.
     * The Prometheus port is automatically mapped to a random available port
     * to avoid conflicts when running multiple containers.
     * 
     * @return Prometheus metrics URL (e.g., "http://localhost:54321/metrics")
     */
    public String getPrometheusUrl() {
        if (!telemetryEnabled) {
            throw new IllegalStateException("Telemetry is disabled. Enable it with withTelemetryEnabled(true)");
        }
        return "http://" + getHost() + ":" + getMappedPort(DEFAULT_PROMETHEUS_PORT) + "/metrics";
    }
    
    /**
     * Get the mapped Prometheus port.
     * The port is randomly assigned to avoid conflicts.
     * 
     * @return The host port mapped to the container's Prometheus port
     */
    public int getPrometheusPort() {
        return getMappedPort(DEFAULT_PROMETHEUS_PORT);
    }
}
```

#### Key Features to Implement

1. **Fluent Configuration API**
   - Easy database configuration
   - Optional: Circuit breaker settings
   - Optional: Connection pool settings
   - Optional: IP whitelisting (for production-like testing)
   - Optional: Telemetry/Prometheus configuration

2. **Network Integration**
   - Support for Testcontainers network (to connect to other database containers)
   - Example: Link with PostgreSQL/MySQL containers

3. **Health Checks**
   - Use OJP's built-in gRPC health service
   - Ensure container is ready before tests run

4. **Resource Management**
   - Proper cleanup on test completion
   - Support for singleton pattern (shared across tests)
   - Support for per-test instances

5. **Port Management**
   - Automatic port mapping for gRPC (1059) and Prometheus (9159) to random available ports
   - Prevents conflicts when running multiple containers in parallel
   - Both ports accessible via getMappedPort() methods

### 4. Maven Configuration (pom.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <artifactId>ojp-testcontainers</artifactId>
    <version>0.3.1-snapshot</version>
    <name>OJP TestContainers</name>
    <description>TestContainers integration for OJP (Open J Proxy)</description>

    <parent>
        <groupId>org.openjproxy</groupId>
        <artifactId>ojp-parent</artifactId>
        <version>0.3.1-snapshot</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <properties>
        <testcontainers.version>1.20.4</testcontainers.version>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>

    <dependencies>
        <!-- TestContainers -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <version>${testcontainers.version}</version>
        </dependency>
        
        <!-- Optional: For typed container support -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>${testcontainers.version}</version>
            <optional>true</optional>
        </dependency>
        
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mysql</artifactId>
            <version>${testcontainers.version}</version>
            <optional>true</optional>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.12.1</version>
            <scope>test</scope>
        </dependency>
        
        <!-- OJP JDBC Driver for testing -->
        <dependency>
            <groupId>org.openjproxy</groupId>
            <artifactId>ojp-jdbc-driver</artifactId>
            <version>0.3.1-snapshot</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Sources JAR -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-source-plugin</artifactId>
                <version>3.2.1</version>
                <executions>
                    <execution>
                        <id>attach-sources</id>
                        <goals>
                            <goal>jar</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            
            <!-- Javadoc JAR -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-javadoc-plugin</artifactId>
                <version>3.6.3</version>
                <executions>
                    <execution>
                        <id>attach-javadocs</id>
                        <goals>
                            <goal>jar</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### 5. Usage Examples

#### Example 1: Basic Usage with H2

```java
import org.openjproxy.testcontainers.OJPContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MyIntegrationTest {
    
    @Container
    static OJPContainer ojp = new OJPContainer()
        .withDatabaseConfig("h2test", "jdbc:h2:mem:test", "sa", "");
    
    @Test
    void testDatabaseAccess() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
            ojp.getJdbcUrl("h2test"), "sa", "")) {
            
            // Your test code here
        }
    }
}
```

#### Example 2: With PostgreSQL Container

```java
@Testcontainers
class PostgresIntegrationTest {
    
    static Network network = Network.newNetwork();
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withNetwork(network)
        .withNetworkAliases("postgres");
    
    @Container
    static OJPContainer ojp = new OJPContainer()
        .withNetwork(network)
        .dependsOn(postgres)
        .withDatabaseConfig("testdb", 
            postgres.getJdbcUrl(), 
            postgres.getUsername(), 
            postgres.getPassword());
    
    @Test
    void testThroughOJP() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
            ojp.getJdbcUrl("testdb"), 
            postgres.getUsername(), 
            postgres.getPassword())) {
            
            // Access PostgreSQL through OJP
        }
    }
}
```

#### Example 3: Singleton Pattern (Shared Container)

```java
public abstract class BaseOJPTest {
    
    protected static final OJPContainer OJP_CONTAINER;
    
    static {
        OJP_CONTAINER = new OJPContainer()
            .withReuse(true) // Enable container reuse
            .withDatabaseConfig("h2", "jdbc:h2:mem:test", "sa", "");
        
        OJP_CONTAINER.start();
    }
}

class MyTest extends BaseOJPTest {
    @Test
    void test() {
        // Use OJP_CONTAINER
    }
}
```

## Advanced Features to Consider

### 1. Configuration Builder Pattern

```java
OJPContainer ojp = new OJPContainer()
    .withServerConfiguration(config -> config
        .withCircuitBreakerTimeout(5000)
        .withCircuitBreakerThreshold(10)
        .withThreadPoolSize(50)
        .withMaxRequestSize(4 * 1024 * 1024))
    .withDatabaseConfig("db1", ...)
    .withDatabaseConfig("db2", ...);
```

### 2. Multi-Database Support

The container should support multiple database configurations simultaneously, which is already a feature of OJP server.

### 3. Observability Support

**Important**: Both the gRPC port (1059) and Prometheus port (9159) are automatically mapped to random available host ports to prevent conflicts when running multiple OJP containers.

```java
OJPContainer ojp = new OJPContainer()
    .withTelemetryEnabled(true)  // Enabled by default
    .withDatabaseConfig("db", ...);

// Access metrics endpoint - port is automatically mapped to avoid conflicts
String metricsUrl = ojp.getPrometheusUrl();  // e.g., "http://localhost:54321/metrics"
int prometheusPort = ojp.getPrometheusPort();  // e.g., 54321 (random)

// Disable telemetry if not needed
OJPContainer ojpNoMetrics = new OJPContainer()
    .withTelemetryEnabled(false)
    .withDatabaseConfig("db", ...);
```

**Port Mapping Strategy**:
- **gRPC Port (1059)**: Mapped to random host port (e.g., 32768)
- **Prometheus Port (9159)**: Mapped to random host port (e.g., 32769)
- This ensures no conflicts when running multiple containers in parallel tests

### 4. Custom OJP Server Image

```java
OJPContainer ojp = new OJPContainer("myregistry/custom-ojp:1.0.0")
    .withDatabaseConfig(...);
```

## Questions to Address

### Q1: Module location - Same repo or separate?
**Answer**: Same repository as a new module `ojp-testcontainers`

**Reasoning**:
- Version synchronization
- Easier maintenance
- Single release process
- Follows existing multi-module pattern

### Q2: Java Version Compatibility
**Answer**: Target Java 11 (LTS) for maximum compatibility

**Reasoning**:
- OJP JDBC Driver uses Java 11
- Most TestContainers users are on Java 11+
- OJP Server runs in Docker, so its Java 21 requirement is internal

### Q3: Maven Central Requirements
**Requirements for publication**:
- ✅ Sources JAR (add maven-source-plugin)
- ✅ Javadoc JAR (add maven-javadoc-plugin)
- ✅ GPG signing (already configured in parent)
- ✅ POM metadata (inherit from parent)
- ✅ Central Publishing Maven Plugin (already configured)

### Q4: Container Image Strategy
**Answer**: Use existing Docker image by default, allow custom images

**Options**:
1. **Use existing Docker image** (Recommended for v1)
   - `rrobetti/ojp:0.3.1-snapshot` already exists
   - Lightweight, fast startup
   - Matches production usage

2. **Build from shaded JAR** (Future option)
   - Use `ojp-server` shaded JAR
   - More flexible for development
   - Requires base image selection

### Q5: Configuration Approach
**Answer**: Hybrid - Environment variables + fluent API

**Why**:
- OJP server already uses environment variables
- Fluent API provides better developer experience
- Environment variables work well with Docker

### Q6: Health Check Implementation
**Answer**: Use gRPC health check protocol

OJP Server already implements gRPC health service. TestContainer should:
```java
waitingFor(Wait.forHealthcheck())
// OR
waitingFor(Wait.forLogMessage(".*OJP gRPC Server started successfully.*", 1))
```

### Q7: Network Configuration
**Answer**: Support both standalone and networked modes

- Default: No network (standalone)
- Advanced: Support Testcontainers Network for multi-container tests

## Implementation Roadmap

### Phase 1: MVP (Minimum Viable Product)
- [ ] Create `ojp-testcontainers` module
- [ ] Implement `OJPContainer` class
- [ ] Basic database configuration support
- [ ] Health check implementation
- [ ] Basic documentation
- [ ] Integration tests with H2
- [ ] README with usage examples

### Phase 2: Enhanced Features
- [ ] Multi-database configuration support
- [ ] Network integration examples
- [ ] PostgreSQL integration test
- [ ] MySQL integration test
- [ ] Advanced configuration options
- [ ] Singleton/shared container patterns

### Phase 3: Production Ready
- [ ] Comprehensive Javadoc
- [ ] Performance testing
- [ ] CI/CD integration
- [ ] Maven Central publication
- [ ] Migration guide for existing tests
- [ ] Blog post / documentation

### Phase 4: Advanced Features
- [ ] Telemetry/observability support
- [ ] Custom server configuration
- [ ] Support for slow query segregation feature
- [ ] Multi-node OJP setup support

## Migration Path for Existing Tests

Current pattern in `ojp-jdbc-driver` tests:
```java
// Before: Manual server startup required
mvn clean install
java -jar ojp-server/target/ojp-server-*-shaded.jar &
mvn test -pl ojp-jdbc-driver -DenableH2Tests=true
```

After TestContainer implementation:
```java
// After: Automatic server startup
@Container
static OJPContainer ojp = new OJPContainer()
    .withDatabaseConfig("h2", "jdbc:h2:mem:test", "sa", "");

@Test
void test() {
    // Server automatically started
    Connection conn = DriverManager.getConnection(
        ojp.getJdbcUrl("h2"), "sa", "");
}
```

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Docker not available in test environment | High | Document requirements clearly, provide fallback instructions |
| Image size / startup time | Medium | Use existing optimized image, implement reuse pattern |
| Version synchronization issues | Medium | Keep in same repo, automated version bumping |
| Configuration complexity | Low | Start simple, add features incrementally |
| Network configuration confusion | Medium | Provide clear examples for both standalone and networked modes |

## Success Criteria

1. ✅ JAR published to Maven Central
2. ✅ Users can add single dependency and use OJP in tests
3. ✅ No manual server startup required
4. ✅ Documentation is clear and examples work
5. ✅ Compatible with JUnit 5 and TestContainers best practices
6. ✅ Supports common use cases (H2, PostgreSQL, MySQL)

## Open Questions for Discussion

1. **Naming**: Should it be `ojp-testcontainers` or `ojp-testcontainer` (singular)?
   - Recommendation: `ojp-testcontainers` (matches TestContainers convention)

2. **Package name**: `org.openjproxy.testcontainers` or `org.testcontainers.ojp`?
   - Recommendation: `org.openjproxy.testcontainers` (maintains project ownership)

3. **Should we support building OJP from source in the container?**
   - Recommendation: No for MVP, use existing Docker image

4. **Should we include database drivers in the testcontainer module?**
   - Recommendation: No, keep them optional/test-scoped

5. **Version strategy**: Same version as parent or independent?
   - Recommendation: Same version (release together)

## 8. Custom TestContainers for Proprietary Databases

### Overview

Due to licensing restrictions, the published `ojp-testcontainers` Maven artifact **cannot include** pre-built TestContainers for proprietary databases (Oracle, SQL Server, DB2). However, developers can easily create their own TestContainer implementations following the patterns documented here.

### Why Custom Implementations are Needed

**Licensing Restrictions**:
- Proprietary database containers require accepting specific license agreements
- These licenses cannot be automatically accepted in a published library
- Maven Central policies prohibit redistributing proprietary database drivers

**Benefits of Custom Implementation**:
- ✅ Full control over database version and configuration
- ✅ Can use specific JDBC driver versions required by your project
- ✅ Can customize database settings for your use case
- ✅ Compliant with database vendor licensing requirements

### Creating a Custom OJP TestContainer

#### Step 1: Add Dependencies to Your Test Scope

Add the OJP TestContainer dependency and the specific database container you need:

```xml
<!-- pom.xml -->
<dependencies>
    <!-- Published OJP TestContainer (for OJP server) -->
    <dependency>
        <groupId>org.openjproxy</groupId>
        <artifactId>ojp-testcontainers</artifactId>
        <version>0.3.1-snapshot</version>
        <scope>test</scope>
    </dependency>
    
    <!-- For Oracle Database -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>oracle-xe</artifactId>
        <version>1.20.4</version>
        <scope>test</scope>
    </dependency>
    
    <!-- Oracle JDBC Driver -->
    <dependency>
        <groupId>com.oracle.database.jdbc</groupId>
        <artifactId>ojdbc11</artifactId>
        <version>23.3.0.23.09</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

#### Step 2: Create a Custom TestContainer Class

Create a utility class in your test source directory:

```java
// src/test/java/com/mycompany/testutil/OJPWithOracleTestContainer.java
package com.mycompany.testutil;

import org.openjproxy.testcontainers.OJPContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.lifecycle.Startables;

import java.util.stream.Stream;

/**
 * Custom TestContainer setup that combines OJP with Oracle Database.
 * This is a local implementation due to Oracle licensing restrictions.
 */
public class OJPWithOracleTestContainer {
    
    private static Network network;
    private static OracleContainer oracleContainer;
    private static OJPContainer ojpContainer;
    private static boolean initialized = false;
    
    /**
     * Initialize and start both Oracle and OJP containers.
     * This method is idempotent - safe to call multiple times.
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        
        // Create shared network
        network = Network.newNetwork();
        
        // Start Oracle container
        oracleContainer = new OracleContainer("gvenzl/oracle-xe:21-slim")
            .withNetwork(network)
            .withNetworkAliases("oracle-db")
            .withReuse(true);  // Optional: reuse container across test runs
        
        // Start OJP container configured to connect to Oracle
        ojpContainer = new OJPContainer()
            .withNetwork(network)
            .dependsOn(oracleContainer)
            .withDatabaseConfig("oracle", 
                "jdbc:oracle:thin:@oracle-db:1521/XEPDB1",
                oracleContainer.getUsername(),
                oracleContainer.getPassword())
            .withReuse(true);  // Optional: reuse container across test runs
        
        // Start both containers in parallel
        Startables.deepStart(Stream.of(oracleContainer, ojpContainer))
            .join();
        
        initialized = true;
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (ojpContainer != null) {
                ojpContainer.stop();
            }
            if (oracleContainer != null) {
                oracleContainer.stop();
            }
            if (network != null) {
                network.close();
            }
        }));
    }
    
    /**
     * Get OJP JDBC URL for Oracle database.
     */
    public static String getOJPJdbcUrl() {
        initialize();
        return ojpContainer.getJdbcUrl("oracle");
    }
    
    /**
     * Get direct Oracle JDBC URL (bypassing OJP).
     */
    public static String getDirectOracleUrl() {
        initialize();
        return oracleContainer.getJdbcUrl();
    }
    
    /**
     * Get Oracle username.
     */
    public static String getUsername() {
        initialize();
        return oracleContainer.getUsername();
    }
    
    /**
     * Get Oracle password.
     */
    public static String getPassword() {
        initialize();
        return oracleContainer.getPassword();
    }
}
```

#### Step 3: Use in Your Tests

```java
import com.mycompany.testutil.OJPWithOracleTestContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleIntegrationTest {
    
    @BeforeAll
    static void setup() {
        // Initialize containers (happens once for all tests)
        OJPWithOracleTestContainer.initialize();
    }
    
    @Test
    void testOracleViaOJP() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                OJPWithOracleTestContainer.getOJPJdbcUrl(),
                OJPWithOracleTestContainer.getUsername(),
                OJPWithOracleTestContainer.getPassword())) {
            
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT 1 FROM DUAL");
            assertTrue(rs.next());
        }
    }
}
```

### Examples for Different Proprietary Databases

#### SQL Server Example

```java
package com.mycompany.testutil;

import org.openjproxy.testcontainers.OJPContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.Network;

public class OJPWithSQLServerTestContainer {
    
    private static Network network;
    private static MSSQLServerContainer<?> sqlServerContainer;
    private static OJPContainer ojpContainer;
    private static boolean initialized = false;
    
    public static synchronized void initialize() {
        if (initialized) return;
        
        network = Network.newNetwork();
        
        sqlServerContainer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .withNetwork(network)
            .withNetworkAliases("sqlserver-db")
            .acceptLicense();
        
        ojpContainer = new OJPContainer()
            .withNetwork(network)
            .dependsOn(sqlServerContainer)
            .withDatabaseConfig("sqlserver",
                sqlServerContainer.getJdbcUrl(),
                sqlServerContainer.getUsername(),
                sqlServerContainer.getPassword());
        
        sqlServerContainer.start();
        ojpContainer.start();
        
        initialized = true;
    }
    
    public static String getOJPJdbcUrl() {
        initialize();
        return ojpContainer.getJdbcUrl("sqlserver");
    }
}
```

#### DB2 Example

```java
package com.mycompany.testutil;

import org.openjproxy.testcontainers.OJPContainer;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.containers.Network;

public class OJPWithDb2TestContainer {
    
    private static Network network;
    private static Db2Container db2Container;
    private static OJPContainer ojpContainer;
    private static boolean initialized = false;
    
    public static synchronized void initialize() {
        if (initialized) return;
        
        network = Network.newNetwork();
        
        db2Container = new Db2Container("icr.io/db2_community/db2:11.5.9.0")
            .withNetwork(network)
            .withNetworkAliases("db2-db")
            .acceptLicense();
        
        ojpContainer = new OJPContainer()
            .withNetwork(network)
            .dependsOn(db2Container)
            .withDatabaseConfig("db2",
                db2Container.getJdbcUrl(),
                db2Container.getUsername(),
                db2Container.getPassword());
        
        db2Container.start();
        ojpContainer.start();
        
        initialized = true;
    }
    
    public static String getOJPJdbcUrl() {
        initialize();
        return ojpContainer.getJdbcUrl("db2");
    }
}
```

### Testing with Specific JDBC Driver Versions

To test with exact JDBC driver versions:

```xml
<dependencies>
    <!-- Specify exact driver version -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.3</version>  <!-- Your specific version -->
        <scope>test</scope>
    </dependency>
</dependencies>
```

Then configure your test:

```java
@Test
void testSpecificDriverVersion() throws Exception {
    // The JDBC driver version in your classpath will be used
    // OJP will proxy connections using this specific driver version
    try (Connection conn = DriverManager.getConnection(
            ojpContainer.getJdbcUrl("postgres"), "user", "pass")) {
        
        DatabaseMetaData meta = conn.getMetaData();
        System.out.println("Driver version: " + meta.getDriverVersion());
        
        // Your test code
    }
}
```

### Best Practices for Custom TestContainers

1. **Create Once, Reuse**: Use singleton pattern to share containers across tests
2. **Use Networks**: Connect database and OJP containers via TestContainers Network
3. **Container Reuse**: Enable `.withReuse(true)` for faster test iterations
4. **Proper Cleanup**: Register shutdown hooks to clean up resources
5. **Documentation**: Document your custom setup in your project's README
6. **Version Control**: Commit your custom TestContainer classes to version control
7. **Team Sharing**: Share custom implementations across your team via internal repositories

### Advanced: Using with Different Database Versions

You can test against multiple database versions:

```java
public class OJPWithMultiplePostgresVersions {
    
    public static OJPContainer createWithPostgres(String postgresVersion) {
        Network network = Network.newNetwork();
        
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                "postgres:" + postgresVersion)
            .withNetwork(network)
            .withNetworkAliases("postgres-db");
        
        OJPContainer ojp = new OJPContainer()
            .withNetwork(network)
            .dependsOn(postgres)
            .withDatabaseConfig("postgres",
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
        
        postgres.start();
        ojp.start();
        
        return ojp;
    }
}

// In your test
@ParameterizedTest
@ValueSource(strings = {"12", "13", "14", "15", "16"})
void testAcrossPostgresVersions(String version) throws Exception {
    OJPContainer ojp = OJPWithMultiplePostgresVersions.createWithPostgres(version);
    
    try (Connection conn = DriverManager.getConnection(
            ojp.getJdbcUrl("postgres"), "test", "test")) {
        // Test against specific version
    } finally {
        ojp.stop();
    }
}
```

### Summary: Licensing Approach

| Database Type | Published in ojp-testcontainers | Custom Implementation Required |
|--------------|--------------------------------|-------------------------------|
| PostgreSQL | ✅ Yes | ❌ No (use published artifact) |
| MySQL/MariaDB | ✅ Yes | ❌ No (use published artifact) |
| H2 | ✅ Yes | ❌ No (use published artifact) |
| Oracle | ❌ No | ✅ Yes (create custom as shown above) |
| SQL Server | ❌ No | ✅ Yes (create custom as shown above) |
| DB2 | ❌ No | ✅ Yes (create custom as shown above) |

This approach ensures:
- ✅ Compliance with all database licensing requirements
- ✅ Maven Central publication is legally sound
- ✅ Developers have full flexibility for proprietary databases
- ✅ Documentation provides clear guidance for all scenarios

## References

- TestContainers Documentation: https://www.testcontainers.org/
- Existing SQL Server TestContainer: `ojp-jdbc-driver/src/test/java/openjproxy/jdbc/testutil/SQLServerTestContainer.java`
- OJP Server Main: `ojp-server/src/main/java/org/openjproxy/grpc/server/GrpcServer.java`
- OJP Server Configuration: `ojp-server/src/main/java/org/openjproxy/grpc/server/ServerConfiguration.java`
- Maven Central Requirements: https://central.sonatype.org/publish/requirements/

## Next Steps

1. Review this analysis with maintainers
2. Address open questions
3. Create GitHub issue for tracking
4. Implement Phase 1 (MVP)
5. Iterate based on feedback

---

**Document Version**: 1.0  
**Date**: 2025-12-17  
**Author**: GitHub Copilot Analysis  
**Status**: Draft for Review
