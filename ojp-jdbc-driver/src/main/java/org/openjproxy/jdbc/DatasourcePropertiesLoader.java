package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utility class for loading datasource-specific properties from ojp.properties file.
 * Shared by Driver, OjpXADataSource, and MultinodeConnectionManager to avoid code duplication.
 */
@Slf4j
public class DatasourcePropertiesLoader {

    /**
     * Load ojp.properties and extract configuration specific to the given dataSource.
     * 
     * Property precedence (highest to lowest):
     * 1. Environment variables (e.g., MULTINODE_OJP_CONNECTION_POOL_ENABLED=false)
     * 2. System properties (e.g., -Dmultinode.ojp.connection.pool.enabled=false)
     * 3. Properties file (ojp.properties)
     * 
     * @param dataSourceName The datasource name to load properties for
     * @return Properties for the specified datasource, or null if none found
     */
    public static Properties loadOjpPropertiesForDataSource(String dataSourceName) {
        Properties allProperties = loadOjpProperties();
        if (allProperties == null || allProperties.isEmpty()) {
            return null;
        }
        
        Properties dataSourceProperties = new Properties();
        
        // Look for dataSource-prefixed properties first: {dataSourceName}.ojp.connection.pool.*
        // Also look for XA-specific properties: {dataSourceName}.ojp.xa.connection.pool.*
        String poolPrefix = dataSourceName + ".ojp.connection.pool.";
        String xaPoolPrefix = dataSourceName + ".ojp.xa.connection.pool.";
        String xaPrefix = dataSourceName + ".ojp.xa.";
        boolean foundDataSourceSpecific = false;
        
        for (String key : allProperties.stringPropertyNames()) {
            if (key.startsWith(poolPrefix) || key.startsWith(xaPoolPrefix) || key.startsWith(xaPrefix)) {
                // Remove the dataSource prefix and keep the standard property name
                String standardKey = key.substring(dataSourceName.length() + 1); // Remove "{dataSourceName}."
                dataSourceProperties.setProperty(standardKey, allProperties.getProperty(key));
                foundDataSourceSpecific = true;
            }
        }
        
        // If no dataSource-specific properties found, and this is the "default" dataSource,
        // look for unprefixed properties: ojp.connection.pool.*, ojp.xa.connection.pool.*, ojp.xa.*
        if (!foundDataSourceSpecific && "default".equals(dataSourceName)) {
            for (String key : allProperties.stringPropertyNames()) {
                if (key.startsWith("ojp.connection.pool.") || 
                    key.startsWith("ojp.xa.connection.pool.") || 
                    key.startsWith("ojp.xa.")) {
                    dataSourceProperties.setProperty(key, allProperties.getProperty(key));
                }
            }
        }
        
        // Merge system properties - they take precedence over file properties
        // Check for datasource-specific system properties first
        Properties systemProps = System.getProperties();
        for (String key : systemProps.stringPropertyNames()) {
            if (key.startsWith(poolPrefix) || key.startsWith(xaPoolPrefix) || key.startsWith(xaPrefix)) {
                // Remove the dataSource prefix and keep the standard property name
                String standardKey = key.substring(dataSourceName.length() + 1);
                dataSourceProperties.setProperty(standardKey, systemProps.getProperty(key));
                foundDataSourceSpecific = true;
                log.debug("Overriding property from system property: {} = {}", standardKey, systemProps.getProperty(key));
            }
        }
        
        // For "default" datasource, also check unprefixed system properties
        if ("default".equals(dataSourceName)) {
            for (String key : systemProps.stringPropertyNames()) {
                if (key.startsWith("ojp.connection.pool.") || 
                    key.startsWith("ojp.xa.connection.pool.") || 
                    key.startsWith("ojp.xa.")) {
                    dataSourceProperties.setProperty(key, systemProps.getProperty(key));
                    log.debug("Overriding property from system property: {} = {}", key, systemProps.getProperty(key));
                }
            }
        }
        
        // Merge environment variables - they take highest precedence
        // Environment variables use underscore separator and uppercase (e.g., MULTINODE_OJP_CONNECTION_POOL_ENABLED)
        // Check for datasource-specific environment variables
        for (java.util.Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String envKey = entry.getKey();
            String envValue = entry.getValue();
            
            // Convert environment variable name to property key format
            // E.g., MULTINODE_OJP_CONNECTION_POOL_ENABLED -> multinode.ojp.connection.pool.enabled
            String propertyKey = envKey.toLowerCase().replace('_', '.');
            
            if (propertyKey.startsWith(poolPrefix) || propertyKey.startsWith(xaPoolPrefix) || propertyKey.startsWith(xaPrefix)) {
                // Remove the dataSource prefix and keep the standard property name
                String standardKey = propertyKey.substring(dataSourceName.length() + 1);
                dataSourceProperties.setProperty(standardKey, envValue);
                foundDataSourceSpecific = true;
                log.debug("Overriding property from environment variable: {} = {}", standardKey, envValue);
            }
        }
        
        // For "default" datasource, also check unprefixed environment variables
        if ("default".equals(dataSourceName)) {
            for (java.util.Map.Entry<String, String> entry : System.getenv().entrySet()) {
                String envKey = entry.getKey();
                String envValue = entry.getValue();
                
                // Convert environment variable name to property key format
                String propertyKey = envKey.toLowerCase().replace('_', '.');
                
                if (propertyKey.startsWith("ojp.connection.pool.") || 
                    propertyKey.startsWith("ojp.xa.connection.pool.") || 
                    propertyKey.startsWith("ojp.xa.")) {
                    dataSourceProperties.setProperty(propertyKey, envValue);
                    log.debug("Overriding property from environment variable: {} = {}", propertyKey, envValue);
                }
            }
        }
        
        // If we found any properties, also include the dataSource name as a single property
        // Note: The dataSource-prefixed properties (e.g., "webApp.ojp.connection.pool.*") 
        // are sent to the server with their prefixes removed (e.g., "ojp.connection.pool.*"),
        // and the dataSource name itself is sent separately as "ojp.datasource.name"
        if (!dataSourceProperties.isEmpty()) {
            dataSourceProperties.setProperty("ojp.datasource.name", dataSourceName);
        }
        
        log.debug("Loaded {} properties for dataSource '{}': {}", 
                dataSourceProperties.size(), dataSourceName, dataSourceProperties);
        
        return dataSourceProperties.isEmpty() ? null : dataSourceProperties;
    }

    /**
     * Load the raw ojp.properties file from classpath.
     * 
     * Supports environment-specific properties files using the naming pattern:
     * ojp-{environment}.properties (e.g., ojp-dev.properties, ojp-prod.properties)
     * 
     * The environment is determined by (in order of precedence):
     * 1. System property: -Dojp.environment=dev
     * 2. Environment variable: OJP_ENVIRONMENT=dev
     * 
     * If environment is specified, attempts to load ojp-{environment}.properties first.
     * Falls back to ojp.properties if environment-specific file not found.
     * 
     * @return All properties from ojp.properties file, or null if not found
     */
    public static Properties loadOjpProperties() {
        Properties properties = new Properties();
        
        // Determine environment from system property or environment variable
        String environment = getEnvironmentName();
        
        // Try to load environment-specific properties file first
        if (environment != null && !environment.isEmpty()) {
            String envPropertiesFile = "ojp-" + environment + ".properties";
            try (InputStream is = DatasourcePropertiesLoader.class.getClassLoader().getResourceAsStream(envPropertiesFile)) {
                if (is != null) {
                    properties.load(is);
                    log.info("Loaded environment-specific properties from {} for environment: {}", envPropertiesFile, environment);
                    return properties;
                }
            } catch (IOException e) {
                log.debug("Could not load {} from resources folder: {}", envPropertiesFile, e.getMessage());
            }
            
            // Log that we're falling back
            log.debug("Environment-specific file {} not found, falling back to ojp.properties", envPropertiesFile);
        }
        
        // Fall back to ojp.properties in the classpath
        try (InputStream is = DatasourcePropertiesLoader.class.getClassLoader().getResourceAsStream("ojp.properties")) {
            if (is != null) {
                properties.load(is);
                log.debug("Loaded ojp.properties from resources folder");
                return properties;
            }
        } catch (IOException e) {
            log.debug("Could not load ojp.properties from resources folder: {}", e.getMessage());
        }
        
        log.debug("No ojp.properties file found, using server defaults");
        return null;
    }

    /**
     * Get the environment name from system property or environment variable.
     * 
     * Precedence:
     * 1. System property: -Dojp.environment
     * 2. Environment variable: OJP_ENVIRONMENT
     * 
     * @return Environment name (trimmed), or null if not specified
     */
    private static String getEnvironmentName() {
        // Check system property first
        String environment = System.getProperty("ojp.environment");
        if (environment != null && !environment.trim().isEmpty()) {
            return environment.trim();
        }
        
        // Fallback to environment variable
        String envVar = System.getenv("OJP_ENVIRONMENT");
        if (envVar != null && !envVar.trim().isEmpty()) {
            return envVar.trim();
        }
        
        return null;
    }
}
