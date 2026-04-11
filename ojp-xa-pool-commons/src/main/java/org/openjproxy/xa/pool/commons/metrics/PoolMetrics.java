package org.openjproxy.xa.pool.commons.metrics;

/**
 * Interface for collecting and reporting connection pool metrics.
 * <p>
 * This abstraction allows different metrics implementations (OpenTelemetry, Micrometer, etc.)
 * to be plugged into the pool without coupling the pool implementation to a specific metrics library.
 * </p>
 */
public interface PoolMetrics {
    
    /**
     * Updates all pool metrics based on current pool state.
     * This method should be called periodically or after state changes.
     *
     * @param poolName the name/identifier of the pool
     * @param numActive number of active (borrowed) connections
     * @param numIdle number of idle connections in the pool
     * @param numWaiters number of threads waiting for a connection
     * @param maxTotal maximum total connections allowed
     * @param minIdle minimum idle connections to maintain
     * @param createdCount total connections created since pool start
     * @param destroyedCount total connections destroyed since pool start
     * @param borrowedCount total borrow operations since pool start
     * @param returnedCount total return operations since pool start
     */
    void recordPoolState(
            String poolName,
            int numActive,
            int numIdle,
            int numWaiters,
            int maxTotal,
            int minIdle,
            long createdCount,
            long destroyedCount,
            long borrowedCount,
            long returnedCount
    );
    
    /**
     * Records the time taken to acquire a connection from the pool.
     *
     * @param poolName the name/identifier of the pool
     * @param durationMillis the duration in milliseconds
     */
    void recordConnectionAcquisitionTime(String poolName, long durationMillis);
    
    /**
     * Records a pool exhaustion event (when borrowing timed out).
     *
     * @param poolName the name/identifier of the pool
     */
    void recordPoolExhaustion(String poolName);
    
    /**
     * Records a connection validation failure.
     *
     * @param poolName the name/identifier of the pool
     */
    void recordValidationFailure(String poolName);
    
    /**
     * Records a connection leak detection.
     *
     * @param poolName the name/identifier of the pool
     */
    void recordLeakDetection(String poolName);
    
    /**
     * Closes the metrics collector and releases any resources.
     */
    void close();
}
