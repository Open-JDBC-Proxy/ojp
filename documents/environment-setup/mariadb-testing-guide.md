# MariaDB Database Testing Guide

This document explains how to set up and run MariaDB Database tests with OJP using TestContainers.

## Prerequisites

1. **Docker** - Required for TestContainers to run MariaDB locally
2. **MariaDB JDBC Driver** - Automatically included in test dependencies

## Overview

MariaDB integration tests use **TestContainers** to automatically manage the MariaDB container lifecycle. This means:
- No manual Docker commands needed
- Container starts automatically when tests run
- Container stops automatically when tests complete
- Consistent testing environment across all developers and CI

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
mvn test -DenableMariaDBTests=true -DdisablePostgresTests -DdisableMySQLTests -DdisableCockroachDBTests -Dtest="*MariaDB*,Blob*,BasicCrud*"
```

### Run MariaDB Tests Alongside Other Databases

```bash
cd ojp-jdbc-driver
mvn test -DenableMariaDBTests=true -DenableOracleTests=true -DenableSqlServerTests=true
```

## Test Infrastructure

### TestContainers

The tests use the following TestContainer configuration:
- **Image**: `mariadb:10.11`
- **Database**: `defaultdb`
- **Username**: `testuser`
- **Password**: `testpassword`
- **Port**: Randomly assigned by TestContainers

The container is managed by:
- `MariaDBTestContainer.java` - Singleton container manager
- `MariaDBConnectionProvider.java` - Provides connection parameters to tests

### Test Files

MariaDB-specific test files include:
- `MySQLMariaDBConnectionExtensiveTests.java`
- `MySQLDatabaseMetaDataExtensiveTests.java`
- `MySQLMultipleTypesIntegrationTest.java`
- `MySQLPreparedStatementExtensiveTests.java`
- `MySQLSpecificFeaturesIntegrationTest.java`
- `MySQLStatementExtensiveTests.java`

These tests are annotated with `@EnabledIf("openjproxy.jdbc.testutil.MariaDBTestContainer#isEnabled")` to ensure they only run when MariaDB tests are explicitly enabled.

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

Previously, MariaDB tests:
- Used a manually started MariaDB service in CI
- Used CSV configuration files (`mysql_mariadb_connection.csv`)
- Were controlled by `-DdisableMariaDBTests` flag (default false)

Now, MariaDB tests:
- Use TestContainers for automatic container management
- Use `MariaDBConnectionProvider` for test parameterization
- Are controlled by `-DenableMariaDBTests` flag (default false)
- Run in a dedicated CI workflow

## Additional Resources

- [MariaDB Official Documentation](https://mariadb.org/documentation/)
- [TestContainers MariaDB Module](https://www.testcontainers.org/modules/databases/mariadb/)
- [MariaDB JDBC Driver](https://mariadb.com/kb/en/about-mariadb-connector-j/)
