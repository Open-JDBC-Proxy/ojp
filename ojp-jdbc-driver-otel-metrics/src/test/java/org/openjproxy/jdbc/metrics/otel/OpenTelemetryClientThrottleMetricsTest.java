package org.openjproxy.jdbc.metrics.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.metrics.ClientThrottleMetrics;
import org.openjproxy.jdbc.metrics.ClientThrottleStateProvider;

import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryClientThrottleMetricsTest {

    private InMemoryMetricReader reader;
    private OpenTelemetry openTelemetry;
    private OpenTelemetryClientThrottleMetrics metrics;

    private static final class FixedState implements ClientThrottleStateProvider {
        @Override public String getMode() { return "COMBINED"; }
        @Override public int getInFlight() { return 4; }
        @Override public int getProactiveLimit() { return 10; }
        @Override public int getReactiveLimit() { return 7; }
        @Override public int getEffectiveLimit() { return 7; }
    }

    @BeforeEach
    void setUp() {
        reader = InMemoryMetricReader.create();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        openTelemetry = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
    }

    @AfterEach
    void tearDown() {
        if (metrics != null) {
            metrics.close();
        }
    }

    private Optional<MetricData> metric(String name) {
        Collection<MetricData> all = reader.collectAllMetrics();
        return all.stream().filter(m -> m.getName().equals(name)).findFirst();
    }

    private long sumCounter(String name) {
        return metric(name)
                .map(m -> m.getLongSumData().getPoints().stream().mapToLong(p -> p.getValue()).sum())
                .orElse(0L);
    }

    private long firstGauge(String name) {
        return metric(name)
                .map(m -> m.getLongGaugeData().getPoints().iterator().next().getValue())
                .orElseThrow(() -> new AssertionError("missing gauge: " + name));
    }

    @Test
    void shouldRejectNullArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpenTelemetryClientThrottleMetrics(null, "h", new FixedState()));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenTelemetryClientThrottleMetrics(openTelemetry, null, new FixedState()));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenTelemetryClientThrottleMetrics(openTelemetry, "", new FixedState()));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenTelemetryClientThrottleMetrics(openTelemetry, "h", null));
    }

    @Test
    void shouldIncrementCountersWhenEventsRecorded() {
        metrics = new OpenTelemetryClientThrottleMetrics(openTelemetry, "h-A", new FixedState());

        metrics.recordAcquired();
        metrics.recordAcquired();
        metrics.recordAcquired();
        metrics.recordRejected();
        metrics.recordServerOverload();
        metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.INCREASE);
        metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.DECREASE);
        metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.DECREASE);

        assertEquals(3L, sumCounter("ojp.client.throttle.acquired.total"));
        assertEquals(1L, sumCounter("ojp.client.throttle.rejected.total"));
        assertEquals(1L, sumCounter("ojp.client.throttle.server.overload.total"));
        // limit.changes.total is split by direction; total across points should be 3.
        assertEquals(3L, sumCounter("ojp.client.throttle.limit.changes.total"));
    }

    @Test
    void shouldEmitGaugesFromStateProvider() {
        metrics = new OpenTelemetryClientThrottleMetrics(openTelemetry, "h-B", new FixedState());

        assertEquals(4L, firstGauge("ojp.client.throttle.inflight"));
        assertEquals(10L, firstGauge("ojp.client.throttle.limit.proactive"));
        assertEquals(7L, firstGauge("ojp.client.throttle.limit.reactive"));
        assertEquals(7L, firstGauge("ojp.client.throttle.limit.effective"));
    }

    @Test
    void shouldSaturateIntMaxValueToZeroForGauges() {
        ClientThrottleStateProvider unlimited = new ClientThrottleStateProvider() {
            @Override public String getMode() { return "OFF"; }
            @Override public int getInFlight() { return 0; }
            @Override public int getProactiveLimit() { return Integer.MAX_VALUE; }
            @Override public int getReactiveLimit() { return Integer.MAX_VALUE; }
            @Override public int getEffectiveLimit() { return Integer.MAX_VALUE; }
        };
        metrics = new OpenTelemetryClientThrottleMetrics(openTelemetry, "h-C", unlimited);

        assertEquals(0L, firstGauge("ojp.client.throttle.limit.proactive"));
        assertEquals(0L, firstGauge("ojp.client.throttle.limit.reactive"));
        assertEquals(0L, firstGauge("ojp.client.throttle.limit.effective"));
    }

    @Test
    void shouldNotThrowOnDoubleClose() {
        metrics = new OpenTelemetryClientThrottleMetrics(openTelemetry, "h-D", new FixedState());
        metrics.close();
        metrics.close();
        // After close, callbacks unregistered — gauges may or may not be present, but recording must not throw.
        metrics.recordAcquired();
        assertNotNull(metrics);
        metrics = null; // prevent tearDown re-close
    }

    @Test
    void shouldTagLimitChangesByDirection() {
        metrics = new OpenTelemetryClientThrottleMetrics(openTelemetry, "h-E", new FixedState());
        metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.INCREASE);
        metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.DECREASE);
        metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.DECREASE);

        MetricData m = metric("ojp.client.throttle.limit.changes.total")
                .orElseThrow(() -> new AssertionError("missing"));
        long increase = m.getLongSumData().getPoints().stream()
                .filter(p -> "increase".equals(p.getAttributes().get(OpenTelemetryClientThrottleMetrics.DIRECTION_KEY)))
                .mapToLong(p -> p.getValue()).sum();
        long decrease = m.getLongSumData().getPoints().stream()
                .filter(p -> "decrease".equals(p.getAttributes().get(OpenTelemetryClientThrottleMetrics.DIRECTION_KEY)))
                .mapToLong(p -> p.getValue()).sum();
        assertEquals(1L, increase);
        assertEquals(2L, decrease);
        assertTrue(m.getLongSumData().isMonotonic());
    }
}
