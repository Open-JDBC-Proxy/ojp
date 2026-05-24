package org.openjproxy.jdbc.metrics.otel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.metrics.ClientThrottleMetrics;
import org.openjproxy.jdbc.metrics.ClientThrottleMetricsFactory;
import org.openjproxy.jdbc.metrics.ClientThrottleStateProvider;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that with this adapter on the classpath, the driver-core factory
 * picks the {@link OpenTelemetryClientThrottleMetrics} when
 * {@code -Dojp.jdbc.metrics=otel} is set.
 */
class FactorySelectsOtelWhenAdapterPresentTest {

    private static final class FixedState implements ClientThrottleStateProvider {
        @Override public String getMode() { return "OFF"; }
        @Override public int getInFlight() { return 0; }
        @Override public int getProactiveLimit() { return Integer.MAX_VALUE; }
        @Override public int getReactiveLimit() { return Integer.MAX_VALUE; }
        @Override public int getEffectiveLimit() { return Integer.MAX_VALUE; }
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ClientThrottleMetricsFactory.PROPERTY);
    }

    @Test
    void shouldReturnOtelMetricsWhenPropertyIsOtelAndAdapterOnClasspath() {
        System.setProperty(ClientThrottleMetricsFactory.PROPERTY, "otel");
        ClientThrottleMetrics m = ClientThrottleMetricsFactory.create("h-factory", new FixedState());
        try {
            assertNotNull(m);
            assertTrue(m instanceof OpenTelemetryClientThrottleMetrics,
                    "Expected OpenTelemetryClientThrottleMetrics, got " + m.getClass().getName());
        } finally {
            m.close();
        }
    }
}
