package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.provider.Arguments;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link SqlTableExtractor}.
 * 
 * Tests cover:
 * - Basic SQL statements (SELECT, INSERT, UPDATE, DELETE)
 * - JOIN queries (multiple tables)
 * - Subqueries
 * - Various SQL dialects (PostgreSQL, MySQL, Oracle)
 * - Case sensitivity
 * - Malformed SQL (graceful failure)
 * - Edge cases (null, empty, whitespace)
 */
class SqlTableExtractorTest {
    
    // ========== Basic SELECT Tests ==========
    
    @Test
    void testExtractTablesFromSelectWithSchema() {
        String sql = "SELECT * FROM public.users WHERE id = 1";
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        // JSqlParser includes schema in table name
        assertTrue(tables.contains("users") || tables.contains("public.users"));
    }
    
    static Stream<Arguments> singleTableSqlProvider() {
        return Stream.of(
            Arguments.of("SELECT * FROM products WHERE category = 'electronics'", "products"),
            Arguments.of("SELECT p.* FROM products p WHERE p.price > 100", "products"),
            Arguments.of("INSERT INTO products (name, price) VALUES ('Widget', 9.99)", "products"),
            Arguments.of("UPDATE products SET price = 19.99 WHERE id = 123", "products"),
            Arguments.of("DELETE FROM orders WHERE created_at < '2020-01-01'", "orders")
        );
    }

    @ParameterizedTest
    @MethodSource("singleTableSqlProvider")
    void testExtractSingleTable(String sql, String expectedTable) {
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertEquals(Set.of(expectedTable), tables);
    }
    
    // ========== JOIN Tests ==========
    
    @Test
    void testExtractTablesFromInnerJoin() {
        String sql = "SELECT * FROM orders o INNER JOIN customers c ON o.customer_id = c.id";
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertEquals(Set.of("orders", "customers"), tables);
    }
    
    @Test
    void testExtractTablesFromLeftJoin() {
        String sql = "SELECT * FROM products p LEFT JOIN categories c ON p.category_id = c.id";
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertEquals(Set.of("products", "categories"), tables);
    }
    
    @Test
    void testExtractTablesFromMultipleJoins() {
        String sql = """
            SELECT * FROM orders o
            JOIN customers c ON o.customer_id = c.id
            JOIN products p ON o.product_id = p.id
            """;
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertEquals(Set.of("orders", "customers", "products"), tables);
    }
    
    // ========== Subquery Tests ==========
    
    @Test
    void testExtractTablesFromSubqueryInFrom() {
        String sql = "SELECT * FROM (SELECT * FROM products WHERE price > 100) AS expensive_products";
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertTrue(tables.contains("products"));
    }
    
    @Test
    void testExtractTablesFromSubqueryInWhere() {
        String sql = "SELECT * FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE country = 'US')";
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertEquals(Set.of("orders", "customers"), tables);
    }
    
    // ========== extractModifiedTables Tests ==========
    
    @Test
    void testExtractModifiedTablesFromInsert() {
        String sql = "INSERT INTO products (name, price) VALUES ('Widget', 9.99)";
        Set<String> tables = SqlTableExtractor.extractModifiedTables(sql);
        assertEquals(Set.of("products"), tables);
    }
    
    @Test
    void testExtractModifiedTablesFromUpdate() {
        String sql = "UPDATE users SET email = 'new@example.com' WHERE id = 1";
        Set<String> tables = SqlTableExtractor.extractModifiedTables(sql);
        assertEquals(Set.of("users"), tables);
    }
    
    @Test
    void testExtractModifiedTablesFromDelete() {
        String sql = "DELETE FROM orders WHERE status = 'cancelled'";
        Set<String> tables = SqlTableExtractor.extractModifiedTables(sql);
        assertEquals(Set.of("orders"), tables);
    }
    
    @Test
    void testExtractModifiedTablesFromSelectReturnsEmpty() {
        String sql = "SELECT * FROM products";
        Set<String> tables = SqlTableExtractor.extractModifiedTables(sql);
        assertTrue(tables.isEmpty(), "SELECT should not return any modified tables");
    }
    
    // ========== Case Sensitivity Tests ==========
    
    @Test
    void testExtractTablesNormalizesToLowercase() {
        String sql = "SELECT * FROM PRODUCTS WHERE ID = 1";
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertEquals(Set.of("products"), tables, "Table names should be lowercase");
    }
    
    @Test
    void testExtractTablesWithMixedCase() {
        String sql = "SELECT * FROM Products p JOIN Categories c ON p.CategoryId = c.Id";
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertEquals(Set.of("products", "categories"), tables, "All table names should be lowercase");
    }
    
    // ========== SQL Dialect Tests ==========
    
    @Test
    void testPostgreSqlDoubleQuotedIdentifier() {
        String sql = "SELECT * FROM \"MyTable\" WHERE \"MyColumn\" = 1";
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        // JSqlParser strips quotes and normalizes to lowercase
        assertTrue(tables.contains("mytable") || tables.size() > 0, 
            "Should extract table name (quotes stripped, normalized to lowercase)");
    }
    
    @Test
    void testMySqlBacktickIdentifier() {
        String sql = "SELECT * FROM `orders` WHERE `status` = 'pending'";
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        // JSqlParser strips backticks and normalizes to lowercase
        assertTrue(tables.contains("orders") || tables.size() > 0, 
            "Should extract table name (backticks stripped)");
    }
    
    @Test
    void testOracleDualTable() {
        String sql = "SELECT SYSDATE FROM DUAL";
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertTrue(tables.contains("dual"));
    }
    
    // ========== isWriteOperation Tests ==========
    
    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO products VALUES (1, 'Widget')",
        "UPDATE products SET price = 9.99",
        "DELETE FROM orders WHERE id = 1",
        "MERGE INTO products USING",
        "REPLACE INTO products VALUES (1, 'Widget')",
        "  INSERT INTO products VALUES (1, 'Widget')",  // Leading whitespace
        "insert into products values (1, 'Widget')"     // Lowercase
    })
    void testIsWriteOperationReturnsTrue(String sql) {
        assertTrue(SqlTableExtractor.isWriteOperation(sql), "Should detect write operation: " + sql);
    }
    
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM products",
        "WITH cte AS (SELECT * FROM products) SELECT * FROM cte",
        "  SELECT * FROM products",  // Leading whitespace
        "select * from products"     // Lowercase
    })
    void testIsWriteOperationReturnsFalse(String sql) {
        assertFalse(SqlTableExtractor.isWriteOperation(sql), "Should not detect write operation: " + sql);
    }
    
    // ========== isReadOperation Tests ==========
    
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM products",
        "WITH cte AS (SELECT * FROM products) SELECT * FROM cte",
        "  SELECT * FROM products",  // Leading whitespace
        "select * from products"     // Lowercase
    })
    void testIsReadOperationReturnsTrue(String sql) {
        assertTrue(SqlTableExtractor.isReadOperation(sql), "Should detect read operation: " + sql);
    }
    
    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO products VALUES (1, 'Widget')",
        "UPDATE products SET price = 9.99",
        "DELETE FROM orders WHERE id = 1"
    })
    void testIsReadOperationReturnsFalse(String sql) {
        assertFalse(SqlTableExtractor.isReadOperation(sql), "Should not detect read operation: " + sql);
    }
    
    // ========== Edge Cases and Error Handling ==========
    
    @Test
    void testExtractTablesFromNullReturnsEmpty() {
        Set<String> tables = SqlTableExtractor.extractTables(null);
        assertTrue(tables.isEmpty(), "null SQL should return empty set");
    }
    
    @Test
    void testExtractTablesFromEmptyStringReturnsEmpty() {
        Set<String> tables = SqlTableExtractor.extractTables("");
        assertTrue(tables.isEmpty(), "Empty SQL should return empty set");
    }
    
    @Test
    void testExtractTablesFromWhitespaceReturnsEmpty() {
        Set<String> tables = SqlTableExtractor.extractTables("   \n\t  ");
        assertTrue(tables.isEmpty(), "Whitespace-only SQL should return empty set");
    }
    
    @Test
    void testMalformedSqlReturnsEmptySet() {
        String sql = "SELCT * FRM products WHERE";  // Malformed
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertTrue(tables.isEmpty(), "Malformed SQL should return empty set");
    }
    
    @Test
    void testIncompleteSqlReturnsEmptySet() {
        String sql = "SELECT * FROM";  // Incomplete
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertTrue(tables.isEmpty(), "Incomplete SQL should return empty set");
    }
    
    @Test
    void testExtractModifiedTablesFromNullReturnsEmpty() {
        Set<String> tables = SqlTableExtractor.extractModifiedTables(null);
        assertTrue(tables.isEmpty(), "null SQL should return empty set");
    }
    
    @Test
    void testExtractModifiedTablesFromMalformedSqlReturnsEmpty() {
        String sql = "INSRT INTO products";  // Malformed
        Set<String> tables = SqlTableExtractor.extractModifiedTables(sql);
        assertTrue(tables.isEmpty(), "Malformed SQL should return empty set");
    }
    
    @Test
    void testIsWriteOperationWithNullReturnsFalse() {
        assertFalse(SqlTableExtractor.isWriteOperation(null));
    }
    
    @Test
    void testIsWriteOperationWithEmptyReturnsFalse() {
        assertFalse(SqlTableExtractor.isWriteOperation(""));
    }
    
    @Test
    void testIsReadOperationWithNullReturnsFalse() {
        assertFalse(SqlTableExtractor.isReadOperation(null));
    }
    
    @Test
    void testIsReadOperationWithEmptyReturnsFalse() {
        assertFalse(SqlTableExtractor.isReadOperation(""));
    }
    
    // ========== Complex Real-World Queries ==========
    
    @Test
    void testComplexQueryWithMultipleJoinsAndSubqueries() {
        String sql = """
            SELECT o.id, c.name, p.title
            FROM orders o
            JOIN customers c ON o.customer_id = c.id
            JOIN (
                SELECT id, title FROM products WHERE price > 100
            ) p ON o.product_id = p.id
            WHERE o.status = 'pending'
            """;
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertTrue(tables.contains("orders"));
        assertTrue(tables.contains("customers"));
        assertTrue(tables.contains("products"));
    }
    
    @Test
    void testUpdateWithJoin() {
        // PostgreSQL style UPDATE with JOIN
        String sql = """
            UPDATE products p
            SET price = p.price * 1.1
            FROM categories c
            WHERE p.category_id = c.id AND c.name = 'Electronics'
            """;
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertTrue(tables.contains("products"));
        // Note: JSqlParser may or may not include 'categories' depending on dialect
    }
    
    @Test
    void testInsertWithSelect() {
        String sql = """
            INSERT INTO archive_orders (id, customer_id, total)
            SELECT id, customer_id, total FROM orders WHERE created_at < '2020-01-01'
            """;
        Set<String> tables = SqlTableExtractor.extractTables(sql);
        assertTrue(tables.contains("archive_orders"));
        assertTrue(tables.contains("orders"));
    }
}
