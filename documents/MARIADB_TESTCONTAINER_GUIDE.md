# MariaDB Integration Tests with TestContainers

## Overview

All MariaDB integration tests have been migrated to use TestContainers. This provides:
- Automatic MariaDB container management
- Consistent test environment across all environments
- No need for external MariaDB instances

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

### Running MariaDB Tests

#### Prerequisites
- Docker must be installed and running
- OJP server must be started (tests connect through OJP proxy)

#### Local Execution
```bash
# Start OJP server first (requires Java 21+)
java -jar ojp-server/target/ojp-server-0.2.1-snapshot-shaded.jar &

# Run MariaDB tests
mvn test -pl ojp-jdbc-driver -DenableMariaDBTests=true -Dtest="*MariaDB*,Blob*,BasicCrud*"
```

#### CI/CD Workflow
The `.github/workflows/mariadb-testing.yml` workflow:
- Automatically builds the OJP server
- Starts the server in background
- Runs all MariaDB integration tests
- Uses TestContainers for MariaDB instance
- Matrix tests against Java 11, 17, 21, 22

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

## Test Files Updated

All MariaDB integration test files:
- MySQLMariaDBConnectionExtensiveTests.java
- MySQLDatabaseMetaDataExtensiveTests.java
- MySQLMultipleTypesIntegrationTest.java
- MySQLPreparedStatementExtensiveTests.java
- MySQLSpecificFeaturesIntegrationTest.java
- MySQLStatementExtensiveTests.java

Multi-database tests updated for flag change:
- BasicCrudIntegrationTest.java (changed from `disableMariaDBTests` to `enableMariaDBTests`)
- BlobIntegrationTest.java (changed from `disableMariaDBTests` to `enableMariaDBTests`)

## Files Removed

- `mysql_mariadb_connection.csv` - No longer needed with TestContainers

## Flag Change

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

## Benefits

1. **No External Dependencies** - No need to set up external MariaDB
2. **Consistency** - Same environment for all developers and CI
3. **Isolation** - Each test run uses fresh containers
4. **Speed** - Shared container across tests improves performance
5. **Simplicity** - Automatic container lifecycle management
6. **Explicit Opt-in** - Tests only run when explicitly enabled

## Migration Notes

If you were previously relying on MariaDB tests running automatically:
- Update your test commands to include `-DenableMariaDBTests=true`
- Update any CI/CD pipelines to explicitly enable MariaDB tests
- Remove any manual MariaDB service configurations (no longer needed)
