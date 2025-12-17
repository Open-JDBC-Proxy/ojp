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
    
    private final Map<String, DatabaseConfig> databases = new HashMap<>();
    
    public OJPContainer() {
        this(DEFAULT_IMAGE_NAME + ":" + DEFAULT_TAG);
    }
    
    public OJPContainer(String dockerImageName) {
        super(DockerImageName.parse(dockerImageName));
        
        // Expose default gRPC port
        withExposedPorts(DEFAULT_GRPC_PORT);
        
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
}
```

#### Key Features to Implement

1. **Fluent Configuration API**
   - Easy database configuration
   - Optional: Circuit breaker settings
   - Optional: Connection pool settings
   - Optional: IP whitelisting (for production-like testing)

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

```java
OJPContainer ojp = new OJPContainer()
    .withTelemetryEnabled(true)
    .withPrometheusPort(9090);

// Access metrics endpoint
String metricsUrl = ojp.getMetricsUrl();
```

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
