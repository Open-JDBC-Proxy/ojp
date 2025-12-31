# Integration Tests Quick Reference

Quick reference table for all integration tests in the OJP project.

## Legend
- ✅ = Uses TestContainers
- ❌ = Uses CSV files (external database required)
- 🔧 = Ready to migrate to TestContainers
- 📝 = Special case / Not applicable for TestContainers

## All Integration Tests (30 total)

| # | Test Class | Database | Uses TestContainers | Migration Status | System Property |
|---|-----------|----------|-------------------|------------------|-----------------|
| 1 | `SQLServerBinaryStreamIntegrationTest.java` | SQL Server | ✅ | Complete | `enableSqlServerTests` |
| 2 | `SQLServerBlobIntegrationTest.java` | SQL Server | ✅ | Complete | `enableSqlServerTests` |
| 3 | `SQLServerMultipleTypesIntegrationTest.java` | SQL Server | ✅ | Complete | `enableSqlServerTests` |
| 4 | `SQLServerReadMultipleBlocksOfDataIntegrationTest.java` | SQL Server | ✅ | Complete | `enableSqlServerTests` |
| 5 | `SqlServerXAIntegrationTest.java` | SQL Server | ✅ | Complete | `enableSqlServerTests` |
| 6 | `OracleBinaryStreamIntegrationTest.java` | Oracle | ❌ 🔧 | Not migrated | `enableOracleTests` |
| 7 | `OracleBlobIntegrationTest.java` | Oracle | ❌ 🔧 | Not migrated | `enableOracleTests` |
| 8 | `OracleMultipleTypesIntegrationTest.java` | Oracle | ❌ 🔧 | Not migrated | `enableOracleTests` |
| 9 | `OracleReadMultipleBlocksOfDataIntegrationTest.java` | Oracle | ❌ 🔧 | Not migrated | `enableOracleTests` |
| 10 | `OracleXAIntegrationTest.java` | Oracle | ❌ 🔧 | Not migrated | `enableOracleTests` |
| 11 | `Db2BinaryStreamIntegrationTest.java` | DB2 | ❌ 🔧 | Not migrated | `enableDb2Tests` |
| 12 | `Db2BlobIntegrationTest.java` | DB2 | ❌ 🔧 | Not migrated | `enableDb2Tests` |
| 13 | `Db2MultipleTypesIntegrationTest.java` | DB2 | ❌ 🔧 | Not migrated | `enableDb2Tests` |
| 14 | `Db2ReadMultipleBlocksOfDataIntegrationTest.java` | DB2 | ❌ 🔧 | Not migrated | `enableDb2Tests` |
| 15 | `PostgresMultipleTypesIntegrationTest.java` | PostgreSQL | ❌ 🔧 | **High Priority** | `enablePostgresTests` |
| 16 | `PostgresXAIntegrationTest.java` | PostgreSQL | ❌ 🔧 | **High Priority** | `enablePostgresTests` |
| 17 | `BinaryStreamIntegrationTest.java` | PostgreSQL/H2 | ❌ 🔧 | **High Priority** | `enablePostgresTests` / `enableH2Tests` |
| 18 | `ReadMultipleBlocksOfDataIntegrationTest.java` | PostgreSQL/H2 | ❌ 🔧 | **High Priority** | `enablePostgresTests` / `enableH2Tests` |
| 19 | `MySQLMultipleTypesIntegrationTest.java` | MySQL | ❌ 🔧 | **High Priority** | `enableMySQLTests` |
| 20 | `MySQLSpecificFeaturesIntegrationTest.java` | MySQL | ❌ 🔧 | **High Priority** | `enableMySQLTests` |
| 21 | `BlobIntegrationTest.java` | MySQL/MariaDB/Oracle/H2 | ❌ 🔧 | **High Priority** | Multiple |
| 22 | `CockroachDBBinaryStreamIntegrationTest.java` | CockroachDB | ❌ 🔧 | Medium Priority | `enableCockroachDBTests` |
| 23 | `CockroachDBBlobIntegrationTest.java` | CockroachDB | ❌ 🔧 | Medium Priority | `enableCockroachDBTests` |
| 24 | `CockroachDBMultipleTypesIntegrationTest.java` | CockroachDB | ❌ 🔧 | Medium Priority | `enableCockroachDBTests` |
| 25 | `CockroachDBReadMultipleBlocksOfDataIntegrationTest.java` | CockroachDB | ❌ 🔧 | Medium Priority | `enableCockroachDBTests` |
| 26 | `H2MultipleTypesIntegrationTest.java` | H2 | ❌ 📝 | N/A (embedded) | `enableH2Tests` |
| 27 | `BasicCrudIntegrationTest.java` | Multiple DBs | ❌ 🔧 | Partial (SQL Server done) | Multiple |
| 28 | `MultiDataSourceIntegrationTest.java` | H2 | ❌ 📝 | N/A (special case) | `enableH2Tests` |
| 29 | `MultinodeIntegrationTest.java` | Multinode | ❌ 📝 | N/A (OJP architecture test) | N/A |
| 30 | `MultinodeXAIntegrationTest.java` | Multinode | ❌ 📝 | N/A (OJP architecture test) | N/A |

## Summary by Database

| Database | Total Tests | Using TestContainers | Using CSV | Migration Ready |
|----------|-------------|---------------------|-----------|-----------------|
| **SQL Server** | 5 | 5 (100%) | 0 | ✅ Complete |
| **Oracle** | 7 | 0 (0%) | 7 | 🔧 Ready |
| **DB2** | 4 | 0 (0%) | 4 | 🔧 Ready |
| **PostgreSQL** | 4 | 0 (0%) | 4 | 🔧 **High Priority** |
| **MySQL** | 4 | 0 (0%) | 4 | 🔧 **High Priority** |
| **MariaDB** | ~2 | 0 (0%) | ~2 | 🔧 **High Priority** |
| **CockroachDB** | 4 | 0 (0%) | 4 | 🔧 Medium Priority |
| **H2** | 5 | 0 (0%) | 5 | 📝 N/A (embedded) |
| **Multinode** | 2 | 0 (0%) | 2 | 📝 N/A (special) |

## Migration Priority

### 🔴 High Priority (Should migrate next)
1. **PostgreSQL** (4 tests) - Excellent TestContainers support, widely used
2. **MySQL** (4 tests) - Excellent TestContainers support, widely used
3. **MariaDB** (~2 tests) - Excellent TestContainers support, MySQL compatible

### 🟡 Medium Priority
4. **CockroachDB** (4 tests) - Good TestContainers support, PostgreSQL compatible
5. **Oracle** (7 tests) - Good TestContainers support (Oracle Free/XE is free for testing)

### ⚪ Low Priority / Special Cases
6. **DB2** (4 tests) - TestContainers support available, requires commercial license
7. **H2** (5 tests) - Embedded database, TestContainers not beneficial
8. **Multinode** (2 tests) - Tests OJP server architecture, not database-specific

## CSV Configuration Files

All CSV files are in: `ojp-jdbc-driver/src/test/resources/`

| CSV File | Used By | Databases |
|----------|---------|-----------|
| `oracle_connections.csv` | Oracle tests | Oracle |
| `oracle_connections_with_record_counts.csv` | Oracle tests | Oracle |
| `oracle_xa_connection.csv` | Oracle XA tests | Oracle |
| `db2_connection.csv` | DB2 tests | DB2 |
| `db2_connections_with_record_counts.csv` | DB2 tests | DB2 |
| `postgres_connection.csv` | PostgreSQL tests | PostgreSQL |
| `postgres_xa_connection.csv` | PostgreSQL XA tests | PostgreSQL |
| `h2_postgres_connections.csv` | Cross-DB tests | H2, PostgreSQL |
| `h2_postgres_connections_with_record_counts.csv` | Cross-DB tests | H2, PostgreSQL |
| `mysql_mariadb_connection.csv` | MySQL/MariaDB tests | MySQL, MariaDB |
| `h2_mysql_mariadb_oracle_connections.csv` | Cross-DB tests | H2, MySQL, MariaDB, Oracle |
| `cockroachdb_connection.csv` | CockroachDB tests | CockroachDB |
| `h2_cockroachdb_connections.csv` | Cross-DB tests | H2, CockroachDB |
| `h2_connection.csv` | H2 tests | H2 |
| `sqlserver_connections.csv` | SQL Server tests (deprecated) | SQL Server |
| `sqlserver_xa_connection.csv` | SQL Server XA tests (deprecated) | SQL Server |
| `h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv` | Cross-DB tests | Multiple |
| `multinode_connection.csv` | Multinode tests | N/A |

**Note:** SQL Server CSV files are now deprecated as all SQL Server tests use TestContainers.

## Running Tests

### With TestContainers (SQL Server)
```bash
# Requires Docker running
mvn test -pl ojp-jdbc-driver -DenableSqlServerTests=true -Dtest="SQLServer*"
```

### With CSV Files (External Database Required)
```bash
# Requires external database setup
mvn test -pl ojp-jdbc-driver -DenablePostgresTests=true -Dtest="Postgres*"
mvn test -pl ojp-jdbc-driver -DenableMySQLTests=true -Dtest="MySQL*"
mvn test -pl ojp-jdbc-driver -DenableOracleTests=true -Dtest="Oracle*"
# etc.
```

## See Also

- [INTEGRATION_TESTS_ANALYSIS.md](./INTEGRATION_TESTS_ANALYSIS.md) - Detailed analysis
- [TESTCONTAINERS_MIGRATION_GUIDE.md](./TESTCONTAINERS_MIGRATION_GUIDE.md) - Step-by-step migration guide
- [../SQLSERVER_TESTCONTAINER_GUIDE.md](../SQLSERVER_TESTCONTAINER_GUIDE.md) - SQL Server reference implementation
