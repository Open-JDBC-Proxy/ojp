package org.openjproxy.grpc.server.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op implementation of {@link QueryCacheMetrics} for when metrics are disabled.
 * <p>
 * All methods do nothing and return immediately. This implementation has zero overhead.
 * </p>
 */
public class NoOpQueryCacheMetrics implements QueryCacheMetrics {

    private static final Logger log = LoggerFactory.getLogger(NoOpQueryCacheMetrics.class);
    private static final NoOpQueryCacheMetrics INSTANCE = new NoOpQueryCacheMetrics();

    /**
     * Get the singleton instance of the no-op metrics collector.
     *
     * @return The singleton instance
     */
    public static NoOpQueryCacheMetrics getInstance() {
        return INSTANCE;
    }

    private NoOpQueryCacheMetrics() {
        log.debug("No-op query cache metrics initialized");
    }

    @Override
    public void recordCacheHit(String datasourceName, String sql) {
        // No-op
    }

    @Override
    public void recordCacheMiss(String datasourceName, String sql) {
        // No-op
    }

    @Override
    public void recordCacheEviction(String datasourceName, String reason) {
        // No-op
    }

    @Override
    public void recordCacheInvalidation(String datasourceName, String tableName) {
        // No-op
    }

    @Override
    public void recordCacheRejection(String datasourceName, long sizeBytes) {
        // No-op
    }

    @Override
    public void updateCacheSize(String datasourceName, long entryCount, long sizeBytes) {
        // No-op
    }

    @Override
    public void recordQueryExecutionTime(String datasourceName, String source, long timeMs) {
        // No-op
    }

    @Override
    public void close() {
        // No-op
    }
}
