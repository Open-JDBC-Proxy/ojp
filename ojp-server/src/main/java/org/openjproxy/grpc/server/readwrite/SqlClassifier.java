package org.openjproxy.grpc.server.readwrite;

/**
 * Interface for classifying SQL statements as READ or WRITE operations.
 * <p>
 * This is a core component of the read/write splitting feature that determines
 * whether a SQL statement should be routed to a replica (read) or primary (write).
 * </p>
 */
public interface SqlClassifier {

    /**
     * Classification result for SQL statements.
     */
    enum SqlOperationType {
        /**
         * Read operations (SELECT without FOR UPDATE/SHARE).
         * These can be safely routed to read replicas.
         */
        READ,

        /**
         * Write operations (INSERT, UPDATE, DELETE, DDL, DCL).
         * These must be routed to the primary database.
         */
        WRITE,

        /**
         * Unknown or unparseable statements.
         * Conservative default: route to primary for safety.
         */
        UNKNOWN
    }

    /**
     * Classifies a SQL statement as READ, WRITE, or UNKNOWN.
     *
     * @param sql the SQL statement to classify (must not be null)
     * @return the classification result
     * @throws IllegalArgumentException if sql is null
     */
    SqlOperationType classify(String sql);

    /**
     * Checks if a SQL statement is a read operation.
     * Convenience method equivalent to classify(sql) == READ.
     *
     * @param sql the SQL statement to check
     * @return true if the statement is a read operation
     */
    default boolean isReadOperation(String sql) {
        return classify(sql) == SqlOperationType.READ;
    }

    /**
     * Checks if a SQL statement is a write operation.
     * Convenience method equivalent to classify(sql) == WRITE.
     *
     * @param sql the SQL statement to check
     * @return true if the statement is a write operation
     */
    default boolean isWriteOperation(String sql) {
        return classify(sql) == SqlOperationType.WRITE;
    }
}
