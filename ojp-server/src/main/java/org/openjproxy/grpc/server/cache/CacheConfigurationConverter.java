package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.PropertyEntry;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses cache configuration from ConnectionDetails properties.
 * Used by the server to parse cache configuration received from JDBC driver clients via properties.
 */
@Slf4j
public class CacheConfigurationConverter {
    
    /**
     * Parse cache configuration from ConnectionDetails properties.
     *
     * @param properties Properties list from ConnectionDetails
     * @param datasourceName Datasource name for this configuration
     * @return Domain CacheConfiguration object
     */
    public static CacheConfiguration fromProperties(
            List<PropertyEntry> properties, 
            String datasourceName) {
        
        if (properties == null || properties.isEmpty()) {
            log.debug("No properties provided for datasource: {}", datasourceName);
            return CacheConfiguration.disabled(datasourceName);
        }
        
        // Convert PropertyEntry list to Map for easier access
        Map<String, String> propsMap = new HashMap<>();
        for (PropertyEntry entry : properties) {
            if (entry.hasStringValue()) {
                propsMap.put(entry.getKey(), entry.getStringValue());
            }
        }
        
        // Check if caching is enabled
        String enabledStr = propsMap.get("ojp.cache.enabled");
        if (enabledStr == null || !Boolean.parseBoolean(enabledStr)) {
            log.debug("Cache configuration disabled for datasource: {}", datasourceName);
            return CacheConfiguration.disabled(datasourceName);
        }
        
        log.info("Parsing cache configuration for datasource '{}': enabled=true", datasourceName);
        
        // Parse query rules
        List<CacheRule> domainRules = new ArrayList<>();
        Set<Integer> indices = findQueryIndices(propsMap);
        
        log.debug("Found {} cache rule indices in properties", indices.size());
        
        for (int index : indices) {
            try {
                CacheRule domainRule = parseRule(propsMap, index);
                if (domainRule != null) {
                    domainRules.add(domainRule);
                    log.debug("Parsed cache rule {}: pattern={}, ttl={}s, invalidateOn={}", 
                        index, domainRule.getSqlPattern().pattern(), domainRule.getTtl().getSeconds(), 
                        domainRule.getInvalidateOn());
                }
            } catch (Exception e) {
                log.error("Failed to parse cache rule {} for datasource '{}': {}", 
                    index, datasourceName, e.getMessage());
                // Continue with other rules - skip invalid ones
            }
        }
        
        log.info("Parsed {} cache rules for datasource '{}'", domainRules.size(), datasourceName);
        
        return new CacheConfiguration(
            datasourceName,
            true,
            domainRules
        );
    }
    
    /**
     * Find all query rule indices in properties map.
     *
     * @param propsMap Properties map
     * @return Set of query indices
     */
    private static Set<Integer> findQueryIndices(Map<String, String> propsMap) {
        Set<Integer> indices = new TreeSet<>();
        String prefix = "ojp.cache.queries.";
        
        for (String key : propsMap.keySet()) {
            if (key.startsWith(prefix)) {
                // Extract index from property like "ojp.cache.queries.1.pattern"
                String remainder = key.substring(prefix.length());
                int dotIndex = remainder.indexOf('.');
                if (dotIndex > 0) {
                    try {
                        int index = Integer.parseInt(remainder.substring(0, dotIndex));
                        indices.add(index);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid query index in property: {}", key);
                    }
                }
            }
        }
        
        return indices;
    }
    
    /**
     * Parse a single cache rule from properties.
     *
     * @param propsMap Properties map
     * @param index Rule index
     * @return Domain CacheRule or null if invalid
     */
    private static CacheRule parseRule(Map<String, String> propsMap, int index) {
        String prefix = "ojp.cache.queries." + index + ".";
        
        // Pattern is required
        String sqlPattern = propsMap.get(prefix + "pattern");
        if (sqlPattern == null || sqlPattern.trim().isEmpty()) {
            log.warn("Cache rule {} has empty pattern, skipping", index);
            return null;
        }
        
        // Compile regex pattern
        Pattern pattern;
        try {
            pattern = Pattern.compile(sqlPattern);
        } catch (PatternSyntaxException e) {
            log.error("Invalid regex pattern for cache rule {}: {}", index, e.getMessage());
            throw new IllegalArgumentException(
                "Invalid regex pattern for cache rule " + index + ": " + e.getMessage(), e);
        }
        
        // Parse TTL
        String ttlStr = propsMap.get(prefix + "ttl");
        if (ttlStr == null || ttlStr.trim().isEmpty()) {
            log.error("Missing TTL for cache rule {}", index);
            throw new IllegalArgumentException("Missing TTL for cache rule " + index);
        }
        
        long ttlSeconds;
        try {
            ttlSeconds = Long.parseLong(ttlStr);
        } catch (NumberFormatException e) {
            log.error("Invalid TTL for cache rule {}: {}", index, ttlStr);
            throw new IllegalArgumentException("Invalid TTL for cache rule " + index + ": " + ttlStr, e);
        }
        
        if (ttlSeconds <= 0) {
            log.error("Invalid TTL for cache rule {}: {} (must be positive)", index, ttlSeconds);
            throw new IllegalArgumentException(
                "Invalid TTL for cache rule " + index + ": " + ttlSeconds + " (must be positive)");
        }
        
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        
        // Parse invalidation tables (comma-separated, optional)
        String invalidateOnStr = propsMap.get(prefix + "invalidateOn");
        List<String> invalidationTables = new ArrayList<>();
        if (invalidateOnStr != null && !invalidateOnStr.trim().isEmpty()) {
            String[] tables = invalidateOnStr.split(",");
            for (String table : tables) {
                String trimmed = table.trim();
                if (!trimmed.isEmpty()) {
                    invalidationTables.add(trimmed);
                }
            }
        }
        
        // Check if rule is enabled (default: true)
        String enabledStr = propsMap.get(prefix + "enabled");
        boolean enabled = enabledStr == null || Boolean.parseBoolean(enabledStr);
        
        return new CacheRule(
            pattern,
            ttl,
            invalidationTables,
            enabled
        );
    }
}
