# OJP TestContainers

TestContainers integration for OJP (Open J Proxy), providing an easy way to run OJP server in integration tests.

## Features

- 🚀 **Zero Configuration**: Just start the container, no database pre-configuration needed
- 🔌 **Automatic Port Management**: gRPC and Prometheus ports automatically mapped to avoid conflicts
- 🐳 **Docker-based**: Uses the official OJP Docker image
- 🧪 **Test-Ready**: Integrates seamlessly with JUnit 5 and TestContainers
- 📊 **Observability**: Built-in Prometheus metrics support

## Usage

### Add Dependency

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-testcontainers</artifactId>
    <version>0.3.1-snapshot</version>
    <scope>test</scope>
</dependency>
```

### Basic Example

```java
import org.openjproxy.testcontainers.OJPContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MyIntegrationTest {
    
    @Container
    static OJPContainer ojp = new OJPContainer();
    
    @Test
    void testDatabaseAccess() throws SQLException {
        // Build OJP JDBC URL from original database URL
        String ojpUrl = ojp.buildJdbcUrl("jdbc:postgresql://localhost:5432/test");
        
        try (Connection conn = DriverManager.getConnection(ojpUrl, "user", "pass")) {
            // Your test code here
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT 1");
            assertTrue(rs.next());
        }
    }
}
```

## API Methods

- `buildJdbcUrl(String originalJdbcUrl)` - Convenience method to build OJP JDBC URLs
- `getGrpcUrl()` - Get gRPC connection string
- `getGrpcPort()` - Get mapped gRPC port (random)
- `getPrometheusUrl()` - Get Prometheus metrics URL
- `getPrometheusPort()` - Get mapped Prometheus port (random)
- `withTelemetryEnabled(boolean)` - Control telemetry (enabled by default)

## How It Works

The OJP server is a **proxy** - it doesn't need database configuration at startup. Database connection details are passed through the JDBC URL when your application connects, following the format:

```
jdbc:ojp[ojp-host:port]_original-jdbc-url
```

The `buildJdbcUrl()` method constructs this format automatically for convenience.

## Port Management

Both the **gRPC port (1059)** and **Prometheus port (9159)** are automatically mapped to random available host ports to prevent conflicts when running multiple containers in parallel.

```java
OJPContainer ojp = new OJPContainer();

// Ports are automatically mapped
String grpcUrl = ojp.getGrpcUrl();           // e.g., "localhost:32768"
String metricsUrl = ojp.getPrometheusUrl();  // e.g., "http://localhost:32769/metrics"
```

## Advanced Examples

### With PostgreSQL Container

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
        .dependsOn(postgres);
    
    @Test
    void testThroughOJP() throws SQLException {
        // Build OJP URL with the database connection details
        String ojpUrl = ojp.buildJdbcUrl(postgres.getJdbcUrl());
        
        try (Connection conn = DriverManager.getConnection(
            ojpUrl, 
            postgres.getUsername(), 
            postgres.getPassword())) {
            
            // Access PostgreSQL through OJP proxy
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT 1");
            assertTrue(rs.next());
        }
    }
}
```

### Singleton Pattern (Shared Container)

```java
public abstract class BaseOJPTest {
    
    protected static final OJPContainer OJP_CONTAINER;
    
    static {
        OJP_CONTAINER = new OJPContainer()
            .withReuse(true); // Enable container reuse
        
        OJP_CONTAINER.start();
    }
}

class MyTest extends BaseOJPTest {
    @Test
    void test() throws SQLException {
        String ojpUrl = OJP_CONTAINER.buildJdbcUrl("jdbc:h2:mem:test");
        
        try (Connection conn = DriverManager.getConnection(ojpUrl, "sa", "")) {
            // Your test code
        }
    }
}
```

## Requirements

- Java 11 or higher
- Docker
- Maven or Gradle

## License

Apache License 2.0
