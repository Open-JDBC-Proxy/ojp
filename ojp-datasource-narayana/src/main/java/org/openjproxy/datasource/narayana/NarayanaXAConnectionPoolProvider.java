package org.openjproxy.datasource.narayana;

import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import com.arjuna.ats.jta.common.jtaPropertyManager;
import org.openjproxy.datasource.PoolConfig;
import org.openjproxy.datasource.XAConnectionPoolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Narayana implementation of {@link XAConnectionPoolProvider}.
 * 
 * <p>This provider creates and manages XA connection pools using the Narayana
 * JTA transaction manager. It provides pooling for XADataSource instances and
 * integrates with Narayana's transaction management and recovery mechanisms.</p>
 * 
 * <p>The provider is registered via ServiceLoader and can be selected by ID
 * "narayana" or used as the default if it has the highest priority.</p>
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>Connection pooling for XA datasources</li>
 *   <li>Dynamic pool resizing (supported)</li>
 *   <li>Transaction recovery integration</li>
 *   <li>Pool statistics and monitoring</li>
 * </ul>
 * 
 * <h2>Configuration</h2>
 * <p>The provider maps {@link PoolConfig} properties to Narayana-specific settings:</p>
 * <ul>
 *   <li>{@code maxPoolSize} → Maximum XA connections in pool</li>
 *   <li>{@code minIdle} → Minimum idle XA connections</li>
 *   <li>{@code connectionTimeoutMs} → Connection acquisition timeout</li>
 *   <li>{@code idleTimeoutMs} → Idle connection timeout</li>
 *   <li>{@code maxLifetimeMs} → Maximum connection lifetime</li>
 * </ul>
 */
public class NarayanaXAConnectionPoolProvider implements XAConnectionPoolProvider {

    private static final Logger log = LoggerFactory.getLogger(NarayanaXAConnectionPoolProvider.class);
    
    public static final String PROVIDER_ID = "narayana";
    private static final int PRIORITY = 85; // Lower than HikariCP (100) but reasonable default
    
    private static volatile boolean narayanaInitialized = false;
    private static final Object initLock = new Object();
    
    /**
     * Initializes Narayana transaction manager if not already initialized.
     * This sets up the recovery manager and transaction properties.
     */
    private static void initializeNarayana() {
        if (!narayanaInitialized) {
            synchronized (initLock) {
                if (!narayanaInitialized) {
                    try {
                        log.info("Initializing Narayana transaction manager");
                        
                        // Set Narayana properties
                        // Use minimal configuration for OJP use case
                        jtaPropertyManager.getJTAEnvironmentBean().setTransactionManagerClassName(
                                "com.arjuna.ats.jbossatx.jta.TransactionManagerDelegate");
                        
                        // Configure transaction logging
                        String logDir = System.getProperty("ojp.xa.narayana.log.dir", "./narayana-logs");
                        com.arjuna.ats.arjuna.common.arjPropertyManager.getObjectStoreEnvironmentBean()
                                .setObjectStoreDir(logDir);
                        
                        log.info("Narayana transaction log directory: {}", logDir);
                        
                        // Start recovery manager
                        RecoveryManager.manager();
                        
                        narayanaInitialized = true;
                        log.info("Narayana transaction manager initialized successfully");
                        
                    } catch (Exception e) {
                        log.error("Failed to initialize Narayana transaction manager", e);
                        throw new RuntimeException("Narayana initialization failed", e);
                    }
                }
            }
        }
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public XADataSource createXADataSource(PoolConfig config) throws SQLException {
        if (config == null) {
            throw new IllegalArgumentException("PoolConfig cannot be null");
        }
        
        // Initialize Narayana if needed
        initializeNarayana();
        
        log.info("Creating Narayana XA DataSource for URL: {}", config.getUrl());
        
        try {
            // Create the pooled XA datasource
            NarayanaPooledXADataSource pooledDS = new NarayanaPooledXADataSource(config);
            
            log.info("Created Narayana XA DataSource: maxPoolSize={}, minIdle={}", 
                    config.getMaxPoolSize(), config.getMinIdle());
            
            return pooledDS;
            
        } catch (Exception e) {
            log.error("Failed to create Narayana XA DataSource: {}", e.getMessage(), e);
            throw new SQLException("Failed to create Narayana XA DataSource: " + e.getMessage(), e);
        }
    }

    @Override
    public void closeXADataSource(XADataSource xaDataSource) throws Exception {
        if (xaDataSource instanceof NarayanaPooledXADataSource) {
            NarayanaPooledXADataSource pooledDS = (NarayanaPooledXADataSource) xaDataSource;
            log.info("Closing Narayana XA DataSource");
            pooledDS.close();
        } else if (xaDataSource != null) {
            log.warn("Cannot close XADataSource: not a NarayanaPooledXADataSource instance ({})", 
                    xaDataSource.getClass().getName());
        }
    }

    @Override
    public Map<String, Object> getXAStatistics(XADataSource xaDataSource) {
        Map<String, Object> stats = new HashMap<>();
        
        if (xaDataSource instanceof NarayanaPooledXADataSource) {
            NarayanaPooledXADataSource pooledDS = (NarayanaPooledXADataSource) xaDataSource;
            
            stats.put("poolName", pooledDS.getPoolName());
            stats.put("maxPoolSize", pooledDS.getMaxPoolSize());
            stats.put("minIdle", pooledDS.getMinIdle());
            stats.put("activeConnections", pooledDS.getActiveCount());
            stats.put("idleConnections", pooledDS.getIdleCount());
            stats.put("totalConnections", pooledDS.getTotalCount());
            stats.put("isClosed", pooledDS.isClosed());
        }
        
        return stats;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public boolean isAvailable() {
        try {
            // Check if Narayana classes are on classpath
            Class.forName("com.arjuna.ats.jta.TransactionManager");
            Class.forName("com.arjuna.ats.arjuna.recovery.RecoveryManager");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean supportsDynamicResizing() {
        // Narayana supports dynamic resizing
        return true;
    }

    @Override
    public void resizeXADataSource(XADataSource xaDataSource, int newMaxPoolSize, int newMinIdle) throws SQLException {
        if (!(xaDataSource instanceof NarayanaPooledXADataSource)) {
            throw new IllegalArgumentException("XADataSource is not a NarayanaPooledXADataSource");
        }
        
        NarayanaPooledXADataSource pooledDS = (NarayanaPooledXADataSource) xaDataSource;
        
        log.info("Resizing Narayana XA pool: maxPoolSize {} -> {}, minIdle {} -> {}", 
                pooledDS.getMaxPoolSize(), newMaxPoolSize, 
                pooledDS.getMinIdle(), newMinIdle);
        
        try {
            pooledDS.resize(newMaxPoolSize, newMinIdle);
            log.info("Narayana XA pool resized successfully");
        } catch (Exception e) {
            log.error("Failed to resize Narayana XA pool", e);
            throw new SQLException("Failed to resize XA pool: " + e.getMessage(), e);
        }
    }

    // Non-XA methods from ConnectionPoolProvider - throw UnsupportedOperationException
    
    @Override
    public javax.sql.DataSource createDataSource(PoolConfig config) throws SQLException {
        throw new UnsupportedOperationException(
                "NarayanaXAConnectionPoolProvider only supports XA datasources. Use createXADataSource() instead.");
    }

    @Override
    public void closeDataSource(javax.sql.DataSource dataSource) throws Exception {
        throw new UnsupportedOperationException(
                "NarayanaXAConnectionPoolProvider only supports XA datasources. Use closeXADataSource() instead.");
    }

    @Override
    public Map<String, Object> getStatistics(javax.sql.DataSource dataSource) {
        throw new UnsupportedOperationException(
                "NarayanaXAConnectionPoolProvider only supports XA datasources. Use getXAStatistics() instead.");
    }
}
