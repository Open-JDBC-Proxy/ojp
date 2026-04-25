package org.openjproxy.grpc.server.cache;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for extracting table names from SQL statements.
 * Uses JSqlParser to parse SQL and identify referenced tables.
 *
 * <p>This class is used for automatic cache invalidation when tables are modified.
 * It handles various SQL dialects (PostgreSQL, MySQL, Oracle, SQL Server) and
 * gracefully handles parsing failures by returning empty sets.
 *
 * <p>Thread-safe: All methods are static and stateless.
 *
 * @since Phase 8
 */
public class SqlTableExtractor {

    private static final Logger logger = LoggerFactory.getLogger(SqlTableExtractor.class);

    /**
     * Extract all table names from a SQL statement (SELECT, INSERT, UPDATE, DELETE).
     * Returns table names in lowercase for case-insensitive matching.
     *
     * <p>Handles:
     * <ul>
     *   <li>Simple SELECT: {@code SELECT * FROM users}</li>
     *   <li>JOIN queries: {@code SELECT * FROM orders JOIN customers}</li>
     *   <li>Subqueries: {@code SELECT * FROM (SELECT * FROM products) p}</li>
     *   <li>INSERT/UPDATE/DELETE: extracts target table</li>
     * </ul>
     *
     * @param sql the SQL statement to parse
     * @return set of table names in lowercase, empty set if parsing fails
     */
    public static Set<String> extractTables(String sql) {
        if (sql == null || sql.isBlank()) {
            return Set.of();
        }

        try {
            Statement statement = CCJSqlParserUtil.parse(sql);

            // Use JSqlParser's built-in table finder
            TablesNamesFinder tablesFinder = new TablesNamesFinder();
            List<String> tableNames = tablesFinder.getTableList(statement);

            // Normalize to lowercase for case-insensitive matching
            return tableNames.stream()
                .map(String::toLowerCase)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toSet());

        } catch (Exception e) {
            // Parsing failed - log at debug level and return empty set
            // This is expected for malformed SQL or unsupported dialects
            logger.debug("Failed to parse SQL for table extraction (SQL: {}): {}",
                truncateSql(sql), e.getMessage());
            return Set.of();
        }
    }

    /**
     * Extract only tables being modified (INSERT, UPDATE, DELETE).
     * Returns empty set for SELECT statements and other read operations.
     *
     * <p>This is useful for cache invalidation - only modified tables need invalidation.
     *
     * @param sql the SQL statement to parse
     * @return set of modified table names in lowercase, empty set for SELECT or if parsing fails
     */
    public static Set<String> extractModifiedTables(String sql) {
        if (sql == null || sql.isBlank()) {
            return Set.of();
        }

        try {
            Statement statement = CCJSqlParserUtil.parse(sql);

            if (statement instanceof Insert insert) {
                String tableName = insert.getTable().getName().toLowerCase();
                return Set.of(tableName);

            } else if (statement instanceof Update update) {
                String tableName = update.getTable().getName().toLowerCase();
                return Set.of(tableName);

            } else if (statement instanceof Delete delete) {
                String tableName = delete.getTable().getName().toLowerCase();
                return Set.of(tableName);
            }

            // Not a DML statement (SELECT, DDL, etc.)
            return Set.of();

        } catch (Exception e) {
            logger.debug("Failed to parse SQL for modified tables (SQL: {}): {}",
                truncateSql(sql), e.getMessage());
            return Set.of();
        }
    }

    /**
     * Check if SQL is a write operation (INSERT, UPDATE, DELETE).
     * Uses simple string prefix matching for performance.
     *
     * <p>Note: This is a fast check but may have false positives.
     * For accurate detection, use {@link #extractModifiedTables(String)} and check if result is non-empty.
     *
     * @param sql the SQL statement to check
     * @return true if the SQL appears to be a write operation
     */
    public static boolean isWriteOperation(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }

        String trimmed = sql.trim().toUpperCase();
        return trimmed.startsWith("INSERT")
            || trimmed.startsWith("UPDATE")
            || trimmed.startsWith("DELETE")
            || trimmed.startsWith("MERGE")      // Oracle MERGE statement
            || trimmed.startsWith("REPLACE");   // MySQL REPLACE statement
    }

    /**
     * Check if SQL is a read operation (SELECT).
     * Uses simple string prefix matching for performance.
     *
     * @param sql the SQL statement to check
     * @return true if the SQL appears to be a read operation
     */
    public static boolean isReadOperation(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }

        String trimmed = sql.trim().toUpperCase();
        return trimmed.startsWith("SELECT") || trimmed.startsWith("WITH");
    }

    /**
     * Truncate SQL for logging (first 100 characters).
     *
     * @param sql the SQL to truncate
     * @return truncated SQL with "..." suffix if needed
     */
    private static String truncateSql(String sql) {
        if (sql == null) {
            return "null";
        }
        if (sql.length() <= 100) {
            return sql;
        }
        return sql.substring(0, 100) + "...";
    }
}
