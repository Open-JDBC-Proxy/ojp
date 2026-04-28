package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import org.openjproxy.constants.CommonConstants;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * Utility class for loading datasource-specific properties from ojp.properties file.
 * Shared by Driver, OjpXADataSource, and MultinodeConnectionManager to avoid code duplication.
 *
 * <p>Each named datasource is identified by the prefix it uses in ojp.properties.
 * For example, properties prefixed with {@code mainApp.ojp.connection.pool.*} belong to
 * the datasource named {@code mainApp}. Unprefixed {@code ojp.connection.pool.*} properties
 * belong to the implicit {@code "default"} datasource.
 */
@Slf4j
public class DatasourcePropertiesLoader {

    private static final String DEFAULT_DATASOURCE_NAME = "default";
    private static final String OJP_POOL_PREFIX = "ojp.connection.pool.";
    private static final String OJP_XA_PREFIX = "ojp.xa.";

    /**
     * Load ojp.properties and extract configuration for the datasource identified by
     * {@code datasourceName}. The name is the prefix used for that datasource's properties
     * (e.g. {@code "mainApp"} loads all {@code mainApp.ojp.connection.pool.*} entries,
     * stripping the prefix before returning them).
     *
     * <p>Pass {@code "default"} to load unprefixed {@code ojp.connection.pool.*} properties.
     *
     * <p>Property precedence (highest to lowest):
     * <ol>
     *   <li>Environment variables (e.g. {@code MAINAPP_OJP_CONNECTION_POOL_ENABLED=false})</li>
     *   <li>System properties (e.g. {@code -Dmainapp.ojp.connection.pool.enabled=false})</li>
     *   <li>Properties file (ojp.properties)</li>
     * </ol>
     *
     * @param datasourceName the prefix/name of the datasource to load
     * @return properties for the datasource, or {@code null} if none found
     */
    public static Properties loadOjpPropertiesForDataSource(String datasourceName) {
        Properties allProperties = loadOjpProperties();
        if (allProperties == null || allProperties.isEmpty()) {
            return null;
        }
        boolean isDefault = DEFAULT_DATASOURCE_NAME.equals(datasourceName);
        String prefixDot = datasourceName + ".";
        Properties result = new Properties();
        applyFileProperties(result, allProperties, prefixDot, isDefault);
        applySystemProperties(result, prefixDot, isDefault);
        applyEnvProperties(result, prefixDot, isDefault);
        if (!result.isEmpty()) {
            result.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, datasourceName);
        }
        log.debug("Loaded {} properties for dataSource '{}': {}", result.size(), datasourceName, result);
        return result.isEmpty() ? null : result;
    }

    private static void applyFileProperties(Properties result, Properties source,
                                             String prefixDot, boolean isDefault) {
        boolean found = false;
        for (String key : source.stringPropertyNames()) {
            if (hasPrefixedOjpKey(key, prefixDot)) {
                result.setProperty(key.substring(prefixDot.length()), source.getProperty(key));
                found = true;
            }
        }
        if (!found && isDefault) {
            copyUnprefixedOjpProperties(result, source);
        }
    }

    private static void applySystemProperties(Properties result, String prefixDot, boolean isDefault) {
        for (String key : System.getProperties().stringPropertyNames()) {
            String value = System.getProperty(key);
            if (hasPrefixedOjpKey(key, prefixDot)) {
                String std = key.substring(prefixDot.length());
                result.setProperty(std, value);
                log.debug("Overriding property from system property: {} = {}", std, value);
            } else if (isDefault && isUnprefixedOjpKey(key)) {
                result.setProperty(key, value);
                log.debug("Overriding property from system property: {} = {}", key, value);
            }
        }
    }

    private static void applyEnvProperties(Properties result, String prefixDot, boolean isDefault) {
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey().toLowerCase().replace('_', '.');
            String value = entry.getValue();
            if (hasPrefixedOjpKey(key, prefixDot)) {
                String std = key.substring(prefixDot.length());
                result.setProperty(std, value);
                log.debug("Overriding property from environment variable: {} = {}", std, value);
            } else if (isDefault && isUnprefixedOjpKey(key)) {
                result.setProperty(key, value);
                log.debug("Overriding property from environment variable: {} = {}", key, value);
            }
        }
    }

    private static boolean hasPrefixedOjpKey(String key, String prefixDot) {
        return key.startsWith(prefixDot + OJP_POOL_PREFIX) || key.startsWith(prefixDot + OJP_XA_PREFIX);
    }

    private static boolean isUnprefixedOjpKey(String key) {
        return key.startsWith(OJP_POOL_PREFIX) || key.startsWith(OJP_XA_PREFIX);
    }

    private static void copyUnprefixedOjpProperties(Properties target, Properties source) {
        for (String key : source.stringPropertyNames()) {
            if (isUnprefixedOjpKey(key)) {
                target.setProperty(key, source.getProperty(key));
            }
        }
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
