package org.openjproxy.grpc.server.sql;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe cache for database schema metadata.
 * Handles concurrent access and schema refresh coordination.
 */
@Slf4j
public class SchemaCache {
    
    private volatile SchemaMetadata currentSchema;
    private volatile long lastRefreshTimestamp;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    
    /**
     * Creates a new empty schema cache.
     */
    public SchemaCache() {
        this.currentSchema = null;
        this.lastRefreshTimestamp = 0;
    }
    
    /**
     * Gets the current schema, optionally falling back to a generic schema.
     * 
     * @param fallbackToGeneric If true and no schema is available, returns null
     *                          (caller should handle fallback)
     * @return Current schema metadata or null
     */
    public SchemaMetadata getSchema(boolean fallbackToGeneric) {
        SchemaMetadata current = currentSchema;
        
        if (current == null && fallbackToGeneric) {
            log.debug("No real schema available in cache");
        }
        
        return current;
    }
    
    /**
     * Updates the cached schema with new metadata.
     * This is thread-safe due to volatile semantics.
     * 
     * @param newSchema The new schema metadata to cache
     */
    public void updateSchema(SchemaMetadata newSchema) {
        if (newSchema == null) {
            log.warn("Attempted to update schema with null value");
            return;
        }
        
        this.currentSchema = newSchema;
        this.lastRefreshTimestamp = System.nanoTime();
        
        log.info("Schema cache updated with {} tables, loaded at timestamp: {}", 
                newSchema.getTables().size(), newSchema.getLoadTimestamp());
    }
    
    /**
     * Checks if the schema needs to be refreshed based on the interval.
     * 
     * @param refreshIntervalMillis Minimum time between refreshes in milliseconds
     * @return true if refresh is needed, false otherwise
     */
    public boolean needsRefresh(long refreshIntervalMillis) {
        if (refreshIntervalMillis <= 0) {
            return false; // Refresh disabled
        }
        
        long timeSinceLastRefresh = (System.nanoTime() - lastRefreshTimestamp) / 1_000_000L;
        return timeSinceLastRefresh >= refreshIntervalMillis;
    }
    
    /**
     * Attempts to acquire the refresh lock.
     * Only one thread should be allowed to refresh at a time.
     * 
     * @return true if lock was acquired, false if refresh is already in progress
     */
    public boolean tryAcquireRefreshLock() {
        boolean acquired = refreshInProgress.compareAndSet(false, true);
        if (acquired) {
            log.debug("Acquired schema refresh lock");
        } else {
            log.debug("Schema refresh already in progress");
        }
        return acquired;
    }
    
    /**
     * Releases the refresh lock.
     */
    public void releaseRefreshLock() {
        refreshInProgress.set(false);
        log.debug("Released schema refresh lock");
    }
    
    /**
     * Gets the last refresh timestamp.
     * 
     * @return Timestamp in milliseconds since epoch
     */
    public long getLastRefreshTimestamp() {
        return lastRefreshTimestamp;
    }
    
    /**
     * Checks if a refresh is currently in progress.
     * 
     * @return true if refresh is in progress
     */
    public boolean isRefreshInProgress() {
        return refreshInProgress.get();
    }
}
