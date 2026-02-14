package org.openjproxy.grpc.server.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SqlSessionAffinityDetector.
 */
class SqlSessionAffinityDetectorTest {

    // ========== Temporary Table Detection Tests ==========

    @Test
    void testTemporaryTableDetection() {
        // Standard temporary table syntax
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TEMPORARY TABLE temp_users (id INT, name VARCHAR(100))"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TEMP TABLE temp_data (value DECIMAL(10,2))"));

        // Case insensitive
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "create temporary table temp (id int)"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TEMP TABLE temp (id INT)"));

        // With leading whitespace
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "  CREATE TEMPORARY TABLE temp (id INT)"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "\t\nCREATE TEMP TABLE temp (id INT)"));
    }

    @Test
    void testGlobalTemporaryTableDetection() {
        // Oracle syntax
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE GLOBAL TEMPORARY TABLE temp_session (user_id INT)"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE GLOBAL TEMP TABLE temp_data (data VARCHAR(100))"));
    }

    @Test
    void testLocalTemporaryTableDetection() {
        // PostgreSQL and H2 syntax
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE LOCAL TEMPORARY TABLE temp_calc (result DOUBLE)"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE LOCAL TEMP TABLE temp_results (id INT)"));
    }

    @Test
    void testSqlServerTempTableDetection() {
        // SQL Server local temp table (single #)
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TABLE #temp_users (id INT, name VARCHAR(100))"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TABLE #t (value INT)"));

        // SQL Server global temp table (##) should NOT require session affinity
        // because it's accessible across sessions
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TABLE ##global_temp (id INT)"));
    }

    @Test
    void testDb2DeclareGlobalTemporaryTableDetection() {
        // DB2 syntax for declaring global temporary tables
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "DECLARE GLOBAL TEMPORARY TABLE temp_session (id INT, value VARCHAR(100)) ON COMMIT PRESERVE ROWS"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "DECLARE GLOBAL TEMPORARY TABLE temp_data (col1 INT) ON COMMIT DELETE ROWS"));

        // Case insensitive
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "declare global temporary table temp (id int)"));

        // With leading whitespace
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "  DECLARE GLOBAL TEMPORARY TABLE temp (id INT)"));
    }

    @Test
    void testRegularTableNotDetected() {
        // Regular tables should NOT trigger session affinity
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TABLE users (id INT, name VARCHAR(100))"));

        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TABLE products (sku VARCHAR(50), price DECIMAL(10,2))"));
    }

    // ========== Session Variable Detection Tests ==========

    @Test
    void testMySQLSessionVariableDetection() {
        // MySQL user variables
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET @user_id = 12345"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET @var = 'test value'"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "set @counter = @counter + 1"));

        // MySQL SESSION variables
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET SESSION sql_mode = 'STRICT_TRANS_TABLES'"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET SESSION autocommit = 0"));
    }

    @Test
    void testPostgreSQLSessionVariableDetection() {
        // PostgreSQL LOCAL variables
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET LOCAL work_mem = '4GB'"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET LOCAL search_path = myschema, public"));

        // PostgreSQL SESSION variables
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET SESSION statement_timeout = '60s'"));
    }

    @Test
    void testSessionVariableWithWhitespace() {
        // Various whitespace patterns
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "  SET @var = 123"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "\tSET SESSION var = 'value'"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "\n\nSET LOCAL var = 100"));
    }

    // Note: Plain "SET variable = value" without @, SESSION, or LOCAL
    // is intentionally not detected because it could be a global setting
    // or configuration change that doesn't require session affinity
    @Test
    void testGlobalSetNotDetected() {
        // These should NOT trigger session affinity (ambiguous)
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET timezone = 'UTC'"));

        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET statement_timeout = '30s'"));
    }

    // ========== Prepared Statement Detection Tests ==========

    @Test
    void testPreparedStatementDetection() {
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "PREPARE stmt FROM 'SELECT * FROM users WHERE id = ?'"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "prepare my_statement from 'INSERT INTO logs VALUES (?, ?)'"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "  PREPARE query FROM 'SELECT 1'"));
    }

    @Test
    void testExecutePreparedNotDetected() {
        // EXECUTE doesn't create a new prepared statement, so doesn't need detection
        // (it will use the existing session that created the PREPARE)
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "EXECUTE stmt USING @user_id"));

        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "DEALLOCATE PREPARE stmt"));
    }

    // ========== Negative Cases ==========

    @Test
    void testNullAndEmptyInput() {
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(null));
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(""));
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity("   "));
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity("\t\n"));
    }

    @Test
    void testRegularDMLNotDetected() {
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SELECT * FROM users"));

        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "INSERT INTO logs VALUES (1, 'test')"));

        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "UPDATE users SET name = 'John' WHERE id = 1"));

        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "DELETE FROM temp_data WHERE id < 100"));
    }

    @Test
    void testRegularDDLNotDetected() {
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE INDEX idx_users_name ON users(name)"));

        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "ALTER TABLE users ADD COLUMN email VARCHAR(255)"));

        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "DROP TABLE old_data"));
    }

    // ========== Edge Cases ==========

    @Test
    void testKeywordsInComments() {
        // SQL with session-specific keywords in comments should NOT be detected
        // (This is a known limitation - we don't parse comments)

        // Comments at the start will NOT be detected since we only look at actual SQL patterns
        // The pattern looks for keywords not in comments
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "-- CREATE TEMPORARY TABLE\nSELECT * FROM users"));

        // Comments after the SQL also won't be detected
        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SELECT * FROM users -- CREATE TEMPORARY TABLE"));
    }

    @Test
    void testVeryLongSQL() {
        // SQL longer than 200 characters should still detect patterns at the start
        StringBuilder longSql = new StringBuilder("CREATE TEMPORARY TABLE temp_data (");
        for (int i = 0; i < 100; i++) {
            longSql.append("col").append(i).append(" VARCHAR(100), ");
        }
        longSql.append("last_col INT)");

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(longSql.toString()));
    }

    @Test
    void testSessionAffinityWithDatabaseName() {
        // Test the overloaded method that takes database name
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TEMPORARY TABLE temp (id INT)", "PostgreSQL"));

        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET @var = 123", "MySQL"));

        assertFalse(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SELECT * FROM users", "Oracle"));
    }

    // ========== Real-World Examples ==========

    @Test
    void testRealWorldTemporaryTableScenarios() {
        // PostgreSQL with ON COMMIT clause
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TEMPORARY TABLE temp_orders (order_id INT) ON COMMIT DROP"));

        // MySQL with ENGINE clause
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE TEMPORARY TABLE temp_cache (key VARCHAR(100), value TEXT) ENGINE=MEMORY"));

        // H2 with NOT LOGGED clause
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "CREATE LOCAL TEMPORARY TABLE temp_results (id INT, result DOUBLE) NOT LOGGED"));
    }

    @Test
    void testRealWorldSessionVariableScenarios() {
        // MySQL: Set multiple variables
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET @start_date = '2024-01-01', @end_date = '2024-12-31'"));

        // PostgreSQL: Set with TO keyword
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET SESSION application_name TO 'MyApp'"));

        // MySQL: Increment counter
        assertTrue(SqlSessionAffinityDetector.requiresSessionAffinity(
                "SET @row_number = @row_number + 1"));
    }
}
