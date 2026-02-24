package org.openjproxy.grpc.server;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OjpMetrics functionality.
 */
class OjpMetricsTest {

    private OjpMetrics metrics;

    @BeforeEach
    void setUp() {
        // Use no-op OpenTelemetry so tests don't need a running Prometheus server
        metrics = new OjpMetrics(OpenTelemetry.noop());
    }

    @Test
    void shouldCreateSuccessfully() {
        assertNotNull(metrics);
    }

    @Test
    void shouldRecordConnectionWaitWithoutErrors() {
        assertDoesNotThrow(() -> {
            metrics.connectionWaitStarted("hash1");
            metrics.connectionAcquired("hash1", 42L, true);
        });
    }

    @Test
    void shouldRecordConnectionWaitFailureWithoutErrors() {
        assertDoesNotThrow(() -> {
            metrics.connectionWaitStarted("hash1");
            metrics.connectionAcquired("hash1", 5000L, false);
        });
    }

    @Test
    void shouldRecordSqlExecutionWithoutErrors() {
        assertDoesNotThrow(() -> metrics.sqlExecuted("abcdef01", 150L));
    }

    @Test
    void shouldRecordSlotAcquireAndReleaseWithoutErrors() {
        assertDoesNotThrow(() -> {
            metrics.slowSlotAcquired();
            metrics.slowSlotReleased();
            metrics.fastSlotAcquired();
            metrics.fastSlotReleased();
        });
    }

    @Test
    void shouldRegisterAndDeregisterDatasourceWithoutErrors() {
        // registerDatasource with a non-null (but non-HikariCP) datasource is silently ignored in gauge callbacks
        assertDoesNotThrow(() -> {
            // deregister a hash that was never registered is a no-op
            metrics.deregisterDatasource("hash-not-registered");
        });
    }

    @Test
    void shouldHandleMultipleConcurrentConnectionsWithoutErrors() {
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10; i++) {
                String connHash = "hash" + i;
                metrics.connectionWaitStarted(connHash);
            }
            for (int i = 0; i < 10; i++) {
                String connHash = "hash" + i;
                metrics.connectionAcquired(connHash, i * 10L, true);
            }
        });
    }

    @Test
    void ojpServerTelemetryShouldExposeOpenTelemetryInstance() {
        OjpServerTelemetry telemetry = new OjpServerTelemetry();
        assertNull(telemetry.getOpenTelemetry(), "getOpenTelemetry() should be null before initialisation");

        telemetry.createNoOpGrpcTelemetry();
        assertNotNull(telemetry.getOpenTelemetry(), "getOpenTelemetry() should be non-null after createNoOpGrpcTelemetry()");
    }

    @Test
    void ojpServerTelemetryShouldCreateOjpMetrics() {
        OjpServerTelemetry telemetry = new OjpServerTelemetry();
        telemetry.createNoOpGrpcTelemetry();
        OjpMetrics created = telemetry.createOjpMetrics();
        assertNotNull(created);
    }

    @Test
    void ojpServerTelemetryShouldThrowWhenCreateOjpMetricsCalledBeforeInit() {
        OjpServerTelemetry telemetry = new OjpServerTelemetry();
        assertThrows(IllegalStateException.class, telemetry::createOjpMetrics);
    }
}
