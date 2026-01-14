# XA Test Implementation Guide

## Overview

This guide documents the implementation of Option 1 (CSV-based XA coverage) to run all integration tests against databases with both normal and XA connections.

## Implementation Status

### Completed
✅ **CSV Files Updated** (3 files)
- `postgres_connection.csv` - Added XA variant
- `mysql_mariadb_connection.csv` - Added XA variants for both MySQL and MariaDB  
- `oracle_connections.csv` - Added XA variant

✅ **Test Classes Refactored** (2 of 27)
- `PostgresSavepointTests.java`
- `PostgresMultipleTypesIntegrationTest.java`

### In Progress
⏳ **Test Classes Remaining** (25 of 27)

**PostgreSQL (11 remaining):**
- PostgresCallableStatementTests
- PostgresConnectionExtensiveTests
- PostgresDatabaseMetaDataExtensiveTests
- PostgresMiniStressTest
- PostgresPreparedStatementExtensiveTests
- PostgresSlowQuerySegregationTest
- PostgresStatementExtensiveTests
- PostgresCall ableStatementTests
- PostgresDatabaseMetaDataExtensiveTests
- And 2 others

**MySQL/MariaDB (6 remaining):**
- MySQLDatabaseMetaDataExtensiveTests
- MySQLMariaDBConnectionExtensiveTests
- MySQLMultipleTypesIntegrationTest
- MySQLPreparedStatementExtensiveTests
- MySQLSpecificFeaturesIntegrationTest
- MySQLStatementExtensiveTests

**Oracle (11 remaining):**
- OracleBinaryStreamIntegrationTest
- OracleBlobIntegrationTest
- OracleConnectionExtensiveTests
- OracleDatabaseMetaDataExtensiveTests
- OracleMultipleTypesIntegrationTest
- OraclePreparedStatementExtensiveTests
- OracleReadMultipleBlocksOfDataIntegrationTest
- OracleResultSetMetaDataExtensiveTests
- OracleResultSetTest
- OracleSavepointTests
- OracleStatementExtensiveTests

## Refactoring Pattern

### Before (Non-XA only)
```java
public class PostgresSavepointTests {
    private Connection connection;

    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException {
        connection = DriverManager.getConnection(url, user, pwd);
        connection.setAutoCommit(false);
        // ... table setup ...
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testSavepoint(String driverClass, String url, String user, String pwd) throws SQLException {
        setUp(driverClass, url, user, pwd);
        // ... test logic ...
    }
}
```

### After (Supports both Non-XA and XA)
```java
public class PostgresSavepointTests {
    private ConnectionResult connectionResult;
    private Connection connection;

    public void setUp(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException {
        connectionResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        connection = connectionResult.getConnection();
        
        // For non-XA connections, set autocommit to false
        if (!isXA) {
            connection.setAutoCommit(false);
        }
        
        // Start transaction for table setup
        connectionResult.startXATransactionIfNeeded();
        // ... table setup ...
        connectionResult.commit();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (connectionResult != null) {
            connectionResult.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testSavepoint(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, pwd, isXA);
        
        // Start transaction for test
        connectionResult.startXATransactionIfNeeded();
        // ... test logic ...
        connectionResult.commit();
    }
}
```

## Step-by-Step Refactoring Instructions

### Step 1: Add Imports
Add `ConnectionResult` import if not already present:
```java
import openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
```

### Step 2: Update Fields
Replace:
```java
private Connection connection;
```

With:
```java
private ConnectionResult connectionResult;
private Connection connection;
```

### Step 3: Update setUp Method Signature
Add `boolean isXA` parameter:
```java
// Before
public void setUp(String driverClass, String url, String user, String password)

// After  
public void setUp(String driverClass, String url, String user, String password, boolean isXA)
```

### Step 4: Replace Connection Creation
Replace `DriverManager.getConnection()` with `TestDBUtils.createConnection()`:
```java
// Before
connection = DriverManager.getConnection(url, user, password);

// After
connectionResult = TestDBUtils.createConnection(url, user, password, isXA);
connection = connectionResult.getConnection();
```

### Step 5: Handle AutoCommit
For non-XA connections, set autocommit to false:
```java
if (!isXA) {
    connection.setAutoCommit(false);
}
```

### Step 6: Wrap DDL/DML in Transactions
Wrap table creation, inserts, updates, deletes in transactions:
```java
// Start transaction
connectionResult.startXATransactionIfNeeded();

// Execute DDL/DML
connection.createStatement().execute("CREATE TABLE ...");
// or
statement.executeUpdate("INSERT INTO ...");

// Commit transaction
connectionResult.commit();
```

**Important:** In PostgreSQL and most databases, DDL statements (CREATE, DROP, ALTER) cannot be executed within XA transactions. Handle this carefully:

```java
// For DDL, try-catch and rollback on error
try {
    connectionResult.startXATransactionIfNeeded();
    connection.createStatement().execute("DROP TABLE IF EXISTS test_table");
    connectionResult.commit();
} catch (Exception e) {
    try {
        connectionResult.rollback();
    } catch (Exception ex) {
        // Ignore rollback errors
    }
}

// For DML, normal transaction handling
connectionResult.startXATransactionIfNeeded();
statement.executeUpdate("INSERT INTO test_table VALUES (...)");
connectionResult.commit();
```

### Step 7: Update Test Method Signatures
Add `boolean isXA` parameter to all `@ParameterizedTest` methods:
```java
// Before
@ParameterizedTest
@CsvFileSource(resources = "/postgres_connection.csv")
public void testMethod(String driverClass, String url, String user, String pwd)

// After
@ParameterizedTest
@CsvFileSource(resources = "/postgres_connection.csv")
public void testMethod(String driverClass, String url, String user, String pwd, boolean isXA)
```

### Step 8: Update setUp Calls
Pass `isXA` parameter when calling setUp:
```java
// Before
setUp(driverClass, url, user, pwd);

// After
setUp(driverClass, url, user, pwd, isXA);
```

### Step 9: Update tearDown/Cleanup
Replace `connection.close()` with `connectionResult.close()`:
```java
// Before
@AfterEach
public void tearDown() throws Exception {
    if (connection != null) connection.close();
}

// After
@AfterEach
public void tearDown() throws Exception {
    if (connectionResult != null) {
        connectionResult.close();
    }
}
```

### Step 10: Update Inline Connection Usage
For tests that create local connections (not using setUp), apply the same pattern:
```java
// Before
Connection conn = DriverManager.getConnection(url, user, pwd);
try {
    // ... test logic ...
} finally {
    conn.close();
}

// After
ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
Connection conn = connResult.getConnection();

if (!isXA) {
    conn.setAutoCommit(false);
}

try {
    connResult.startXATransactionIfNeeded();
    // ... test logic ...
    connResult.commit();
} finally {
    connResult.close();
}
```

## XA-Specific Considerations

### 1. Transaction Boundaries
XA transactions must be explicitly started, ended, and committed:
- Non-XA: `connection.commit()` commits the transaction
- XA: Must call `xaResource.end()` then `xaResource.commit()`

`ConnectionResult` handles this automatically:
```java
connectionResult.commit(); // Calls XAResource.commit() for XA, Connection.commit() for non-XA
connectionResult.rollback(); // Calls XAResource.rollback() for XA, Connection.rollback() for non-XA
```

### 2. DDL in XA Transactions
Many databases (PostgreSQL, MySQL, Oracle) do NOT allow DDL statements within XA transactions:
- CREATE TABLE
- DROP TABLE
- ALTER TABLE

**Solution:** Execute DDL outside of XA transactions or in separate transactions:
```java
// Option 1: Try-catch pattern
try {
    connectionResult.startXATransactionIfNeeded();
    connection.createStatement().execute("DROP TABLE IF EXISTS test_table");
    connectionResult.commit();
} catch (Exception e) {
    // If DDL fails in XA, rollback and continue
    try {
        connectionResult.rollback();
    } catch (Exception ex) {
        // Ignore
    }
}

// Option 2: Use non-XA connection for DDL (not recommended - creates separate connection)
```

### 3. Savepoints in XA
XA transactions support savepoints, but behavior may vary by database:
- PostgreSQL: Supports savepoints in XA transactions
- MySQL: Limited savepoint support in XA  
- Oracle: Supports savepoints in XA transactions

Test savepoint functionality with both XA and non-XA connections to ensure compatibility.

### 4. AutoCommit
XA connections should NEVER have autocommit enabled:
```java
// XA connections automatically have autocommit disabled
// No need to call connection.setAutoCommit(false) for XA

// Non-XA connections may need explicit setting
if (!isXA) {
    connection.setAutoCommit(false);
}
```

### 5. Transaction Timeout
XA transactions can have timeouts. The `ConnectionResult` helper doesn't currently set timeouts, but this can be added if needed:
```java
// In XAResource
xaResource.setTransactionTimeout(300); // 5 minutes
```

## Testing Your Refactored Tests

### 1. Run with Non-XA Connections
```bash
mvn test -pl ojp-jdbc-driver -Dtest=PostgresSavepointTests -DenablePostgresTests=true
```

This should execute the test twice (non-XA and XA from CSV) and both should pass.

### 2. Run with Specific Database
```bash
# PostgreSQL
mvn test -pl ojp-jdbc-driver -Dtest=Postgres* -DenablePostgresTests=true

# MySQL
mvn test -pl ojp-jdbc-driver -Dtest=MySQL* -DenableMySQLTests=true

# Oracle
mvn test -pl ojp-jdbc-driver -Dtest=Oracle* -DenableOracleTests=true
```

### 3. Check Test Output
Verify that tests run twice per database:
```
[INFO] Running openjproxy.jdbc.PostgresSavepointTests
Testing for url -> jdbc:ojp[localhost:1059]_postgresql://localhost:5432/defaultdb (XA: false)
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
Testing for url -> jdbc:ojp[localhost:1059]_postgresql://localhost:5432/defaultdb (XA: true)
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

### 4. Debug XA Failures
If XA tests fail, check:
1. **Server logs** (`/tmp/ojp-server.log`) for XA-specific errors
2. **Database configuration**: Ensure max_prepared_transactions > 0 for PostgreSQL
3. **XA permissions**: Ensure database user has XA permissions (especially Oracle)
4. **DDL in XA**: Check if DDL statements are being executed within XA transactions

Common XA errors:
- `ERROR: prepared transactions are disabled` → PostgreSQL needs `max_prepared_transactions > 0`
- `ORA-24756: transaction does not exist` → Oracle XA transaction management issue
- `DDL not allowed in XA transaction` → Move DDL outside of XA transaction

## Automation Script

For bulk refactoring, a Python script can automate most changes:

```python
#!/usr/bin/env python3
import re
import sys

def refactor_test_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()
    
    # Skip if already refactored
    if 'ConnectionResult' in content:
        return False
    
    # Add ConnectionResult import
    if 'import openjproxy.jdbc.testutil.TestDBUtils;' in content:
        content = content.replace(
            'import openjproxy.jdbc.testutil.TestDBUtils;',
            'import openjproxy.jdbc.testutil.TestDBUtils;\nimport openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;'
        )
    
    # Replace Connection field
    content = re.sub(
        r'(\s+)private Connection connection;',
        r'\1private ConnectionResult connectionResult;\n\1private Connection connection;',
        content
    )
    
    # Update setUp signature
    content = re.sub(
        r'public void setUp\(String driverClass, String url, String user, String (password|pwd)\)',
        r'public void setUp(String driverClass, String url, String user, String \1, boolean isXA)',
        content
    )
    
    # Replace DriverManager.getConnection
    content = re.sub(
        r'connection = DriverManager\.getConnection\(url, user, (password|pwd)\);',
        r'connectionResult = TestDBUtils.createConnection(url, user, \1, isXA);\n        connection = connectionResult.getConnection();',
        content
    )
    
    # Update test method signatures
    content = re.sub(
        r'(@ParameterizedTest[^}]+?public void \w+\(String driverClass, String url, String user, String (?:password|pwd))(\))',
        r'\1, boolean isXA\2',
        content,
        flags=re.DOTALL
    )
    
    # Update setUp calls
    content = re.sub(
        r'setUp\(driverClass, url, user, (password|pwd)\);',
        r'setUp(driverClass, url, user, \1, isXA);',
        content
    )
    
    # Replace connection.close() in tearDown
    content = re.sub(
        r'if \(connection != null\) connection\.close\(\);',
        r'if (connectionResult != null) {\n            connectionResult.close();\n        }',
        content
    )
    
    with open(filepath, 'w') as f:
        f.write(content)
    
    return True

if __name__ == '__main__':
    for filepath in sys.argv[1:]:
        if refactor_test_file(filepath):
            print(f"✓ Refactored: {filepath}")
        else:
            print(f"- Skipped: {filepath}")
```

**Note:** This script handles the mechanical refactoring but MANUAL REVIEW IS REQUIRED to:
1. Add transaction boundaries (`startXATransactionIfNeeded()` / `commit()`)
2. Handle DDL statements properly (DDL cannot be in XA transactions)
3. Add `if (!isXA) { conn.setAutoCommit(false); }` where needed
4. Verify test logic still works with both XA and non-XA

## Completion Checklist

### Phase 1: CSV Files ✅
- [x] postgres_connection.csv
- [x] mysql_mariadb_connection.csv  
- [x] oracle_connections.csv

### Phase 2: PostgreSQL Tests (2 of 13)
- [x] PostgresSavepointTests
- [x] PostgresMultipleTypesIntegrationTest
- [ ] PostgresCallableStatementTests
- [ ] PostgresConnectionExtensiveTests
- [ ] PostgresDatabaseMetaDataExtensiveTests
- [ ] PostgresMiniStressTest
- [ ] PostgresPreparedStatementExtensiveTests
- [ ] PostgresSlowQuerySegregationTest
- [ ] PostgresStatementExtensiveTests
- [ ] And 4 others

### Phase 3: MySQL/MariaDB Tests (0 of 6)
- [ ] MySQLDatabaseMetaDataExtensiveTests
- [ ] MySQLMariaDBConnectionExtensiveTests
- [ ] MySQLMultipleTypesIntegrationTest
- [ ] MySQLPreparedStatementExtensiveTests
- [ ] MySQLSpecificFeaturesIntegrationTest
- [ ] MySQLStatementExtensiveTests

### Phase 4: Oracle Tests (0 of 11)
- [ ] All Oracle tests

### Phase 5: Shared Tests (0 of 1)
- [ ] BlobIntegrationTest (uses h2_mysql_mariadb_oracle_connections.csv)

## Expected Benefits

Once implementation is complete:

1. **2x Test Coverage**: Every database-specific test runs with both normal and XA connections
2. **No Code Duplication**: Single test class executes both scenarios
3. **Inline Execution**: Tests run sequentially, no parallelization needed
4. **XA Bug Detection**: Will catch XA-specific issues early
5. **Transaction Validation**: Ensures proper transaction handling for both modes

## Timeline Estimate

- **Completed**: CSV files + 2 test classes (2 hours)
- **Remaining**: 25 test classes × 30 mins each = **12.5 hours**
- **Testing & Validation**: 2-3 hours
- **Total**: **15-18 hours** of development work

## Next Steps

1. Continue refactoring test classes using the pattern above
2. Test each refactored class individually before moving to next
3. Update this guide with any issues or edge cases discovered
4. Once all tests refactored, run full test suite to validate
5. Document any database-specific XA limitations found during testing

## References

- `TestDBUtils.java` - Connection helper utilities
- `XA_SUPPORT.md` - XA transaction support documentation
- `XA_TRANSACTION_FLOW.md` - XA transaction flow details
- `BasicCrudIntegrationTest.java` - Reference implementation (already supports isXA)
