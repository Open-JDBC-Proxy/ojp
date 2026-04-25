package org.openjproxy.grpc.server.readwrite;

/**
 * Classifies SQL statements as read-only or write operations to support
 * read/write traffic splitting.
 *
 * <p>SELECT, WITH (CTEs), EXPLAIN, SHOW, and DESCRIBE statements are treated
 * as read-only and may be routed to a replica.  All other statements
 * (INSERT, UPDATE, DELETE, MERGE, DDL, etc.) are treated as writes and are
 * always routed to the primary.
 */
public final class ReadWriteSqlClassifier {

    /** Indicates whether a SQL statement modifies data. */
    public enum QueryType {
        READ,
        WRITE
    }

    private ReadWriteSqlClassifier() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Classifies the given SQL statement as {@link QueryType#READ} or
     * {@link QueryType#WRITE}.
     *
     * @param sql the SQL statement to classify (may be {@code null})
     * @return {@link QueryType#READ} for read-only statements,
     *         {@link QueryType#WRITE} for all other statements and for
     *         {@code null} / blank input
     */
    public static QueryType classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return QueryType.WRITE;
        }
        String upper = sql.stripLeading().toUpperCase();
        if (upper.startsWith("SELECT")
                || upper.startsWith("WITH")
                || upper.startsWith("EXPLAIN")
                || upper.startsWith("SHOW")
                || upper.startsWith("DESCRIBE")
                || upper.startsWith("DESC ")) {
            return QueryType.READ;
        }
        return QueryType.WRITE;
    }
}
