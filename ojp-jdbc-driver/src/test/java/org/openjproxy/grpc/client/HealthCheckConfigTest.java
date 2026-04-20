package org.openjproxy.grpc.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HealthCheckConfig} including duration string parsing,
 * properties file loading, and system-property override priority.
 */
class HealthCheckConfigTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("ojp.health.check.interval");
        System.clearProperty("ojp.health.check.threshold");
        System.clearProperty("ojp.health.check.timeout");
        System.clearProperty("ojp.redistribution.enabled");
        System.clearProperty("ojp.redistribution.idleRebalanceFraction");
        System.clearProperty("ojp.redistribution.maxClosePerRecovery");
        System.clearProperty("ojp.loadaware.selection.enabled");
    }

    // -------------------------------------------------------------------------
    // parseDurationMs
    // -------------------------------------------------------------------------

    @Test
    void testParseDurationMs_plainInteger() {
        assertEquals(5000L, HealthCheckConfig.parseDurationMs("5000"));
        assertEquals(0L,    HealthCheckConfig.parseDurationMs("0"));
    }

    @Test
    void testParseDurationMs_milliseconds() {
        assertEquals(500L,  HealthCheckConfig.parseDurationMs("500ms"));
        assertEquals(2000L, HealthCheckConfig.parseDurationMs("2000ms"));
    }

    @Test
    void testParseDurationMs_seconds() {
        assertEquals(10_000L, HealthCheckConfig.parseDurationMs("10s"));
        assertEquals(2_000L,  HealthCheckConfig.parseDurationMs("2s"));
        assertEquals(1_000L,  HealthCheckConfig.parseDurationMs("1s"));
    }

    @Test
    void testParseDurationMs_minutes() {
        assertEquals(60_000L,  HealthCheckConfig.parseDurationMs("1m"));
        assertEquals(120_000L, HealthCheckConfig.parseDurationMs("2m"));
    }

    @Test
    void testParseDurationMs_invalidValue() {
        assertThrows(NumberFormatException.class, () -> HealthCheckConfig.parseDurationMs("abc"));
        assertThrows(NumberFormatException.class, () -> HealthCheckConfig.parseDurationMs("10x"));
    }

    // -------------------------------------------------------------------------
    // loadFromProperties with duration-string values (file properties)
    // -------------------------------------------------------------------------

    @Test
    void testLoadFromProperties_durationStrings() {
        Properties props = new Properties();
        props.setProperty("ojp.health.check.interval",  "10s");
        props.setProperty("ojp.health.check.threshold", "10s");
        props.setProperty("ojp.health.check.timeout",   "2s");
        props.setProperty("ojp.redistribution.idleRebalanceFraction", "1.0");
        props.setProperty("ojp.redistribution.maxClosePerRecovery",   "99");
        props.setProperty("ojp.loadaware.selection.enabled", "true");

        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(props);

        assertEquals(10_000L, config.getHealthCheckIntervalMs());
        assertEquals(10_000L, config.getHealthCheckThresholdMs());
        assertEquals(2_000,   config.getHealthCheckTimeoutMs());
        assertEquals(1.0,     config.getIdleRebalanceFraction(), 0.0001);
        assertEquals(99,      config.getMaxClosePerRecovery());
        assertTrue(config.isLoadAwareSelectionEnabled());
    }

    @Test
    void testLoadFromProperties_plainMilliseconds() {
        Properties props = new Properties();
        props.setProperty("ojp.health.check.interval",  "5000");
        props.setProperty("ojp.health.check.threshold", "5000");
        props.setProperty("ojp.health.check.timeout",   "3000");

        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(props);

        assertEquals(5_000L, config.getHealthCheckIntervalMs());
        assertEquals(5_000L, config.getHealthCheckThresholdMs());
        assertEquals(3_000,  config.getHealthCheckTimeoutMs());
    }

    @Test
    void testLoadFromProperties_minutesDuration() {
        Properties props = new Properties();
        props.setProperty("ojp.health.check.interval",  "1m");
        props.setProperty("ojp.health.check.threshold", "2m");

        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(props);

        assertEquals(60_000L,  config.getHealthCheckIntervalMs());
        assertEquals(120_000L, config.getHealthCheckThresholdMs());
    }

    @Test
    void testLoadFromProperties_invalidDurationFallsBackToDefault() {
        Properties props = new Properties();
        props.setProperty("ojp.health.check.interval",  "invalid");
        props.setProperty("ojp.health.check.timeout",   "10x");

        // Both invalid values should fall back to defaults without throwing
        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(props);
        HealthCheckConfig defaults = HealthCheckConfig.createDefault();

        assertEquals(defaults.getHealthCheckIntervalMs(), config.getHealthCheckIntervalMs());
        assertEquals(defaults.getHealthCheckTimeoutMs(),  config.getHealthCheckTimeoutMs());
    }

    @Test
    void testLoadFromProperties_nullProps_usesDefaults() {
        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(null);
        HealthCheckConfig defaults = HealthCheckConfig.createDefault();

        assertEquals(defaults.getHealthCheckIntervalMs(),    config.getHealthCheckIntervalMs());
        assertEquals(defaults.getHealthCheckThresholdMs(),   config.getHealthCheckThresholdMs());
        assertEquals(defaults.getHealthCheckTimeoutMs(),     config.getHealthCheckTimeoutMs());
        assertEquals(defaults.getIdleRebalanceFraction(),    config.getIdleRebalanceFraction(), 0.0001);
        assertEquals(defaults.getMaxClosePerRecovery(),      config.getMaxClosePerRecovery());
        assertEquals(defaults.isLoadAwareSelectionEnabled(), config.isLoadAwareSelectionEnabled());
    }

    @Test
    void testLoadFromProperties_loadAwareDisabled() {
        Properties props = new Properties();
        props.setProperty("ojp.loadaware.selection.enabled", "false");

        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(props);
        assertFalse(config.isLoadAwareSelectionEnabled());
    }

    // -------------------------------------------------------------------------
    // System-property override (simulates -D flags and Spring Boot application.yml
    // forwarded by OjpSystemPropertiesBridge)
    // -------------------------------------------------------------------------

    @Test
    void testSystemPropertyOverridesFileProperty() {
        Properties fileProps = new Properties();
        fileProps.setProperty("ojp.health.check.interval", "30s"); // would be 30 000 ms

        // System property wins
        System.setProperty("ojp.health.check.interval", "10s");

        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(fileProps);

        assertEquals(10_000L, config.getHealthCheckIntervalMs(),
                "System property should override file property");
    }

    @Test
    void testSystemPropertyUsedWhenFilePropertyAbsent() {
        System.setProperty("ojp.health.check.threshold", "20s");

        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(null);

        assertEquals(20_000L, config.getHealthCheckThresholdMs(),
                "System property should be read when no file properties are provided");
    }

    @Test
    void testSystemPropertyDurationStrings() {
        System.setProperty("ojp.health.check.interval",  "2m");
        System.setProperty("ojp.health.check.timeout",   "500ms");
        System.setProperty("ojp.loadaware.selection.enabled", "false");
        System.setProperty("ojp.redistribution.maxClosePerRecovery", "50");

        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(null);

        assertEquals(120_000L, config.getHealthCheckIntervalMs());
        assertEquals(500,      config.getHealthCheckTimeoutMs());
        assertFalse(config.isLoadAwareSelectionEnabled());
        assertEquals(50, config.getMaxClosePerRecovery());
    }

    @Test
    void testAllPropertiesViaSystemProperties() {
        System.setProperty("ojp.health.check.interval",                  "10s");
        System.setProperty("ojp.health.check.threshold",                 "10s");
        System.setProperty("ojp.health.check.timeout",                   "2s");
        System.setProperty("ojp.redistribution.enabled",                 "true");
        System.setProperty("ojp.redistribution.idleRebalanceFraction",   "1.0");
        System.setProperty("ojp.redistribution.maxClosePerRecovery",     "99");
        System.setProperty("ojp.loadaware.selection.enabled",            "true");

        HealthCheckConfig config = HealthCheckConfig.loadFromProperties(null);

        assertEquals(10_000L, config.getHealthCheckIntervalMs());
        assertEquals(10_000L, config.getHealthCheckThresholdMs());
        assertEquals(2_000,   config.getHealthCheckTimeoutMs());
        assertTrue(config.isRedistributionEnabled());
        assertEquals(1.0, config.getIdleRebalanceFraction(), 0.0001);
        assertEquals(99, config.getMaxClosePerRecovery());
        assertTrue(config.isLoadAwareSelectionEnabled());
    }
}
