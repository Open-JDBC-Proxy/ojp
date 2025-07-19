package openjdbcproxy.jdbc.testutil;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared utility class for database test setup, teardown, and validation operations.
 * Used by both H2 and Postgres integration tests to provide common functionality
 * while allowing database-specific customizations.
 */
public class TestDBUtils {

    /**
     * Enum representing different SQL syntax variations for database-specific operations.
     */
    public enum SqlSyntax {
        H2,
        POSTGRES
    }

    /**
     * Creates a basic test table for integration tests.
     * @param connection The database connection
     * @param sqlSyntax The SQL syntax to use (H2 or POSTGRES)
     * @throws SQLException if table creation fails
     */
    public static void createBasicTestTable(Connection connection, SqlSyntax sqlSyntax) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Drop table if exists
            String dropTableSql;
            if (sqlSyntax == SqlSyntax.H2) {
                dropTableSql = "DROP TABLE IF EXISTS test_table";
            } else { // PostgreSQL
                dropTableSql = "DROP TABLE IF EXISTS test_table";
            }
            statement.execute(dropTableSql);

            // Create table with appropriate syntax
            String createTableSql;
            if (sqlSyntax == SqlSyntax.H2) {
                createTableSql = "CREATE TABLE test_table (id INT PRIMARY KEY, name VARCHAR(255))";
            } else {
                createTableSql = "CREATE TABLE test_table (id INT PRIMARY KEY, name VARCHAR(255))";
            }
            statement.execute(createTableSql);

            // Insert initial test data
            statement.execute("INSERT INTO test_table (id, name) VALUES (1, 'Alice')");
            statement.execute("INSERT INTO test_table (id, name) VALUES (2, 'Bob')");
        }
    }

    /**
     * Creates a test table with auto-increment capabilities.
     * @param connection The database connection
     * @param tableName The name of the table to create
     * @param sqlSyntax The SQL syntax to use (H2 or POSTGRES)
     * @throws SQLException if table creation fails
     */
    public static void createAutoIncrementTestTable(Connection connection, String tableName, SqlSyntax sqlSyntax) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Drop table if exists
            String dropTableSql = "DROP TABLE IF EXISTS " + tableName;
            statement.execute(dropTableSql);

            // Create table with appropriate auto-increment syntax
            String createTableSql;
            if (sqlSyntax == SqlSyntax.H2) {
                createTableSql = "CREATE TABLE " + tableName + " (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255))";
            } else {
                createTableSql = "CREATE TABLE " + tableName + " (id SERIAL PRIMARY KEY, name VARCHAR(255))";
            }
            statement.execute(createTableSql);
        }
    }

    /**
     * Creates a comprehensive test table with multiple data types.
     * @param connection The database connection
     * @param sqlSyntax The SQL syntax to use (H2 or POSTGRES)
     * @throws SQLException if table creation fails
     */
    public static void createMultiTypeTestTable(Connection connection, SqlSyntax sqlSyntax) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Drop table if exists
            String dropTableSql = "DROP TABLE IF EXISTS test_table";
            statement.execute(dropTableSql);

            // Create table with appropriate data type syntax
            String createTableSql;
            if (sqlSyntax == SqlSyntax.H2) {
                createTableSql = "CREATE TABLE test_table(" +
                        " val_int INT NOT NULL," +
                        " val_varchar VARCHAR(50) NOT NULL," +
                        " val_double_precision DOUBLE PRECISION," +
                        " val_bigint BIGINT," +
                        " val_tinyint TINYINT," +
                        " val_smallint SMALLINT," +
                        " val_boolean BOOLEAN," +
                        " val_decimal DECIMAL," +
                        " val_float FLOAT(2)," +
                        " val_byte BINARY," +
                        " val_binary BINARY(4)," +
                        " val_date DATE," +
                        " val_time TIME," +
                        " val_timestamp TIMESTAMP)";
            } else {
                // PostgreSQL syntax - adjust types for PostgreSQL compatibility
                createTableSql = "CREATE TABLE test_table(" +
                        " val_int INT NOT NULL," +
                        " val_varchar VARCHAR(50) NOT NULL," +
                        " val_double_precision DOUBLE PRECISION," +
                        " val_bigint BIGINT," +
                        " val_tinyint SMALLINT," +  // PostgreSQL doesn't have TINYINT, use SMALLINT
                        " val_smallint SMALLINT," +
                        " val_boolean BOOLEAN," +
                        " val_decimal DECIMAL," +
                        " val_float REAL," +  // PostgreSQL uses REAL instead of FLOAT(2)
                        " val_byte BYTEA," +  // PostgreSQL uses BYTEA instead of BINARY
                        " val_binary BYTEA," +
                        " val_date DATE," +
                        " val_time TIME," +
                        " val_timestamp TIMESTAMP)";
            }
            statement.execute(createTableSql);
        }
    }

    /**
     * Cleans up test tables by dropping them.
     * @param connection The database connection
     * @param tableNames The names of the tables to drop
     */
    public static void cleanupTestTables(Connection connection, String... tableNames) {
        try (Statement statement = connection.createStatement()) {
            for (String tableName : tableNames) {
                try {
                    statement.execute("DROP TABLE " + tableName);
                } catch (SQLException e) {
                    // Ignore - table might not exist
                }
            }
        } catch (SQLException e) {
            // Ignore cleanup errors
        }
    }

    /**
     * Validates that all rows in a ResultSet can be read without errors.
     * This is useful for testing metadata operations that return ResultSets.
     * @param rs The ResultSet to validate
     * @throws SQLException if validation fails
     */
    public static void validateAllRows(ResultSet rs) throws SQLException {
        assertNotNull(rs, "ResultSet should not be null");
        ResultSetMetaData rsmd = rs.getMetaData();
        assertNotNull(rsmd, "ResultSetMetaData should not be null");
        int cols = rsmd.getColumnCount();
        assertTrue(cols >= 0, "Column count should be >= 0");
        
        int rowCount = 0;
        while (rs.next()) {
            for (int i = 1; i <= cols; i++) {
                // Always validate column value can be read (nulls are acceptable)
                try {
                    Object value = rs.getObject(i);
                    // If not null, assert string conversion doesn't throw
                    if (value != null) {
                        value.toString();
                    }
                } catch (Exception e) {
                    fail("Reading column " + i + " failed: " + e.getMessage());
                }
            }
            rowCount++;
            if (rowCount > 100) break; // Limit for test runtime
        }
        assertTrue(rowCount >= 0, "Row count should be >= 0");
    }

    /**
     * Executes an update statement and returns the affected row count.
     * @param connection The database connection
     * @param sql The SQL update statement
     * @return The number of affected rows
     * @throws SQLException if the update fails
     */
    public static int executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    /**
     * Checks if the current test should be skipped due to database-specific flags.
     * @param disablePostgresTests Whether Postgres tests are disabled
     * @param isPostgresTest Whether this is a Postgres test
     * @return true if the test should be skipped
     */
    public static boolean shouldSkipTest(boolean disablePostgresTests, boolean isPostgresTest) {
        return disablePostgresTests && isPostgresTest;
    }



    /**
     * Safely closes database resources without throwing exceptions.
     * @param autoCloseables The resources to close
     */
    public static void closeQuietly(AutoCloseable... autoCloseables) {
        for (AutoCloseable resource : autoCloseables) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
        }
    }
}