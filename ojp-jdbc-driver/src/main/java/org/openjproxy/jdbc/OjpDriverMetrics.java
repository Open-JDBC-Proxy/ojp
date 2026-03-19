package org.openjproxy.jdbc;

/**
 * Interface for collecting driver-side metrics for OJP JDBC connections and statement executions.
 *
 * <p>This abstraction decouples the JDBC driver from any specific metrics library,
 * allowing integrations such as Micrometer (Spring Boot) or no-op (default) to be
 * plugged in at runtime via {@link OjpDriverMetricsHolder}.</p>
 *
 * <p>Implementations must be thread-safe as methods may be called concurrently
 * from multiple connections and statement executions.</p>
 */
public interface OjpDriverMetrics {

    /**
     * Called when a new JDBC connection is successfully established to the OJP server.
     */
    void onConnectionCreated();

    /**
     * Called when a JDBC connection attempt fails.
     */
    void onConnectionFailed();

    /**
     * Called when a JDBC connection is closed.
     */
    void onConnectionClosed();

    /**
     * Called after a SQL statement (query or update) is successfully executed.
     *
     * @param durationMs the round-trip execution time in milliseconds
     */
    void onStatementExecuted(long durationMs);

    /**
     * Called when a SQL statement execution fails with an exception.
     */
    void onStatementFailed();
}
