# Integration Tests Analysis

**Date:** December 31, 2024  
**Purpose:** Comprehensive analysis of all integration tests in the OJP project, identifying which tests use TestContainers and which do not.

## Executive Summary

- **Total Integration Tests:** 30
- **Tests Using TestContainers:** 5 (SQL Server only)
- **Tests NOT Using TestContainers:** 25 (Oracle, DB2, PostgreSQL, MySQL, MariaDB, CockroachDB, H2, Multinode)

## Tests Using TestContainers

The following **5 tests** have been migrated to use TestContainers with SQL Server:

### SQL Server Integration Tests

| Test Class | Purpose | Uses TestContainers |
|-----------|---------|-------------------|
| `SQLServerBinaryStreamIntegrationTest.java` | Tests SQL Server-specific binary stream handling (VARBINARY, IMAGE types) | ✅ Yes |
| `SQLServerBlobIntegrationTest.java` | Tests SQL Server BLOB operations and large binary data | ✅ Yes |
| `SQLServerMultipleTypesIntegrationTest.java` | Tests multiple SQL Server data types | ✅ Yes |
| `SQLServerReadMultipleBlocksOfDataIntegrationTest.java` | Tests reading large result sets in SQL Server | ✅ Yes |
| `SqlServerXAIntegrationTest.java` | Tests XA distributed transactions in SQL Server | ✅ Yes |

**Implementation Details:**
- Uses `@ArgumentsSource(SQLServerConnectionProvider.class)` annotation
- Uses `@EnabledIf("openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")` for conditional execution
- Container: `mcr.microsoft.com/mssql/server:2022-latest`
- Shared singleton container across all tests for efficiency
- Automatic XA stored procedures installation
- Test user and database setup (`testuser`, `defaultdb`)

**Key Files:**
- `SQLServerTestContainer.java` - Singleton container manager
- `SQLServerConnectionProvider.java` - JUnit ArgumentsProvider for dynamic connection details

## Tests NOT Using TestContainers

The following **25 tests** currently use CSV files for connection configuration and require external database instances:

### Oracle Integration Tests (7 tests)

| Test Class | CSV File | Purpose |
|-----------|----------|---------|
| `OracleBinaryStreamIntegrationTest.java` | `oracle_connections.csv` | Tests Oracle RAW and BLOB binary stream handling |
| `OracleBlobIntegrationTest.java` | `oracle_connections.csv` | Tests Oracle BLOB operations |
| `OracleMultipleTypesIntegrationTest.java` | `oracle_connections.csv` | Tests multiple Oracle data types |
| `OracleReadMultipleBlocksOfDataIntegrationTest.java` | `oracle_connections_with_record_counts.csv` | Tests reading large result sets in Oracle |
| `OracleXAIntegrationTest.java` | `oracle_xa_connection.csv` | Tests XA distributed transactions in Oracle |
| `BlobIntegrationTest.java` (partial) | `h2_mysql_mariadb_oracle_connections.csv` | Tests BLOB operations across multiple databases |
| `BasicCrudIntegrationTest.java` (partial) | `h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv` | Tests basic CRUD operations |

**System Property:** `enableOracleTests=true/false`

### DB2 Integration Tests (4 tests)

| Test Class | CSV File | Purpose |
|-----------|----------|---------|
| `Db2BinaryStreamIntegrationTest.java` | `db2_connection.csv` | Tests DB2 binary stream handling |
| `Db2BlobIntegrationTest.java` | `db2_connection.csv` | Tests DB2 BLOB operations |
| `Db2MultipleTypesIntegrationTest.java` | `db2_connection.csv` | Tests multiple DB2 data types |
| `Db2ReadMultipleBlocksOfDataIntegrationTest.java` | `db2_connections_with_record_counts.csv` | Tests reading large result sets in DB2 |

**System Property:** `enableDb2Tests=true/false`

### PostgreSQL Integration Tests (4 tests)

| Test Class | CSV File | Purpose |
|-----------|----------|---------|
| `PostgresMultipleTypesIntegrationTest.java` | `postgres_connection.csv` | Tests multiple PostgreSQL data types |
| `PostgresXAIntegrationTest.java` | `postgres_xa_connection.csv` | Tests XA distributed transactions in PostgreSQL |
| `BinaryStreamIntegrationTest.java` (partial) | `h2_postgres_connections.csv` | Tests binary stream handling |
| `ReadMultipleBlocksOfDataIntegrationTest.java` (partial) | `h2_postgres_connections_with_record_counts.csv` | Tests reading large result sets |

**System Property:** `enablePostgresTests=true/false`

### MySQL/MariaDB Integration Tests (4 tests)

| Test Class | CSV File | Purpose |
|-----------|----------|---------|
| `MySQLMultipleTypesIntegrationTest.java` | `mysql_mariadb_connection.csv` | Tests multiple MySQL data types |
| `MySQLSpecificFeaturesIntegrationTest.java` | `mysql_mariadb_connection.csv` | Tests MySQL-specific features |
| `BlobIntegrationTest.java` (partial) | `h2_mysql_mariadb_oracle_connections.csv` | Tests BLOB operations |
| `BasicCrudIntegrationTest.java` (partial) | `h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv` | Tests basic CRUD operations |

**System Properties:** 
- `enableMySQLTests=true/false`
- `enableMariaDBTests=true/false`

### CockroachDB Integration Tests (4 tests)

| Test Class | CSV File | Purpose |
|-----------|----------|---------|
| `CockroachDBBinaryStreamIntegrationTest.java` | `cockroachdb_connection.csv` | Tests CockroachDB binary stream handling |
| `CockroachDBBlobIntegrationTest.java` | `cockroachdb_connection.csv` | Tests CockroachDB BLOB operations |
| `CockroachDBMultipleTypesIntegrationTest.java` | `cockroachdb_connection.csv` | Tests multiple CockroachDB data types |
| `CockroachDBReadMultipleBlocksOfDataIntegrationTest.java` | `cockroachdb_connection.csv` | Tests reading large result sets in CockroachDB |

**System Property:** `enableCockroachDBTests=true/false`

### H2 Integration Tests (5 tests)

| Test Class | CSV File | Purpose |
|-----------|----------|---------|
| `H2MultipleTypesIntegrationTest.java` | `h2_connection.csv` | Tests multiple H2 data types |
| `BinaryStreamIntegrationTest.java` (partial) | `h2_postgres_connections.csv` | Tests binary stream handling |
| `BlobIntegrationTest.java` (partial) | `h2_mysql_mariadb_oracle_connections.csv` | Tests BLOB operations |
| `ReadMultipleBlocksOfDataIntegrationTest.java` (partial) | `h2_postgres_connections_with_record_counts.csv` | Tests reading large result sets |
| `BasicCrudIntegrationTest.java` (partial) | `h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv` | Tests basic CRUD operations |

**System Property:** `enableH2Tests=true/false`

### Multinode Integration Tests (2 tests)

| Test Class | CSV File | Purpose |
|-----------|----------|---------|
| `MultinodeIntegrationTest.java` | `multinode_connection.csv` | Tests multi-node OJP server configuration |
| `MultinodeXAIntegrationTest.java` | `multinode_connection.csv` | Tests XA transactions across multiple OJP nodes |

**Note:** These tests verify OJP multi-node functionality, not specific database features.

### Other Integration Tests (1 test)

| Test Class | Configuration | Purpose |
|-----------|--------------|---------|
| `MultiDataSourceIntegrationTest.java` | Hardcoded H2 URLs | Tests multi-datasource functionality with H2 in-memory databases |

**System Property:** `enableH2Tests=true/false` (or runs by default when no other DB tests are enabled)

## Database Coverage Summary

| Database | Total Tests | Uses TestContainers | Uses CSV Files | TestContainers Available |
|----------|-------------|-------------------|---------------|------------------------|
| SQL Server | 5 | 5 | 0 | ✅ Yes (implemented) |
| Oracle | 7 | 0 | 7 | 🔶 Possible (commercial license required) |
| DB2 | 4 | 0 | 4 | 🔶 Possible (commercial license required) |
| PostgreSQL | 4 | 0 | 4 | ✅ Yes (easy migration) |
| MySQL | 4 | 0 | 4 | ✅ Yes (easy migration) |
| MariaDB | ~2 | 0 | ~2 | ✅ Yes (easy migration) |
| CockroachDB | 4 | 0 | 4 | ✅ Yes (easy migration) |
| H2 | 5 | 0 | 5 | ⚠️ N/A (embedded, no container needed) |
| Multinode | 2 | 0 | 2 | ⚠️ Special case (tests OJP architecture) |

## Migration Priority Recommendations

### High Priority (Easy & High Value)
1. **PostgreSQL** - 4 tests, excellent TestContainers support, widely used
2. **MySQL** - 4 tests, excellent TestContainers support, widely used
3. **MariaDB** - 2-4 tests, excellent TestContainers support, MySQL compatible

### Medium Priority (Moderate Complexity)
4. **CockroachDB** - 4 tests, TestContainers support available, PostgreSQL compatible
5. **Oracle** - 7 tests, TestContainers support available but requires accepting license

### Low Priority (Special Cases)
6. **DB2** - 4 tests, TestContainers support available but requires accepting license
7. **H2** - 5 tests, embedded database, TestContainers not beneficial
8. **Multinode** - 2 tests, require special OJP server setup, not database-specific

## Current TestContainers Dependencies

From `ojp-jdbc-driver/pom.xml`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mssqlserver</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
```

## Benefits of TestContainers Migration

1. **No External Dependencies** - No need to maintain external database instances
2. **Consistency** - Same database version and configuration for all developers and CI/CD
3. **Isolation** - Each test run uses fresh, isolated database instances
4. **Speed** - Parallel test execution with isolated containers
5. **Simplicity** - Automatic container lifecycle management
6. **CI/CD Ready** - Easy integration with GitHub Actions and other CI systems
7. **Version Control** - Database version is defined in code, not documentation

## Current Limitations

### Tests Using CSV Files
- **Manual Setup Required** - Developers need to set up external databases
- **Configuration Drift** - Different developers may have different database versions/configurations
- **CI/CD Complexity** - Requires external database services or setup in CI pipelines
- **Resource Management** - Tests cannot clean up after themselves as easily
- **Documentation Overhead** - Need to maintain CSV files with connection details

### CSV Configuration Files Location
All CSV files are located in: `ojp-jdbc-driver/src/test/resources/`

Example CSV files:
- `oracle_connections.csv`
- `db2_connection.csv`
- `postgres_connection.csv`
- `mysql_mariadb_connection.csv`
- `cockroachdb_connection.csv`
- `h2_connection.csv`
- And various combined CSV files for multi-database tests

## See Also

- [TESTCONTAINERS_MIGRATION_GUIDE.md](./TESTCONTAINERS_MIGRATION_GUIDE.md) - Step-by-step guide to migrate tests to TestContainers
- [../SQLSERVER_TESTCONTAINER_GUIDE.md](../SQLSERVER_TESTCONTAINER_GUIDE.md) - SQL Server TestContainers implementation details
- [TestContainers Documentation](https://www.testcontainers.org/) - Official TestContainers documentation
