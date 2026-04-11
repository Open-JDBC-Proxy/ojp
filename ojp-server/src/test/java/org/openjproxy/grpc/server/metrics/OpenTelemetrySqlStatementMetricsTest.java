package org.openjproxy.grpc.server.metrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OpenTelemetrySqlStatementMetrics}.
 */
class OpenTelemetrySqlStatementMetricsTest {

    private InMemoryMetricReader metricReader;
    private OpenTelemetry openTelemetry;
    private OpenTelemetrySqlStatementMetrics metrics;

    @BeforeEach
    void setUp() {
        metricReader = InMemoryMetricReader.create();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build();
        metrics = new OpenTelemetrySqlStatementMetrics(openTelemetry);
    }

    @AfterEach
    void tearDown() {
        if (metrics != null) {
            metrics.close();
        }
    }

    @Test
    void shouldRejectNullOpenTelemetry() {
        assertThrows(IllegalArgumentException.class, () -> new OpenTelemetrySqlStatementMetrics(null));
    }

    @Test
    void shouldRecordExecutionTimeMetric() {
        metrics.recordSqlExecution("abc123", 42L, false);

        Collection<MetricData> collected = metricReader.collectAllMetrics();
        assertFalse(collected.isEmpty(), "Expected at least one metric");
        assertTrue(collected.stream().anyMatch(m -> m.getName().equals("ojp.sql.execution.time")),
                "Expected ojp.sql.execution.time histogram");
    }

    @Test
    void shouldRecordTotalExecutionsMetric() {
        metrics.recordSqlExecution("abc123", 10L, false);
        metrics.recordSqlExecution("abc123", 20L, false);

        Collection<MetricData> collected = metricReader.collectAllMetrics();
        assertTrue(collected.stream().anyMatch(m -> m.getName().equals("ojp.sql.executions.total")),
                "Expected ojp.sql.executions.total counter");
    }

    @Test
    void shouldRecordSlowExecutionMetric() {
        metrics.recordSqlExecution("abc123", 500L, true);

        Collection<MetricData> collected = metricReader.collectAllMetrics();
        assertTrue(collected.stream().anyMatch(m -> m.getName().equals("ojp.sql.slow.executions.total")),
                "Expected ojp.sql.slow.executions.total counter");
    }

    @Test
    void shouldNotRecordSlowExecutionWhenNotSlow() {
        // Record a non-slow execution – the slow counter should not appear in
        // collected metrics because its value is 0 (never incremented).
        metrics.recordSqlExecution("abc123", 10L, false);

        Collection<MetricData> collected = metricReader.collectAllMetrics();
        // The counter for slow executions should either not be present or have value 0
        collected.stream()
                .filter(m -> m.getName().equals("ojp.sql.slow.executions.total"))
                .forEach(m -> {
                    long total = m.getLongSumData().getPoints().stream()
                            .mapToLong(LongPointData::getValue)
                            .sum();
                    assertEquals(0, total, "Slow executions counter should be 0 for a non-slow query");
                });
    }

    @Test
    void shouldIgnoreNullOrEmptySql() {
        // Should not throw
        assertDoesNotThrow(() -> metrics.recordSqlExecution(null, 10L, false));
        assertDoesNotThrow(() -> metrics.recordSqlExecution("", 10L, false));
    }

    @Test
    void shouldTagMetricWithSqlStatement() {
        String sql = "SELECT 1 FROM dual";
        metrics.recordSqlExecution(sql, 15L, false);

        Collection<MetricData> collected = metricReader.collectAllMetrics();
        boolean statementAttributeFound = collected.stream()
                .filter(m -> m.getName().equals("ojp.sql.execution.time"))
                .flatMap(m -> m.getHistogramData().getPoints().stream())
                .anyMatch(p -> sql.equals(p.getAttributes().get(OpenTelemetrySqlStatementMetrics.SQL_STATEMENT_KEY)));

        assertTrue(statementAttributeFound, "Expected sql.statement attribute on execution time histogram");
    }

    @Test
    void shouldCloseWithoutError() {
        assertDoesNotThrow(() -> metrics.close());
    }
}
