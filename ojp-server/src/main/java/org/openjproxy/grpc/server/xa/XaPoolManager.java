package org.openjproxy.grpc.server.xa;

import com.openjproxy.grpc.ConnectionDetails;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.ServerConfiguration;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages Atomikos XA connection pools with support for safe recreation
 * triggered by cluster/server health changes.
 * 
 * Key responsibilities:
 * - Maintain Map<String, AtomikosXAConnectionPool> guarded by ReentrantReadWriteLock
 * - Provide thread-safe access to pools (read lock held for connection lifetime)
 * - Support asynchronous, debounced pool recreation on health changes
 * - Abort recreation attempts on timeout
 * 
 * Thread Safety:
 * - Read lock: Held during normal pool operations (borrow/return connections)
 * - Write lock: Held during pool recreation (close old pool, create new pool)
 * - Debouncing: Prevents rapid successive recreations for same connection hash
 * 
 * Performance:
 * - Uses virtual threads for pool recreation (requires Java 21+)
 * 
 * @requires Java 21 or later
 */
@Slf4j
public class XaPoolManager {
    
    // Map of connection hash to XA connection pool, guarded by the lock
    private final Map<String, AtomikosXAConnectionPool> poolMap = new ConcurrentHashMap<>();
    
    // Read-write lock for thread-safe pool access and recreation
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Executor for asynchronous pool recreation using virtual threads
    private final ExecutorService recreationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Scheduled executor for delayed recreation (debouncing)
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "xa-pool-recreation-scheduler");
        t.setDaemon(true);
        return t;
    });
    
    // Map of connection hash to scheduled recreation futures (for cancellation)
    private final Map<String, ScheduledFuture<?>> scheduledRecreations = new ConcurrentHashMap<>();
    
    // Map of connection hash to last recreation time for tracking
    private final Map<String, Long> lastRecreationTime = new ConcurrentHashMap<>();
    
    // Map of connection hash to pending recreation futures
    private final Map<String, CompletableFuture<Void>> pendingRecreations = new ConcurrentHashMap<>();
    
    // Configuration - can be set via constructor or defaults
    private final long debounceIntervalMs;
    private final long recreationTimeoutMs;
    
    /**
     * Creates a new XaPoolManager with default configuration values.
     */
    public XaPoolManager() {
        this.debounceIntervalMs = ServerConfiguration.DEFAULT_XA_POOL_RECREATION_DEBOUNCE_MS;
        this.recreationTimeoutMs = ServerConfiguration.DEFAULT_XA_POOL_RECREATION_TIMEOUT_MS;
    }
    
    /**
     * Creates a new XaPoolManager with custom configuration values.
     * 
     * @param debounceIntervalMs Minimum interval between recreation attempts (milliseconds)
     * @param recreationTimeoutMs Maximum time to wait for recreation to complete (milliseconds)
     */
    public XaPoolManager(long debounceIntervalMs, long recreationTimeoutMs) {
        this.debounceIntervalMs = debounceIntervalMs;
        this.recreationTimeoutMs = recreationTimeoutMs;
        log.info("XaPoolManager initialized with debounceIntervalMs={}, recreationTimeoutMs={}", 
                debounceIntervalMs, recreationTimeoutMs);
    }
    
    /**
     * Gets or creates an XA connection pool for the given connection hash.
     * Holds read lock during pool access to prevent recreation while in use.
     * 
     * @param connHash Connection hash
     * @param xaDataSourceFactory Factory function to create XADataSource if pool doesn't exist
     * @param poolConfig Pool configuration properties
     * @return AtomikosXAConnectionPool instance
     * @throws SQLException if pool creation fails
     */
    public AtomikosXAConnectionPool getOrCreatePool(
            String connHash,
            XADataSourceFactory xaDataSourceFactory,
            Properties poolConfig) throws SQLException {
        
        // Try to get existing pool with read lock (fast path)
        lock.readLock().lock();
        try {
            AtomikosXAConnectionPool existingPool = poolMap.get(connHash);
            if (existingPool != null) {
                return existingPool;
            }
        } finally {
            lock.readLock().unlock();
        }
        
        // Pool doesn't exist, need to create it with write lock
        lock.writeLock().lock();
        try {
            // Double-check after acquiring write lock
            AtomikosXAConnectionPool existingPool = poolMap.get(connHash);
            if (existingPool != null) {
                return existingPool;
            }
            
            // Create new pool
            XADataSource xaDataSource = xaDataSourceFactory.create();
            AtomikosXAConnectionPool newPool = new AtomikosXAConnectionPool(
                    xaDataSource, connHash, poolConfig);
            
            poolMap.put(connHash, newPool);
            log.info("Created new XA pool for connHash: {} - {}", connHash, newPool.getPoolStats());
            
            return newPool;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Gets an existing pool if it exists, or returns null.
     * Holds read lock during pool access.
     * 
     * @param connHash Connection hash
     * @return AtomikosXAConnectionPool or null if not found
     */
    public AtomikosXAConnectionPool getPool(String connHash) {
        lock.readLock().lock();
        try {
            return poolMap.get(connHash);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Borrows an XAConnection from the pool for a session/branch.
     * Holds read lock to prevent pool recreation during borrow.
     * 
     * @param connHash Connection hash
     * @param sessionId Session identifier
     * @param branchId XA branch identifier
     * @return XAConnection leased for this session/branch
     * @throws SQLException if connection cannot be acquired or pool doesn't exist
     */
    public XAConnection borrowConnection(String connHash, String sessionId, String branchId) 
            throws SQLException {
        lock.readLock().lock();
        try {
            AtomikosXAConnectionPool pool = poolMap.get(connHash);
            if (pool == null) {
                throw new SQLException("XA pool not found for connection hash: " + connHash);
            }
            return pool.borrowXAConnection(sessionId, branchId);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Returns an XAConnection to the pool.
     * Holds read lock to prevent pool recreation during return.
     * 
     * @param connHash Connection hash
     * @param sessionId Session identifier
     * @param branchId XA branch identifier
     * @throws SQLException if connection return fails
     */
    public void returnConnection(String connHash, String sessionId, String branchId) 
            throws SQLException {
        lock.readLock().lock();
        try {
            AtomikosXAConnectionPool pool = poolMap.get(connHash);
            if (pool != null) {
                pool.returnXAConnection(sessionId, branchId);
            } else {
                log.warn("Cannot return connection to non-existent pool: {}", connHash);
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Triggers asynchronous recreation of an XA pool due to health changes.
     * Schedules recreation after debounce interval to prevent recreation on transient health changes.
     * If health changes again before the interval expires, the scheduled recreation is cancelled.
     * 
     * @param connHash Connection hash
     * @param xaDataSourceFactory Factory function to create new XADataSource
     * @param poolConfig Pool configuration properties
     */
    public void triggerPoolRecreation(
            String connHash,
            XADataSourceFactory xaDataSourceFactory,
            Properties poolConfig) {
        
        // Cancel any existing scheduled recreation for this connection hash
        ScheduledFuture<?> existingScheduled = scheduledRecreations.get(connHash);
        if (existingScheduled != null && !existingScheduled.isDone()) {
            log.debug("Cancelling previously scheduled recreation for {} due to new health change", connHash);
            existingScheduled.cancel(false);
        }
        
        // Check if recreation is currently in progress
        CompletableFuture<Void> existingRecreation = pendingRecreations.get(connHash);
        if (existingRecreation != null && !existingRecreation.isDone()) {
            log.debug("Pool recreation already in progress for {}, scheduling will wait", connHash);
            // Don't schedule a new one if recreation is already running
            return;
        }
        
        log.info("Scheduling XA pool recreation for {} after {}ms debounce interval", 
                connHash, debounceIntervalMs);
        
        // Schedule recreation after debounce interval
        ScheduledFuture<?> scheduledFuture = scheduledExecutor.schedule(() -> {
            // Remove from scheduled map as we're now executing
            scheduledRecreations.remove(connHash);
            
            log.info("Debounce interval elapsed, triggering XA pool recreation for {}", connHash);
            
            // Start asynchronous recreation with timeout
            CompletableFuture<Void> recreationFuture = CompletableFuture.runAsync(() -> {
                try {
                    recreatePool(connHash, xaDataSourceFactory, poolConfig);
                } catch (Exception e) {
                    log.error("Failed to recreate XA pool for {}: {}", connHash, e.getMessage(), e);
                }
            }, recreationExecutor)
            .orTimeout(recreationTimeoutMs, TimeUnit.MILLISECONDS)
            .exceptionally(throwable -> {
                if (throwable instanceof TimeoutException) {
                    log.error("XA pool recreation timed out after {}ms for {}", 
                            recreationTimeoutMs, connHash);
                } else {
                    log.error("XA pool recreation failed for {}: {}", 
                            connHash, throwable.getMessage(), throwable);
                }
                return null;
            });
            
            pendingRecreations.put(connHash, recreationFuture);
            lastRecreationTime.put(connHash, System.currentTimeMillis());
        }, debounceIntervalMs, TimeUnit.MILLISECONDS);
        
        scheduledRecreations.put(connHash, scheduledFuture);
    }
    
    /**
     * Recreates an XA pool. Called asynchronously.
     * Acquires write lock, closes old pool, creates new pool.
     * 
     * @param connHash Connection hash
     * @param xaDataSourceFactory Factory function to create new XADataSource
     * @param poolConfig Pool configuration properties
     * @throws SQLException if recreation fails
     */
    private void recreatePool(
            String connHash,
            XADataSourceFactory xaDataSourceFactory,
            Properties poolConfig) throws SQLException {
        
        log.info("Starting XA pool recreation for {}", connHash);
        
        lock.writeLock().lock();
        try {
            // Get and close old pool
            AtomikosXAConnectionPool oldPool = poolMap.get(connHash);
            if (oldPool != null) {
                log.info("Closing old XA pool for {}: {}", connHash, oldPool.getPoolStats());
                try {
                    oldPool.close();
                } catch (Exception e) {
                    log.error("Error closing old XA pool for {}: {}", connHash, e.getMessage(), e);
                    // Continue with recreation even if close fails
                }
            }
            
            // Create new pool
            XADataSource xaDataSource = xaDataSourceFactory.create();
            AtomikosXAConnectionPool newPool = new AtomikosXAConnectionPool(
                    xaDataSource, connHash, poolConfig);
            
            poolMap.put(connHash, newPool);
            log.info("Successfully recreated XA pool for {}: {}", connHash, newPool.getPoolStats());
            
        } finally {
            lock.writeLock().unlock();
            pendingRecreations.remove(connHash);
        }
    }
    
    /**
     * Closes a specific XA pool and removes it from management.
     * 
     * @param connHash Connection hash
     */
    public void closePool(String connHash) {
        lock.writeLock().lock();
        try {
            AtomikosXAConnectionPool pool = poolMap.remove(connHash);
            if (pool != null) {
                log.info("Closing XA pool for {}: {}", connHash, pool.getPoolStats());
                try {
                    pool.close();
                } catch (Exception e) {
                    log.error("Error closing XA pool for {}: {}", connHash, e.getMessage(), e);
                }
            }
            lastRecreationTime.remove(connHash);
            pendingRecreations.remove(connHash);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Closes all XA pools and shuts down the manager.
     * Should be called on server shutdown.
     */
    public void shutdown() {
        log.info("Shutting down XA pool manager...");
        
        // Cancel scheduled recreations
        for (ScheduledFuture<?> future : scheduledRecreations.values()) {
            future.cancel(false);
        }
        scheduledRecreations.clear();
        
        // Cancel pending recreations
        for (CompletableFuture<Void> future : pendingRecreations.values()) {
            future.cancel(true);
        }
        pendingRecreations.clear();
        
        // Close all pools
        lock.writeLock().lock();
        try {
            for (Map.Entry<String, AtomikosXAConnectionPool> entry : poolMap.entrySet()) {
                try {
                    log.info("Closing XA pool {}: {}", 
                            entry.getKey(), entry.getValue().getPoolStats());
                    entry.getValue().close();
                } catch (Exception e) {
                    log.error("Error closing XA pool {}: {}", 
                            entry.getKey(), e.getMessage(), e);
                }
            }
            poolMap.clear();
        } finally {
            lock.writeLock().unlock();
        }
        
        lastRecreationTime.clear();
        
        // Shutdown scheduled executor
        scheduledExecutor.shutdown();
        try {
            if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Shutdown recreation executor
        recreationExecutor.shutdown();
        try {
            if (!recreationExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                recreationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            recreationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("XA pool manager shutdown complete");
    }
    
    /**
     * Gets statistics for a specific pool.
     * 
     * @param connHash Connection hash
     * @return Pool statistics string, or null if pool doesn't exist
     */
    public String getPoolStats(String connHash) {
        lock.readLock().lock();
        try {
            AtomikosXAConnectionPool pool = poolMap.get(connHash);
            return pool != null ? pool.getPoolStats() : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Factory interface for creating XADataSource instances.
     * Used to defer XADataSource creation until needed.
     */
    @FunctionalInterface
    public interface XADataSourceFactory {
        XADataSource create() throws SQLException;
    }
}
