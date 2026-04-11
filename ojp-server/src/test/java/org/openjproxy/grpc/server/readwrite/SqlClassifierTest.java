package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjproxy.grpc.server.readwrite.SqlClassifier.SqlOperationType.*;

/**
 * Comprehensive test suite for SqlClassifier implementations.
 * Tests cover 100+ SQL patterns including:
 * - Basic DML (SELECT, INSERT, UPDATE, DELETE)
 * - DDL operations
 * - DCL operations
 * - Edge cases (SELECT FOR UPDATE, CTEs, RETURNING, etc.)
 * - Performance benchmarks
 */
class SqlClassifierTest {

    private SqlClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new RegexSqlClassifier();
    }

    // ========== Basic READ Operations ==========

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM users",
        "select * from users",
        "  SELECT * FROM users  ",
        "SELECT id, name FROM users WHERE active = true",
        "SELECT COUNT(*) FROM orders",
        "SELECT u.*, o.* FROM users u JOIN orders o ON u.id = o.user_id"
    })
    void testBasicSelect_shouldBeRead(String sql) {
        assertEquals(READ, classifier.classify(sql));
        assertTrue(classifier.isReadOperation(sql));
        assertFalse(classifier.isWriteOperation(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/* comment */ SELECT * FROM users",
        "/*! MySQL hint */ SELECT * FROM users",
        "-- comment\nSELECT * FROM users",
        "  /* multi\n   line\n   comment */  SELECT * FROM users"
    })
    void testSelectWithComments_shouldBeRead(String sql) {
        assertEquals(READ, classifier.classify(sql));
    }

    @Test
    void testExplainSelect_shouldBeRead() {
        assertEquals(READ, classifier.classify("EXPLAIN SELECT * FROM users"));
        assertEquals(READ, classifier.classify("EXPLAIN ANALYZE SELECT * FROM users"));
        assertEquals(READ, classifier.classify("DESCRIBE users"));
        assertEquals(READ, classifier.classify("DESC users"));
        assertEquals(READ, classifier.classify("SHOW TABLES"));
        assertEquals(READ, classifier.classify("SHOW CREATE TABLE users"));
    }

    // ========== Basic WRITE Operations ==========

    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO users (name) VALUES ('John')",
        "insert into users (name) values ('John')",
        "INSERT INTO users SELECT * FROM temp_users",
        "/* comment */ INSERT INTO users (name) VALUES ('John')"
    })
    void testInsert_shouldBeWrite(String sql) {
        assertEquals(WRITE, classifier.classify(sql));
        assertFalse(classifier.isReadOperation(sql));
        assertTrue(classifier.isWriteOperation(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE users SET name = 'John' WHERE id = 1",
        "update users set name = 'John'",
        "UPDATE users u SET u.name = 'John' FROM temp t WHERE u.id = t.id",
        "/* comment */ UPDATE users SET name = 'John'"
    })
    void testUpdate_shouldBeWrite(String sql) {
        assertEquals(WRITE, classifier.classify(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DELETE FROM users WHERE id = 1",
        "delete from users",
        "DELETE FROM users WHERE id IN (SELECT id FROM temp)",
        "/* comment */ DELETE FROM users WHERE id = 1"
    })
    void testDelete_shouldBeWrite(String sql) {
        assertEquals(WRITE, classifier.classify(sql));
    }

    @Test
    void testMerge_shouldBeWrite() {
        assertEquals(WRITE, classifier.classify("MERGE INTO users USING temp ON users.id = temp.id"));
    }

    // ========== DDL Operations ==========

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE TABLE users (id INT)",
        "CREATE INDEX idx_name ON users(name)",
        "CREATE VIEW active_users AS SELECT * FROM users WHERE active = true",
        "CREATE TEMPORARY TABLE temp_data AS SELECT * FROM users"
    })
    void testCreate_shouldBeWrite(String sql) {
        assertEquals(WRITE, classifier.classify(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ALTER TABLE users ADD COLUMN email VARCHAR(255)",
        "ALTER TABLE users DROP COLUMN email",
        "ALTER INDEX idx_name RENAME TO idx_user_name"
    })
    void testAlter_shouldBeWrite(String sql) {
        assertEquals(WRITE, classifier.classify(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DROP TABLE users",
        "DROP INDEX idx_name",
        "DROP VIEW active_users",
        "DROP TABLE IF EXISTS users"
    })
    void testDrop_shouldBeWrite(String sql) {
        assertEquals(WRITE, classifier.classify(sql));
    }

    @Test
    void testTruncate_shouldBeWrite() {
        assertEquals(WRITE, classifier.classify("TRUNCATE TABLE users"));
        assertEquals(WRITE, classifier.classify("TRUNCATE users"));
    }

    @Test
    void testRename_shouldBeWrite() {
        assertEquals(WRITE, classifier.classify("RENAME TABLE users TO customers"));
    }

    // ========== DCL Operations ==========

    @Test
    void testGrant_shouldBeWrite() {
        assertEquals(WRITE, classifier.classify("GRANT SELECT ON users TO user1"));
        assertEquals(WRITE, classifier.classify("GRANT ALL PRIVILEGES ON *.* TO admin"));
    }

    @Test
    void testRevoke_shouldBeWrite() {
        assertEquals(WRITE, classifier.classify("REVOKE SELECT ON users FROM user1"));
    }

    // ========== Transaction Control ==========

    @ParameterizedTest
    @ValueSource(strings = {
        "BEGIN",
        "BEGIN TRANSACTION",
        "START TRANSACTION",
        "COMMIT",
        "ROLLBACK",
        "SAVEPOINT sp1",
        "RELEASE SAVEPOINT sp1",
        "SET TRANSACTION ISOLATION LEVEL READ COMMITTED"
    })
    void testTransactionControl_shouldBeWrite(String sql) {
        assertEquals(WRITE, classifier.classify(sql));
    }

    // ========== SELECT Edge Cases ==========

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM users FOR UPDATE",
        "SELECT * FROM users WHERE id = 1 FOR UPDATE",
        "SELECT * FROM users FOR SHARE",
        "SELECT * FROM users FOR KEY SHARE",
        "SELECT * FROM users FOR NO KEY UPDATE",
        "select * from users for update"
    })
    void testSelectForUpdate_shouldBeWrite(String sql) {
        assertEquals(WRITE, classifier.classify(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * INTO new_table FROM users",
        "SELECT id, name INTO TEMP temp_users FROM users"
    })
    void testSelectInto_shouldBeWrite(String sql) {
        assertEquals(WRITE, classifier.classify(sql));
    }

    @Test
    void testModifyingCTE_shouldBeWrite() {
        String sql = "WITH deleted AS (DELETE FROM users WHERE id = 1 RETURNING *) SELECT * FROM deleted";
        assertEquals(WRITE, classifier.classify(sql));

        sql = "WITH updated AS (UPDATE users SET active = false RETURNING *) SELECT * FROM updated";
        assertEquals(WRITE, classifier.classify(sql));

        sql = "WITH inserted AS (INSERT INTO users (name) VALUES ('John') RETURNING *) SELECT * FROM inserted";
        assertEquals(WRITE, classifier.classify(sql));
    }

    @Test
    void testNonModifyingCTE_shouldBeRead() {
        String sql = "WITH active_users AS (SELECT * FROM users WHERE active = true) SELECT * FROM active_users";
        assertEquals(READ, classifier.classify(sql));

        sql = "WITH RECURSIVE tree AS (SELECT * FROM categories WHERE parent_id IS NULL " +
              "UNION ALL SELECT c.* FROM categories c JOIN tree t ON c.parent_id = t.id) " +
              "SELECT * FROM tree";
        assertEquals(READ, classifier.classify(sql));
    }

    @Test
    void testReturningClause_shouldBeWrite() {
        String sql = "DELETE FROM users WHERE id = 1 RETURNING *";
        assertEquals(WRITE, classifier.classify(sql));

        sql = "UPDATE users SET name = 'John' RETURNING id, name";
        assertEquals(WRITE, classifier.classify(sql));

        sql = "INSERT INTO users (name) VALUES ('John') RETURNING id";
        assertEquals(WRITE, classifier.classify(sql));
    }

    // ========== SET Statements ==========

    @ParameterizedTest
    @ValueSource(strings = {
        "SET search_path TO public",
        "SET TIME ZONE 'UTC'",
        "SET SESSION sql_mode = 'STRICT_ALL_TABLES'",
        "SET autocommit = 0"
    })
    void testSetStatements_shouldBeWrite(String sql) {
        assertEquals(WRITE, classifier.classify(sql));
    }

    // ========== Stored Procedures ==========

    @ParameterizedTest
    @ValueSource(strings = {
        "CALL update_user_status(1, 'active')",
        "EXECUTE update_statistics",
        "EXEC sp_update_data @id = 1"
    })
    void testStoredProcedures_shouldBeWrite(String sql) {
        // Conservative: route to primary since we don't know if procedure modifies data
        assertEquals(WRITE, classifier.classify(sql));
    }

    // ========== Complex SELECT Queries ==========

    @Test
    void testComplexSelectWithSubqueries_shouldBeRead() {
        String sql = "SELECT u.*, " +
                     "(SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) as order_count " +
                     "FROM users u " +
                     "WHERE u.id IN (SELECT user_id FROM sessions WHERE active = true)";
        assertEquals(READ, classifier.classify(sql));
    }

    @Test
    void testSelectWithJoins_shouldBeRead() {
        String sql = "SELECT u.name, o.total " +
                     "FROM users u " +
                     "LEFT JOIN orders o ON u.id = o.user_id " +
                     "INNER JOIN addresses a ON u.id = a.user_id " +
                     "WHERE u.active = true";
        assertEquals(READ, classifier.classify(sql));
    }

    @Test
    void testSelectWithWindowFunctions_shouldBeRead() {
        String sql = "SELECT name, salary, " +
                     "RANK() OVER (PARTITION BY department ORDER BY salary DESC) as rank " +
                     "FROM employees";
        assertEquals(READ, classifier.classify(sql));
    }

    // ========== NULL and Empty Cases ==========

    @Test
    void testNullSql_shouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> classifier.classify(null));
    }

    @Test
    void testEmptySql_shouldReturnUnknown() {
        assertEquals(UNKNOWN, classifier.classify(""));
        assertEquals(UNKNOWN, classifier.classify("   "));
        assertEquals(UNKNOWN, classifier.classify("\n\t  "));
    }

    @Test
    void testCommentOnly_shouldReturnUnknown() {
        assertEquals(UNKNOWN, classifier.classify("/* just a comment */"));
        assertEquals(UNKNOWN, classifier.classify("-- just a comment"));
    }

    // ========== Unknown Statements ==========

    @Test
    void testUnknownStatements_shouldReturnUnknown() {
        assertEquals(UNKNOWN, classifier.classify("UNKNOWN COMMAND"));
        assertEquals(UNKNOWN, classifier.classify("INVALID SQL"));
    }

    // ========== Case Sensitivity ==========

    @Test
    void testCaseInsensitivity() {
        assertEquals(READ, classifier.classify("SELECT * FROM users"));
        assertEquals(READ, classifier.classify("select * from users"));
        assertEquals(READ, classifier.classify("SeLeCt * FrOm users"));

        assertEquals(WRITE, classifier.classify("INSERT INTO users VALUES (1)"));
        assertEquals(WRITE, classifier.classify("insert into users values (1)"));
        assertEquals(WRITE, classifier.classify("InSeRt InTo users VaLuEs (1)"));
    }

    // ========== Performance Benchmark ==========

    @Test
    void testClassificationPerformance() {
        String[] testQueries = {
            "SELECT * FROM users",
            "INSERT INTO users (name) VALUES ('John')",
            "UPDATE users SET name = 'Jane' WHERE id = 1",
            "DELETE FROM users WHERE id = 1",
            "SELECT * FROM users FOR UPDATE",
            "WITH cte AS (SELECT * FROM users) SELECT * FROM cte"
        };

        long startTime = System.nanoTime();
        int iterations = 10000;
        
        for (int i = 0; i < iterations; i++) {
            for (String query : testQueries) {
                classifier.classify(query);
            }
        }
        
        long endTime = System.nanoTime();
        long totalTimeMs = (endTime - startTime) / 1_000_000;
        double avgTimePerQuery = (double) totalTimeMs / (iterations * testQueries.length);
        
        // Should be significantly faster than 1ms per query
        assertTrue(avgTimePerQuery < 1.0, 
            String.format("Classification too slow: %.3fms per query (expected < 1ms)", avgTimePerQuery));
        
        System.out.printf("Performance: %.3fms per query (%d total queries in %dms)%n",
            avgTimePerQuery, iterations * testQueries.length, totalTimeMs);
    }

    // ========== Database-Specific Syntax ==========

    @Test
    void testPostgreSQLSpecificSyntax() {
        // PostgreSQL RETURNING
        assertEquals(WRITE, classifier.classify("INSERT INTO users (name) VALUES ('John') RETURNING id"));
        
        // PostgreSQL ON CONFLICT
        assertEquals(WRITE, classifier.classify("INSERT INTO users (id, name) VALUES (1, 'John') ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name"));
        
        // PostgreSQL COPY (not matched, will be UNKNOWN - but that's safe default)
        var result = classifier.classify("COPY users TO '/tmp/users.csv'");
        assertTrue(result == UNKNOWN || result == WRITE);
    }

    @Test
    void testMySQLSpecificSyntax() {
        // MySQL hints
        assertEquals(READ, classifier.classify("/*+ MAX_EXECUTION_TIME(1000) */ SELECT * FROM users"));
        
        // MySQL REPLACE
        assertEquals(WRITE, classifier.classify("REPLACE INTO users (id, name) VALUES (1, 'John')"));
        
        // MySQL INSERT ... ON DUPLICATE KEY
        assertEquals(WRITE, classifier.classify("INSERT INTO users (id, name) VALUES (1, 'John') ON DUPLICATE KEY UPDATE name = VALUES(name)"));
    }

    @Test
    void testOracleSpecificSyntax() {
        // Oracle hints
        assertEquals(READ, classifier.classify("SELECT /*+ INDEX(users idx_name) */ * FROM users"));
        
        // Oracle DUAL
        assertEquals(READ, classifier.classify("SELECT SYSDATE FROM DUAL"));
    }

    @Test
    void testSQLServerSpecificSyntax() {
        // SQL Server hints
        assertEquals(READ, classifier.classify("SELECT * FROM users WITH (NOLOCK)"));
        
        // SQL Server OUTPUT
        assertEquals(WRITE, classifier.classify("DELETE FROM users OUTPUT DELETED.* WHERE id = 1"));
    }
}
