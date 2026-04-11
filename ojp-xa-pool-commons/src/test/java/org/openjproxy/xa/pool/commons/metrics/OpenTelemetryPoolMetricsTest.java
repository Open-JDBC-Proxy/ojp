package org.openjproxy.xa.pool.commons.metrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenTelemetryPoolMetrics.
 */
class OpenTelemetryPoolMetricsTest {
    
    private InMemoryMetricReader metricReader;
    private OpenTelemetry openTelemetry;
    private OpenTelemetryPoolMetrics metrics;
    
    @BeforeEach
    void setUp() {
        // Create an in-memory metric reader for testing
        metricReader = InMemoryMetricReader.create();
        
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build();
        
        openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build();
        
        metrics = new OpenTelemetryPoolMetrics(openTelemetry, "test-pool");
    }
    
    @AfterEach
    void tearDown() {
        if (metrics != null) {
            metrics.close();
        }
    }
    
    @Test
    void testRecordPoolState() {
        // Record pool state
        metrics.recordPoolState(
                "test-pool",
                5,  // active
                3,  // idle
                2,  // waiters
                10, // max
                1,  // min
                100L, // created
                50L,  // destroyed
                500L, // borrowed
                450L  // returned
        );
        
        // Collect metrics
        Collection<MetricData> metricDataCollection = metricReader.collectAllMetrics();
        
        // Verify metrics are recorded
        assertFalse(metricDataCollection.isEmpty(), "Metrics should be collected");
        
        // Find and verify specific metrics
        boolean foundActiveMetric = metricDataCollection.stream()
                .anyMatch(md -> md.getName().equals("ojp.xa.pool.connections.active"));
        assertTrue(foundActiveMetric, "Should have active connections metric");
        
        boolean foundIdleMetric = metricDataCollection.stream()
                .anyMatch(md -> md.getName().equals("ojp.xa.pool.connections.idle"));
        assertTrue(foundIdleMetric, "Should have idle connections metric");

    }
    
    @Test
    void testRecordConnectionAcquisitionTime() {
        // Record acquisition time
        metrics.recordConnectionAcquisitionTime("test-pool", 150L);
        
        // Collect metrics
        Collection<MetricData> metricDataCollection = metricReader.collectAllMetrics();
        
        // Verify acquisition time histogram is recorded (replaces the former counters)
        boolean foundAcquisitionTime = metricDataCollection.stream()
                .anyMatch(md -> md.getName().equals("ojp.xa.pool.connections.acquisition.time"));
        assertTrue(foundAcquisitionTime, "Should have acquisition time histogram metric");
    }
    
    @Test
    void testRecordPoolExhaustion() {
        // Record exhaustion event
        metrics.recordPoolExhaustion("test-pool");
        
        // Collect metrics
        Collection<MetricData> metricDataCollection = metricReader.collectAllMetrics();
        
        // Verify exhaustion metric is recorded
        boolean foundExhaustedMetric = metricDataCollection.stream()
                .anyMatch(md -> md.getName().equals("ojp.xa.pool.connections.exhausted"));
        assertTrue(foundExhaustedMetric, "Should have exhaustion metric");
    }
    
    @Test
    void testRecordValidationFailure() {
        // Record validation failure
        metrics.recordValidationFailure("test-pool");
        
        // Collect metrics
        Collection<MetricData> metricDataCollection = metricReader.collectAllMetrics();
        
        // Verify validation failure metric is recorded
        boolean foundValidationFailure = metricDataCollection.stream()
                .anyMatch(md -> md.getName().equals("ojp.xa.pool.connections.validation.failed"));
        assertTrue(foundValidationFailure, "Should have validation failure metric");
    }
    
    @Test
    void testRecordLeakDetection() {
        // Record leak detection
        metrics.recordLeakDetection("test-pool");
        
        // Collect metrics
        Collection<MetricData> metricDataCollection = metricReader.collectAllMetrics();
        
        // Verify leak detection metric is recorded
        boolean foundLeakDetection = metricDataCollection.stream()
                .anyMatch(md -> md.getName().equals("ojp.xa.pool.connections.leaks.detected"));
        assertTrue(foundLeakDetection, "Should have leak detection metric");
    }
    
    @Test
    void testConstructorWithNullOpenTelemetry() {
        assertThrows(IllegalArgumentException.class, () -> {
            new OpenTelemetryPoolMetrics(null, "test-pool");
        });
    }
    
    @Test
    void testConstructorWithNullPoolName() {
        assertThrows(IllegalArgumentException.class, () -> {
            new OpenTelemetryPoolMetrics(openTelemetry, null);
        });
    }
    
    @Test
    void testConstructorWithEmptyPoolName() {
        assertThrows(IllegalArgumentException.class, () -> {
            new OpenTelemetryPoolMetrics(openTelemetry, "");
        });
    }
}
