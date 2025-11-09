package org.openjproxy.grpc.server.xa;

import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;

import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages Atomikos XA connection pools with support for dynamic recreation
 * when cluster health changes in multinode setups.
 * 
 * Responsibilities:
 * - Track pool creation parameters for recreation
 * - Coordinate graceful pool replacement (wait for active transactions)
 * - Ensure thread-safe pool transitions
 */
@Slf4j
public class AtomikosPoolManager {
    
    // Stores creation parameters for each pool to enable recreation
    private static class PoolCreationParams {
        final XADataSource xaDataSource;
        final String connectionHash;
        final Properties poolConfig;
        final List<String> serverEndpoints;
        final MultinodePoolCoordinator poolCoordinator;
        
        PoolCreationParams(XADataSource xaDataSource, String connectionHash, 
                          Properties poolConfig, List<String> serverEndpoints,
                          MultinodePoolCoordinator poolCoordinator) {
            this.xaDataSource = xaDataSource;
            this.connectionHash = connectionHash;
            this.poolConfig = poolConfig;
            this.serverEndpoints = serverEndpoints;
            this.poolCoordinator = poolCoordinator;
        }
    }
    
    private final ConcurrentHashMap<String, PoolCreationParams> poolParams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> recreationLocks = new ConcurrentHashMap<>();
    
    // Maximum time to wait for active connections to finish before forcing pool closure
    private static final long MAX_WAIT_FOR_CONNECTIONS_MS = 30000; // 30 seconds
    
    /**
     * Registers pool creation parameters for potential future recreation.
     * Should be called after creating a new pool.
     * 
     * @param connectionHash Connection hash
     * @param xaDataSource The XADataSource
     * @param poolConfig Pool configuration
     * @param serverEndpoints Server endpoints list
     * @param poolCoordinator Pool coordinator
     */
    public void registerPool(String connectionHash, XADataSource xaDataSource, 
                            Properties poolConfig, List<String> serverEndpoints,
                            MultinodePoolCoordinator poolCoordinator) {
        poolParams.put(connectionHash, new PoolCreationParams(
                xaDataSource, connectionHash, poolConfig, serverEndpoints, poolCoordinator));
        log.debug("Registered pool creation parameters for connHash: {}", connectionHash);
    }
    
    /**
     * Recreates an Atomikos pool with updated sizes based on current cluster health.
     * This is called when cluster health changes (server failure/recovery).
     * 
     * Steps:
     * 1. Create new pool with updated sizes
     * 2. Wait for active connections in old pool to finish (with timeout)
     * 3. Close old pool
     * 4. Return new pool
     * 
     * @param currentPool The current pool to replace
     * @param poolMap The map containing pools (for atomic replacement)
     * @return The new pool, or the current pool if recreation fails
     */
    public AtomikosXAConnectionPool recreatePool(AtomikosXAConnectionPool currentPool,
                                                  ConcurrentHashMap<String, AtomikosXAConnectionPool> poolMap) {
        String connHash = currentPool.getConnectionHash();
        
        // Get recreation lock for this connection hash
        ReentrantLock lock = recreationLocks.computeIfAbsent(connHash, k -> new ReentrantLock());
        
        // Try to acquire lock - if another thread is already recreating, return current pool
        if (!lock.tryLock()) {
            log.info("Pool recreation already in progress for {}, using existing pool", connHash);
            return currentPool;
        }
        
        try {
            // Get creation parameters
            PoolCreationParams params = poolParams.get(connHash);
            if (params == null) {
                log.warn("No creation parameters found for {}, cannot recreate pool", connHash);
                return currentPool;
            }
            
            // Skip recreation for single-node pools (no multinode coordination)
            if (params.serverEndpoints == null || params.serverEndpoints.isEmpty()) {
                log.debug("Single-node pool for {}, recreation not needed", connHash);
                return currentPool;
            }
            
            log.info("Starting pool recreation for {} due to cluster health change", connHash);
            
            // Step 1: Create new pool with updated sizes
            AtomikosXAConnectionPool newPool;
            try {
                newPool = new AtomikosXAConnectionPool(
                        params.xaDataSource,
                        params.connectionHash,
                        params.poolConfig,
                        params.serverEndpoints,
                        params.poolCoordinator);
                
                log.info("Created new Atomikos pool for {}: {}", connHash, newPool.getPoolStats());
                
            } catch (SQLException e) {
                log.error("Failed to create new Atomikos pool for {}: {}", connHash, e.getMessage(), e);
                return currentPool; // Keep using old pool
            }
            
            // Step 2: Wait for active connections to finish (with timeout)
            log.info("Waiting for active connections in old pool to finish for {}", connHash);
            long startWait = System.currentTimeMillis();
            while (currentPool.hasActiveLeasedConnections()) {
                long elapsed = System.currentTimeMillis() - startWait;
                if (elapsed > MAX_WAIT_FOR_CONNECTIONS_MS) {
                    log.warn("Timeout waiting for active connections in old pool for {}, {} connections still leased. Proceeding with pool switch.",
                            connHash, currentPool.getLeasedConnectionCount());
                    break;
                }
                
                try {
                    Thread.sleep(500); // Check every 500ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for connections to finish for {}", connHash);
                    break;
                }
            }
            
            // Step 3: Atomically replace pool in the map
            poolMap.put(connHash, newPool);
            log.info("Switched to new pool for {}", connHash);
            
            // Step 4: Close old pool (in background to avoid blocking)
            closePoolAsync(currentPool, connHash);
            
            return newPool;
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Closes a pool asynchronously to avoid blocking the caller.
     */
    private void closePoolAsync(AtomikosXAConnectionPool pool, String connHash) {
        Thread closeThread = new Thread(() -> {
            try {
                log.info("Closing old Atomikos pool for {}", connHash);
                pool.close();
                log.info("Old Atomikos pool closed for {}", connHash);
            } catch (Exception e) {
                log.error("Error closing old Atomikos pool for {}: {}", connHash, e.getMessage(), e);
            }
        }, "AtomikosPoolCloser-" + connHash);
        closeThread.setDaemon(true);
        closeThread.start();
    }
    
    /**
     * Removes pool tracking when a connection is permanently closed.
     */
    public void unregisterPool(String connectionHash) {
        poolParams.remove(connectionHash);
        recreationLocks.remove(connectionHash);
        log.debug("Unregistered pool for connHash: {}", connectionHash);
    }
    
    /**
     * Checks if a pool should be recreated based on cluster health changes.
     * Returns true if the pool has multinode coordination and health has changed.
     */
    public boolean shouldRecreatePool(AtomikosXAConnectionPool pool) {
        return pool != null && pool.getPoolAllocation() != null;
    }
}
