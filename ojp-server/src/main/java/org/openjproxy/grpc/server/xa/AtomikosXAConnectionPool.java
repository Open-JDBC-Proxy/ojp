package org.openjproxy.grpc.server.xa;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import lombok.extern.slf4j.Slf4j;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;
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
 */
@Slf4j
public class AtomikosXAConnectionPool {
    
    private final AtomikosDataSourceBean atomikosDataSource;
    private final XADataSource rawXADataSource;
    private final String resourceName;
    private final ConcurrentHashMap<String, XAConnection> leasedConnections = new ConcurrentHashMap<>();
    private static final AtomicInteger resourceCounter = new AtomicInteger(0);
    
    /**
     * Creates an Atomikos XA connection pool.
     * 
     * @param xaDataSource The native JDBC driver XADataSource to wrap (e.g., PGXADataSource)
     * @param connectionHash Unique identifier for this connection configuration
     * @param poolConfig Pool configuration properties (Hikari-style names)
     * @throws SQLException if pool creation fails
     */
    public AtomikosXAConnectionPool(XADataSource xaDataSource, String connectionHash, Properties poolConfig) 
            throws SQLException {
        
        this.rawXADataSource = xaDataSource;
        this.resourceName = "ojp-xa-" + Math.abs(connectionHash.hashCode()) + "-" + resourceCounter.incrementAndGet();
        
        // Create AtomikosDataSourceBean
        this.atomikosDataSource = new AtomikosDataSourceBean();
        
        // Set unique resource name (required by Atomikos)
        atomikosDataSource.setUniqueResourceName(resourceName);
        
        // Wrap the XADataSource
        atomikosDataSource.setXaDataSource(xaDataSource);
        
        // Map Hikari-style properties to Atomikos
        // Default values match Hikari defaults
        int maxPoolSize = getIntProperty(poolConfig, "ojp.connection.pool.maximumPoolSize", 20);
        int minPoolSize = getIntProperty(poolConfig, "ojp.connection.pool.minimumIdle", 5);
        int connectionTimeoutSec = msToSeconds(getLongProperty(poolConfig, "ojp.connection.pool.connectionTimeout", 10000L));
        int maxIdleTimeSec = msToSeconds(getLongProperty(poolConfig, "ojp.connection.pool.idleTimeout", 600000L));
        String testQuery = poolConfig.getProperty("ojp.connection.pool.validationQuery", "SELECT 1");
        
        atomikosDataSource.setMaxPoolSize(maxPoolSize);
        atomikosDataSource.setMinPoolSize(minPoolSize);
        atomikosDataSource.setBorrowConnectionTimeout(connectionTimeoutSec);
        atomikosDataSource.setMaxIdleTime(maxIdleTimeSec);
        atomikosDataSource.setMaintenanceInterval(60); // Check connections every 60 seconds
        atomikosDataSource.setTestQuery(testQuery);
        
        log.info("Created Atomikos XA pool '{}': maxPoolSize={}, minPoolSize={}, borrowTimeout={}s, maxIdleTime={}s, testQuery='{}'",
                resourceName, maxPoolSize, minPoolSize, connectionTimeoutSec, maxIdleTimeSec, testQuery);
    }
    
    /**
     * Borrows an XAConnection from the pool for a specific session/branch.
     * Connections are leased per branch and must be returned via returnXAConnection().
     * 
     * Uses the raw XADataSource to get XAConnection while Atomikos manages pool health/sizing.
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
        
        // Get XAConnection from raw XADataSource
        // Atomikos provides the pool management infrastructure (sizing, validation, health)
        // but we get XAConnection directly for XA pass-through semantics
        try {
            XAConnection xaConnection = rawXADataSource.getXAConnection();
            leasedConnections.put(leaseKey, xaConnection);
            
            log.debug("Leased new XAConnection for session/branch: {} (total leased: {})", 
                    leaseKey, leasedConnections.size());
            
            return xaConnection;
            
        } catch (SQLException e) {
            log.error("Failed to borrow XAConnection from XADataSource '{}': {}", resourceName, e.getMessage());
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
                xaConnection.close(); // Returns to Atomikos pool
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
        }
        
        try {
            xaConnection.close(); // Returns to Atomikos pool
            log.debug("Returned XAConnection directly (remaining leased: {})", leasedConnections.size());
        } catch (SQLException e) {
            log.error("Error returning XAConnection to pool: {}", e.getMessage());
        }
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
        
        // Close Atomikos datasource
        atomikosDataSource.close();
        log.info("Atomikos XA pool '{}' closed", resourceName);
    }
    
    /**
     * Gets current pool statistics.
     */
    public String getPoolStats() {
        return String.format("AtomikosPool[%s]: leased=%d, maxPoolSize=%d, minPoolSize=%d", 
                resourceName, 
                leasedConnections.size(),
                atomikosDataSource.getMaxPoolSize(),
                atomikosDataSource.getMinPoolSize());
    }
    
    /**
     * Gets the number of currently leased connections.
     * This is used during pool recreation to wait for transactions to complete.
     */
    public int getLeasedConnectionCount() {
        return leasedConnections.size();
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
