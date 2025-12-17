# OJP TestContainer Architecture

## Current vs. Proposed Architecture

### Current Testing Workflow (Manual)

```
┌─────────────────────────────────────────────────────────┐
│                     Developer                            │
└───────────────────────────┬─────────────────────────────┘
                            │
                            ▼
           ┌────────────────────────────────┐
           │  1. Build OJP Server           │
           │     mvn clean install          │
           └────────────┬───────────────────┘
                        │
                        ▼
           ┌────────────────────────────────┐
           │  2. Start OJP Server Manually  │
           │     java -jar ojp-server.jar   │
           └────────────┬───────────────────┘
                        │
                        ▼
           ┌────────────────────────────────┐
           │  3. Run Tests                  │
           │     mvn test                   │
           └────────────┬───────────────────┘
                        │
                        ▼
           ┌────────────────────────────────┐
           │  4. Manually Stop Server       │
           └────────────────────────────────┘

Problem: Manual steps, error-prone, slow feedback loop
```

### Proposed Testing Workflow (Automated with TestContainer)

```
┌─────────────────────────────────────────────────────────┐
│                     Developer                            │
└───────────────────────────┬─────────────────────────────┘
                            │
                            ▼
           ┌────────────────────────────────┐
           │  1. Run Tests                  │
           │     mvn test                   │
           │     (Everything automatic!)    │
           └────────────┬───────────────────┘
                        │
                        ▼
                    ✅ Done!

Benefit: Automatic, reliable, fast feedback loop
```

## Component Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                         User's Test Code                           │
│                                                                    │
│   @Testcontainers                                                 │
│   class MyTest {                                                  │
│       @Container                                                  │
│       static OJPContainer ojp = new OJPContainer()                │
│           .withDatabaseConfig("db1", ...)                         │
│   }                                                               │
└──────────────────────────────┬─────────────────────────────────────┘
                               │
                               │ Uses
                               ▼
┌────────────────────────────────────────────────────────────────────┐
│              ojp-testcontainers Module (NEW)                       │
│                                                                    │
│   ┌────────────────────────────────────────────┐                  │
│   │  OJPContainer extends GenericContainer     │                  │
│   │                                            │                  │
│   │  + withDatabaseConfig(...)                │                  │
│   │  + getJdbcUrl(...)                        │                  │
│   │  + getGrpcUrl()                           │                  │
│   │  + withServerConfiguration(...)           │                  │
│   └────────────────────────────────────────────┘                  │
│                       │                                            │
│                       │ Extends                                    │
│                       ▼                                            │
│   ┌────────────────────────────────────────────┐                  │
│   │  TestContainers GenericContainer           │                  │
│   └────────────────────────────────────────────┘                  │
└──────────────────────────────┬─────────────────────────────────────┘
                               │
                               │ Starts
                               ▼
┌────────────────────────────────────────────────────────────────────┐
│                      Docker Container                              │
│                                                                    │
│   ┌────────────────────────────────────────────────────────┐      │
│   │  rrobetti/ojp:0.3.1-snapshot                           │      │
│   │                                                        │      │
│   │  ┌──────────────────────────────────────────┐          │      │
│   │  │  OJP gRPC Server (Java 21)               │          │      │
│   │  │                                          │          │      │
│   │  │  - Port: 1059 (mapped to random port)   │          │      │
│   │  │  - Health Check: gRPC health service    │          │      │
│   │  │  - Configuration: ENV variables         │          │      │
│   │  └──────────────────────────────────────────┘          │      │
│   │                                                        │      │
│   │  ┌──────────────────────────────────────────┐          │      │
│   │  │  HikariCP Connection Pool                │          │      │
│   │  │                                          │          │      │
│   │  │  - Database 1 Pool                       │          │      │
│   │  │  - Database 2 Pool                       │          │      │
│   │  │  - ...                                   │          │      │
│   │  └──────────────────────────────────────────┘          │      │
│   └────────────────────────────────────────────────────────┘      │
│                                                                    │
└──────────────────────────────┬─────────────────────────────────────┘
                               │
                               │ Connects to
                               ▼
┌────────────────────────────────────────────────────────────────────┐
│                    Actual Database Containers                      │
│                                                                    │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐            │
│   │ PostgreSQL   │  │    MySQL     │  │     H2       │            │
│   │  Container   │  │  Container   │  │  (embedded)  │            │
│   └──────────────┘  └──────────────┘  └──────────────┘            │
└────────────────────────────────────────────────────────────────────┘
```

## Network Integration Example

```
┌─────────────────────────────────────────────────────────────────┐
│                  TestContainers Network                         │
│                                                                 │
│  ┌───────────────────┐                 ┌──────────────────┐    │
│  │  PostgreSQL       │◄────────────────│  OJP Container   │    │
│  │  Container        │   Internal      │                  │    │
│  │                   │   Network       │  Port: 1059      │    │
│  │  Alias: postgres  │   Connection    │                  │    │
│  │  Port: 5432       │                 │  Config:         │    │
│  └───────────────────┘                 │  DB URL =        │    │
│           ▲                            │  postgres:5432   │    │
│           │                            └───────┬──────────┘    │
│           │                                    │               │
│           │                                    │               │
└───────────┼────────────────────────────────────┼───────────────┘
            │                                    │
            │ Direct                             │ Via OJP
            │ Access                             │ Proxy
            │                                    │
            │                                    ▼
       ┌────┴────────────────────────────────────────┐
       │            Test Code                        │
       │                                             │
       │  Option 1: Direct access to postgres       │
       │  Option 2: Access through OJP              │
       └─────────────────────────────────────────────┘
```

## Module Dependencies

```
ojp-parent (pom.xml)
    │
    ├── ojp-grpc-commons (Java 11)
    │   └── gRPC contracts
    │
    ├── ojp-jdbc-driver (Java 11)
    │   ├── depends on: ojp-grpc-commons
    │   └── JDBC driver implementation
    │
    ├── ojp-server (Java 21)
    │   ├── depends on: ojp-grpc-commons
    │   └── produces: Docker image + shaded JAR
    │
    └── ojp-testcontainers (Java 11) ← NEW
        ├── depends on: TestContainers
        ├── uses: OJP Docker image
        ├── test depends on: ojp-jdbc-driver
        └── produces: JAR for Maven Central
```

## Data Flow in Tests

```
Test Code
    │
    │ 1. Create Connection
    ├──► DriverManager.getConnection(ojp.getJdbcUrl("db1"))
    │
    │ 2. OJP JDBC URL
    ├──► jdbc:ojp[localhost:12345]_postgresql://postgres:5432/test
    │                      │
    │                      └─► Port mapped from container
    │
    │ 3. gRPC Request
    ├──► OJP Container (localhost:12345)
    │         │
    │         │ 4. Execute SQL
    │         ├──► HikariCP Pool
    │         │         │
    │         │         │ 5. Real Connection
    │         │         ├──► PostgreSQL Container
    │         │         │
    │         │         │ 6. Results
    │         │         ◄────
    │         │
    │         │ 7. gRPC Response
    │         ◄────
    │
    │ 8. JDBC Results
    ◄────
```

## Class Hierarchy

```
java.lang.Object
    │
    └── org.testcontainers.containers.GenericContainer<SELF>
            │
            └── org.openjproxy.testcontainers.OJPContainer
                    │
                    ├── withDatabaseConfig(String name, String url, String user, String pass)
                    ├── withServerConfiguration(ServerConfigBuilder config)
                    ├── withTelemetryEnabled(boolean enabled)
                    ├── withPrometheusPort(int port)
                    ├── getJdbcUrl(String dbName)
                    ├── getGrpcUrl()
                    ├── getMetricsUrl()
                    └── getDatabaseConfig(String name)
```

## Lifecycle

```
Test Execution
    │
    │ @BeforeAll / @Container annotation
    │
    ├─► OJPContainer.start()
    │       │
    │       ├─► Pull Docker image (if needed)
    │       ├─► Start container
    │       ├─► Wait for health check
    │       └─► Container ready ✅
    │
    │ Test methods execute
    │
    ├─► @Test test1() { ... }
    ├─► @Test test2() { ... }
    ├─► @Test test3() { ... }
    │
    │ @AfterAll / automatic cleanup
    │
    └─► OJPContainer.stop()
            │
            └─► Stop and remove container
```

## Maven Central Publication Flow

```
Developer
    │
    ├─► mvn clean deploy
    │
    └─► Maven Central Publishing Plugin
            │
            ├─► Build ojp-testcontainers.jar
            ├─► Build ojp-testcontainers-sources.jar
            ├─► Build ojp-testcontainers-javadoc.jar
            ├─► Sign with GPG
            │
            └─► Upload to Maven Central
                    │
                    └─► Available for download
                            │
                            └─► Users add dependency:
                                <dependency>
                                    <groupId>org.openjproxy</groupId>
                                    <artifactId>ojp-testcontainers</artifactId>
                                    <version>0.3.1-snapshot</version>
                                    <scope>test</scope>
                                </dependency>
```

## Configuration Flow

```
User Configuration (Fluent API)
    │
    │ OJPContainer ojp = new OJPContainer()
    │     .withDatabaseConfig("db1", "jdbc:postgresql://...", "user", "pass")
    │     .withCircuitBreakerTimeout(5000)
    │     .withThreadPoolSize(50);
    │
    ▼
Environment Variables (Internal)
    │
    │ OJP_DB_DB1_URL=jdbc:postgresql://...
    │ OJP_DB_DB1_USERNAME=user
    │ OJP_DB_DB1_PASSWORD=pass
    │ OJP_CIRCUIT_BREAKER_TIMEOUT=5000
    │ OJP_THREAD_POOL_SIZE=50
    │
    ▼
OJP Server (reads from environment)
    │
    └─► ServerConfiguration.java
            │
            └─► Creates connection pools, configures settings
```

---

This architecture enables:
✅ Automatic OJP server lifecycle management
✅ Isolated test environments
✅ Easy multi-database testing
✅ CI/CD integration
✅ No manual setup required
