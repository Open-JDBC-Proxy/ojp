# OJP TestContainer - Quick Reference Guide

> **Status**: Analysis Complete - Ready for Implementation
> 
> **Last Updated**: 2025-12-17

## 📋 Table of Contents

1. [What is this?](#what-is-this)
2. [Quick Start Guide](#quick-start-guide)
3. [Key Decisions](#key-decisions)
4. [Implementation Plan](#implementation-plan)
5. [Related Documents](#related-documents)
6. [FAQ](#faq)

---

## What is this?

A plan to create an **OJP TestContainer** - a reusable Java library that makes it trivial to run OJP server in integration tests using TestContainers.

### Problem it Solves

**Current Workflow** (Manual):
```bash
# Step 1: Build OJP
mvn clean install

# Step 2: Start OJP server manually
java -jar ojp-server/target/ojp-server-0.3.1-snapshot-shaded.jar &

# Step 3: Run tests
mvn test -pl ojp-jdbc-driver -DenableH2Tests=true

# Step 4: Kill server manually
pkill -f ojp-server
```

**Proposed Workflow** (Automatic):
```java
@Testcontainers
class MyTest {
    @Container
    static OJPContainer ojp = new OJPContainer()
        .withDatabaseConfig("db", "jdbc:postgresql://...", "user", "pass");
    
    @Test
    void test() {
        // OJP automatically started, tested, and stopped!
    }
}
```

---

## Quick Start Guide

### For End Users (After Implementation)

**Step 1**: Add dependency
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-testcontainers</artifactId>
    <version>0.3.1-snapshot</version>
    <scope>test</scope>
</dependency>
```

**Step 2**: Write test
```java
import org.openjproxy.testcontainers.OJPContainer;

@Testcontainers
class DatabaseTest {
    @Container
    static OJPContainer ojp = new OJPContainer();
    
    @Test
    void testQuery() throws SQLException {
        // Database config is in the JDBC URL
        String ojpUrl = ojp.buildJdbcUrl("jdbc:postgresql://localhost:5432/test");
        
        try (Connection conn = DriverManager.getConnection(
            ojpUrl, "user", "password")) {
            
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT 1");
            assertTrue(rs.next());
        }
    }
}
```

**Step 3**: Run
```bash
mvn test
```

That's it! ✅

---

## Key Decisions

### ✅ Decision 1: Module Location
**Create as new module in same repository**: `ojp-testcontainers/`

**Why?**
- Version synchronization
- Single release process
- Easier dependency management
- Follows existing pattern

### ✅ Decision 2: Implementation Strategy
**Use existing Docker image** (`rrobetti/ojp:0.3.1-snapshot`)

**Why?**
- Image already exists and tested
- Fast startup
- Matches production usage

### ✅ Decision 3: Java Version
**Target Java 11**

**Why?**
- Maximum compatibility
- Matches `ojp-jdbc-driver`
- OJP server runs in Docker (its Java 21 requirement is internal)

### ✅ Decision 4: Configuration Approach
**Fluent API + Environment Variables**

**Example**:
```java
OJPContainer ojp = new OJPContainer()
    .withDatabaseConfig("db1", jdbcUrl, user, pass)
    .withCircuitBreakerTimeout(5000)
    .withThreadPoolSize(50);
```

### ✅ Decision 5: Publication
**Maven Central** (using existing infrastructure)

**Why?**
- Parent POM already configured
- Same process as other modules
- Wide accessibility

### ✅ Decision 6: Database Licensing Strategy
**Published artifact: Open-source databases only**

**Included in Maven Central**:
- ✅ PostgreSQL, MySQL, MariaDB, H2

**Custom implementations** (documentation provided):
- 📝 Oracle Database
- 📝 Microsoft SQL Server
- 📝 IBM DB2

**Why?**
- Licensing compliance with Maven Central policies
- Proprietary databases require accepting licenses
- Full documentation provided for custom implementations

---

## Implementation Plan

### Phase 1: MVP (Essential Features)

**Module Setup**:
- [ ] Create `ojp-testcontainers/` directory
- [ ] Create `pom.xml` with parent reference
- [ ] Add to parent `<modules>` list
- [ ] Configure Maven Central publishing

**Core Implementation**:
- [ ] `OJPContainer` class extending `GenericContainer`
- [ ] `withDatabaseConfig()` method
- [ ] `getJdbcUrl()` method
- [ ] Health check implementation
- [ ] Basic configuration support

**Testing**:
- [ ] Unit tests for configuration
- [ ] Integration test with H2
- [ ] Integration test with PostgreSQL container

**Documentation**:
- [ ] README.md with examples
- [ ] Javadoc for public APIs
- [ ] Usage guide

**Estimated Time**: 2-3 weeks

### Phase 2: Enhanced Features

**Features**:
- [ ] Multi-database configuration support
- [ ] Network integration examples
- [ ] MySQL integration test
- [ ] Advanced server configuration
- [ ] Container reuse patterns
- [ ] Performance optimization

**Documentation**:
- [ ] Advanced usage examples
- [ ] Migration guide for existing tests
- [ ] Best practices guide

**Estimated Time**: 1-2 weeks

### Phase 3: Production Ready

**Tasks**:
- [ ] Comprehensive Javadoc
- [ ] Performance testing
- [ ] CI/CD integration
- [ ] Maven Central publication
- [ ] Release notes
- [ ] Blog post / announcement

**Estimated Time**: 1 week

**Total Estimated Time**: 4-6 weeks

---

## Related Documents

| Document | Purpose | Audience |
|----------|---------|----------|
| [OJP_TESTCONTAINER_SUMMARY.md](OJP_TESTCONTAINER_SUMMARY.md) | Executive summary and quick decisions | Product owners, architects |
| [OJP_TESTCONTAINER_ANALYSIS.md](OJP_TESTCONTAINER_ANALYSIS.md) | Full technical analysis | Developers, implementers |
| [OJP_TESTCONTAINER_ARCHITECTURE.md](OJP_TESTCONTAINER_ARCHITECTURE.md) | Architecture diagrams and data flow | Developers, architects |
| This file | Quick reference and navigation | Everyone |

---

## FAQ

### Q: Why not a separate repository?

**A**: Keeping it in the same repo ensures:
- Automatic version synchronization
- Single release process
- Easier dependency management
- Single issue tracker

### Q: What databases will be supported?

**A**: The published Maven Central artifact includes **open-source databases only** due to licensing:

**Included in published JAR**:
- ✅ H2 (embedded, no Docker needed)
- ✅ PostgreSQL
- ✅ MySQL / MariaDB

**Requires custom implementation** (full documentation provided):
- 📝 Oracle Database
- 📝 Microsoft SQL Server  
- 📝 IBM DB2

For proprietary databases, you create a simple custom TestContainer in your test code following our documented patterns. See the [full analysis](OJP_TESTCONTAINER_ANALYSIS.md#8-custom-testcontainers-for-proprietary-databases) for complete examples.

### Q: How do I use OJP with Oracle/SQL Server/DB2?

**A**: Create a custom TestContainer in your test code:

```java
// src/test/java/com/mycompany/testutil/OJPWithOracleContainer.java
public class OJPWithOracleContainer {
    private static Network network = Network.newNetwork();
    
    private static OracleContainer oracle = new OracleContainer("gvenzl/oracle-xe:21-slim")
        .withNetwork(network)
        .withNetworkAliases("oracle-db");
    
    private static OJPContainer ojp = new OJPContainer()
        .withNetwork(network)
        .dependsOn(oracle);
    
    public static void initialize() {
        oracle.start();
        ojp.start();
    }
    
    public static String getOJPJdbcUrl() {
        // Use network alias in the JDBC URL
        return ojp.buildJdbcUrl("jdbc:oracle:thin:@oracle-db:1521/XEPDB1");
    }
    
    public static String getUsername() {
        return oracle.getUsername();
    }
    
    public static String getPassword() {
        return oracle.getPassword();
    }
}
```

Full examples for Oracle, SQL Server, and DB2 are in the [technical analysis](OJP_TESTCONTAINER_ANALYSIS.md#8-custom-testcontainers-for-proprietary-databases).

### Q: Will this work in CI/CD?

**A**: Yes! TestContainers works anywhere Docker is available:
- GitHub Actions ✅
- GitLab CI ✅
- Jenkins ✅
- CircleCI ✅
- Local development ✅

### Q: What if Docker isn't available?

**A**: Tests will be skipped gracefully with clear error messages. Documentation will explain:
- How to install Docker
- Alternative manual setup
- How to disable tests

### Q: Can I use multiple databases in one test?

**A**: Yes! OJP already supports multiple databases:
```java
OJPContainer ojp = new OJPContainer()
    .withDatabaseConfig("postgres", postgresUrl, user, pass)
    .withDatabaseConfig("mysql", mysqlUrl, user, pass);

// Use both
String postgresUrl = ojp.getJdbcUrl("postgres");
String mysqlUrl = ojp.getJdbcUrl("mysql");
```

### Q: How does this relate to existing SQLServerTestContainer?

**A**: This is a more general solution:
- **SQLServerTestContainer**: Manages SQL Server database container
- **OJPContainer**: Manages OJP server container
- **Together**: Create complete integration test environment

Example combining both:
```java
@Container
static SQLServerTestContainer sqlServer = new SQLServerTestContainer();

@Container
static OJPContainer ojp = new OJPContainer()
    .dependsOn(sqlServer)
    .withDatabaseConfig("sqlserver", 
        sqlServer.getJdbcUrl(), 
        sqlServer.getUsername(), 
        sqlServer.getPassword());
```

### Q: What's the performance impact?

**A**: 
- **First test run**: ~5-10 seconds (pull image + start)
- **Subsequent runs**: ~2-3 seconds (image cached)
- **With reuse**: <1 second (container reused)

### Q: Can I customize OJP server settings?

**A**: Yes! Phase 2 will add:
```java
OJPContainer ojp = new OJPContainer()
    .withServerConfiguration(config -> config
        .withCircuitBreakerTimeout(5000)
        .withThreadPoolSize(50)
        .withMaxRequestSize(4 * 1024 * 1024))
    .withTelemetryEnabled(true);  // Enabled by default

// Access Prometheus metrics (port automatically mapped to avoid conflicts)
String metricsUrl = ojp.getPrometheusUrl();  // e.g., http://localhost:54321/metrics
int prometheusPort = ojp.getPrometheusPort();  // e.g., 54321 (random)
```

### Q: How do I migrate existing tests?

**A**: Replace manual server startup with container:

**Before**:
```java
// Requires: java -jar ojp-server.jar & running in background

@Test
void test() throws SQLException {
    Connection conn = DriverManager.getConnection(
        "jdbc:ojp[localhost:1059]_postgresql://...", "user", "pass");
    // test code
}
```

**After**:
```java
@Testcontainers
class Test {
    @Container
    static OJPContainer ojp = new OJPContainer()
        .withDatabaseConfig("db", "jdbc:postgresql://...", "user", "pass");
    
    @Test
    void test() throws SQLException {
        Connection conn = DriverManager.getConnection(
            ojp.getJdbcUrl("db"), "user", "pass");
        // same test code
    }
}
```

### Q: What Java versions are supported?

**A**: 
- **TestContainer module**: Java 11+ (for compatibility)
- **OJP Server** (in Docker): Java 21 (internal, doesn't affect users)
- **Test code**: Any version ≥ Java 11

### Q: How do I contribute?

**A**: Once implementation starts:
1. Check GitHub issues for "good first issue" tags
2. Read CONTRIBUTING.md
3. Fork, implement, test, PR
4. Follow existing code patterns

### Q: Will there be port conflicts when running multiple OJP containers?

**A**: No! Both the gRPC port (1059) and Prometheus port (9159) are automatically mapped to random available host ports by TestContainers.

```java
// Each container gets its own random ports
OJPContainer ojp1 = new OJPContainer();
OJPContainer ojp2 = new OJPContainer();

// Ports are different for each container
int grpcPort1 = ojp1.getMappedPort(1059);    // e.g., 32768
int grpcPort2 = ojp2.getMappedPort(1059);    // e.g., 32769
int prometheusPort1 = ojp1.getPrometheusPort(); // e.g., 32770
int prometheusPort2 = ojp2.getPrometheusPort(); // e.g., 32771

// Access metrics
String metricsUrl = ojp1.getPrometheusUrl();  // http://localhost:32770/metrics
```

This allows you to run multiple OJP containers in parallel without any conflicts.

---

## Implementation Checklist

Use this checklist when implementing:

### Pre-Implementation
- [ ] Review all analysis documents
- [ ] Get maintainer approval
- [ ] Create GitHub issue for tracking
- [ ] Set up development branch

### Module Setup
- [ ] Create `ojp-testcontainers/` directory structure
- [ ] Create `pom.xml` with dependencies
- [ ] Add module to parent POM
- [ ] Configure Maven plugins (sources, javadoc)

### Core Development
- [ ] Implement `OJPContainer` class
- [ ] Implement `DatabaseConfig` class
- [ ] Implement configuration methods
- [ ] Add health check
- [ ] Add logging

### Testing
- [ ] Write unit tests
- [ ] Write H2 integration test
- [ ] Write PostgreSQL integration test
- [ ] Test error scenarios
- [ ] Test concurrent usage

### Documentation
- [ ] Write README.md
- [ ] Add Javadoc to all public methods
- [ ] Create usage examples
- [ ] Document troubleshooting

### Quality Assurance
- [ ] Code review
- [ ] Test on different Java versions (11, 17, 21)
- [ ] Test on different OS (Linux, macOS, Windows)
- [ ] Performance testing
- [ ] Security review

### Publication
- [ ] Test Maven Central deployment
- [ ] Create release notes
- [ ] Update main README
- [ ] Announce on social media / blog

---

## Success Metrics

How we'll know this is successful:

1. ✅ Published to Maven Central
2. ✅ Zero manual steps to use in tests
3. ✅ Works on all major CI/CD platforms
4. ✅ Positive community feedback
5. ✅ Adopted in OJP's own integration tests
6. ✅ Documentation is clear and comprehensive
7. ✅ <5 lines of code to get started

---

## Contact & Discussion

- **GitHub Issues**: For tracking implementation
- **Pull Requests**: For code contributions
- **Discussions**: For questions and ideas
- **Discord**: For real-time chat (link in main README)

---

## Status Updates

| Date | Status | Notes |
|------|--------|-------|
| 2025-12-17 | Analysis Complete | Three documents created, ready for review |
| TBD | Approved | Awaiting maintainer approval |
| TBD | In Progress | Development started |
| TBD | Beta | Published to Maven Central (snapshot) |
| TBD | Released | Published to Maven Central (release) |

---

**Ready to proceed?** Review the documents and let's build this! 🚀
