package org.openjproxy.grpc.server.metrics;

/**
 * No-op implementation of {@link SqlStatementMetrics} that does nothing.
 * Used when telemetry is disabled or unavailable.
 */
public class NoOpSqlStatementMetrics implements SqlStatementMetrics {

    public static final NoOpSqlStatementMetrics INSTANCE = new NoOpSqlStatementMetrics();

    private NoOpSqlStatementMetrics() {
        // Private constructor for singleton
    }

    @Override
    public void recordSqlExecution(String sql, long executionTimeMs, boolean isSlow) {
        // No-op
    }

    @Override
    public void close() {
        // No-op
    }
}
