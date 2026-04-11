package org.openjproxy.grpc.server.cache;

/**
 * Interface for collecting and reporting query cache metrics.
 * <p>
 * This abstraction allows different metrics implementations (OpenTelemetry, no-op, etc.)
 * to be plugged in without coupling the cache to a specific metrics library.
 * </p>
 */
public interface QueryCacheMetrics {
    
    /**
     * Record a cache hit.
     *
     * @param datasourceName The datasource name
     * @param sql The SQL statement (may be truncated for cardinality control)
     */
    void recordCacheHit(String datasourceName, String sql);
    
    /**
     * Record a cache miss.
     *
     * @param datasourceName The datasource name
     * @param sql The SQL statement (may be truncated for cardinality control)
     */
    void recordCacheMiss(String datasourceName, String sql);
    
    /**
     * Record a cache eviction.
     *
     * @param datasourceName The datasource name
     * @param reason The eviction reason (size, ttl, etc.)
     */
    void recordCacheEviction(String datasourceName, String reason);
    
    /**
     * Record a cache invalidation.
     *
     * @param datasourceName The datasource name
     * @param tableName The table that triggered invalidation
     */
    void recordCacheInvalidation(String datasourceName, String tableName);
    
    /**
     * Record a cache rejection (result too large to cache).
     *
     * @param datasourceName The datasource name
     * @param sizeBytes The size in bytes that was rejected
     */
    void recordCacheRejection(String datasourceName, long sizeBytes);
    
    /**
     * Update the current cache size gauge.
     *
     * @param datasourceName The datasource name
     * @param entryCount Number of entries in cache
     * @param sizeBytes Total size in bytes
     */
    void updateCacheSize(String datasourceName, long entryCount, long sizeBytes);
    
    /**
     * Record the time taken to execute a cached query.
     *
     * @param datasourceName The datasource name
     * @param source The source of the result ("cache" or "database")
     * @param timeMs Execution time in milliseconds
     */
    void recordQueryExecutionTime(String datasourceName, String source, long timeMs);
    
    /**
     * Closes the metrics collector and releases any resources.
     */
    void close();
}
