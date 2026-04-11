package org.openjproxy.xa.pool.commons.metrics;

/**
 * No-op implementation of PoolMetrics that does nothing.
 * Used when metrics collection is disabled.
 */
public class NoOpPoolMetrics implements PoolMetrics {
    
    public static final NoOpPoolMetrics INSTANCE = new NoOpPoolMetrics();
    
    private NoOpPoolMetrics() {
        // Private constructor for singleton
    }
    
    @Override
    public void recordPoolState(
            String poolName,
            int numActive,
            int numIdle,
            int numWaiters,
            int maxTotal,
            int minIdle,
            long createdCount,
            long destroyedCount,
            long borrowedCount,
            long returnedCount) {
        // No-op
    }
    
    @Override
    public void recordConnectionAcquisitionTime(String poolName, long durationMillis) {
        // No-op
    }
    
    @Override
    public void recordPoolExhaustion(String poolName) {
        // No-op
    }
    
    @Override
    public void recordValidationFailure(String poolName) {
        // No-op
    }
    
    @Override
    public void recordLeakDetection(String poolName) {
        // No-op
    }
    
    @Override
    public void close() {
        // No-op
    }
}
