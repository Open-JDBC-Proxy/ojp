package org.openjproxy.grpc.server.xa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages HikariCP-based XA connection pooling for OJP server.
 * This class wraps JDBC driver XADataSources with HikariCP for connection pooling.
 * 
 * IMPORTANT: This is a POOLING ONLY solution - NO transaction manager behavior on server.
 * Server remains XA pass-through: clients control transaction lifecycle via XAResource.
 * 
 * Key responsibilities:
 * - Create HikariDataSource wrapping driver's XADataSource (e.g., PGXADataSource) via DecoratingDataSource
 * - Lease one XAConnection per XA branch/session (no sharing across branches)
 * - Return XAConnection to pool on branch end/commit/rollback
 * - Map pool properties to HikariCP configuration
 */
@Slf4j
public class AtomikosXAConnectionPool {
    
    private final HikariDataSource hikariDataSource;
    private final XADataSource rawXADataSource;
    private final String resourceName;
    private final ConcurrentHashMap<String, XAConnection> leasedConnections = new ConcurrentHashMap<>();
    private static final AtomicInteger resourceCounter = new AtomicInteger(0);
    
    /**
     * Creates a HikariCP XA connection pool.
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
        
        // Create HikariCP configuration
        HikariConfig config = new HikariConfig();
        
        // Set pool name
        config.setPoolName(resourceName);
        
        // Create DecoratingDataSource that wraps the XADataSource
        // We use a dummy DataSource as the delegate (not used in XA mode)
        DecoratingDataSource decoratingDS = new DecoratingDataSource(
            new DummyDataSource(), // Dummy delegate (not used when XADataSource is provided)
            xaDataSource
        );
        config.setDataSource(decoratingDS);
        
        // Map pool properties to HikariCP
        // Default values match Hikari defaults
        int maxPoolSize = getIntProperty(poolConfig, "ojp.connection.pool.maximumPoolSize", 20);
        int minIdle = getIntProperty(poolConfig, "ojp.connection.pool.minimumIdle", 5);
        long connectionTimeout = getLongProperty(poolConfig, "ojp.connection.pool.connectionTimeout", 10000L);
        long idleTimeout = getLongProperty(poolConfig, "ojp.connection.pool.idleTimeout", 600000L);
        long maxLifetime = getLongProperty(poolConfig, "ojp.connection.pool.maxLifetime", 1800000L);
        String connectionTestQuery = poolConfig.getProperty("ojp.connection.pool.connectionTestQuery");
        
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        
        if (connectionTestQuery != null && !connectionTestQuery.isEmpty()) {
            config.setConnectionTestQuery(connectionTestQuery);
        }
        
        // Disable auto-commit for XA connections
        config.setAutoCommit(false);
        
        // Disable failFast initialization for tests
        // In production, connection validation happens on first use
        config.setInitializationFailTimeout(-1);
        
        // Create HikariDataSource
        this.hikariDataSource = new HikariDataSource(config);
        
        log.info("Created HikariCP XA pool '{}': maxPoolSize={}, minIdle={}, connectionTimeout={}ms, idleTimeout={}ms",
                resourceName, maxPoolSize, minIdle, connectionTimeout, idleTimeout);
    }
    
    /**
     * Borrows an XAConnection from the pool for a specific session/branch.
     * Connections are leased per branch and must be returned via returnXAConnection().
     * 
     * Uses the HikariCP pool to get connections.
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
        
        // Get connection from HikariCP pool
        // The DecoratingDataSource will wrap it as XAResourceConnection
        try {
            Connection conn = hikariDataSource.getConnection();
            
            // The connection is actually an XAResourceConnection proxy
            if (conn instanceof XAConnection) {
                XAConnection xaConnection = (XAConnection) conn;
                leasedConnections.put(leaseKey, xaConnection);
                
                log.debug("Leased new XAConnection for session/branch: {} (total leased: {})", 
                        leaseKey, leasedConnections.size());
                
                return xaConnection;
            } else {
                // Fallback: get directly from raw XADataSource
                XAConnection xaConnection = rawXADataSource.getXAConnection();
                leasedConnections.put(leaseKey, xaConnection);
                
                log.debug("Leased new XAConnection (direct) for session/branch: {} (total leased: {})", 
                        leaseKey, leasedConnections.size());
                
                return xaConnection;
            }
            
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
                // Cast to Connection to return to HikariCP pool
                if (xaConnection instanceof Connection) {
                    ((Connection) xaConnection).close();
                } else {
                    xaConnection.close(); // Fallback
                }
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
            // Cast to Connection to return to HikariCP pool
            if (xaConnection instanceof Connection) {
                ((Connection) xaConnection).close();
            } else {
                xaConnection.close(); // Fallback
            }
            log.debug("Returned XAConnection directly (remaining leased: {})", leasedConnections.size());
        } catch (SQLException e) {
            log.error("Error returning XAConnection to pool: {}", e.getMessage());
        }
    }
    
    /**
     * Closes the HikariCP pool and releases all resources.
     * Should be called on server shutdown.
     */
    public void close() {
        log.info("Closing HikariCP XA pool '{}'...", resourceName);
        
        // Close any remaining leased connections
        for (var entry : leasedConnections.entrySet()) {
            try {
                if (entry.getValue() instanceof Connection) {
                    ((Connection) entry.getValue()).close();
                } else {
                    entry.getValue().close();
                }
                log.warn("Force-closed leaked XAConnection for: {}", entry.getKey());
            } catch (SQLException e) {
                log.error("Error closing leaked XAConnection: {}", e.getMessage());
            }
        }
        leasedConnections.clear();
        
        // Close HikariCP datasource
        hikariDataSource.close();
        log.info("HikariCP XA pool '{}' closed", resourceName);
    }
    
    /**
     * Gets current pool statistics.
     */
    public String getPoolStats() {
        return String.format("HikariPool[%s]: leased=%d, active=%d, idle=%d, total=%d, maxPoolSize=%d", 
                resourceName, 
                leasedConnections.size(),
                hikariDataSource.getHikariPoolMXBean().getActiveConnections(),
                hikariDataSource.getHikariPoolMXBean().getIdleConnections(),
                hikariDataSource.getHikariPoolMXBean().getTotalConnections(),
                hikariDataSource.getMaximumPoolSize());
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
     * Dummy DataSource implementation used as a delegate in DecoratingDataSource.
     * Not actually used when XADataSource is provided.
     */
    private static class DummyDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("DummyDataSource should not be called directly");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("DummyDataSource should not be called directly");
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger(DummyDataSource.class.getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("Not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return false;
        }
    }
}
