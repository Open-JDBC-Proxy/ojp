package org.openjproxy.jdbc;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Best-effort static classifier that decides, without executing the SQL, whether a statement
 * is expected to return a {@link java.sql.ResultSet} (e.g. SELECT-style queries) or an update
 * count (INSERT/UPDATE/DELETE/DDL/stored procedure calls).
 *
 * <p>OJP's wire protocol exposes two distinct RPCs for query and update execution
 * ({@code executeQuery} streams result rows, {@code executeUpdate} returns a row count). The
 * generic {@link java.sql.Statement#execute(String)} / {@link java.sql.PreparedStatement#execute()}
 * contract does not tell the caller in advance which one applies, so this classifier inspects the
 * SQL text once, before any network round trip, to route the call to the correct RPC.
 *
 * <p>This is a heuristic, not a SQL parser: it cannot detect every case (e.g. a stored procedure
 * that conditionally returns rows depending on its arguments). Statements it cannot recognize as
 * query-shaped default to "update", matching the historical OJP behaviour. Tools that rely heavily
 * on {@code DatabaseMetaData} (e.g. SQL IDEs) frequently issue metadata calls that do not start
 * with {@code SELECT} (CTEs via {@code WITH}, catalog stored procedures such as
 * {@code sp_columns}/{@code sp_tables} on SQL Server, {@code CALL}/{@code EXEC} statements, etc.),
 * which is why the prefix list below is broader than a plain {@code SELECT} check.
 */
final class SqlStatementClassifier {

    private static final Pattern LEADING_COMMENTS = Pattern.compile(
            "\\A(\\s*(--[^\\n]*\\n|/\\*.*?\\*/))*\\s*", Pattern.DOTALL);

    private static final String[] RESULT_SET_PREFIXES = {
        "SELECT", "WITH", "EXEC ", "EXEC(", "EXECUTE ", "EXECUTE(", "CALL ", "CALL(",
        "SHOW ", "DESCRIBE ", "DESC ", "EXPLAIN ", "VALUES ", "VALUES(", "PRAGMA ", "SP_",
    };

    private SqlStatementClassifier() {
        // Utility class, no instances.
    }

    /**
     * Returns {@code true} when the given SQL text is likely to produce a {@link java.sql.ResultSet}
     * rather than an update count.
     *
     * @param sql the raw SQL text as supplied by the caller
     * @return {@code true} if the statement should be routed to {@code executeQuery}
     */
    static boolean looksLikeQuery(String sql) {
        if (sql == null) {
            return false;
        }
        String withoutLeadingComments = LEADING_COMMENTS.matcher(sql).replaceFirst("");
        String upperSql = withoutLeadingComments.trim().toUpperCase(Locale.ROOT);
        for (String prefix : RESULT_SET_PREFIXES) {
            if (upperSql.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
