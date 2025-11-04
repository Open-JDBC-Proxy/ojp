package org.openjproxy.grpc.server.pool;

import com.openjproxy.grpc.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Utility class responsible for configuring HikariCP connection pools.
 * Extracted from StatementServiceImpl to reduce its responsibilities.
 * Updated to support multi-datasource configuration and multinode pool coordination.
 */
@Slf4j
public class ConnectionPoolConfigurer {

    private static final MultinodePoolCoordinator poolCoordinator = new MultinodePoolCoordinator();

    /**
     * Configures a HikariCP connection pool with connection details and client properties.
     * Now supports multi-datasource configuration and multinode pool coordination.
     *
     * @param config            The HikariConfig to configure
     * @param connectionDetails The connection details containing properties
     * @param connHash          Connection hash for tracking pool allocations
     * @return The datasource configuration used for this pool
     */
    public static DataSourceConfigurationManager.DataSourceConfiguration configureHikariPool(
            HikariConfig config, ConnectionDetails connectionDetails, String connHash) {
        Properties clientProperties = extractClientProperties(connectionDetails);
        
        // Get datasource-specific configuration
        DataSourceConfigurationManager.DataSourceConfiguration dsConfig = 
                DataSourceConfigurationManager.getConfiguration(clientProperties);

        // Configure basic connection pool settings first
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        // Check if multinode configuration is present
        List<String> serverEndpoints = connectionDetails.getServerEndpointsList();
        int maxPoolSize = dsConfig.getMaximumPoolSize();
        int minIdle = dsConfig.getMinimumIdle();
        
        if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
            // Multinode: calculate divided pool sizes
            MultinodePoolCoordinator.PoolAllocation allocation = 
                    poolCoordinator.calculatePoolSizes(connHash, maxPoolSize, minIdle, serverEndpoints);
            
            maxPoolSize = allocation.getCurrentMaxPoolSize();
            minIdle = allocation.getCurrentMinIdle();
            
            log.info("Multinode pool coordination enabled for {}: {} servers, divided pool sizes: max={}, min={}", 
                    connHash, serverEndpoints.size(), maxPoolSize, minIdle);
        }

        // Configure HikariCP pool settings
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setIdleTimeout(dsConfig.getIdleTimeout());
        config.setMaxLifetime(dsConfig.getMaxLifetime());
        config.setConnectionTimeout(dsConfig.getConnectionTimeout());
        
        // Additional settings for high concurrency scenarios
        config.setLeakDetectionThreshold(60000); // 60 seconds - detect connection leaks
        config.setValidationTimeout(5000);       // 5 seconds - faster validation timeout
        config.setInitializationFailTimeout(10000); // 10 seconds - fail fast on initialization issues
        
        // Set pool name for better monitoring - include dataSource name
        String poolName = "OJP-Pool-" + dsConfig.getDataSourceName() + "-" + System.currentTimeMillis();
        config.setPoolName(poolName);
        
        // Enable JMX for monitoring if not explicitly disabled
        config.setRegisterMbeans(true);

        log.info("HikariCP configured for dataSource '{}' with maximumPoolSize={}, minimumIdle={}, connectionTimeout={}ms, poolName={}",
                dsConfig.getDataSourceName(), config.getMaximumPoolSize(), config.getMinimumIdle(), 
                config.getConnectionTimeout(), poolName);
                
        return dsConfig;
    }

    /**
     * Legacy method for backward compatibility - calls new method with null connHash.
     */
    public static DataSourceConfigurationManager.DataSourceConfiguration configureHikariPool(
            HikariConfig config, ConnectionDetails connectionDetails) {
        return configureHikariPool(config, connectionDetails, null);
    }
    
    /**
     * Gets the multinode pool coordinator instance.
     */
    public static MultinodePoolCoordinator getPoolCoordinator() {
        return poolCoordinator;
    }

    /**
     * Extracts client properties from connection details.
     *
     * @param connectionDetails The connection details
     * @return Properties object or null if not available
     */
    private static Properties extractClientProperties(ConnectionDetails connectionDetails) {
        if (connectionDetails.getPropertiesList().isEmpty()) {
            return null;
        }

        try {
            Map<String, Object> propertiesMap = ProtoConverter.propertiesFromProto(connectionDetails.getPropertiesList());
            Properties clientProperties = new Properties();
            clientProperties.putAll(propertiesMap);
            log.debug("Received {} properties from client for connection pool configuration", clientProperties.size());
            return clientProperties;
        } catch (Exception e) {
            log.warn("Failed to deserialize client properties, using defaults: {}", e.getMessage());
            return null;
        }
    }
}