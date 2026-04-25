package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.PatternSyntaxException;

/**
 * Adds cache configuration properties to connection properties map.
 *
 * <p>Properties format (added to existing properties):
 * <pre>
 * ojp.cache.enabled=true
 * ojp.cache.queries.1.pattern=SELECT .* FROM products.*
 * ojp.cache.queries.1.ttl=600s
 * ojp.cache.queries.1.invalidateOn=products,product_prices
 * ojp.cache.queries.2.pattern=SELECT .* FROM users.*
 * ojp.cache.queries.2.ttl=300s
 * ojp.cache.queries.2.invalidateOn=users
 * </pre>
 *
 * <p>These properties are sent via ConnectionDetails.properties field and parsed on the server side.
 */
@Slf4j
public class CacheConfigurationBuilder {

    // Private constructor to hide the implicit public one
    private CacheConfigurationBuilder() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Add cache configuration properties to the properties map.
     * Reads cache configuration from System properties and adds them to the connection properties.
     *
     * @param propertiesMap The properties map to add cache configuration to
     * @param datasourceName The datasource name to build configuration for (may be empty for default datasource)
     */
    public static void addCachePropertiesToMap(Map<String, Object> propertiesMap, String datasourceName) {
        // Handle empty/null datasource name (default datasource case)
        // When datasourceName is empty, prefix becomes "ojp.cache." instead of ".ojp.cache."
        String effectiveName = (datasourceName == null || datasourceName.trim().isEmpty())
            ? ""
            : datasourceName.trim() + ".";
        String prefix = effectiveName + "ojp.cache.";

        // Check if caching is enabled
        String enabledValue = System.getProperty(prefix + "enabled");
        if (enabledValue == null || !Boolean.parseBoolean(enabledValue)) {
            log.debug("Caching disabled for datasource: {}", datasourceName);
            return;
        }

        // Add enabled flag
        propertiesMap.put("ojp.cache.enabled", "true");
        log.info("Caching enabled for datasource: {}", datasourceName);

        // Parse and add query rules
        Set<Integer> indices = findQueryIndices(prefix);
        log.debug("Found {} cache query rule indices for datasource '{}'", indices.size(), datasourceName);

        for (int index : indices) {
            try {
                addQueryRuleProperties(propertiesMap, prefix, index);
                log.debug("Added cache rule {} to properties", index);
            } catch (Exception e) {
                log.warn("Failed to add cache rule {} for datasource '{}': {}",
                    index, datasourceName, e.getMessage());
            }
        }

        log.info("Added cache configuration properties for datasource '{}' with {} rules",
            datasourceName, indices.size());
    }

    /**
     * Find all query indices in System properties.
     * Looks for properties like: datasource.ojp.cache.queries.N.*
     *
     * @param prefix The property prefix (e.g., "postgres_prod.ojp.cache.")
     * @return Set of query indices found
     */
    private static Set<Integer> findQueryIndices(String prefix) {
        Set<Integer> indices = new TreeSet<>();  // Sorted set for consistent ordering
        String queriesPrefix = prefix + "queries.";

        // Scan System properties for query indices
        for (Object key : System.getProperties().keySet()) {
            String propertyKey = (String) key;
            if (propertyKey.startsWith(queriesPrefix)) {
                // Extract index from property like "datasource.ojp.cache.queries.1.pattern"
                String remainder = propertyKey.substring(queriesPrefix.length());
                int dotIndex = remainder.indexOf('.');
                if (dotIndex > 0) {
                    try {
                        int index = Integer.parseInt(remainder.substring(0, dotIndex));
                        indices.add(index);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid query index in property: {}", propertyKey);
                    }
                }
            }
        }

        return indices;
    }

    /**
     * Add a single cache rule's properties to the map.
     *
     * @param propertiesMap The properties map to add to
     * @param prefix The datasource property prefix (e.g., "postgres_prod.ojp.cache.")
     * @param index The query rule index
     */
    private static void addQueryRuleProperties(Map<String, Object> propertiesMap, String prefix, int index) {
        String queryPrefix = prefix + "queries." + index + ".";
        String targetPrefix = "ojp.cache.queries." + index + ".";

        // Pattern is required
        String pattern = System.getProperty(queryPrefix + "pattern");
        if (pattern == null || pattern.trim().isEmpty()) {
            log.warn("Missing pattern for cache rule {}, skipping", index);
            return;
        }

        // Validate regex pattern
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            log.error("Invalid regex pattern for cache rule {}: {}", index, e.getMessage());
            throw new IllegalArgumentException(
                "Invalid regex pattern for cache rule " + index + ": " + e.getMessage(), e);
        }

        propertiesMap.put(targetPrefix + "pattern", pattern);

        // Parse TTL (default: 300s = 5 minutes)
        String ttlStr = System.getProperty(queryPrefix + "ttl", "300s");
        long ttlSeconds = parseDurationToSeconds(ttlStr);

        if (ttlSeconds <= 0) {
            log.error("Invalid TTL for cache rule {}: {} (must be positive)", index, ttlStr);
            throw new IllegalArgumentException(
                "Invalid TTL for cache rule " + index + ": " + ttlStr + " (must be positive)");
        }

        propertiesMap.put(targetPrefix + "ttl", String.valueOf(ttlSeconds));

        // Parse invalidateOn table list (comma-separated, optional)
        String invalidateOnStr = System.getProperty(queryPrefix + "invalidateOn", "");
        if (invalidateOnStr != null && !invalidateOnStr.trim().isEmpty()) {
            propertiesMap.put(targetPrefix + "invalidateOn", invalidateOnStr);
        }

        // Check if rule is enabled (default: true)
        String enabledStr = System.getProperty(queryPrefix + "enabled", "true");
        propertiesMap.put(targetPrefix + "enabled", enabledStr);
    }

    /**
     * Parse duration string to seconds.
     * Supports formats: "300s", "10m", "2h"
     *
     * @param duration Duration string
     * @return Duration in seconds
     */
    private static long parseDurationToSeconds(String duration) {
        if (duration == null || duration.trim().isEmpty()) {
            return 300; // Default: 5 minutes
        }

        duration = duration.trim().toLowerCase();

        // Parse numeric value and unit
        char unit = duration.charAt(duration.length() - 1);
        String valueStr = duration.substring(0, duration.length() - 1).trim();

        try {
            long value = Long.parseLong(valueStr);

            switch (unit) {
                case 's':  // seconds
                    return value;
                case 'm':  // minutes
                    return value * 60;
                case 'h':  // hours
                    return value * 3600;
                default:
                    // No unit, assume seconds
                    return Long.parseLong(duration);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid duration format: " + duration, e);
        }
    }

    /**
     * Get boolean property with default value.
     */
    private static boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
}
