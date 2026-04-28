package org.openjproxy.grpc.server.cache;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Track cache statistics including hits, misses, evictions, invalidations, and rejections.
 * Thread-safe using atomic counters.
 */
public class CacheStatistics {
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong invalidations = new AtomicLong(0);
    private final AtomicLong rejections = new AtomicLong(0);

    /**
     * Record a cache hit.
     */
    public void recordHit() {
        hits.incrementAndGet();
    }

    /**
     * Record a cache miss.
     */
    public void recordMiss() {
        misses.incrementAndGet();
    }

    /**
     * Record a cache eviction (entry removed due to size or TTL).
     */
    public void recordEviction() {
        evictions.incrementAndGet();
    }

    /**
     * Record a cache invalidation (entry removed due to table write).
     */
    public void recordInvalidation() {
        invalidations.incrementAndGet();
    }

    /**
     * Record a cache rejection (entry too large to cache).
     */
    public void recordRejection() {
        rejections.incrementAndGet();
    }

    /**
     * Calculate the cache hit rate.
     *
     * @return Hit rate as a percentage (0.0 to 1.0)
     */
    public double getHitRate() {
        long h = hits.get();
        long m = misses.get();
        return (h + m == 0) ? 0.0 : (double) h / (h + m);
    }

    /**
     * Get total number of hits.
     *
     * @return Hit count
     */
    public long getHits() {
        return hits.get();
    }

    /**
     * Get total number of misses.
     *
     * @return Miss count
     */
    public long getMisses() {
        return misses.get();
    }

    /**
     * Get total number of evictions.
     *
     * @return Eviction count
     */
    public long getEvictions() {
        return evictions.get();
    }

    /**
     * Get total number of invalidations.
     *
     * @return Invalidation count
     */
    public long getInvalidations() {
        return invalidations.get();
    }

    /**
     * Get total number of rejections.
     *
     * @return Rejection count
     */
    public long getRejections() {
        return rejections.get();
    }

    /**
     * Reset all statistics to zero.
     */
    public void reset() {
        hits.set(0);
        misses.set(0);
        evictions.set(0);
        invalidations.set(0);
        rejections.set(0);
    }

    @Override
    public String toString() {
        return String.format(
            "CacheStatistics{hits=%d, misses=%d, hitRate=%.2f%%, evictions=%d, invalidations=%d, rejections=%d}",
            hits.get(), misses.get(), getHitRate() * 100, evictions.get(), invalidations.get(), rejections.get()
        );
    }
}
