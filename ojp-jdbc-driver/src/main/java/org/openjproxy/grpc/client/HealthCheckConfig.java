package org.openjproxy.grpc.client;

import org.openjproxy.constants.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Configuration for health check and connection redistribution in multinode deployments.
 * Loads settings from ojp.properties.
 */
public class HealthCheckConfig {
    
    private static final Logger log = LoggerFactory.getLogger(HealthCheckConfig.class);
    
    // Default values
    private static final long DEFAULT_HEALTH_CHECK_INTERVAL_MS = 30000L; // 30 seconds
    private static final long DEFAULT_HEALTH_CHECK_THRESHOLD_MS = 30000L; // 30 seconds
    private static final int DEFAULT_HEALTH_CHECK_TIMEOUT_MS = 5000; // 5 seconds
    private static final String DEFAULT_HEALTH_CHECK_QUERY = "SELECT 1";
    private static final boolean DEFAULT_REDISTRIBUTION_ENABLED = true;
    private static final double DEFAULT_IDLE_REBALANCE_FRACTION = 0.2; // 20% of idle connections
    private static final int DEFAULT_MAX_CLOSE_PER_RECOVERY = 10; // Max connections to close per recovery event
    
    // Property keys
    private static final String PROP_HEALTH_CHECK_INTERVAL = "ojp.health.check.interval";
    private static final String PROP_HEALTH_CHECK_THRESHOLD = "ojp.health.check.threshold";
    private static final String PROP_HEALTH_CHECK_TIMEOUT = "ojp.health.check.timeout";
    private static final String PROP_HEALTH_CHECK_QUERY = "ojp.health.check.query";
    private static final String PROP_REDISTRIBUTION_ENABLED = "ojp.redistribution.enabled";
    private static final String PROP_IDLE_REBALANCE_FRACTION = "ojp.redistribution.idleRebalanceFraction";
    private static final String PROP_MAX_CLOSE_PER_RECOVERY = "ojp.redistribution.maxClosePerRecovery";
    
    private final long healthCheckIntervalMs;
    private final long healthCheckThresholdMs;
    private final int healthCheckTimeoutMs;
    private final String healthCheckQuery;
    private final boolean redistributionEnabled;
    private final double idleRebalanceFraction;
    private final int maxClosePerRecovery;
    
    private HealthCheckConfig(long healthCheckIntervalMs, long healthCheckThresholdMs,
                            int healthCheckTimeoutMs, String healthCheckQuery,
                            boolean redistributionEnabled, double idleRebalanceFraction,
                            int maxClosePerRecovery) {
        this.healthCheckIntervalMs = healthCheckIntervalMs;
        this.healthCheckThresholdMs = healthCheckThresholdMs;
        this.healthCheckTimeoutMs = healthCheckTimeoutMs;
        this.healthCheckQuery = healthCheckQuery;
        this.redistributionEnabled = redistributionEnabled;
        this.idleRebalanceFraction = idleRebalanceFraction;
        this.maxClosePerRecovery = maxClosePerRecovery;
    }
    
    /**
     * Loads health check configuration from properties.
     * 
     * @param props Properties to load from (typically from ojp.properties)
     * @return HealthCheckConfig instance with loaded or default values
     */
    public static HealthCheckConfig loadFromProperties(Properties props) {
        if (props == null) {
            log.debug("No properties provided, using default health check configuration");
            return createDefault();
        }
        
        long interval = getLongProperty(props, PROP_HEALTH_CHECK_INTERVAL, DEFAULT_HEALTH_CHECK_INTERVAL_MS);
        long threshold = getLongProperty(props, PROP_HEALTH_CHECK_THRESHOLD, DEFAULT_HEALTH_CHECK_THRESHOLD_MS);
        int timeout = getIntProperty(props, PROP_HEALTH_CHECK_TIMEOUT, DEFAULT_HEALTH_CHECK_TIMEOUT_MS);
        String query = props.getProperty(PROP_HEALTH_CHECK_QUERY, DEFAULT_HEALTH_CHECK_QUERY);
        boolean enabled = getBooleanProperty(props, PROP_REDISTRIBUTION_ENABLED, DEFAULT_REDISTRIBUTION_ENABLED);
        double idleFraction = getDoubleProperty(props, PROP_IDLE_REBALANCE_FRACTION, DEFAULT_IDLE_REBALANCE_FRACTION);
        int maxClose = getIntProperty(props, PROP_MAX_CLOSE_PER_RECOVERY, DEFAULT_MAX_CLOSE_PER_RECOVERY);
        
        log.info("Health check configuration loaded: interval={}ms, threshold={}ms, timeout={}ms, enabled={}, idleFraction={}, maxClose={}", 
                interval, threshold, timeout, enabled, idleFraction, maxClose);
        
        return new HealthCheckConfig(interval, threshold, timeout, query, enabled, idleFraction, maxClose);
    }
    
    /**
     * Creates a configuration with default values.
     * 
     * @return HealthCheckConfig instance with default values
     */
    public static HealthCheckConfig createDefault() {
        return new HealthCheckConfig(
            DEFAULT_HEALTH_CHECK_INTERVAL_MS,
            DEFAULT_HEALTH_CHECK_THRESHOLD_MS,
            DEFAULT_HEALTH_CHECK_TIMEOUT_MS,
            DEFAULT_HEALTH_CHECK_QUERY,
            DEFAULT_REDISTRIBUTION_ENABLED,
            DEFAULT_IDLE_REBALANCE_FRACTION,
            DEFAULT_MAX_CLOSE_PER_RECOVERY
        );
    }
    
    private static long getLongProperty(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            long longValue = Long.parseLong(value.trim());
            if (longValue < 0) {
                log.warn("Invalid negative value for {}: {}, using default: {}", key, value, defaultValue);
                return defaultValue;
            }
            return longValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    private static int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int intValue = Integer.parseInt(value.trim());
            if (intValue < 0) {
                log.warn("Invalid negative value for {}: {}, using default: {}", key, value, defaultValue);
                return defaultValue;
            }
            return intValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    private static boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }
    
    private static double getDoubleProperty(Properties props, String key, double defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            double doubleValue = Double.parseDouble(value.trim());
            if (doubleValue < 0.0 || doubleValue > 1.0) {
                log.warn("Invalid value for {} (must be between 0 and 1): {}, using default: {}", key, value, defaultValue);
                return defaultValue;
            }
            return doubleValue;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: {}, using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    public long getHealthCheckIntervalMs() {
        return healthCheckIntervalMs;
    }
    
    public long getHealthCheckThresholdMs() {
        return healthCheckThresholdMs;
    }
    
    public int getHealthCheckTimeoutMs() {
        return healthCheckTimeoutMs;
    }
    
    public String getHealthCheckQuery() {
        return healthCheckQuery;
    }
    
    public boolean isRedistributionEnabled() {
        return redistributionEnabled;
    }
    
    public double getIdleRebalanceFraction() {
        return idleRebalanceFraction;
    }
    
    public int getMaxClosePerRecovery() {
        return maxClosePerRecovery;
    }
    
    @Override
    public String toString() {
        return "HealthCheckConfig{" +
                "intervalMs=" + healthCheckIntervalMs +
                ", thresholdMs=" + healthCheckThresholdMs +
                ", timeoutMs=" + healthCheckTimeoutMs +
                ", query='" + healthCheckQuery + '\'' +
                ", enabled=" + redistributionEnabled +
                ", idleRebalanceFraction=" + idleRebalanceFraction +
                ", maxClosePerRecovery=" + maxClosePerRecovery +
                '}';
    }
}
