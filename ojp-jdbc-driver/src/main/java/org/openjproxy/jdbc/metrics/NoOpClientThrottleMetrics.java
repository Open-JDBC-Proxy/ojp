package org.openjproxy.jdbc.metrics;

/**
 * No-op {@link ClientThrottleMetrics}. Used when {@code ojp.jdbc.metrics=none}
 * or when no metrics backend is available.
 */
public final class NoOpClientThrottleMetrics implements ClientThrottleMetrics {

    public static final NoOpClientThrottleMetrics INSTANCE = new NoOpClientThrottleMetrics();

    private NoOpClientThrottleMetrics() {
        // singleton
    }

    @Override
    public void recordAcquired() {
        // no-op
    }

    @Override
    public void recordRejected() {
        // no-op
    }

    @Override
    public void recordServerOverload() {
        // no-op
    }

    @Override
    public void recordLimitChange(LimitChangeDirection direction) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}
