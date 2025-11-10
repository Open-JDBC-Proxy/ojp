package org.openjproxy.grpc.server.xa;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Atomikos-based XA connection pooling for OJP server.
 * This class wraps JDBC driver XADataSources with AtomikosDataSourceBean for connection pooling.
 * 
 * IMPORTANT: This is a POOLING ONLY solution - NO transaction manager behavior on server.
 * Server remains XA pass-through: clients control transaction lifecycle via XAResource.
 * 
 * Key responsibilities:
 * - Create AtomikosDataSourceBean wrapping driver's XADataSource (e.g., PGXADataSource)
 * - Lease one XAConnection per XA branch/session (no sharing across branches)
 * - Return XAConnection to pool on branch end/commit/rollback
 * - Map Hikari-style pool properties to Atomikos configuration
 * - Support multinode-aware pool sizing and recreation
 * 
 * NOTE: This class now delegates to AtomikosPoolManager for pool lifecycle management.
 */
@Slf4j
public class AtomikosXAConnectionPool {
    
    // Delegate to AtomikosPoolManager for multinode support
    private final AtomikosPoolManager poolManager;
    private final String connHash;
    private final XADataSource rawXADataSource;
    private final String resourceName;
    private final ConcurrentHashMap<String, XAConnection> leasedConnections = new ConcurrentHashMap<>();
    private static final AtomicInteger resourceCounter = new AtomicInteger(0);
    
    /**
     * Creates an Atomikos XA connection pool with multinode support.
     * 
     * @param xaDataSource The native JDBC driver XADataSource to wrap (e.g., PGXADataSource)
     * @param connectionHash Unique identifier for this connection configuration
     * @param poolConfig Pool configuration properties (Hikari-style names)
     * @param serverEndpoints List of server endpoints for multinode sizing (null for single node)
     * @param poolCoordinator MultinodePoolCoordinator for calculating divided sizes
     * @throws SQLException if pool creation fails
     */
    public AtomikosXAConnectionPool(XADataSource xaDataSource, 
                                   String connectionHash, 
                                   Properties poolConfig,
                                   List<String> serverEndpoints,
                                   MultinodePoolCoordinator poolCoordinator) 
            throws SQLException {
        
        this.rawXADataSource = xaDataSource;
        this.connHash = connectionHash;
        this.resourceName = "ojp-xa-" + Math.abs(connectionHash.hashCode()) + "-" + resourceCounter.incrementAndGet();
        this.poolManager = new AtomikosPoolManager();
        
        // Extract pool sizes from config
        int requestedMaxPoolSize = getIntProperty(poolConfig, "ojp.connection.pool.maximumPoolSize", 20);
        int requestedMinIdle = getIntProperty(poolConfig, "ojp.connection.pool.minimumIdle", 5);
        
        // Calculate divided pool sizes using coordinator
        MultinodePoolCoordinator.PoolAllocation allocation;
        if (poolCoordinator != null) {
            allocation = poolCoordinator.calculatePoolSizes(
                connectionHash, requestedMaxPoolSize, requestedMinIdle, serverEndpoints);
        } else {
            // Fallback for backward compatibility
            allocation = new MultinodePoolCoordinator.PoolAllocation(
                requestedMaxPoolSize, requestedMinIdle, 1);
        }
        
        // Create pool via manager
        poolManager.getOrCreatePool(connectionHash, xaDataSource, poolConfig, allocation);
        
        log.info("Created Atomikos XA pool '{}' via manager: maxPoolSize={}, minPoolSize={}, " +
                "healthy={}/{} servers",
                resourceName, allocation.getCurrentMaxPoolSize(), allocation.getCurrentMinIdle(),
                allocation.getHealthyServers(), allocation.getTotalServers());
    }
    
    /**
     * Legacy constructor for backward compatibility (no multinode support).
     * 
     * @param xaDataSource The native JDBC driver XADataSource to wrap
     * @param connectionHash Unique identifier for this connection configuration
     * @param poolConfig Pool configuration properties
     * @throws SQLException if pool creation fails
     */
    public AtomikosXAConnectionPool(XADataSource xaDataSource, String connectionHash, Properties poolConfig) 
            throws SQLException {
        this(xaDataSource, connectionHash, poolConfig, null, null);
    }
    
    /**
     * Borrows an XAConnection from the pool for a specific session/branch.
     * Connections are leased per branch and must be returned via returnXAConnection().
     * 
     * Uses Atomikos-managed pool - NEVER bypasses Atomikos with raw XADataSource.
     * This ensures exactly one physical connection per logical borrow.
     * 
     * @param sessionId The session identifier  
     * @param branchId The XA branch identifier (can be same as sessionId if 1:1 mapping)
     * @return An XAConnection leased for this session/branch
     * @throws SQLException if connection cannot be acquired
     */
    public XAConnection borrowXAConnection(String sessionId, String branchId) throws SQLException {
        String leaseKey = sessionId + ":" + branchId;
        
        // Check if already leased for this session/branch
        XAConnection existing = leasedConnections.get(leaseKey);
        if (existing != null) {
            log.debug("Returning existing leased XAConnection for session/branch: {}", leaseKey);
            return existing;
        }
        
        // Borrow from Atomikos pool via manager - ensures one physical connection per borrow
        // CRITICAL: Uses Atomikos-managed getXAConnection(), NOT raw XADataSource
        try {
            XAConnection xaConnection = poolManager.borrowXAConnection(connHash, sessionId, branchId);
            leasedConnections.put(leaseKey, xaConnection);
            
            log.debug("Leased new XAConnection for session/branch: {} (total leased: {})", 
                    leaseKey, leasedConnections.size());
            
            return xaConnection;
            
        } catch (SQLException e) {
            log.error("Failed to borrow XAConnection from pool '{}': {}", resourceName, e.getMessage());
            throw new SQLException("Failed to acquire XA connection: " + e.getMessage(), e);
        }
    }
    
    /**
     * Returns an XAConnection to the pool.
     * Should be called on XA branch end, commit, or rollback.
     * 
     * @param sessionId The session identifier
     * @param branchId The XA branch identifier
     * @throws SQLException if connection close fails
     */
    public void returnXAConnection(String sessionId, String branchId) throws SQLException {
        String leaseKey = sessionId + ":" + branchId;
        
        XAConnection xaConnection = leasedConnections.remove(leaseKey);
        if (xaConnection != null) {
            try {
                poolManager.returnXAConnection(connHash, sessionId, branchId);
                log.debug("Returned XAConnection for session/branch: {} (remaining leased: {})", 
                        leaseKey, leasedConnections.size());
            } catch (SQLException e) {
                log.error("Error returning XAConnection to pool for {}: {}", leaseKey, e.getMessage());
                throw e;
            }
        } else {
            log.warn("Attempted to return XAConnection for {}, but no lease found", leaseKey);
        }
    }
    
    /**
     * Returns an XAConnection directly (for cases where we only have the connection object).
     * This is less precise than returning by session/branch, but still returns to pool.
     * 
     * @param xaConnection The connection to return
     */
    public void returnXAConnection(XAConnection xaConnection) {
        if (xaConnection == null) {
            return;
        }
        
        // Find and remove this connection from leased map
        String foundKey = null;
        for (var entry : leasedConnections.entrySet()) {
            if (entry.getValue() == xaConnection) {
                foundKey = entry.getKey();
                break;
            }
        }
        
        if (foundKey != null) {
            leasedConnections.remove(foundKey);
            String[] parts = foundKey.split(":");
            if (parts.length >= 2) {
                try {
                    poolManager.returnXAConnection(connHash, parts[0], parts[1]);
                } catch (SQLException e) {
                    log.error("Error returning XAConnection to pool: {}", e.getMessage());
                }
            }
        }
        
        try {
            xaConnection.close(); // Fallback: Returns to pool
            log.debug("Returned XAConnection directly (remaining leased: {})", leasedConnections.size());
        } catch (SQLException e) {
            log.error("Error closing XAConnection: {}", e.getMessage());
        }
    }
    
    /**
     * Recreates the pool with new allocation due to cluster health change.
     * This is delegated to the AtomikosPoolManager for graceful recreation.
     * 
     * @param newAllocation New pool allocation with updated sizes
     * @throws SQLException if recreation fails
     */
    public void recreatePool(MultinodePoolCoordinator.PoolAllocation newAllocation) throws SQLException {
        poolManager.recreatePool(connHash, newAllocation);
    }
    
    /**
     * Closes the Atomikos pool and releases all resources.
     * Should be called on server shutdown.
     */
    public void close() {
        log.info("Closing Atomikos XA pool '{}'...", resourceName);
        
        // Close any remaining leased connections
        for (var entry : leasedConnections.entrySet()) {
            try {
                entry.getValue().close();
                log.warn("Force-closed leaked XAConnection for: {}", entry.getKey());
            } catch (SQLException e) {
                log.error("Error closing leaked XAConnection: {}", e.getMessage());
            }
        }
        leasedConnections.clear();
        
        // Close pool via manager
        poolManager.removePool(connHash);
        log.info("Atomikos XA pool '{}' closed", resourceName);
    }
    
    /**
     * Gets current pool statistics.
     */
    public String getPoolStats() {
        String managerStats = poolManager.getPoolStats(connHash);
        if (managerStats != null) {
            return managerStats;
        }
        
        // Fallback if manager doesn't have stats
        return String.format("AtomikosPool[%s]: leased=%d", 
                resourceName, leasedConnections.size());
    }
    
    // Helper methods for property conversion
    
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
    
    private long getLongProperty(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid long value for {}: '{}', using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Converts milliseconds to seconds for Atomikos configuration.
     * Atomikos uses seconds, Hikari uses milliseconds.
     * Minimum value is 1 second.
     */
    private int msToSeconds(long milliseconds) {
        return Math.max(1, (int) Math.round(milliseconds / 1000.0));
    }
}
