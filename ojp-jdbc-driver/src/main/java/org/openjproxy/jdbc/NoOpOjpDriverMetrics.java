package org.openjproxy.jdbc;

/**
 * No-op implementation of {@link OjpDriverMetrics} that discards all measurements.
 *
 * <p>This is the default implementation used when no metrics integration (e.g. Micrometer)
 * has been registered via {@link OjpDriverMetricsHolder}. It imposes zero overhead.</p>
 */
public final class NoOpOjpDriverMetrics implements OjpDriverMetrics {

    /** Singleton instance. */
    public static final NoOpOjpDriverMetrics INSTANCE = new NoOpOjpDriverMetrics();

    private NoOpOjpDriverMetrics() {
    }

    @Override
    public void onConnectionCreated() {
    }

    @Override
    public void onConnectionFailed() {
    }

    @Override
    public void onConnectionClosed() {
    }

    @Override
    public void onStatementExecuted(long durationMs) {
    }

    @Override
    public void onStatementFailed() {
    }
}
