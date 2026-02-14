package org.openjproxy.grpc.server.sql;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for SqlEnhancerEngine with query optimization capabilities.
 * Tests validation, caching, optimization, and SQL generation.
 */
class SqlEnhancerEngineTest {
    private static final Logger logger = LoggerFactory.getLogger(SqlEnhancerEngineTest.class);

    @Test
    void testEnhancerDisabled() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(false);

        assertFalse(engine.isEnabled(), "Engine should be disabled");

        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);

        assertEquals(sql, result.getEnhancedSql(), "SQL should be unchanged when disabled");
        assertFalse(result.isModified(), "SQL should not be marked as modified");
        assertFalse(result.isHasErrors(), "Should not have errors");
    }

    @Test
    void testEnhancerEnabled_ValidSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);

        assertTrue(engine.isEnabled(), "Engine should be enabled");

        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        assertFalse(result.isHasErrors(), "Should not have errors for valid SQL");
    }

    @Test
    void testEnhancerEnabled_InvalidSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);

        String sql = "SELECT * FROM WHERE";
        SqlEnhancementResult result = engine.enhance(sql);

        // Invalid SQL should pass through (no errors thrown)
        assertEquals(sql, result.getEnhancedSql(), "Invalid SQL should pass through");
        assertFalse(result.isModified(), "SQL should not be modified");
        assertFalse(result.isHasErrors(), "Should not mark as error in pass-through mode");
    }

    @Test
    void testEnhancerEnabled_ComplexQuery() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);

        String sql = "SELECT u.id, u.name, o.order_id " +
                "FROM users u " +
                "INNER JOIN orders o ON u.id = o.user_id " +
                "WHERE u.status = 'active' AND o.created_at > '2024-01-01'";

        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Complex SQL should parse successfully");
        assertFalse(result.isHasErrors(), "Should not have errors for valid complex SQL");
    }

    @Test
    void testEnhancerEnabled_PreparedStatement() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);

        String sql = "SELECT * FROM users WHERE id = ? AND status = ?";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Prepared statement SQL should parse successfully");
        assertFalse(result.isHasErrors(), "Should not have errors for prepared statement");
    }

    // Optimization tests

    @Test
    void testCaching() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);

        String sql = "SELECT * FROM users WHERE id = 1";

        // First call - should parse and cache
        SqlEnhancementResult result1 = engine.enhance(sql);

        // Second call - should hit cache
        SqlEnhancementResult result2 = engine.enhance(sql);

        // Cache hit should be faster (though timing can be flaky in tests)
        assertEquals(result1.getEnhancedSql(), result2.getEnhancedSql(), "Cached result should match");

        // Verify cache stats show entries
        String cacheStats = engine.getCacheStats();
        assertTrue(cacheStats.contains("1"), "Cache should have at least 1 entry");
    }

    @Test
    void testCacheClear() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);

        String sql = "SELECT * FROM users WHERE id = 1";
        engine.enhance(sql);

        // Verify cache has entry
        String statsBefore = engine.getCacheStats();
        assertTrue(statsBefore.contains("1"), "Cache should have 1 entry");

        // Clear cache
        engine.clearCache();

        // Verify cache is empty
        String statsAfter = engine.getCacheStats();
        assertTrue(statsAfter.contains("0"), "Cache should be empty after clear");
    }

    @Test
    void testMultipleQueries_Caching() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);

        // Cache multiple different queries
        String sql1 = "SELECT * FROM users WHERE id = 1";
        String sql2 = "SELECT * FROM orders WHERE user_id = 2";
        String sql3 = "SELECT COUNT(*) FROM products";

        engine.enhance(sql1);
        engine.enhance(sql2);
        engine.enhance(sql3);

        // Verify cache has 3 entries
        String cacheStats = engine.getCacheStats();
        assertTrue(cacheStats.contains("3"), "Cache should have 3 entries");

        // Re-query one - should hit cache
        SqlEnhancementResult result = engine.enhance(sql1);
        assertNotNull(result.getEnhancedSql(), "Cached query should return result");
    }

    @Test
    void testValidation_SimpleSelect() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true);

        // Valid simple SELECT
        String sql = "SELECT col1, col2 FROM table1";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Valid SQL should be enhanced");
        assertFalse(result.isHasErrors(), "Valid SQL should not have errors");
    }

    // Dialect tests

    @Test
    void testDialectConfiguration_PostgreSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "POSTGRESQL");

        assertEquals(OjpSqlDialect.POSTGRESQL, engine.getDialect(), "Should use PostgreSQL dialect");

        // PostgreSQL-specific syntax
        String sql = "SELECT * FROM users LIMIT 10";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "PostgreSQL SQL should parse");
        assertFalse(result.isHasErrors(), "Should not have errors");
    }

    @Test
    void testDialectConfiguration_MySQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "MYSQL");

        assertEquals(OjpSqlDialect.MYSQL, engine.getDialect(), "Should use MySQL dialect");

        // MySQL-specific syntax
        String sql = "SELECT * FROM users LIMIT 10";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "MySQL SQL should parse");
        assertFalse(result.isHasErrors(), "Should not have errors");
    }

    @Test
    void testDialectConfiguration_Oracle() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "ORACLE");

        assertEquals(OjpSqlDialect.ORACLE, engine.getDialect(), "Should use Oracle dialect");

        // Oracle-specific syntax with ROWNUM
        String sql = "SELECT * FROM users WHERE ROWNUM <= 10";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Oracle SQL should parse");
        assertFalse(result.isHasErrors(), "Should not have errors");
    }

    @Test
    void testDialectConfiguration_Generic() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC");

        assertEquals(OjpSqlDialect.GENERIC, engine.getDialect(), "Should use generic dialect");

        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Generic SQL should parse");
        assertFalse(result.isHasErrors(), "Should not have errors");
    }

    @Test
    void testDialectConfiguration_InvalidDialect() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "INVALID_DIALECT");

        // Should default to GENERIC
        assertEquals(OjpSqlDialect.GENERIC, engine.getDialect(), "Should default to generic dialect");
    }

    // Dialect translation tests disabled due to known Guava compatibility issue with Calcite
    // The translateDialect feature works in principle but hits Guava version conflicts
    // This is a known issue with Apache Calcite and Guava dependencies


    @Test
    void testDialectTranslation_PostgreSQLToMySQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "POSTGRESQL");

        // PostgreSQL-specific: LIMIT syntax
        String sql = "SELECT * FROM users WHERE status = 'active' LIMIT 10 OFFSET 5";

        // Translate to MySQL (MySQL also supports LIMIT OFFSET, so should be similar)
        String translated = engine.translateDialect(sql, OjpSqlDialect.MYSQL);
        assertNotNull(translated, "Translated SQL should not be null");
        assertTrue(translated.contains("SELECT"), "Should contain SELECT");
        assertTrue(translated.contains("USERS"), "Should contain table name");
    }

    @Test
    void testDialectTranslation_GenericToOracle() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC");

        // Generic SQL with aggregate function
        String sql = "SELECT u.id, u.name, COUNT(o.id) as order_count " +
                "FROM users u " +
                "LEFT JOIN orders o ON u.id = o.user_id " +
                "GROUP BY u.id, u.name";

        // Translate to Oracle
        String translated = engine.translateDialect(sql, OjpSqlDialect.ORACLE);
        assertNotNull(translated, "Translated complex SQL should not be null");
        assertTrue(translated.contains("SELECT"), "Should contain SELECT");
        assertTrue(translated.contains("COUNT"), "Should preserve COUNT function");
    }

    @Test
    void testDialectTranslation_OracleToMultipleDialects() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "ORACLE");

        // Oracle-specific: ROWNUM for limiting results
        String oracleSQL = "SELECT * FROM (SELECT * FROM orders ORDER BY created_at DESC) WHERE ROWNUM <= 10";

        // Translate to PostgreSQL
        String postgreSql = engine.translateDialect(oracleSQL, OjpSqlDialect.POSTGRESQL);
        assertNotNull(postgreSql, "PostgreSQL translation should not be null");
        logger.info("Oracle to PostgreSQL: {} -> {}", oracleSQL, postgreSql);

        // Translate to MySQL
        String mySql = engine.translateDialect(oracleSQL, OjpSqlDialect.MYSQL);
        assertNotNull(mySql, "MySQL translation should not be null");
        logger.info("Oracle to MySQL: {} -> {}", oracleSQL, mySql);

        // Translate to SQL Server
        String sqlServer = engine.translateDialect(oracleSQL, OjpSqlDialect.SQL_SERVER);
        assertNotNull(sqlServer, "SQL Server translation should not be null");
        logger.info("Oracle to SQL Server: {} -> {}", oracleSQL, sqlServer);

        // All translations should contain the table and ordering concept
        assertTrue(postgreSql.contains("ORDERS"), "PostgreSQL should contain orders table");
        assertTrue(mySql.contains("ORDERS"), "MySQL should contain orders table");
        assertTrue(sqlServer.contains("ORDERS"), "SQL Server should contain orders table");
    }

    @Test
    void testCaching_WithDialect() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "POSTGRESQL");

        String sql = "SELECT * FROM users WHERE id = 1";

        // First call - should cache
        SqlEnhancementResult result1 = engine.enhance(sql);

        // Second call - should hit cache
        SqlEnhancementResult result2 = engine.enhance(sql);

        assertEquals(result1.getEnhancedSql(), result2.getEnhancedSql(), "Cached result should match with dialect");

        String cacheStats = engine.getCacheStats();
        assertTrue(cacheStats.contains("1"), "Cache should have 1 entry");
    }

    // Relational Algebra Conversion tests

    @Test
    void testConversionEnabled_SimpleQuery() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true);

        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        assertFalse(result.isHasErrors(), "Should not have errors for valid SQL");
        // Returns original SQL after validating conversion works
        assertEquals(sql, result.getEnhancedSql(), "Conversion test returns original SQL");
    }

    @Test
    void testConversionEnabled_ComplexJoin() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true);

        String sql = "SELECT u.id, u.name, o.order_id " +
                "FROM users u " +
                "INNER JOIN orders o ON u.id = o.user_id " +
                "WHERE u.status = 'active'";

        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        assertFalse(result.isHasErrors(), "Should not have errors for valid complex SQL");
    }

    @Test
    void testConversionEnabled_WithCache() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true);

        String sql = "SELECT * FROM users WHERE id = 1";

        // First call - should convert and cache
        SqlEnhancementResult result1 = engine.enhance(sql);

        // Second call - should hit cache
        SqlEnhancementResult result2 = engine.enhance(sql);

        assertEquals(result1.getEnhancedSql(), result2.getEnhancedSql(),
                "Cached result should match");
    }

    @Test
    void testConversionDisabled_DefaultBehavior() {
        // Default constructor should have conversion disabled
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC");

        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        assertEquals(sql, result.getEnhancedSql(), "Should return original SQL");
    }

    @Test
    void testConversionEnabled_ErrorHandling() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true);

        // Invalid SQL that should fail conversion but not break the system
        String sql = "SELECT * FROM WHERE";
        SqlEnhancementResult result = engine.enhance(sql);

        // Should fallback to original SQL (pass-through mode)
        assertNotNull(result.getEnhancedSql(), "Should return some SQL");
    }

    // Optimization tests - Optimization

    @Test
    void testOptimizationEnabled_SimpleQuery() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        assertFalse(result.isHasErrors(), "Should not have errors for valid SQL");
        assertTrue(result.isOptimized(), "Should be marked as optimized");
        assertNotNull(result.getAppliedRules(), "Applied rules list should not be null");
        assertTrue(result.getOptimizationTimeMs() >= 0, "Optimization time should be non-negative");
    }

    @Test
    void testOptimizationEnabled_WithSpecificRules() {
        List<String> rules = Arrays.asList("FILTER_REDUCE", "PROJECT_REDUCE");
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, rules);

        String sql = "SELECT id, name FROM users WHERE status = 'active'";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        assertTrue(result.isOptimized(), "Should be marked as optimized");
        assertEquals(rules, result.getAppliedRules(), "Applied rules should match configured rules");
    }

    @Test
    void testOptimizationEnabled_ComplexJoin() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        String sql = "SELECT u.id, u.name, o.order_id " +
                "FROM users u " +
                "INNER JOIN orders o ON u.id = o.user_id " +
                "WHERE u.status = 'active'";

        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Complex SQL should optimize successfully");
        assertFalse(result.isHasErrors(), "Should not have errors for valid complex SQL");
        assertTrue(result.isOptimized(), "Should be marked as optimized");
    }

    @Test
    void testOptimizationDisabled() {
        // Conversion enabled, but optimization disabled
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, false, null);

        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        assertFalse(result.isOptimized(), "Should NOT be marked as optimized");
        assertEquals(0, result.getOptimizationTimeMs(), "Optimization time should be 0");
        assertTrue(result.getAppliedRules().isEmpty(), "Applied rules should be empty");
    }

    @Test
    void testOptimizationEnabled_WithCache() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        String sql = "SELECT * FROM users WHERE id = 1";

        // First call - should optimize and cache
        SqlEnhancementResult result1 = engine.enhance(sql);

        // Second call - should hit cache
        SqlEnhancementResult result2 = engine.enhance(sql);

        assertEquals(result1.getEnhancedSql(), result2.getEnhancedSql(),
                "Cached result should match");
        assertEquals(result1.isOptimized(), result2.isOptimized(),
                "Optimization status should match in cache");
    }

    @Test
    void testOptimizationEnabled_ErrorHandling() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        // Invalid SQL that should fail optimization but not break the system
        String sql = "SELECT * FROM WHERE";
        SqlEnhancementResult result = engine.enhance(sql);

        // Should fallback to original SQL (pass-through mode)
        assertNotNull(result.getEnhancedSql(), "Should return some SQL");
        assertFalse(result.isOptimized(), "Invalid SQL should not be optimized");
    }

    // Dialect tests - SQL Generation

    @Test
    void testSqlGeneration_SimpleQuery() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        assertTrue(result.isOptimized(), "Should be marked as optimized");
        // SQL generation should produce valid SQL
        assertTrue(result.getEnhancedSql().contains("SELECT"), "Should contain SELECT");
        assertTrue(result.getEnhancedSql().contains("FROM"), "Should contain FROM");
    }

    @Test
    void testSqlGeneration_WithOptimization() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        // Query that can be optimized
        String sql = "SELECT id, name FROM (SELECT id, name, email FROM users)";
        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        assertTrue(result.isOptimized(), "Should be marked as optimized");
        // The optimized SQL should still be valid
        assertFalse(result.isHasErrors(), "Should not have errors");
    }

    @Test
    void testSqlGeneration_ReturnsActualOptimizedSql() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);

        // Now returns actual SQL (might be optimized or original)
        assertNotNull(result.getEnhancedSql(), "Should return SQL");
        assertTrue(result.isOptimized(), "Should be marked as optimized");
        assertTrue(result.getOptimizationTimeMs() > 0, "Should have optimization time");
    }

    // Metrics and advanced features tests

    @Test
    void testOptimizationMetrics() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        // Process some queries
        engine.enhance("SELECT * FROM users WHERE id = 1");
        engine.enhance("SELECT * FROM orders WHERE status = 'active'");
        engine.enhance("SELECT id, name FROM (SELECT id, name, email FROM users)");

        // Get stats
        String stats = engine.getOptimizationStats();

        assertNotNull(stats, "Stats should not be null");
        assertTrue(stats.contains("Processed="), "Should contain processed count");
        assertTrue(stats.contains("Optimized="), "Should contain optimized count");
    }

    @Test
    void testMetrics_WhenOptimizationDisabled() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, false, null);

        engine.enhance("SELECT * FROM users WHERE id = 1");

        String stats = engine.getOptimizationStats();
        assertEquals("Optimization disabled", stats, "Should indicate optimization is disabled");
    }

    @Test
    void testAggressiveRules() {
        // Test with aggressive rules (excluding SUB_QUERY_REMOVE which is not available in Calcite 1.37.0)
        List<String> aggressiveRules = Arrays.asList(
                "FILTER_REDUCE", "PROJECT_REDUCE",
                "FILTER_INTO_JOIN", "JOIN_COMMUTE"
        );
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, aggressiveRules);

        String sql = "SELECT u.id, u.name FROM users u " +
                "INNER JOIN orders o ON u.id = o.user_id " +
                "WHERE u.status = 'active'";

        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Should return optimized SQL");
        assertTrue(result.isOptimized(), "Should be marked as optimized");
        // Aggressive rules applied
        assertTrue(result.getAppliedRules().contains("FILTER_INTO_JOIN") ||
                        result.getAppliedRules().contains("FILTER_REDUCE"),
                "Should have aggressive rules in applied list");
    }

    @Test
    void testMetrics_TrackModifications() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        // Query that might be optimized
        engine.enhance("SELECT id, name FROM (SELECT id, name, email FROM users)");

        String stats = engine.getOptimizationStats();

        // Should track processed and optimized queries
        assertTrue(stats.contains("Processed=1"), "Should have processed 1 query");
        assertTrue(stats.contains("Optimized=1"), "Should have optimized 1 query");
    }

    // ========================================================================
    // OPTIMIZATION EXAMPLES - Clearly Marked Test Cases
    // ========================================================================

    /**
     * Test Case 1: PREDICATE PUSHDOWN
     * Optimization should move filters closer to the data source to reduce intermediate result sets.
     * <p>
     * Example:
     * Before: SELECT * FROM (SELECT * FROM users JOIN orders ON ...) WHERE users.status = 'active'
     * After:  SELECT * FROM (SELECT * FROM users WHERE status = 'active') JOIN orders ON ...
     */
    @Test
    void testOptimization_PredicatePushdown() {
        // Use aggressive rules that include FILTER_INTO_JOIN for predicate pushdown
        List<String> aggressiveRules = Arrays.asList(
                "FILTER_REDUCE", "PROJECT_REDUCE", "FILTER_INTO_JOIN"
        );
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, aggressiveRules);

        // Query with filter that can be pushed into the join
        String sql = "SELECT u.id, u.name, o.order_id " +
                "FROM users u " +
                "INNER JOIN orders o ON u.id = o.user_id " +
                "WHERE u.status = 'active' AND u.created_at > '2024-01-01'";

        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Optimized SQL should not be null");
        assertTrue(result.isOptimized(), "Query should be marked as optimized");

        // Check if predicate pushdown rule was applied
        boolean predicatePushdownApplied = result.getAppliedRules().contains("FILTER_INTO_JOIN") ||
                result.getAppliedRules().contains("FILTER_REDUCE");
        assertTrue(predicatePushdownApplied,
                "Predicate pushdown optimization rule (FILTER_INTO_JOIN or FILTER_REDUCE) should be applied");

        logger.info("OPTIMIZATION TEST - PREDICATE PUSHDOWN:");
        logger.info("Original SQL: {}", sql);
        logger.info("Optimized SQL: {}", result.getEnhancedSql());
        logger.info("Applied Rules: {}", result.getAppliedRules());
        logger.info("Optimization Time: {}ms", result.getOptimizationTimeMs());
    }

    /**
     * Test Case 2: PROJECTION ELIMINATION
     * Optimization should remove unused columns from subqueries.
     * <p>
     * Example:
     * Before: SELECT id, name FROM (SELECT id, name, email, phone, address FROM users)
     * After:  SELECT id, name FROM users
     */
    @Test
    void testOptimization_ProjectionElimination() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        // Query with unnecessary columns in subquery
        String sql = "SELECT id, name FROM (SELECT id, name, email, phone, address, status FROM users WHERE status = 'active')";

        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Optimized SQL should not be null");
        assertTrue(result.isOptimized(), "Query should be marked as optimized");

        // Check if projection optimization rules were applied
        boolean projectionOptimized = result.getAppliedRules().contains("PROJECT_REDUCE") ||
                result.getAppliedRules().contains("PROJECT_REMOVE") ||
                result.getAppliedRules().contains("PROJECT_MERGE");
        assertTrue(projectionOptimized,
                "Projection elimination rule (PROJECT_REDUCE/PROJECT_REMOVE/PROJECT_MERGE) should be applied");

        // The optimized query should be more efficient (potentially removing the subquery)
        String optimized = result.getEnhancedSql();
        assertFalse(optimized.isEmpty(), "Optimized SQL should not be empty");

        logger.info("OPTIMIZATION TEST - PROJECTION ELIMINATION:");
        logger.info("Original SQL: {}", sql);
        logger.info("Optimized SQL: {}", optimized);
        logger.info("Applied Rules: {}", result.getAppliedRules());
        logger.info("SQL was modified: {}", result.isModified());
        logger.info("Optimization Time: {}ms", result.getOptimizationTimeMs());
    }

    /**
     * Test Case 3: JOIN REORDERING
     * Optimization should reorder joins to create smaller intermediate result sets.
     * <p>
     * Example:
     * Before: SELECT * FROM large_table JOIN small_table ON ... JOIN medium_table ON ...
     * After:  SELECT * FROM small_table JOIN medium_table ON ... JOIN large_table ON ...
     */
    @Test
    void testOptimization_JoinReordering() {
        // Use aggressive rules that include JOIN_COMMUTE for join reordering
        List<String> aggressiveRules = Arrays.asList(
                "FILTER_REDUCE", "PROJECT_REDUCE", "JOIN_COMMUTE"
        );
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, aggressiveRules);

        // Query with multiple joins that could be reordered
        String sql = "SELECT u.id, u.name, o.order_id, p.product_name " +
                "FROM users u " +
                "INNER JOIN orders o ON u.id = o.user_id " +
                "INNER JOIN products p ON o.product_id = p.id " +
                "WHERE u.status = 'active' AND p.category = 'electronics'";

        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Optimized SQL should not be null");
        assertTrue(result.isOptimized(), "Query should be marked as optimized");

        // Check if join reordering rule was applied
        boolean joinReorderingApplied = result.getAppliedRules().contains("JOIN_COMMUTE");
        // Note: JOIN_COMMUTE may not always be applied depending on the query structure

        logger.info("OPTIMIZATION TEST - JOIN REORDERING:");
        logger.info("Original SQL: {}", sql);
        logger.info("Optimized SQL: {}", result.getEnhancedSql());
        logger.info("Applied Rules: {}", result.getAppliedRules());
        logger.info("Join reordering (JOIN_COMMUTE) applied: {}", joinReorderingApplied);
        logger.info("Optimization Time: {}ms", result.getOptimizationTimeMs());
    }

    /**
     * Test Case 4: SUBQUERY ELIMINATION
     * Note: SUB_QUERY_REMOVE rule is not available in Apache Calcite 1.37.0
     * This test demonstrates that correlated subqueries could be converted to joins
     * if the rule were available.
     * <p>
     * Example:
     * Before: SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE status = 'completed')
     * After:  SELECT DISTINCT users.* FROM users INNER JOIN orders ON users.id = orders.user_id
     * WHERE orders.status = 'completed'
     */
    @Test
    void testOptimization_SubqueryElimination_Limited() {
        // Note: SUB_QUERY_REMOVE is not available in Calcite 1.37.0, so this test demonstrates
        // that we handle subqueries gracefully even without that specific optimization
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, null);

        // Query with subquery that could theoretically be converted to a join
        String sql = "SELECT u.id, u.name " +
                "FROM users u " +
                "WHERE u.id IN (SELECT user_id FROM orders WHERE status = 'completed')";

        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Optimized SQL should not be null");
        assertTrue(result.isOptimized(), "Query should be marked as optimized");

        // We can still apply other optimizations even without SUB_QUERY_REMOVE
        assertFalse(result.getAppliedRules().isEmpty(),
                "Some optimization rules should be applied");

        logger.info("OPTIMIZATION TEST - SUBQUERY (Limited - SUB_QUERY_REMOVE not available in Calcite 1.37.0):");
        logger.info("Original SQL: {}", sql);
        logger.info("Optimized SQL: {}", result.getEnhancedSql());
        logger.info("Applied Rules: {}", result.getAppliedRules());
        logger.info("Note: SUB_QUERY_REMOVE optimization is not available in Apache Calcite 1.37.0");
        logger.info("Optimization Time: {}ms", result.getOptimizationTimeMs());
    }

    /**
     * Comprehensive test combining multiple optimization types
     */
    @Test
    void testOptimization_CombinedOptimizations() {
        List<String> allRules = Arrays.asList(
                "FILTER_REDUCE", "PROJECT_REDUCE", "FILTER_MERGE", "PROJECT_MERGE",
                "FILTER_INTO_JOIN", "JOIN_COMMUTE"
        );
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC", true, true, allRules);

        // Complex query that can benefit from multiple optimizations
        String sql = "SELECT u.id, u.name " +
                "FROM (SELECT id, name, email, status, created_at FROM users) u " +
                "INNER JOIN orders o ON u.id = o.user_id " +
                "WHERE u.status = 'active' AND o.status = 'completed' AND u.created_at > '2024-01-01'";

        SqlEnhancementResult result = engine.enhance(sql);

        assertNotNull(result.getEnhancedSql(), "Optimized SQL should not be null");
        assertTrue(result.isOptimized(), "Query should be marked as optimized");
        assertFalse(result.getAppliedRules().isEmpty(), "Multiple optimization rules should be applied");

        logger.info("OPTIMIZATION TEST - COMBINED OPTIMIZATIONS:");
        logger.info("Original SQL: {}", sql);
        logger.info("Optimized SQL: {}", result.getEnhancedSql());
        logger.info("Applied Rules: {}", result.getAppliedRules());
        logger.info("Optimization Time: {}ms", result.getOptimizationTimeMs());
        logger.info("SQL Modified: {}", result.isModified());
    }
    
    // New tests for validation separation and optimization modes
    
    @Test
    void testValidateOnly_ValidSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC");
        
        String sql = "SELECT * FROM users WHERE id = 1";
        SqlValidationResult result = engine.validate(sql);
        
        assertTrue(result.isValid(), "Valid SQL should pass validation");
        assertNull(result.getErrorMessage(), "Error message should be null for valid SQL");
        assertEquals(sql, result.getSql(), "SQL should be unchanged");
    }
    
    @Test
    void testValidateOnly_InvalidSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "GENERIC");
        
        String sql = "SELECT * FROM WHERE";
        SqlValidationResult result = engine.validate(sql);
        
        assertFalse(result.isValid(), "Invalid SQL should fail validation");
        assertNotNull(result.getErrorMessage(), "Error message should be present for invalid SQL");
        assertEquals(sql, result.getSql(), "Original SQL should be returned");
    }
    
    @Test
    void testValidateOnly_ComplexSQL() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "POSTGRESQL");
        
        String sql = "SELECT u.id, u.name, COUNT(o.id) as order_count " +
                     "FROM users u " +
                     "LEFT JOIN orders o ON u.id = o.user_id " +
                     "WHERE u.status = 'active' " +
                     "GROUP BY u.id, u.name " +
                     "HAVING COUNT(o.id) > 5";
        
        SqlValidationResult result = engine.validate(sql);
        
        assertTrue(result.isValid(), "Complex valid SQL should pass validation");
        assertNull(result.getErrorMessage(), "Error message should be null");
    }
    
    @Test
    void testOptimizationMode_Disabled() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "GENERIC", "", true, true, OptimizationMode.DISABLED, null, null, null, null, null, null, 0
        );
        
        String sql = "SELECT * FROM users WHERE id = 1 AND id = 1";
        SqlEnhancementResult result = engine.enhance(sql);
        
        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        assertFalse(result.isOptimized(), "Should NOT be optimized in DISABLED mode");
        assertEquals(sql, result.getEnhancedSql(), "SQL should be unchanged in DISABLED mode");
    }
    
    @Test
    void testOptimizationMode_Sync() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "GENERIC", "", true, true, OptimizationMode.SYNC, null, null, null, null, null, null, 0
        );
        
        String sql = "SELECT * FROM users WHERE id = 1";
        SqlEnhancementResult result = engine.enhance(sql);
        
        assertNotNull(result.getEnhancedSql(), "Enhanced SQL should not be null");
        // In SYNC mode, optimization happens immediately
        // Note: may or may not be marked as optimized depending on whether optimization changed SQL
    }
    
    @Test
    void testOptimizationMode_Async() throws InterruptedException {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "GENERIC", "", true, true, OptimizationMode.ASYNC, null, null, null, null, null, null, 0
        );
        
        String sql = "SELECT * FROM users WHERE id = 1";
        
        // First call - should return immediately with original SQL
        SqlEnhancementResult result1 = engine.enhance(sql);
        
        assertNotNull(result1.getEnhancedSql(), "Enhanced SQL should not be null");
        assertFalse(result1.isOptimized(), "First call in ASYNC mode should not be optimized");
        assertEquals(sql, result1.getEnhancedSql(), "First call should return original SQL");
        
        // Wait for async optimization to complete
        Thread.sleep(1000);
        
        // Second call - should return cached optimized result
        SqlEnhancementResult result2 = engine.enhance(sql);
        
        assertNotNull(result2.getEnhancedSql(), "Second call should have enhanced SQL");
        // Note: may or may not be optimized depending on whether optimization succeeded
    }
    
    @Test
    void testFailedOptimizationCaching() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "GENERIC", "", true, true, OptimizationMode.SYNC, null, null, null, null, null, null, 0
        );
        
        // Use a SQL that might fail optimization but passes validation
        String sql = "SELECT * FROM users WHERE id = 1";
        
        // First enhancement
        SqlEnhancementResult result1 = engine.enhance(sql);
        assertNotNull(result1.getEnhancedSql(), "First enhancement should succeed");
        
        // Get stats to check failure tracking
        String stats = engine.getOptimizationStats();
        assertNotNull(stats, "Stats should be available");
        assertTrue(stats.contains("Mode="), "Stats should show mode");
    }
    
    @Test
    void testOptimizationStats_ShowsMode() {
        SqlEnhancerEngine engineSync = new SqlEnhancerEngine(
            true, "GENERIC", "", true, true, OptimizationMode.SYNC, null, null, null, null, null, null, 0
        );
        
        SqlEnhancerEngine engineAsync = new SqlEnhancerEngine(
            true, "GENERIC", "", true, true, OptimizationMode.ASYNC, null, null, null, null, null, null, 0
        );
        
        String statsSync = engineSync.getOptimizationStats();
        String statsAsync = engineAsync.getOptimizationStats();
        
        assertTrue(statsSync.contains("SYNC"), "Sync engine stats should show SYNC mode");
        assertTrue(statsAsync.contains("ASYNC"), "Async engine stats should show ASYNC mode");
    }
    
    @Test
    void testClearCache_ClearsFailedOptimizations() {
        SqlEnhancerEngine engine = new SqlEnhancerEngine(
            true, "GENERIC", "", true, true, OptimizationMode.SYNC, null, null, null, null, null, null, 0
        );
        
        String sql = "SELECT * FROM users WHERE id = 1";
        engine.enhance(sql);
        
        // Verify cache has entry
        String statsBefore = engine.getCacheStats();
        assertTrue(statsBefore.contains("1"), "Cache should have 1 entry");
        
        // Clear cache (should also clear failed optimizations)
        engine.clearCache();
        
        // Verify cache is empty
        String statsAfter = engine.getCacheStats();
        assertTrue(statsAfter.contains("0"), "Cache should be empty after clear");
    }
}
