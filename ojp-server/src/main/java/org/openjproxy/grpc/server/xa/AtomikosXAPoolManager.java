package org.openjproxy.grpc.server.xa;

import lombok.extern.slf4j.Slf4j;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages Atomikos XA connection pool lifecycle with support for pool recreation
 * on cluster health changes.
 * 
 * This manager wraps an AtomikosXAConnectionPool and handles graceful pool switching
 * when pool sizes need to change due to server failures/recoveries.
 * 
 * Key responsibilities:
 * - Hold current active pool
 * - Coordinate pool recreation when cluster health changes
 * - Ensure graceful transition: wait for existing transactions, create new pool, switch
 * - Hold new requests during pool switching
 */
@Slf4j
public class AtomikosXAPoolManager {
    
    private final String connHash;
    private final XADataSource xaDataSource;
    private final AtomicReference<AtomikosXAConnectionPool> currentPool;
    private final AtomicReference<PoolRecreationTask> recreationTask;
    private final ReentrantReadWriteLock poolLock;
    private final AtomicBoolean isShuttingDown;
    
    /**
     * Creates a pool manager with an initial pool.
     * 
     * @param xaDataSource The XADataSource to use for creating pools
     * @param connHash Connection hash identifier
     * @param initialPoolConfig Initial pool configuration
     * @throws SQLException if initial pool creation fails
     */
    public AtomikosXAPoolManager(XADataSource xaDataSource, String connHash, Properties initialPoolConfig) 
            throws SQLException {
        this.xaDataSource = xaDataSource;
        this.connHash = connHash;
        this.currentPool = new AtomicReference<>();
        this.recreationTask = new AtomicReference<>();
        this.poolLock = new ReentrantReadWriteLock();
        this.isShuttingDown = new AtomicBoolean(false);
        
        // Create initial pool
        AtomikosXAConnectionPool initialPool = new AtomikosXAConnectionPool(
                xaDataSource, connHash, initialPoolConfig);
        this.currentPool.set(initialPool);
        
        log.info("Created AtomikosXAPoolManager for {}: {}", connHash, initialPool.getPoolStats());
    }
    
    /**
     * Borrows an XA connection from the current active pool.
     * This method blocks if a pool recreation is in progress.
     * 
     * @param sessionId Session identifier
     * @param branchId Branch identifier
     * @return XAConnection from the pool
     * @throws SQLException if connection cannot be acquired
     */
    public XAConnection borrowXAConnection(String sessionId, String branchId) throws SQLException {
        if (isShuttingDown.get()) {
            throw new SQLException("Pool manager is shutting down, cannot borrow connections");
        }
        
        // Acquire read lock - allows multiple concurrent borrows but blocks during pool recreation
        poolLock.readLock().lock();
        try {
            AtomikosXAConnectionPool pool = currentPool.get();
            if (pool == null) {
                throw new SQLException("No active pool available");
            }
            
            return pool.borrowXAConnection(sessionId, branchId);
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Returns an XA connection to the current active pool.
     * 
     * @param sessionId Session identifier
     * @param branchId Branch identifier
     * @throws SQLException if connection return fails
     */
    public void returnXAConnection(String sessionId, String branchId) throws SQLException {
        poolLock.readLock().lock();
        try {
            AtomikosXAConnectionPool pool = currentPool.get();
            if (pool != null) {
                pool.returnXAConnection(sessionId, branchId);
            }
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Returns an XA connection directly to the current active pool.
     * 
     * @param xaConnection The connection to return
     */
    public void returnXAConnection(XAConnection xaConnection) {
        poolLock.readLock().lock();
        try {
            AtomikosXAConnectionPool pool = currentPool.get();
            if (pool != null) {
                pool.returnXAConnection(xaConnection);
            }
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Recreates the pool with new configuration.
     * This is an asynchronous operation that:
     * 1. Creates a new pool with the new configuration
     * 2. Waits for existing transactions in old pool to complete (with timeout)
     * 3. Switches to the new pool atomically
     * 4. Closes the old pool
     * 
     * New connection requests are blocked during the switch.
     * 
     * @param newPoolConfig New pool configuration with updated sizes
     * @return CompletableFuture that completes when pool recreation is done
     */
    public CompletableFuture<Void> recreatePool(Properties newPoolConfig) {
        if (isShuttingDown.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Cannot recreate pool - manager is shutting down"));
        }
        
        // Check if a recreation is already in progress
        PoolRecreationTask existingTask = recreationTask.get();
        if (existingTask != null && !existingTask.future.isDone()) {
            log.info("Pool recreation already in progress for {}, returning existing task", connHash);
            return existingTask.future;
        }
        
        // Create new recreation task
        CompletableFuture<Void> future = new CompletableFuture<>();
        PoolRecreationTask newTask = new PoolRecreationTask(future, newPoolConfig);
        
        if (!recreationTask.compareAndSet(existingTask, newTask)) {
            // Another thread started recreation, return that task's future
            PoolRecreationTask current = recreationTask.get();
            return current != null ? current.future : future;
        }
        
        // Execute recreation asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                executePoolRecreation(newPoolConfig);
                future.complete(null);
            } catch (Exception e) {
                log.error("Pool recreation failed for {}: {}", connHash, e.getMessage(), e);
                future.completeExceptionally(e);
            } finally {
                recreationTask.compareAndSet(newTask, null);
            }
        });
        
        return future;
    }
    
    /**
     * Executes the pool recreation logic.
     * This method acquires the write lock to ensure no concurrent connection operations.
     */
    private void executePoolRecreation(Properties newPoolConfig) throws SQLException {
        log.info("Starting pool recreation for {}", connHash);
        
        // Create new pool first (before acquiring write lock)
        AtomikosXAConnectionPool newPool;
        try {
            newPool = new AtomikosXAConnectionPool(xaDataSource, connHash + "-new", newPoolConfig);
            log.info("Created new pool for {}: {}", connHash, newPool.getPoolStats());
        } catch (SQLException e) {
            log.error("Failed to create new pool for {}: {}", connHash, e.getMessage(), e);
            throw e;
        }
        
        // Acquire write lock - blocks all new connection operations
        poolLock.writeLock().lock();
        try {
            AtomikosXAConnectionPool oldPool = currentPool.get();
            
            if (oldPool == null) {
                // No old pool, just set the new one
                currentPool.set(newPool);
                log.info("Switched to new pool for {} (no old pool)", connHash);
                return;
            }
            
            // Wait for existing transactions to complete in old pool
            // We give a reasonable timeout for active connections to be returned
            int waitAttempts = 30; // Wait up to 30 seconds
            int leasedConnections = oldPool.getLeasedConnectionCount();
            
            log.info("Waiting for {} leased connections in old pool to be returned for {}", 
                    leasedConnections, connHash);
            
            for (int i = 0; i < waitAttempts && leasedConnections > 0; i++) {
                try {
                    Thread.sleep(1000); // Wait 1 second
                    leasedConnections = oldPool.getLeasedConnectionCount();
                    
                    if (leasedConnections > 0) {
                        log.debug("Still waiting for {} leased connections to be returned for {} (attempt {}/{})", 
                                leasedConnections, connHash, i + 1, waitAttempts);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for connections to be returned for {}", connHash);
                    break;
                }
            }
            
            if (leasedConnections > 0) {
                log.warn("Proceeding with pool switch for {} despite {} active connections", 
                        connHash, leasedConnections);
            }
            
            // Switch to new pool
            currentPool.set(newPool);
            log.info("Switched to new pool for {}: {}", connHash, newPool.getPoolStats());
            
            // Close old pool
            try {
                oldPool.close();
                log.info("Closed old pool for {}", connHash);
            } catch (Exception e) {
                log.error("Error closing old pool for {}: {}", connHash, e.getMessage(), e);
            }
            
        } finally {
            poolLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets the current pool statistics.
     */
    public String getPoolStats() {
        poolLock.readLock().lock();
        try {
            AtomikosXAConnectionPool pool = currentPool.get();
            return pool != null ? pool.getPoolStats() : "No active pool";
        } finally {
            poolLock.readLock().unlock();
        }
    }
    
    /**
     * Closes the pool manager and releases all resources.
     */
    public void close() {
        isShuttingDown.set(true);
        
        log.info("Closing AtomikosXAPoolManager for {}", connHash);
        
        // Wait for any in-progress recreation to complete
        PoolRecreationTask task = recreationTask.get();
        if (task != null && !task.future.isDone()) {
            log.info("Waiting for pool recreation to complete before closing {}", connHash);
            try {
                task.future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Error waiting for pool recreation to complete for {}: {}", 
                        connHash, e.getMessage());
            }
        }
        
        poolLock.writeLock().lock();
        try {
            AtomikosXAConnectionPool pool = currentPool.get();
            if (pool != null) {
                pool.close();
                currentPool.set(null);
            }
        } finally {
            poolLock.writeLock().unlock();
        }
        
        log.info("Closed AtomikosXAPoolManager for {}", connHash);
    }
    
    /**
     * Internal class to track pool recreation tasks.
     */
    private static class PoolRecreationTask {
        final CompletableFuture<Void> future;
        final Properties newPoolConfig;
        
        PoolRecreationTask(CompletableFuture<Void> future, Properties newPoolConfig) {
            this.future = future;
            this.newPoolConfig = newPoolConfig;
        }
    }
}
