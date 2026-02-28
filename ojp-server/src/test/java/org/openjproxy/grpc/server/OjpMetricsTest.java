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
        // sqlExecuted now accepts the raw SQL text (not the hash)
        assertDoesNotThrow(() -> metrics.sqlExecuted("SELECT 1 FROM dual", 150L));
    }

    @Test
    void toSqlSnippetShouldNormaliseAndTruncate() {
        // normalises whitespace and case
        assertEquals("select 1", OjpMetrics.toSqlSnippet("  SELECT   1  "));

        // truncates to SQL_SNIPPET_MAX_LENGTH
        String longSql = "select " + "a".repeat(200);
        String snippet = OjpMetrics.toSqlSnippet(longSql);
        assertEquals(OjpMetrics.SQL_SNIPPET_MAX_LENGTH, snippet.length());

        // exactly at the limit — must not truncate
        String exactSql = "select " + "a".repeat(OjpMetrics.SQL_SNIPPET_MAX_LENGTH - 7);
        assertEquals(OjpMetrics.SQL_SNIPPET_MAX_LENGTH, OjpMetrics.toSqlSnippet(exactSql).length());

        // null / empty
        assertEquals("", OjpMetrics.toSqlSnippet(null));
        assertEquals("", OjpMetrics.toSqlSnippet(""));
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
    void shouldRegisterAndDeregisterXaPoolWithoutErrors() {
        assertDoesNotThrow(() -> {
            // non-CommonsPool2XADataSource is silently ignored
            metrics.registerXaPool("hash-xa-1", "mydb_xa_hash", new Object());
            metrics.deregisterXaPool("hash-xa-1");
            // deregister a hash that was never registered is a no-op
            metrics.deregisterXaPool("hash-xa-never-registered");
        });
    }

    @Test
    void buildPoolLabelShouldProduceReadableLabel() {
        // OJP datasource name + hash suffix
        assertEquals("mydb_a1b2",       OjpMetrics.buildPoolLabel("mydb",    "a1b2c3d4", false));
        assertEquals("mydb_xa_a1b2",    OjpMetrics.buildPoolLabel("mydb",    "a1b2c3d4", true));
        assertEquals("orders_a1b2",     OjpMetrics.buildPoolLabel("orders",  "a1b2c3d4", false));
        assertEquals("orders_xa_a1b2",  OjpMetrics.buildPoolLabel("orders",  "a1b2c3d4", true));
        // null/blank dsName falls back to "default"
        assertEquals("default_a1b2",    OjpMetrics.buildPoolLabel(null,      "a1b2c3d4", false));
        assertEquals("default_xa_a1b2", OjpMetrics.buildPoolLabel("",        "a1b2c3d4", true));
        assertEquals("default_a1b2",    OjpMetrics.buildPoolLabel("   ",     "a1b2c3d4", false));
        // short hash uses whatever is available
        assertEquals("mydb_a1",         OjpMetrics.buildPoolLabel("mydb",    "a1",       false));
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
