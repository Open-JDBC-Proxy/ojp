package org.openjproxy.grpc.server.metrics;

/**
 * Interface for collecting and reporting SQL statement execution metrics.
 * <p>
 * This abstraction allows different metrics implementations (OpenTelemetry, no-op, etc.)
 * to be plugged in without coupling the execution path to a specific metrics library.
 * </p>
 */
public interface SqlStatementMetrics {

    /**
     * Records the execution time of a SQL statement.
     *
     * @param sql             the actual SQL statement text
     * @param executionTimeMs the execution time in milliseconds
     * @param isSlow          whether this execution is classified as slow
     */
    void recordSqlExecution(String sql, long executionTimeMs, boolean isSlow);

    /**
     * Closes the metrics collector and releases any resources.
     */
    void close();
}
