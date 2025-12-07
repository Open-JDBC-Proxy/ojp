# MariaDB Database Testing Guide

This document explains how to set up and run MariaDB Database tests with OJP using TestContainers.

## Overview

All MariaDB integration tests have been migrated to use TestContainers. This provides:
- Automatic MariaDB container management
- Consistent test environment across all environments
- No need for external MariaDB instances
- Automatic container lifecycle management

## Prerequisites

1. **Docker** - Required for TestContainers to run MariaDB locally
2. **MariaDB JDBC Driver** - Automatically included in test dependencies

## How It Works

### TestContainer Setup

1. **MariaDBTestContainer** - Singleton class that manages a shared MariaDB container
   - Located: `ojp-jdbc-driver/src/test/java/openjproxy/jdbc/testutil/MariaDBTestContainer.java`
   - Uses `mariadb:10.11` Docker image
   - Automatically starts on first test execution
   - Shared across all MariaDB tests for efficiency
   - Automatically stops when tests complete

2. **MariaDBConnectionProvider** - Custom JUnit ArgumentsProvider
   - Located: `ojp-jdbc-driver/src/test/java/openjproxy/jdbc/testutil/MariaDBConnectionProvider.java`
   - Provides dynamic connection details from the TestContainer
   - Replaces CSV-based connection configuration

### Test Changes

All MariaDB test classes have been updated:
- Changed from `@CsvFileSource` to `@ArgumentsSource(MariaDBConnectionProvider.class)`
- Added `@EnabledIf("openjproxy.jdbc.testutil.MariaDBTestContainer#isEnabled")` annotation
- Tests automatically use TestContainer when `enableMariaDBTests=true`

## Running MariaDB Tests

### Enable MariaDB Tests

MariaDB tests are **disabled by default**. To run them, use the `-DenableMariaDBTests=true` flag:

```bash
cd ojp-jdbc-driver
mvn test -DenableMariaDBTests=true
```

### Run Only MariaDB Tests

To run **only** MariaDB integration tests (excluding other databases):

```bash
cd ojp-jdbc-driver
mvn test -DenableMariaDBTests=true -DenablePostgresTests=false -DenableMySQLTests=false -DdisableCockroachDBTests=true -Dtest="MariaDB*,Blob*,BasicCrud*"
```

### Run MariaDB Tests Alongside Other Databases

```bash
cd ojp-jdbc-driver
mvn test -DenableMariaDBTests=true -DenableOracleTests=true -DenableSqlServerTests=true
```

## Test Infrastructure

### TestContainers Configuration

The tests use the following TestContainer configuration:
- **Image**: `mariadb:10.11`
- **Database**: `defaultdb`
- **Username**: `testuser`
- **Password**: `testpassword`
- **Port**: Randomly assigned by TestContainers

The container is managed by:
- `MariaDBTestContainer.java` - Singleton container manager
- `MariaDBConnectionProvider.java` - Provides connection parameters to tests

### Dependencies

#### ojp-jdbc-driver/pom.xml
```xml
<!-- MariaDB JDBC Driver - Required by TestContainers MariaDB -->
<dependency>
    <groupId>org.mariadb.jdbc</groupId>
    <artifactId>mariadb-java-client</artifactId>
    <version>3.5.1</version>
    <scope>test</scope>
</dependency>

<!-- TestContainers MariaDB -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mariadb</artifactId>
    <version>1.20.4</version>
    <scope>test</scope>
</dependency>
```

### Test Files

MariaDB-specific test files include:
- `MariaDBConnectionExtensiveTests.java`
- `MariaDBDatabaseMetaDataExtensiveTests.java`
- `MariaDBMultipleTypesIntegrationTest.java`
- `MariaDBPreparedStatementExtensiveTests.java`
- `MariaDBSpecificFeaturesIntegrationTest.java`
- `MariaDBStatementExtensiveTests.java`

These tests are annotated with `@EnabledIf("openjproxy.jdbc.testutil.MariaDBTestContainer#isEnabled")` to ensure they only run when MariaDB tests are explicitly enabled.

**Note:** These test files were duplicated from their MySQL counterparts (`MySQLMariaDBConnectionExtensiveTests.java`, etc.) to allow independent testing of MySQL and MariaDB with separate TestContainers and enable flags.

### MySQL Test Files (Separate)

MySQL test files (remain unchanged, using MySQLTestContainer):
- `MySQLMariaDBConnectionExtensiveTests.java`
- `MySQLDatabaseMetaDataExtensiveTests.java`
- `MySQLMultipleTypesIntegrationTest.java`
- `MySQLPreparedStatementExtensiveTests.java`
- `MySQLSpecificFeaturesIntegrationTest.java`
- `MySQLStatementExtensiveTests.java`

### Multi-Database Tests

Multi-database tests updated for both flags:
- `BasicCrudIntegrationTest.java` (supports both `enableMySQLTests` and `enableMariaDBTests`)
- `BlobIntegrationTest.java` (supports both `enableMySQLTests` and `enableMariaDBTests`)

## Files Removed

- `mysql_mariadb_connection.csv` - No longer needed with TestContainers

## Connection String Format

The MariaDB connection string for OJP follows this format:

```
jdbc:ojp[localhost:1059]_mariadb://localhost:RANDOM_PORT/defaultdb
```

Where:
- `localhost:1059` - OJP server address and port
- `mariadb://localhost:RANDOM_PORT` - MariaDB instance (port assigned by TestContainers)
- `defaultdb` - Target database

## Skipping MariaDB Tests

MariaDB tests are skipped by default when running:

```bash
mvn test
```

You can also explicitly disable MariaDB tests:

```bash
mvn test -DenableMariaDBTests=false
```

## CI/CD Integration

In GitHub Actions, there is a dedicated workflow `mariadb-testing.yml` that:
- Runs only MariaDB integration tests
- Uses TestContainers for database management
- Tests against multiple Java versions (11, 17, 21, 22)
- Runs automatically on push/PR to main branch

## Troubleshooting

### Docker Not Running

If you see errors about Docker not being available:

```bash
# Check if Docker is running
docker info

# Start Docker Desktop or Docker daemon
```

### Port Conflicts

TestContainers automatically assigns random available ports, so port conflicts are unlikely. If you experience issues:

```bash
# Stop any running containers
docker ps
docker stop <container-id>
```

### Memory Issues

MariaDB TestContainers requires sufficient Docker memory allocation. Ensure Docker has at least 4GB RAM allocated:

```bash
# Check Docker resource limits
docker info | grep Memory
```

## Differences from Previous Setup

### Flag Change

**Previous behavior:**
- Flag: `-DdisableMariaDBTests` (default: `false`, tests enabled)
- MariaDB tests ran by default in main CI workflow
- Used manually started MariaDB service in GitHub Actions

**New behavior:**
- Flag: `-DenableMariaDBTests` (default: `false`, tests disabled)
- MariaDB tests are disabled by default
- Must explicitly enable with `-DenableMariaDBTests=true`
- Uses TestContainers for automatic container management
- Has dedicated `mariadb-testing.yml` workflow

### Migration Summary

Previously, MariaDB tests:
- Used a manually started MariaDB service in CI
- Used CSV configuration files (`mysql_mariadb_connection.csv`)
- Were controlled by `-DdisableMariaDBTests` flag (default false)

Now, MariaDB tests:
- Use TestContainers for automatic container management
- Use `MariaDBConnectionProvider` for test parameterization
- Are controlled by `-DenableMariaDBTests` flag (default false)
- Run in a dedicated CI workflow

### Benefits

1. **No External Dependencies** - No need to set up external MariaDB
2. **Consistency** - Same environment for all developers and CI
3. **Isolation** - Each test run uses fresh containers
4. **Speed** - Shared container across tests improves performance
5. **Simplicity** - Automatic container lifecycle management
6. **Explicit Opt-in** - Tests only run when explicitly enabled

### Migration Notes

If you were previously relying on MariaDB tests running automatically:
- Update your test commands to include `-DenableMariaDBTests=true`
- Update any CI/CD pipelines to explicitly enable MariaDB tests
- Remove any manual MariaDB service configurations (no longer needed)

## Additional Resources

- [MariaDB Official Documentation](https://mariadb.org/documentation/)
- [TestContainers MariaDB Module](https://www.testcontainers.org/modules/databases/mariadb/)
- [MariaDB JDBC Driver](https://mariadb.com/kb/en/about-mariadb-connector-j/)
