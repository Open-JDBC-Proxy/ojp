package org.openjproxy.grpc.server.xa;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;

/**
 * Factory for creating and warming up Atomikos XA connection pools.
 * 
 * Handles:
 * - Pool creation with multinode-aware sizing
 * - Deterministic warm-up to overcome Atomikos lazy initialization
 * - Configuration of Atomikos-specific properties
 */
@Slf4j
public class AtomikosPoolFactory {
    
    // Configuration
    private final boolean warmUpEnabled;
    private final long warmUpTimeoutSeconds;
    private final int warmUpConcurrency;
    
    public AtomikosPoolFactory() {
        this(true, 30, 10);
    }
    
    public AtomikosPoolFactory(boolean warmUpEnabled, long warmUpTimeoutSeconds, int warmUpConcurrency) {
        this.warmUpEnabled = warmUpEnabled;
        this.warmUpTimeoutSeconds = warmUpTimeoutSeconds;
        this.warmUpConcurrency = warmUpConcurrency;
    }
    
    /**
     * Creates an AtomikosDataSourceBean with multinode-aware pool sizing.
     * 
     * @param poolId Unique pool identifier (will be used as Atomikos uniqueResourceName)
     * @param xaDataSource Raw XADataSource to wrap
     * @param poolConfig Pool configuration properties
     * @param allocation Pool allocation with divided sizes
     * @return Configured AtomikosDataSourceBean
     * @throws SQLException if pool creation fails
     */
    public AtomikosDataSourceBean createPool(String poolId,
                                             XADataSource xaDataSource,
                                             Properties poolConfig,
                                             MultinodePoolCoordinator.PoolAllocation allocation) 
            throws SQLException {
        
        // Create AtomikosDataSourceBean
        AtomikosDataSourceBean atomikosDataSource = new AtomikosDataSourceBean();
        
        // Set unique resource name (required by Atomikos)
        atomikosDataSource.setUniqueResourceName(poolId);
        
        // Wrap the XADataSource
        atomikosDataSource.setXaDataSource(xaDataSource);
        
        // Use divided pool sizes from allocation
        int maxPoolSize = allocation.getCurrentMaxPoolSize();
        int minPoolSize = allocation.getCurrentMinIdle();
        
        // Extract configuration properties with defaults
        int connectionTimeoutSec = msToSeconds(
                getLongProperty(poolConfig, "ojp.connection.pool.connectionTimeout", 10000L));
        int maxIdleTimeSec = msToSeconds(
                getLongProperty(poolConfig, "ojp.connection.pool.idleTimeout", 600000L));
        String testQuery = poolConfig.getProperty("ojp.connection.pool.validationQuery", "SELECT 1");
        
        // Configure Atomikos pool settings
        atomikosDataSource.setMaxPoolSize(maxPoolSize);
        atomikosDataSource.setMinPoolSize(minPoolSize);
        atomikosDataSource.setBorrowConnectionTimeout(connectionTimeoutSec);
        atomikosDataSource.setMaxIdleTime(maxIdleTimeSec);
        atomikosDataSource.setMaintenanceInterval(60); // Check connections every 60 seconds
        atomikosDataSource.setTestQuery(testQuery);
        
        // Atomikos-specific tuning for better pool behavior
        atomikosDataSource.setReapTimeout(0); // Disable automatic reaping, rely on maintenance
        
        log.info("Created Atomikos pool '{}': maxPoolSize={}, minPoolSize={}, " +
                "borrowTimeout={}s, maxIdleTime={}s, testQuery='{}'",
                poolId, maxPoolSize, minPoolSize, connectionTimeoutSec, maxIdleTimeSec, testQuery);
        
        return atomikosDataSource;
    }
    
    /**
     * Warms up an Atomikos pool by eagerly creating connections up to minPoolSize.
     * 
     * Atomikos often initializes pools lazily, which can cause issues during failover
     * and make tests flaky. This method forces eager initialization by concurrently
     * borrowing and returning connections.
     * 
     * @param dataSourceBean The Atomikos pool to warm up
     * @param targetConnections Number of connections to create (typically minPoolSize)
     * @throws SQLException if warm-up fails
     */
    public void warmUpPool(AtomikosDataSourceBean dataSourceBean, int targetConnections) 
            throws SQLException {
        
        if (!warmUpEnabled) {
            log.debug("Pool warm-up disabled, skipping for '{}'", dataSourceBean.getUniqueResourceName());
            return;
        }
        
        if (targetConnections <= 0) {
            log.debug("Target connections <= 0, skipping warm-up for '{}'", 
                    dataSourceBean.getUniqueResourceName());
            return;
        }
        
        log.info("Warming up Atomikos pool '{}' to {} connections...", 
                dataSourceBean.getUniqueResourceName(), targetConnections);
        
        long startTime = System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(warmUpConcurrency, targetConnections));
        
        List<Future<Boolean>> futures = new ArrayList<>();
        
        try {
            // Submit warm-up tasks
            // Note: AtomikosDataSourceBean is a javax.sql.DataSource, not XADataSource
            // We warm up by getting regular Connections, which internally creates the pool
            for (int i = 0; i < targetConnections; i++) {
                final int connNum = i;
                Future<Boolean> future = executor.submit(() -> {
                    try {
                        // Borrow connection from Atomikos DataSource
                        java.sql.Connection conn = dataSourceBean.getConnection();
                        log.debug("Warm-up connection {} acquired for '{}'", 
                                connNum, dataSourceBean.getUniqueResourceName());
                        
                        // Return immediately to pool
                        conn.close();
                        log.debug("Warm-up connection {} returned for '{}'", 
                                connNum, dataSourceBean.getUniqueResourceName());
                        
                        return true;
                    } catch (SQLException e) {
                        log.warn("Failed to warm up connection {} for '{}': {}", 
                                connNum, dataSourceBean.getUniqueResourceName(), e.getMessage());
                        return false;
                    }
                });
                futures.add(future);
            }
            
            // Wait for all warm-up tasks to complete (with timeout)
            int successCount = 0;
            int failureCount = 0;
            
            for (Future<Boolean> future : futures) {
                try {
                    Boolean success = future.get(warmUpTimeoutSeconds, TimeUnit.SECONDS);
                    if (success) {
                        successCount++;
                    } else {
                        failureCount++;
                    }
                } catch (TimeoutException e) {
                    log.warn("Warm-up task timed out for '{}'", dataSourceBean.getUniqueResourceName());
                    future.cancel(true);
                    failureCount++;
                } catch (InterruptedException | ExecutionException e) {
                    log.warn("Warm-up task failed for '{}': {}", 
                            dataSourceBean.getUniqueResourceName(), e.getMessage());
                    failureCount++;
                }
            }
            
            long elapsedMs = System.currentTimeMillis() - startTime;
            
            if (failureCount > 0) {
                log.warn("Pool warm-up for '{}' completed with {} successes and {} failures in {}ms",
                        dataSourceBean.getUniqueResourceName(), successCount, failureCount, elapsedMs);
            } else {
                log.info("Pool warm-up for '{}' completed successfully with {} connections in {}ms",
                        dataSourceBean.getUniqueResourceName(), successCount, elapsedMs);
            }
            
            // If most connections failed, throw exception
            if (successCount == 0 && targetConnections > 0) {
                throw new SQLException("Pool warm-up failed: no connections could be created");
            }
            
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    // Helper methods for property conversion
    
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
