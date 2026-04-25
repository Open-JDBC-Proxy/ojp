package org.openjproxy.xa.pool.commons.housekeeping;

import org.openjproxy.xa.pool.commons.metrics.PoolMetrics;

/**
 * Housekeeping listener that delegates to another listener and records metrics.
 * <p>
 * This wrapper combines housekeeping event handling with metrics collection,
 * ensuring that events like leak detection are properly tracked in metrics.
 * </p>
 */
public class MetricsAwareHousekeepingListener implements HousekeepingListener {

    private final HousekeepingListener delegate;
    private final PoolMetrics poolMetrics;
    private final String poolName;

    /**
     * Creates a new metrics-aware housekeeping listener.
     *
     * @param delegate the delegate listener to forward events to
     * @param poolMetrics the metrics collector
     * @param poolName the pool name for metrics
     */
    public MetricsAwareHousekeepingListener(HousekeepingListener delegate, PoolMetrics poolMetrics, String poolName) {
        this.delegate = delegate;
        this.poolMetrics = poolMetrics;
        this.poolName = poolName;
    }

    @Override
    public void onLeakDetected(Object connection, Thread holdingThread, StackTraceElement[] stackTrace) {
        // Record metric
        poolMetrics.recordLeakDetection(poolName);

        // Delegate to actual listener
        delegate.onLeakDetected(connection, holdingThread, stackTrace);
    }

    @Override
    public void onConnectionExpired(Object connection, long ageMs) {
        delegate.onConnectionExpired(connection, ageMs);
    }

    @Override
    public void onConnectionRecycled(Object connection) {
        delegate.onConnectionRecycled(connection);
    }

    @Override
    public void onHousekeepingError(String message, Throwable cause) {
        delegate.onHousekeepingError(message, cause);
    }

    @Override
    public void onPoolStateLog(String stateInfo) {
        delegate.onPoolStateLog(stateInfo);
    }
}
