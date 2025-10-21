package org.openjproxy.grpc.server.pool;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.openjproxy.grpc.ConnectionDetails;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;

import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Properties;

import static org.openjproxy.grpc.SerializationHandler.deserialize;

/**
 * Factory for creating Atomikos XA datasources.
 * Maps Hikari configuration properties to Atomikos equivalents and handles
 * timeout conversions (milliseconds to seconds).
 */
@Slf4j
public class AtomikosDataSourceFactory {
    
    /**
     * Creates an AtomikosDataSourceBean from connection details.
     * 
     * @param connectionDetails Connection details including URL, credentials, and properties
     * @param xaDataSource The XADataSource to wrap
     * @param uniqueResourceName Unique name for this datasource resource
     * @return Configured AtomikosDataSourceBean
     */
    public static AtomikosDataSourceBean createAtomikosDataSource(
            ConnectionDetails connectionDetails, 
            XADataSource xaDataSource,
            String uniqueResourceName) {
        
        log.info("Creating Atomikos datasource with resource name: {}", uniqueResourceName);
        
        // Extract client properties
        Properties clientProperties = extractClientProperties(connectionDetails);
        
        // Get datasource-specific configuration
        DataSourceConfigurationManager.DataSourceConfiguration dsConfig = 
                DataSourceConfigurationManager.getConfiguration(clientProperties);
        
        // Create Atomikos datasource bean
        AtomikosDataSourceBean atomikosDS = new AtomikosDataSourceBean();
        
        // Set unique resource name (required for Atomikos)
        atomikosDS.setUniqueResourceName(uniqueResourceName);
        
        // Set the XADataSource
        atomikosDS.setXaDataSource(xaDataSource);
        
        // Map pool size settings
        atomikosDS.setMaxPoolSize(dsConfig.getMaximumPoolSize());
        atomikosDS.setMinPoolSize(dsConfig.getMinimumIdle());
        
        // Map timeout settings (convert milliseconds to seconds)
        // borrowConnectionTimeout: timeout for acquiring a connection from pool
        atomikosDS.setBorrowConnectionTimeout((int) (dsConfig.getConnectionTimeout() / 1000));
        
        // maxIdleTime: equivalent to Hikari's idleTimeout
        atomikosDS.setMaxIdleTime((int) (dsConfig.getIdleTimeout() / 1000));
        
        // maxLifetime: Atomikos uses maxLifetime property (in seconds)
        atomikosDS.setMaxLifetime((int) (dsConfig.getMaxLifetime() / 1000));
        
        // Set test query for connection validation (if database-specific)
        String testQuery = getTestQueryForDatabase(connectionDetails.getUrl());
        if (testQuery != null) {
            atomikosDS.setTestQuery(testQuery);
        }
        
        // Enable concurrent connection validation for better performance
        atomikosDS.setConcurrentConnectionValidation(true);
        
        // Set maintenance interval (how often to check for idle connections)
        // Default to 60 seconds
        atomikosDS.setMaintenanceInterval(60);
        
        log.info("Atomikos datasource configured: maxPoolSize={}, minPoolSize={}, borrowTimeout={}s, maxIdleTime={}s, maxLifetime={}s",
                atomikosDS.getMaxPoolSize(), atomikosDS.getMinPoolSize(), 
                atomikosDS.getBorrowConnectionTimeout(), atomikosDS.getMaxIdleTime(),
                atomikosDS.getMaxLifetime());
        
        return atomikosDS;
    }
    
    /**
     * Gets appropriate test query for the database type.
     */
    private static String getTestQueryForDatabase(String url) {
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.contains("postgresql")) {
            return "SELECT 1";
        } else if (lowerUrl.contains("mysql")) {
            return "SELECT 1";
        } else if (lowerUrl.contains("oracle")) {
            return "SELECT 1 FROM DUAL";
        } else if (lowerUrl.contains("sqlserver") || lowerUrl.contains("microsoft")) {
            return "SELECT 1";
        } else if (lowerUrl.contains("h2")) {
            return "SELECT 1";
        }
        // Default for unknown databases
        return "SELECT 1";
    }
    
    /**
     * Extracts client properties from connection details.
     */
    private static Properties extractClientProperties(ConnectionDetails connectionDetails) {
        if (connectionDetails.getProperties().isEmpty()) {
            return new Properties();
        }
        
        try {
            Properties clientProperties = deserialize(connectionDetails.getProperties().toByteArray(), Properties.class);
            log.debug("Extracted {} properties from client for Atomikos configuration", clientProperties.size());
            return clientProperties;
        } catch (Exception e) {
            log.warn("Failed to deserialize client properties, using defaults: {}", e.getMessage());
            return new Properties();
        }
    }
    
    /**
     * Reads Atomikos-specific configuration from properties.
     */
    public static class AtomikosConfig {
        private final boolean loggingEnabled;
        private final String logDir;
        
        public AtomikosConfig(Properties properties) {
            // Check for Atomikos-specific properties
            this.loggingEnabled = Boolean.parseBoolean(
                    properties.getProperty("jdbc.atomikos.logging.enabled", "false"));
            this.logDir = properties.getProperty("jdbc.atomikos.logging.dir", "./atomikos-logs");
        }
        
        public boolean isLoggingEnabled() {
            return loggingEnabled;
        }
        
        public String getLogDir() {
            return logDir;
        }
    }
    
    /**
     * Gets Atomikos configuration from client properties.
     */
    public static AtomikosConfig getAtomikosConfig(ConnectionDetails connectionDetails) {
        Properties clientProperties = extractClientProperties(connectionDetails);
        return new AtomikosConfig(clientProperties);
    }
}
