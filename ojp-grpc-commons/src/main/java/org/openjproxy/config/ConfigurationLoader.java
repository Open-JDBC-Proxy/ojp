package org.openjproxy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * Utility class for loading configuration from both YAML and Properties files.
 * Supports environment-specific configuration files.
 * 
 * Loading order (first found wins):
 * 1. ojp-{environment}.yaml
 * 2. ojp.yaml
 * 3. ojp-{environment}.properties
 * 4. ojp.properties
 * 
 * Environment is determined by (in order of precedence):
 * 1. System property: -Dojp.environment=dev
 * 2. Environment variable: OJP_ENVIRONMENT=dev
 */
public class ConfigurationLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationLoader.class);
    
    /**
     * Load configuration properties from YAML or Properties files.
     * 
     * @return Properties object containing all loaded configuration, or null if no configuration found
     */
    public static Properties loadConfiguration() {
        String environment = getEnvironmentName();
        
        // Try YAML files first
        Properties props = tryLoadYamlConfiguration(environment);
        if (props != null) {
            return props;
        }
        
        // Fall back to properties files
        return tryLoadPropertiesConfiguration(environment);
    }
    
    /**
     * Try to load YAML configuration files.
     * 
     * @param environment The environment name (may be null)
     * @return Properties if YAML file found, null otherwise
     */
    private static Properties tryLoadYamlConfiguration(String environment) {
        // Try environment-specific YAML file first
        if (environment != null && !environment.isEmpty()) {
            String envYamlFile = "ojp-" + environment + ".yaml";
            Properties props = loadYamlFile(envYamlFile);
            if (props != null) {
                logger.info("Loaded environment-specific YAML configuration from {} for environment: {}", envYamlFile, environment);
                return props;
            }
            
            // Also try .yml extension
            String envYmlFile = "ojp-" + environment + ".yml";
            props = loadYamlFile(envYmlFile);
            if (props != null) {
                logger.info("Loaded environment-specific YAML configuration from {} for environment: {}", envYmlFile, environment);
                return props;
            }
        }
        
        // Try default YAML file
        Properties props = loadYamlFile("ojp.yaml");
        if (props != null) {
            logger.info("Loaded YAML configuration from ojp.yaml");
            return props;
        }
        
        // Try default YML file
        props = loadYamlFile("ojp.yml");
        if (props != null) {
            logger.info("Loaded YAML configuration from ojp.yml");
            return props;
        }
        
        return null;
    }
    
    /**
     * Try to load Properties configuration files.
     * 
     * @param environment The environment name (may be null)
     * @return Properties if properties file found, null otherwise
     */
    private static Properties tryLoadPropertiesConfiguration(String environment) {
        // Try environment-specific properties file
        if (environment != null && !environment.isEmpty()) {
            String envPropertiesFile = "ojp-" + environment + ".properties";
            Properties props = loadPropertiesFile(envPropertiesFile);
            if (props != null) {
                logger.info("Loaded environment-specific properties from {} for environment: {}", envPropertiesFile, environment);
                return props;
            }
        }
        
        // Try default properties file
        Properties props = loadPropertiesFile("ojp.properties");
        if (props != null) {
            logger.debug("Loaded properties from ojp.properties");
            return props;
        }
        
        return null;
    }
    
    /**
     * Load a YAML file from classpath and convert to Properties.
     * 
     * @param filename The YAML filename to load
     * @return Properties if file found and loaded successfully, null otherwise
     */
    private static Properties loadYamlFile(String filename) {
        try (InputStream is = ConfigurationLoader.class.getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                return null;
            }
            
            // Create mapper and parse YAML. A LinkageError (NoClassDefFoundError,
            // NoSuchMethodError, etc.) here means jackson-dataformat-yaml is either
            // absent or at a version incompatible with the runtime jackson-core.
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            
            // Parse YAML into a Map
            @SuppressWarnings("unchecked")
            Map<String, Object> yamlMap = mapper.readValue(is, Map.class);
            
            // Convert to flat Properties
            Properties properties = new Properties();
            flattenYamlMap("", yamlMap, properties);
            
            return properties;
        } catch (LinkageError e) {
            logger.debug("jackson-dataformat-yaml is not available or incompatible; YAML file {} will not be processed", filename);
            return null;
        } catch (IOException e) {
            logger.warn("Failed to load YAML file {}: {}", filename, e.getMessage());
            return null;
        }
    }
    
    /**
     * Load a properties file from classpath.
     * 
     * @param filename The properties filename to load
     * @return Properties if file found and loaded successfully, null otherwise
     */
    private static Properties loadPropertiesFile(String filename) {
        Properties properties = new Properties();
        try (InputStream is = ConfigurationLoader.class.getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                return null;
            }
            properties.load(is);
            return properties;
        } catch (IOException e) {
            logger.warn("Failed to load properties file {}: {}", filename, e.getMessage());
            return null;
        }
    }
    
    /**
     * Flatten a nested YAML map into dot-notation properties.
     * 
     * Example:
     * YAML:
     *   ojp:
     *     server:
     *       port: 1059
     * 
     * Properties:
     *   ojp.server.port=1059
     * 
     * @param prefix The current prefix for nested keys
     * @param map The map to flatten
     * @param properties The Properties object to populate
     */
    @SuppressWarnings("unchecked")
    private static void flattenYamlMap(String prefix, Map<String, Object> map, Properties properties) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof Map) {
                // Recursively flatten nested maps
                flattenYamlMap(key, (Map<String, Object>) value, properties);
            } else if (value instanceof Iterable) {
                // Convert lists to comma-separated strings
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Object item : (Iterable<?>) value) {
                    if (!first) {
                        sb.append(",");
                    }
                    sb.append(item.toString());
                    first = false;
                }
                properties.setProperty(key, sb.toString());
            } else if (value != null) {
                // Set simple values
                properties.setProperty(key, value.toString());
            }
        }
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
