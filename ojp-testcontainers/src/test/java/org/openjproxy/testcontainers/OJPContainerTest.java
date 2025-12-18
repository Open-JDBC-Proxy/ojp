package org.openjproxy.testcontainers;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for OJPContainer.
 * This test verifies that the OJP container can be started and provides
 * the expected configuration methods.
 * 
 * Note: Full JDBC integration tests are in ojp-jdbc-driver module to avoid
 * cyclic dependencies.
 */
@Testcontainers
class OJPContainerTest {
    
    @Container
    static OJPContainer ojp = new OJPContainer();
    
    @Test
    void testContainerStarts() {
        // Verify container is running
        assertTrue(ojp.isRunning(), "OJP container should be running");
        
        // Verify gRPC port is mapped
        assertTrue(ojp.getGrpcPort() > 0, "gRPC port should be mapped");
        
        // Verify Prometheus port is mapped
        assertTrue(ojp.getPrometheusPort() > 0, "Prometheus port should be mapped");
    }
    
    @Test
    void testGetGrpcUrl() {
        String grpcUrl = ojp.getGrpcUrl();
        assertNotNull(grpcUrl);
        assertTrue(grpcUrl.contains(":"), "gRPC URL should contain host and port");
    }
    
    @Test
    void testBuildJdbcUrl() {
        String originalUrl = "jdbc:h2:mem:test";
        String ojpUrl = ojp.buildJdbcUrl(originalUrl);
        
        assertNotNull(ojpUrl);
        assertTrue(ojpUrl.startsWith("jdbc:ojp["), "OJP URL should start with jdbc:ojp[");
        assertTrue(ojpUrl.contains("]_h2:mem:test"), "OJP URL should contain the original database URL");
    }
    
    @Test
    void testGetPrometheusUrl() {
        String prometheusUrl = ojp.getPrometheusUrl();
        
        assertNotNull(prometheusUrl);
        assertTrue(prometheusUrl.startsWith("http://"), "Prometheus URL should start with http://");
        assertTrue(prometheusUrl.endsWith("/metrics"), "Prometheus URL should end with /metrics");
    }
    
    @Test
    void testWithTelemetryDisabled() {
        OJPContainer ojpNoTelemetry = new OJPContainer()
            .withTelemetryEnabled(false);
        
        // Should throw exception when trying to get Prometheus URL with telemetry disabled
        assertThrows(IllegalStateException.class, ojpNoTelemetry::getPrometheusUrl,
            "Should throw exception when telemetry is disabled");
    }
}
