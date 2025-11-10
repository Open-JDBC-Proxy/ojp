package org.openjproxy.grpc.server.datasource;

import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.xa.AtomikosXAConnectionPool;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages dynamic pool sizing for Atomikos XA connection pools.
 * 
 * This class implements per-server pool size calculation and pool recreation
 * when server membership changes, mirroring HikariCP's dynamic pool-sizing approach.
 * 
 * Key responsibilities:
 * - Calculate per-server min and max pool sizes based on global configuration and number of UP servers
 * - Recreate Atomikos pools when server membership changes (increase or decrease)
 * - Safely handle pool recreation while preserving in-flight transactions
 * - Provide clear logging for pool operations and membership changes
 * 
 * Formula: perServerMin = max(1, ceil(configuredMin / numServersUp))
 *          perServerMax = max(1, ceil(configuredMax / numServersUp))
 */
@Slf4j
public class DynamicAtomikosPoolManager {
    
    // Map of connection hash to XA pool
    private final Map<String, AtomikosXAConnectionPool> xaPoolMap = new ConcurrentHashMap<>();
    
    // Map of connection hash to pool metadata (for recreation)
    private final Map<String, PoolMetadata> poolMetadataMap = new ConcurrentHashMap<>();
    
    /**
     * Metadata needed to recreate a pool when membership changes.
     */
    private static class PoolMetadata {
        final XADataSource xaDataSource;
        final String connHash;
        final Properties originalPoolConfig;
        final int configuredMaxPoolSize;
        final int configuredMinPoolSize;
        int currentServerCount;
        
        PoolMetadata(XADataSource xaDataSource, String connHash, Properties poolConfig, 
                    int maxPoolSize, int minPoolSize, int serverCount) {
            this.xaDataSource = xaDataSource;
            this.connHash = connHash;
            this.originalPoolConfig = poolConfig;
            this.configuredMaxPoolSize = maxPoolSize;
            this.configuredMinPoolSize = minPoolSize;
            this.currentServerCount = serverCount;
        }
    }
    
    /**
     * Result of per-server size calculation.
     */
    public static class PerServerSizes {
        public final int perServerMin;
        public final int perServerMax;
        
        public PerServerSizes(int perServerMin, int perServerMax) {
            this.perServerMin = perServerMin;
            this.perServerMax = perServerMax;
        }
    }
    
    /**
     * Creates or retrieves an Atomikos XA connection pool with dynamic sizing based on server count.
     * 
     * @param connHash Unique identifier for this connection configuration
     * @param xaDataSource The XADataSource to wrap
     * @param poolConfig Pool configuration properties
     * @param serverEndpoints List of server endpoints in the cluster (null or empty for single-node)
     * @return The Atomikos XA connection pool
     * @throws SQLException if pool creation fails
     */
    public AtomikosXAConnectionPool getOrCreatePool(String connHash, XADataSource xaDataSource, 
                                                   Properties poolConfig, List<String> serverEndpoints) 
            throws SQLException {
        
        // Check if pool already exists
        AtomikosXAConnectionPool existingPool = xaPoolMap.get(connHash);
        if (existingPool != null) {
            return existingPool;
        }
        
        // Parse configured sizes from properties
        int configuredMaxPoolSize = getIntProperty(poolConfig, "ojp.connection.pool.maximumPoolSize", 20);
        int configuredMinPoolSize = getIntProperty(poolConfig, "ojp.connection.pool.minimumIdle", 5);
        
        // Calculate server count
        int serverCount = (serverEndpoints == null || serverEndpoints.isEmpty()) ? 1 : serverEndpoints.size();
        
        // Calculate per-server sizes using the formula
        PerServerSizes sizes = calculatePerServerSizes(configuredMaxPoolSize, configuredMinPoolSize, serverCount);
        
        log.info("Creating Atomikos XA pool for {}: servers={}, configured max={}, min={}, per-server max={}, min={}", 
                connHash, serverCount, configuredMaxPoolSize, configuredMinPoolSize, 
                sizes.perServerMax, sizes.perServerMin);
        
        // Create pool with per-server sizes
        Properties adjustedConfig = createAdjustedPoolConfig(poolConfig, sizes);
        AtomikosXAConnectionPool pool = new AtomikosXAConnectionPool(xaDataSource, connHash, adjustedConfig);
        
        // Store pool and metadata
        xaPoolMap.put(connHash, pool);
        poolMetadataMap.put(connHash, new PoolMetadata(xaDataSource, connHash, poolConfig, 
                configuredMaxPoolSize, configuredMinPoolSize, serverCount));
        
        return pool;
    }
    
    /**
     * Calculates per-server min and max pool sizes based on configured values and server count.
     * 
     * Formula:
     *   perServerMin = max(1, ceil(configuredMin / numServersUp))
     *   perServerMax = max(1, ceil(configuredMax / numServersUp))
     * 
     * This ensures:
     * - At least 1 connection per server
     * - Total capacity distributed evenly across all UP servers
     * - Rounding up to avoid underprovisioning
     * 
     * @param configuredMax Global maximum pool size from configuration
     * @param configuredMin Global minimum pool size from configuration
     * @param numServersUp Number of UP servers in the cluster
     * @return PerServerSizes with calculated values
     */
    public PerServerSizes calculatePerServerSizes(int configuredMax, int configuredMin, int numServersUp) {
        if (numServersUp <= 0) {
            numServersUp = 1; // Fallback for safety
        }
        
        int perServerMax = Math.max(1, (int) Math.ceil((double) configuredMax / numServersUp));
        int perServerMin = Math.max(1, (int) Math.ceil((double) configuredMin / numServersUp));
        
        log.debug("Calculated per-server sizes: configuredMax={}, configuredMin={}, servers={} -> perServerMax={}, perServerMin={}", 
                configuredMax, configuredMin, numServersUp, perServerMax, perServerMin);
        
        return new PerServerSizes(perServerMin, perServerMax);
    }
    
    /**
     * Recreates Atomikos pools when server membership changes.
     * 
     * This method:
     * 1. Calculates new per-server sizes based on updated server count
     * 2. Gracefully closes the existing pool
     * 3. Creates a new pool with updated sizes
     * 4. Preserves pool metadata for future recreations
     * 
     * @param connHash Connection hash identifying the pool
     * @param newServerCount Updated number of UP servers
     * @throws SQLException if pool recreation fails
     */
    public void recreatePoolForNewMembership(String connHash, int newServerCount) throws SQLException {
        log.debug("Membership change trigger for {}: new server count = {}", connHash, newServerCount);
        
        PoolMetadata metadata = poolMetadataMap.get(connHash);
        if (metadata == null) {
            log.warn("Cannot recreate pool for {}: no metadata found", connHash);
            return;
        }
        
        // Check if server count actually changed
        if (metadata.currentServerCount == newServerCount) {
            log.debug("Server count unchanged for {}, skipping recreation", connHash);
            return;
        }
        
        log.info("Recreating Atomikos XA pool for {} due to membership change: {} -> {} servers", 
                connHash, metadata.currentServerCount, newServerCount);
        
        // Calculate new per-server sizes
        PerServerSizes newSizes = calculatePerServerSizes(
                metadata.configuredMaxPoolSize, 
                metadata.configuredMinPoolSize, 
                newServerCount);
        
        // Get and close existing pool
        AtomikosXAConnectionPool oldPool = xaPoolMap.remove(connHash);
        if (oldPool != null) {
            log.debug("Closing old pool for {}", connHash);
            oldPool.close();
        }
        
        // Create new pool with updated sizes
        Properties adjustedConfig = createAdjustedPoolConfig(metadata.originalPoolConfig, newSizes);
        AtomikosXAConnectionPool newPool = new AtomikosXAConnectionPool(
                metadata.xaDataSource, connHash, adjustedConfig);
        
        // Update maps
        xaPoolMap.put(connHash, newPool);
        metadata.currentServerCount = newServerCount;
        
        log.info("Recreated Atomikos XA pool for {}: new per-server max={}, min={}, {}", 
                connHash, newSizes.perServerMax, newSizes.perServerMin, newPool.getPoolStats());
    }
    
    /**
     * Handles server membership updates for all pools.
     * Recreates pools that need size adjustments based on the new membership.
     * 
     * @param healthyServerCount New count of healthy servers
     */
    public void updateServerMembership(int healthyServerCount) {
        log.debug("Global membership update: {} healthy servers", healthyServerCount);
        
        // Recreate all pools with new server count
        for (String connHash : poolMetadataMap.keySet()) {
            try {
                recreatePoolForNewMembership(connHash, healthyServerCount);
            } catch (SQLException e) {
                log.error("Failed to recreate pool for {} after membership change: {}", 
                        connHash, e.getMessage(), e);
            }
        }
    }
    
    /**
     * Gets an existing pool without creating a new one.
     * 
     * @param connHash Connection hash
     * @return The pool or null if not found
     */
    public AtomikosXAConnectionPool getPool(String connHash) {
        return xaPoolMap.get(connHash);
    }
    
    /**
     * Borrows an XA connection from the pool.
     * 
     * @param connHash Connection hash
     * @param sessionId Session identifier
     * @param branchId XA branch identifier
     * @return XAConnection
     * @throws SQLException if connection cannot be acquired
     */
    public XAConnection borrowConnection(String connHash, String sessionId, String branchId) 
            throws SQLException {
        AtomikosXAConnectionPool pool = xaPoolMap.get(connHash);
        if (pool == null) {
            throw new SQLException("No XA pool found for connection hash: " + connHash);
        }
        return pool.borrowXAConnection(sessionId, branchId);
    }
    
    /**
     * Returns an XA connection to the pool.
     * 
     * @param connHash Connection hash
     * @param sessionId Session identifier
     * @param branchId XA branch identifier
     * @throws SQLException if connection cannot be returned
     */
    public void returnConnection(String connHash, String sessionId, String branchId) 
            throws SQLException {
        AtomikosXAConnectionPool pool = xaPoolMap.get(connHash);
        if (pool != null) {
            pool.returnXAConnection(sessionId, branchId);
        }
    }
    
    /**
     * Closes a specific pool and removes it from management.
     * 
     * @param connHash Connection hash
     */
    public void closePool(String connHash) {
        AtomikosXAConnectionPool pool = xaPoolMap.remove(connHash);
        if (pool != null) {
            log.info("Closing Atomikos XA pool for {}", connHash);
            pool.close();
        }
        poolMetadataMap.remove(connHash);
    }
    
    /**
     * Closes all pools managed by this manager.
     */
    public void closeAll() {
        log.info("Closing all Atomikos XA pools managed by DynamicAtomikosPoolManager");
        for (Map.Entry<String, AtomikosXAConnectionPool> entry : xaPoolMap.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.error("Error closing pool {}: {}", entry.getKey(), e.getMessage());
            }
        }
        xaPoolMap.clear();
        poolMetadataMap.clear();
    }
    
    // Helper methods
    
    private Properties createAdjustedPoolConfig(Properties originalConfig, PerServerSizes sizes) {
        Properties adjusted = new Properties();
        adjusted.putAll(originalConfig);
        
        // Override pool sizes with per-server calculated values
        adjusted.setProperty("ojp.connection.pool.maximumPoolSize", String.valueOf(sizes.perServerMax));
        adjusted.setProperty("ojp.connection.pool.minimumIdle", String.valueOf(sizes.perServerMin));
        
        return adjusted;
    }
    
    private int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value for {}: '{}', using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
}
