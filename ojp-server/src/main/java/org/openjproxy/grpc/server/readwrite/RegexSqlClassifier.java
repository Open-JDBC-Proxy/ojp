package org.openjproxy.grpc.server.readwrite;

import java.util.regex.Pattern;

/**
 * Regex-based implementation of SqlClassifier.
 * <p>
 * Uses regular expressions to classify SQL statements efficiently.
 * This implementation handles:
 * - Standard DML: SELECT, INSERT, UPDATE, DELETE
 * - DDL: CREATE, ALTER, DROP, TRUNCATE
 * - DCL: GRANT, REVOKE
 * - Transaction control: BEGIN, COMMIT, ROLLBACK, SAVEPOINT
 * - Locking reads: SELECT FOR UPDATE/SHARE
 * - Common Table Expressions (CTEs) with modifying clauses
 * - RETURNING clauses (PostgreSQL)
 * </p>
 * <p>
 * Thread-safe and optimized for performance with compiled patterns.
 * </p>
 */
public class RegexSqlClassifier implements SqlClassifier {

    // Pattern to match SELECT statements (conservative approach)
    // Matches SELECT but excludes SELECT FOR UPDATE/SHARE and SELECT with INTO
    private static final Pattern SELECT_PATTERN = Pattern.compile(
        "^\\s*" +
        "(?:(?:/\\*.*?\\*/|--[^\\n]*\\n)\\s*)*" +  // Optional leading comments (block or line)
        "(?:WITH\\s+.*?\\s+)?" +        // Optional CTE (will check separately for modifying CTEs)
        "SELECT\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Pattern to detect SELECT FOR UPDATE or SELECT FOR SHARE (must route to primary)
    private static final Pattern SELECT_FOR_UPDATE_PATTERN = Pattern.compile(
        "\\bFOR\\s+(UPDATE|SHARE|KEY SHARE|NO KEY UPDATE)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern to detect SELECT INTO (write operation)
    private static final Pattern SELECT_INTO_PATTERN = Pattern.compile(
        "\\bSELECT\\s+.*?\\s+INTO\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Pattern to detect modifying CTEs (WITH ... INSERT/UPDATE/DELETE)
    private static final Pattern MODIFYING_CTE_PATTERN = Pattern.compile(
        "\\bWITH\\s+.*?\\b(INSERT|UPDATE|DELETE)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Pattern to detect RETURNING clause (PostgreSQL write operation)
    private static final Pattern RETURNING_PATTERN = Pattern.compile(
        "\\bRETURNING\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Patterns for write operations
    private static final Pattern INSERT_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?INSERT\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern UPDATE_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?UPDATE\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern DELETE_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?DELETE\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern MERGE_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?MERGE\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern REPLACE_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?REPLACE\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // DDL patterns
    private static final Pattern DDL_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?" +
        "(CREATE|ALTER|DROP|TRUNCATE|RENAME)\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // DCL patterns
    private static final Pattern DCL_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?" +
        "(GRANT|REVOKE)\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Transaction control patterns (route to primary for safety)
    private static final Pattern TRANSACTION_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?" +
        "(BEGIN|START\\s+TRANSACTION|COMMIT|ROLLBACK|SAVEPOINT|RELEASE\\s+SAVEPOINT|SET\\s+TRANSACTION)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // CALL/EXECUTE patterns (stored procedures - conservative: route to primary)
    private static final Pattern CALL_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?" +
        "(CALL|EXECUTE|EXEC)\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // EXPLAIN/DESCRIBE (read-only analysis)
    private static final Pattern EXPLAIN_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?" +
        "(EXPLAIN|DESCRIBE|DESC|SHOW)\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // SET statements (session configuration - route to primary for consistency)
    private static final Pattern SET_PATTERN = Pattern.compile(
        "^\\s*(?:/\\*.*?\\*/\\s*)?" +
        "SET\\s+",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    @Override
    public SqlOperationType classify(String sql) {
        if (sql == null) {
            throw new IllegalArgumentException("SQL statement cannot be null");
        }

        // Trim and handle empty strings
        String trimmedSql = sql.trim();
        if (trimmedSql.isEmpty()) {
            return SqlOperationType.UNKNOWN;
        }

        // Check for explicit write operations first
        if (INSERT_PATTERN.matcher(trimmedSql).find() ||
            UPDATE_PATTERN.matcher(trimmedSql).find() ||
            DELETE_PATTERN.matcher(trimmedSql).find() ||
            MERGE_PATTERN.matcher(trimmedSql).find() ||
            REPLACE_PATTERN.matcher(trimmedSql).find()) {
            return SqlOperationType.WRITE;
        }

        // Check for DDL operations
        if (DDL_PATTERN.matcher(trimmedSql).find()) {
            return SqlOperationType.WRITE;
        }

        // Check for DCL operations
        if (DCL_PATTERN.matcher(trimmedSql).find()) {
            return SqlOperationType.WRITE;
        }

        // Check for transaction control (route to primary)
        if (TRANSACTION_PATTERN.matcher(trimmedSql).find()) {
            return SqlOperationType.WRITE;
        }

        // Check for SET statements (route to primary for session consistency)
        if (SET_PATTERN.matcher(trimmedSql).find()) {
            return SqlOperationType.WRITE;
        }

        // Check for CALL/EXECUTE (stored procedures - conservative: route to primary)
        if (CALL_PATTERN.matcher(trimmedSql).find()) {
            return SqlOperationType.WRITE;
        }

        // Check for SELECT statements
        if (SELECT_PATTERN.matcher(trimmedSql).find()) {
            // Check for special SELECT cases that must route to primary

            // SELECT FOR UPDATE/SHARE requires locks
            if (SELECT_FOR_UPDATE_PATTERN.matcher(trimmedSql).find()) {
                return SqlOperationType.WRITE;
            }

            // SELECT INTO creates a table
            if (SELECT_INTO_PATTERN.matcher(trimmedSql).find()) {
                return SqlOperationType.WRITE;
            }

            // Modifying CTEs (WITH ... INSERT/UPDATE/DELETE)
            if (MODIFYING_CTE_PATTERN.matcher(trimmedSql).find()) {
                return SqlOperationType.WRITE;
            }

            // RETURNING clause indicates a write operation
            if (RETURNING_PATTERN.matcher(trimmedSql).find()) {
                return SqlOperationType.WRITE;
            }

            // Pure SELECT - safe for replica
            return SqlOperationType.READ;
        }

        // EXPLAIN/DESCRIBE are read-only
        if (EXPLAIN_PATTERN.matcher(trimmedSql).find()) {
            return SqlOperationType.READ;
        }

        // Unknown statement type - route to primary for safety
        return SqlOperationType.UNKNOWN;
    }
}
