# OJP TestContainer - Executive Summary & Key Decisions

## Quick Summary

Creating an OJP TestContainer is **highly feasible and recommended**. The project already uses TestContainers for SQL Server tests, has a Docker image available, and follows patterns that make this a natural extension.

## Key Recommendations

### 1. Repository Location: **Same Repository** ✅

Create a new module `ojp-testcontainers` in the existing repository.

**Why?**
- Version synchronization with OJP server
- Single release process
- Easier dependency management
- Follows existing multi-module pattern
- Single source for issues and contributions

**Module structure**:
```
ojp/
├── ojp-server/
├── ojp-jdbc-driver/
├── ojp-grpc-commons/
└── ojp-testcontainers/  ← NEW MODULE
```

### 2. Implementation Approach: **Docker Image Based** ✅

Use the existing Docker image `rrobetti/ojp:0.3.1-snapshot` by default.

**Why?**
- Image already exists and is tested
- Fast startup time
- Matches production usage
- Simpler implementation

**Important Licensing Note**: The published Maven Central artifact will only include pre-configured support for **open-source databases** (PostgreSQL, MySQL, MariaDB, H2). For proprietary databases (Oracle, SQL Server, DB2), developers can create custom TestContainer implementations following documented patterns. See the full analysis for detailed guidance.

### 3. Target Java Version: **Java 11** ✅

**Why?**
- Maximum compatibility with test projects
- Matches `ojp-jdbc-driver` (Java 11)
- OJP server runs in Docker (its Java 21 requirement is internal)

### 4. Configuration Strategy: **Fluent API + Environment Variables** ✅

```java
OJPContainer ojp = new OJPContainer()
    .withDatabaseConfig("mydb", "jdbc:postgresql://...", "user", "pass")
    .withNetworkMode(network);
```

**Why?**
- Better developer experience
- OJP server already uses environment variables
- Follows TestContainers conventions

## Key Questions Answered

### Q: Should this be a separate repository or a module?
**A: Module in the same repository** (`ojp-testcontainers`)

Keeping it in the same repo ensures version sync and simplifies releases.

### Q: What's the minimal implementation?
**A: Core features**:
1. `OJPContainer` class extending `GenericContainer`
2. Database configuration method (`withDatabaseConfig()`)
3. JDBC URL generation (`getJdbcUrl()`)
4. Health check implementation
5. Basic documentation and examples

### Q: How complex is the configuration?
**A: Start simple, then enhance**:

**Phase 1 (MVP)**:
- Basic database configuration
- Single database support
- Default OJP server settings

**Phase 2**:
- Multiple databases
- Network configuration
- Advanced server settings

### Q: What about Maven Central publication?
**A: Use existing infrastructure**:
- Parent POM already configured with Maven Central plugin
- Just need to add sources and javadoc plugins (already done in other modules)
- Follow same pattern as `ojp-jdbc-driver`

### Q: What testing strategy?
**A: Multi-layered**:
1. Unit tests for container configuration
2. Integration tests with H2 (embedded)
3. Integration tests with PostgreSQL container
4. Integration tests with MySQL container

### Q: How do users consume this?
**A: Single dependency**:

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-testcontainers</artifactId>
    <version>0.3.1-snapshot</version>
    <scope>test</scope>
</dependency>
```

## Usage Example (MVP)

```java
import org.openjproxy.testcontainers.OJPContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.Test;

@Testcontainers
class MyApplicationTest {
    
    @Container
    static OJPContainer ojp = new OJPContainer();
    
    @Test
    void testDatabaseAccess() throws SQLException {
        // Build OJP JDBC URL - database config is in the URL
        String jdbcUrl = ojp.buildJdbcUrl("jdbc:postgresql://localhost:5432/test");
        
        try (Connection conn = DriverManager.getConnection(
                jdbcUrl, "user", "password")) {
            // Your test code - database access goes through OJP
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT 1");
            assertTrue(rs.next());
        }
    }
}
```

## Benefits

1. **Simplified Testing**: No manual server startup
2. **Isolation**: Each test suite can have its own OJP instance
3. **CI/CD Ready**: Works in any environment with Docker
4. **Realistic**: Uses actual OJP Docker image
5. **Flexible**: Supports multiple databases and configurations
6. **Reusable**: Published to Maven Central for community use
7. **License Compliant**: Only open-source databases in published artifact; custom implementations available for proprietary databases
8. **Conflict-Free**: Automatic port mapping (gRPC and Prometheus) prevents conflicts in parallel tests

## Licensing Strategy

### Published to Maven Central (Open-Source Databases Only)

The `ojp-testcontainers` Maven artifact will include pre-configured support for:
- ✅ PostgreSQL
- ✅ MySQL / MariaDB
- ✅ H2
- ✅ Other open-source databases

### Custom Implementations (Proprietary Databases)

For licensing reasons, proprietary databases require custom implementations:
- ❌ Oracle Database - developers create custom TestContainer
- ❌ Microsoft SQL Server - developers create custom TestContainer
- ❌ IBM DB2 - developers create custom TestContainer

**Documentation Provided**: Complete guide on creating custom TestContainers for proprietary databases, including code examples for Oracle, SQL Server, and DB2. See [Section 8 of the full analysis](OJP_TESTCONTAINER_ANALYSIS.md#8-custom-testcontainers-for-proprietary-databases).

**Example Custom Implementation** (developers add to their test code):
```java
// src/test/java/com/mycompany/testutil/OJPWithOracleContainer.java
public class OJPWithOracleContainer {
    private static OracleContainer oracle = new OracleContainer("gvenzl/oracle-xe:21-slim")
        .withNetworkAliases("oracle-db");
    private static OJPContainer ojp = new OJPContainer()
        .withNetwork(network)
        .dependsOn(oracle);
    
    public static String getOJPJdbcUrl() {
        return ojp.buildJdbcUrl("jdbc:oracle:thin:@oracle-db:1521/XEPDB1");
    }
    // Container lifecycle methods...
}
```

## Implementation Roadmap

### Phase 1: MVP (2-3 weeks)
- [ ] Create `ojp-testcontainers` module
- [ ] Implement `OJPContainer` class
- [ ] `buildJdbcUrl()` convenience method
- [ ] Health check
- [ ] H2 integration tests
- [ ] Documentation

### Phase 2: Enhanced (1-2 weeks)
- [ ] Multi-database support
- [ ] Network integration
- [ ] PostgreSQL/MySQL examples
- [ ] Advanced configuration

### Phase 3: Publication (1 week)
- [ ] Maven Central setup
- [ ] Comprehensive docs
- [ ] Release notes
- [ ] Blog post

## Potential Challenges & Solutions

| Challenge | Solution |
|-----------|----------|
| Docker not available | Document requirements, provide manual setup fallback |
| Image size/startup time | Use optimized image, support container reuse |
| Network configuration complexity | Provide clear examples for both standalone and networked modes |
| Version drift | Keep in same repo, release together |
| Configuration complexity | Start simple (MVP), add features incrementally |

## Success Metrics

1. ✅ Published to Maven Central
2. ✅ Users can start testing with <5 lines of code
3. ✅ No manual OJP server startup needed
4. ✅ Works with common databases (H2, PostgreSQL, MySQL)
5. ✅ Clear documentation with working examples

## Risk Assessment: **LOW** 🟢

- **Technical Risk**: Low (pattern already proven with SQLServerTestContainer)
- **Maintenance Risk**: Low (part of main repository)
- **Adoption Risk**: Low (solves real pain point)
- **Implementation Risk**: Low (well-understood technology)

## Next Actions

1. **Review this analysis** with project maintainers
2. **Discuss open questions**:
   - Naming convention preferences?
   - Any specific configuration requirements?
   - Priority features for MVP?
3. **Create implementation issue** in GitHub
4. **Begin Phase 1** implementation
5. **Iterate** based on feedback

## Questions for Maintainers

1. **Module Name**: `ojp-testcontainers` or another preference?
2. **Package Name**: `org.openjproxy.testcontainers` OK?
3. **Priority**: Which databases should be supported first?
4. **Timeline**: Any release deadlines to consider?
5. **Documentation**: Any specific documentation standards to follow?
6. **Testing**: Should we migrate existing tests to use this?

## Additional Resources

- Full Analysis: [OJP_TESTCONTAINER_ANALYSIS.md](OJP_TESTCONTAINER_ANALYSIS.md)
- TestContainers Docs: https://www.testcontainers.org/
- Existing SQL Server TestContainer: `ojp-jdbc-driver/src/test/java/openjproxy/jdbc/testutil/SQLServerTestContainer.java`

---

**Recommendation**: Proceed with implementation as new module in this repository. The benefits are clear, risks are low, and it aligns well with project goals and existing patterns.
