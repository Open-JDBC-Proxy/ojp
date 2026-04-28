package org.openjproxy.xa.pool.commons.housekeeping;

import java.util.Map;

/**
 * Configuration for XA connection pool housekeeping features.
 * <p>
 * This class encapsulates all configuration options for leak detection,
 * max lifetime enforcement, and enhanced diagnostics.
 * </p>
 *
 * <h3>Configuration Options:</h3>
 * <ul>
 *   <li><strong>Leak Detection:</strong> Tracks borrowed connections and warns when held too long</li>
 *   <li><strong>Max Lifetime:</strong> Recycles connections after maximum age (with idle requirement)</li>
 *   <li><strong>Diagnostics:</strong> Periodic pool state logging</li>
 * </ul>
 *
 * <p>Use the {@link Builder} to construct instances with custom settings.</p>
 */
public class HousekeepingConfig {

    // Leak Detection settings
    private final boolean leakDetectionEnabled;
    private final long leakTimeoutMs;
    private final boolean enhancedLeakReport;
    private final long leakCheckIntervalMs;

    // Max Lifetime settings
    private final long maxLifetimeMs;
    private final long idleBeforeRecycleMs;

    // Diagnostics settings
    private final boolean diagnosticsEnabled;
    private final long diagnosticsIntervalMs;

    private HousekeepingConfig(Builder builder) {
        this.leakDetectionEnabled = builder.leakDetectionEnabled;
        this.leakTimeoutMs = builder.leakTimeoutMs;
        this.enhancedLeakReport = builder.enhancedLeakReport;
        this.leakCheckIntervalMs = builder.leakCheckIntervalMs;
        this.maxLifetimeMs = builder.maxLifetimeMs;
        this.idleBeforeRecycleMs = builder.idleBeforeRecycleMs;
        this.diagnosticsEnabled = builder.diagnosticsEnabled;
        this.diagnosticsIntervalMs = builder.diagnosticsIntervalMs;
    }

    // Getters

    public boolean isLeakDetectionEnabled() {
        return leakDetectionEnabled;
    }

    public long getLeakTimeoutMs() {
        return leakTimeoutMs;
    }

    public boolean isEnhancedLeakReport() {
        return enhancedLeakReport;
    }

    public long getLeakCheckIntervalMs() {
        return leakCheckIntervalMs;
    }

    public long getMaxLifetimeMs() {
        return maxLifetimeMs;
    }

    public long getIdleBeforeRecycleMs() {
        return idleBeforeRecycleMs;
    }

    public boolean isDiagnosticsEnabled() {
        return diagnosticsEnabled;
    }

    public long getDiagnosticsIntervalMs() {
        return diagnosticsIntervalMs;
    }

    /**
     * Creates a new builder for HousekeepingConfig.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses housekeeping configuration from a properties map.
     *
     * @param config the configuration properties
     * @return a configured HousekeepingConfig instance
     */
    public static HousekeepingConfig parseFromProperties(Map<String, String> config) {
        Builder builder = new Builder();

        // Leak Detection
        builder.leakDetectionEnabled(getBooleanConfig(config, "xa.leakDetection.enabled", true));
        builder.leakTimeoutMs(getLongConfig(config, "xa.leakDetection.timeoutMs", 300000L));
        builder.enhancedLeakReport(getBooleanConfig(config, "xa.leakDetection.enhanced", false));
        builder.leakCheckIntervalMs(getLongConfig(config, "xa.leakDetection.intervalMs", 60000L));

        // Max Lifetime
        builder.maxLifetimeMs(getLongConfig(config, "xa.maxLifetimeMs", 1800000L));
        builder.idleBeforeRecycleMs(getLongConfig(config, "xa.idleBeforeRecycleMs", 300000L));

        // Diagnostics
        builder.diagnosticsEnabled(getBooleanConfig(config, "xa.diagnostics.enabled", false));
        builder.diagnosticsIntervalMs(getLongConfig(config, "xa.diagnostics.intervalMs", 300000L));

        return builder.build();
    }

    private static boolean getBooleanConfig(Map<String, String> config, String key, boolean defaultValue) {
        String value = config.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private static long getLongConfig(Map<String, String> config, String key, long defaultValue) {
        String value = config.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Builder for HousekeepingConfig.
     */
    public static class Builder {
        // Leak Detection defaults
        private boolean leakDetectionEnabled = true;  // Enabled by default per stakeholder
        private long leakTimeoutMs = 300000L;         // 5 minutes
        private boolean enhancedLeakReport = false;   // Stack traces off by default
        private long leakCheckIntervalMs = 60000L;    // Check every minute

        // Max Lifetime defaults
        private long maxLifetimeMs = 1800000L;        // 30 minutes
        private long idleBeforeRecycleMs = 300000L;   // Must be idle 5 minutes

        // Diagnostics defaults
        private boolean diagnosticsEnabled = false;    // Disabled by default
        private long diagnosticsIntervalMs = 300000L;  // Log every 5 minutes

        private Builder() {
        }

        public Builder leakDetectionEnabled(boolean enabled) {
            this.leakDetectionEnabled = enabled;
            return this;
        }

        public Builder leakTimeoutMs(long timeoutMs) {
            this.leakTimeoutMs = timeoutMs;
            return this;
        }

        public Builder enhancedLeakReport(boolean enhanced) {
            this.enhancedLeakReport = enhanced;
            return this;
        }

        public Builder leakCheckIntervalMs(long intervalMs) {
            this.leakCheckIntervalMs = intervalMs;
            return this;
        }

        public Builder maxLifetimeMs(long lifetimeMs) {
            this.maxLifetimeMs = lifetimeMs;
            return this;
        }

        public Builder idleBeforeRecycleMs(long idleMs) {
            this.idleBeforeRecycleMs = idleMs;
            return this;
        }

        public Builder diagnosticsEnabled(boolean enabled) {
            this.diagnosticsEnabled = enabled;
            return this;
        }

        public Builder diagnosticsIntervalMs(long intervalMs) {
            this.diagnosticsIntervalMs = intervalMs;
            return this;
        }

        public HousekeepingConfig build() {
            return new HousekeepingConfig(this);
        }
    }
}
