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
 * - Support multinode pool coordination (dividing pool sizes across servers)
 */
@Slf4j
public class AtomikosXAConnectionPool {
    
    private final AtomikosDataSourceBean atomikosDataSource;
    private final XADataSource rawXADataSource;
    private final String resourceName;
    private final String connectionHash;
    private final ConcurrentHashMap<String, XAConnection> leasedConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Connection> leasedManagedConnections = new ConcurrentHashMap<>();
    private static final AtomicInteger resourceCounter = new AtomicInteger(0);
    
    // Pool allocation info for multinode coordination
    private final MultinodePoolCoordinator.PoolAllocation poolAllocation;
    
    /**
     * Creates an Atomikos XA connection pool with multinode support.
     * 
     * @param xaDataSource The native JDBC driver XADataSource to wrap (e.g., PGXADataSource)
     * @param connectionHash Unique identifier for this connection configuration
     * @param poolConfig Pool configuration properties (Hikari-style names)
     * @param serverEndpoints List of server endpoints in multinode setup (null or empty for single node)
     * @param poolCoordinator MultinodePoolCoordinator for calculating divided pool sizes
     * @throws SQLException if pool creation fails
     */
    public AtomikosXAConnectionPool(XADataSource xaDataSource, String connectionHash, 
                                   Properties poolConfig, List<String> serverEndpoints,
                                   MultinodePoolCoordinator poolCoordinator) throws SQLException {
        
        this.rawXADataSource = xaDataSource;
        this.connectionHash = connectionHash;
        this.resourceName = "ojp-xa-" + Math.abs(connectionHash.hashCode()) + "-" + resourceCounter.incrementAndGet();
        
        // Create AtomikosDataSourceBean
        this.atomikosDataSource = new AtomikosDataSourceBean();
        
        // Set unique resource name (required by Atomikos)
        atomikosDataSource.setUniqueResourceName(resourceName);
        
        // Wrap the XADataSource
        atomikosDataSource.setXaDataSource(xaDataSource);
        
        // Map Hikari-style properties to Atomikos
        // Default values match Hikari defaults
        int requestedMaxPoolSize = getIntProperty(poolConfig, "ojp.connection.pool.maximumPoolSize", 20);
        int requestedMinPoolSize = getIntProperty(poolConfig, "ojp.connection.pool.minimumIdle", 5);
        int connectionTimeoutSec = msToSeconds(getLongProperty(poolConfig, "ojp.connection.pool.connectionTimeout", 10000L));
        int maxIdleTimeSec = msToSeconds(getLongProperty(poolConfig, "ojp.connection.pool.idleTimeout", 600000L));
        String testQuery = poolConfig.getProperty("ojp.connection.pool.validationQuery", "SELECT 1");
        
        // Calculate divided pool sizes for multinode setup
        int maxPoolSize = requestedMaxPoolSize;
        int minPoolSize = requestedMinPoolSize;
        
        if (poolCoordinator != null && serverEndpoints != null && !serverEndpoints.isEmpty()) {
            // Multinode: use pool coordinator to calculate divided sizes
            this.poolAllocation = poolCoordinator.calculatePoolSizes(
                    connectionHash, requestedMaxPoolSize, requestedMinPoolSize, serverEndpoints);
            
            maxPoolSize = poolAllocation.getCurrentMaxPoolSize();
            minPoolSize = poolAllocation.getCurrentMinIdle();
            
            log.info("Multinode XA pool coordination enabled for {}: {} servers, original max={}, min={}, divided max={}, min={}", 
                    connectionHash, serverEndpoints.size(), requestedMaxPoolSize, requestedMinPoolSize, 
                    maxPoolSize, minPoolSize);
        } else {
            // Single node: no coordination needed, use original values
            this.poolAllocation = null;
            log.debug("Single node XA pool for {}, using original pool sizes: max={}, min={}", 
                    connectionHash, maxPoolSize, minPoolSize);
        }
        
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
     * Creates an Atomikos XA connection pool (legacy single-node constructor).
     * 
     * @param xaDataSource The native JDBC driver XADataSource to wrap (e.g., PGXADataSource)
     * @param connectionHash Unique identifier for this connection configuration
     * @param poolConfig Pool configuration properties (Hikari-style names)
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
     * Gets connections through Atomikos pool to respect pool sizing and management.
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
        
        // Get a managed connection from Atomikos pool
        // This enforces pool size limits and connection management
        try {
            Connection managedConnection = atomikosDataSource.getConnection();
            
            // Now get an XAConnection from the raw XADataSource for XA operations
            // The managedConnection ensures we respect pool limits
            XAConnection xaConnection = rawXADataSource.getXAConnection();
            
            // Store both - we need to close the managed connection when returning
            leasedConnections.put(leaseKey, xaConnection);
            leasedManagedConnections.put(leaseKey, managedConnection);
            
            log.debug("Leased new XAConnection for session/branch: {} (total leased: {}, pool max={})", 
                    leaseKey, leasedConnections.size(),
                    atomikosDataSource.getMaxPoolSize());
            
            return xaConnection;
            
        } catch (SQLException e) {
            log.error("Failed to borrow XAConnection from Atomikos pool '{}': {}", resourceName, e.getMessage());
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
        Connection managedConnection = leasedManagedConnections.remove(leaseKey);
        
        SQLException firstException = null;
        
        if (xaConnection != null) {
            try {
                xaConnection.close();
                log.debug("Returned XAConnection for session/branch: {} (remaining leased: {})", 
                        leaseKey, leasedConnections.size());
            } catch (SQLException e) {
                log.error("Error closing XAConnection for {}: {}", leaseKey, e.getMessage());
                firstException = e;
            }
        } else {
            log.warn("Attempted to return XAConnection for {}, but no lease found", leaseKey);
        }
        
        if (managedConnection != null) {
            try {
                managedConnection.close(); // Returns to Atomikos pool
            } catch (SQLException e) {
                log.error("Error returning managed connection to Atomikos pool for {}: {}", leaseKey, e.getMessage());
                if (firstException == null) {
                    firstException = e;
                }
            }
        }
        
        if (firstException != null) {
            throw firstException;
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
            Connection managedConnection = leasedManagedConnections.remove(foundKey);
            
            try {
                xaConnection.close();
            } catch (SQLException e) {
                log.error("Error closing XAConnection: {}", e.getMessage());
            }
            
            if (managedConnection != null) {
                try {
                    managedConnection.close(); // Returns to Atomikos pool
                } catch (SQLException e) {
                    log.error("Error returning managed connection to Atomikos pool: {}", e.getMessage());
                }
            }
            
            log.debug("Returned XAConnection directly (remaining leased: {})", leasedConnections.size());
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
        
        // Close any remaining managed connections
        for (var entry : leasedManagedConnections.entrySet()) {
            try {
                entry.getValue().close();
                log.warn("Force-closed leaked managed connection for: {}", entry.getKey());
            } catch (SQLException e) {
                log.error("Error closing leaked managed connection: {}", e.getMessage());
            }
        }
        leasedManagedConnections.clear();
        
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
     * Gets the connection hash for this pool.
     */
    public String getConnectionHash() {
        return connectionHash;
    }
    
    /**
     * Gets the pool allocation (for multinode coordination).
     * Returns null for single-node pools.
     */
    public MultinodePoolCoordinator.PoolAllocation getPoolAllocation() {
        return poolAllocation;
    }
    
    /**
     * Gets the raw XADataSource wrapped by this pool.
     */
    public XADataSource getRawXADataSource() {
        return rawXADataSource;
    }
    
    /**
     * Gets the Atomikos datasource bean.
     */
    public AtomikosDataSourceBean getAtomikosDataSource() {
        return atomikosDataSource;
    }
    
    /**
     * Checks if there are any active leased connections.
     */
    public boolean hasActiveLeasedConnections() {
        return !leasedConnections.isEmpty();
    }
    
    /**
     * Gets the count of currently leased connections.
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
