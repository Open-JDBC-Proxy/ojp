package org.openjproxy.grpc.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ServerConfiguration class.
 */
class ServerConfigurationTest {

    @AfterEach
    void cleanup() {
        // Clear any system properties set during tests
        System.clearProperty("ojp.server.port");
        System.clearProperty("ojp.prometheus.port");
        System.clearProperty("ojp.opentelemetry.enabled");
        System.clearProperty("ojp.opentelemetry.endpoint");
        System.clearProperty("ojp.server.threadPoolSize");
        System.clearProperty("ojp.server.maxRequestSize");
        System.clearProperty("ojp.server.logLevel");
        System.clearProperty("ojp.server.allowedIps");
        System.clearProperty("ojp.server.connectionIdleTimeout");
        System.clearProperty("ojp.prometheus.allowedIps");
        System.clearProperty("ojp.server.circuitBreakerTimeout");
        System.clearProperty("ojp.libs.path");
    }

    @Test
    void testDefaultConfiguration() {
        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_SERVER_PORT, config.getServerPort());
        assertEquals(ServerConfiguration.DEFAULT_PROMETHEUS_PORT, config.getPrometheusPort());
        assertEquals(ServerConfiguration.DEFAULT_OPENTELEMETRY_ENABLED, config.isOpenTelemetryEnabled());
        assertEquals(ServerConfiguration.DEFAULT_OPENTELEMETRY_ENDPOINT, config.getOpenTelemetryEndpoint());
        assertEquals(ServerConfiguration.DEFAULT_THREAD_POOL_SIZE, config.getThreadPoolSize());
        assertEquals(ServerConfiguration.DEFAULT_MAX_REQUEST_SIZE, config.getMaxRequestSize());
        assertEquals(ServerConfiguration.DEFAULT_LOG_LEVEL, config.getLogLevel());
        assertEquals(ServerConfiguration.DEFAULT_ALLOWED_IPS, config.getAllowedIps());
        assertEquals(ServerConfiguration.DEFAULT_CONNECTION_IDLE_TIMEOUT, config.getConnectionIdleTimeout());
        assertEquals(ServerConfiguration.DEFAULT_PROMETHEUS_ALLOWED_IPS, config.getPrometheusAllowedIps());
        assertEquals(ServerConfiguration.DEFAULT_CIRCUIT_BREAKER_TIMEOUT, config.getCircuitBreakerTimeout());
        assertEquals(ServerConfiguration.DEFAULT_CIRCUIT_BREAKER_THRESHOLD, config.getCircuitBreakerThreshold());
        assertEquals(ServerConfiguration.DEFAULT_DRIVERS_PATH, config.getDriversPath());
    }

    @Test
    void testJvmSystemPropertiesOverride() {
        // Set JVM system properties
        System.setProperty("ojp.server.port", "8080");
        System.setProperty("ojp.prometheus.port", "9091");
        System.setProperty("ojp.opentelemetry.enabled", "false");
        System.setProperty("ojp.opentelemetry.endpoint", "http://localhost:4317");
        System.setProperty("ojp.server.threadPoolSize", "100");
        System.setProperty("ojp.server.maxRequestSize", "8388608"); // 8MB
        System.setProperty("ojp.server.logLevel", "DEBUG");
        System.setProperty("ojp.server.allowedIps", "192.168.1.0/24,10.0.0.1");
        System.setProperty("ojp.server.connectionIdleTimeout", "60000");
        System.setProperty("ojp.prometheus.allowedIps", "127.0.0.1,192.168.1.0/24");
        System.setProperty("ojp.server.circuitBreakerTimeout", "120000");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(8080, config.getServerPort());
        assertEquals(9091, config.getPrometheusPort());
        assertFalse(config.isOpenTelemetryEnabled());
        assertEquals("http://localhost:4317", config.getOpenTelemetryEndpoint());
        assertEquals(100, config.getThreadPoolSize());
        assertEquals(8388608, config.getMaxRequestSize());
        assertEquals("DEBUG", config.getLogLevel());
        assertEquals(List.of("192.168.1.0/24", "10.0.0.1"), config.getAllowedIps());
        assertEquals(60000, config.getConnectionIdleTimeout());
        assertEquals(List.of("127.0.0.1", "192.168.1.0/24"), config.getPrometheusAllowedIps());
        assertEquals(120000, config.getCircuitBreakerTimeout());
    }

    @Test
    void testInvalidIntegerValues() {
        System.setProperty("ojp.server.port", "invalid");
        System.setProperty("ojp.prometheus.port", "not-a-number");
        System.setProperty("ojp.server.threadPoolSize", "abc");
        System.setProperty("ojp.server.circuitBreakerThreshold", "xyz");

        ServerConfiguration config = new ServerConfiguration();

        // Should fall back to defaults for invalid values
        assertEquals(ServerConfiguration.DEFAULT_SERVER_PORT, config.getServerPort());
        assertEquals(ServerConfiguration.DEFAULT_PROMETHEUS_PORT, config.getPrometheusPort());
        assertEquals(ServerConfiguration.DEFAULT_THREAD_POOL_SIZE, config.getThreadPoolSize());
        assertEquals(ServerConfiguration.DEFAULT_CIRCUIT_BREAKER_THRESHOLD, config.getCircuitBreakerThreshold());
    }

    @Test
    void testInvalidLongValues() {
        System.setProperty("ojp.server.connectionIdleTimeout", "invalid-long");
        System.setProperty("ojp.server.circuitBreakerTimeout", "not-a-number");

        ServerConfiguration config = new ServerConfiguration();

        // Should fall back to default for invalid values
        assertEquals(ServerConfiguration.DEFAULT_CONNECTION_IDLE_TIMEOUT, config.getConnectionIdleTimeout());
    }

    @Test
    void testBooleanValues() {
        System.setProperty("ojp.opentelemetry.enabled", "true");

        ServerConfiguration config = new ServerConfiguration();

        assertTrue(config.isOpenTelemetryEnabled());
    }

    @Test
    void testListProperties() {
        System.setProperty("ojp.server.allowedIps", "192.168.1.1, 10.0.0.0/8 , 172.16.0.1");
        System.setProperty("ojp.prometheus.allowedIps", "127.0.0.1,192.168.0.0/16");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(List.of("192.168.1.1", "10.0.0.0/8", "172.16.0.1"), config.getAllowedIps());
        assertEquals(List.of("127.0.0.1", "192.168.0.0/16"), config.getPrometheusAllowedIps());
    }

    @Test
    void testEmptyListProperties() {
        System.setProperty("ojp.server.allowedIps", "");
        System.setProperty("ojp.prometheus.allowedIps", " ");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_ALLOWED_IPS, config.getAllowedIps());
        assertEquals(ServerConfiguration.DEFAULT_PROMETHEUS_ALLOWED_IPS, config.getPrometheusAllowedIps());
    }

    @Test
    void testGettersReturnDefensiveCopies() {
        ServerConfiguration config = new ServerConfiguration();

        List<String> allowedIps = config.getAllowedIps();
        List<String> prometheusAllowedIps = config.getPrometheusAllowedIps();

        // Modifying returned lists should not affect the configuration
        allowedIps.clear();
        prometheusAllowedIps.clear();

        assertFalse(config.getAllowedIps().isEmpty());
        assertFalse(config.getPrometheusAllowedIps().isEmpty());
    }

    @Test
    void testDriversPathConfiguration() {
        // Test default value
        ServerConfiguration config = new ServerConfiguration();
        assertEquals(ServerConfiguration.DEFAULT_DRIVERS_PATH, config.getDriversPath());

        // Test custom value via system property
        System.setProperty("ojp.libs.path", "/custom/path/to/libs");
        config = new ServerConfiguration();
        assertEquals("/custom/path/to/libs", config.getDriversPath());
    }

    @Test
    void testDefaultSessionCleanupConfiguration() {
        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_SESSION_CLEANUP_ENABLED, config.isSessionCleanupEnabled());
        assertEquals(ServerConfiguration.DEFAULT_SESSION_TIMEOUT_MINUTES, config.getSessionTimeoutMinutes());
        assertEquals(ServerConfiguration.DEFAULT_SESSION_CLEANUP_INTERVAL_MINUTES, config.getSessionCleanupIntervalMinutes());
    }

    @Test
    void testCustomSessionCleanupConfiguration() {
        // Set custom properties
        System.setProperty("ojp.server.sessionCleanup.enabled", "false");
        System.setProperty("ojp.server.sessionCleanup.timeoutMinutes", "60");
        System.setProperty("ojp.server.sessionCleanup.intervalMinutes", "10");

        ServerConfiguration config = new ServerConfiguration();

        assertFalse(config.isSessionCleanupEnabled());
        assertEquals(60, config.getSessionTimeoutMinutes());
        assertEquals(10, config.getSessionCleanupIntervalMinutes());

        // Cleanup
        System.clearProperty("ojp.server.sessionCleanup.enabled");
        System.clearProperty("ojp.server.sessionCleanup.timeoutMinutes");
        System.clearProperty("ojp.server.sessionCleanup.intervalMinutes");
    }
}