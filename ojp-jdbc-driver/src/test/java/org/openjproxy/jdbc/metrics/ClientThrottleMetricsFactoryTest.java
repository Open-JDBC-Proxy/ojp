package org.openjproxy.jdbc.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientThrottleMetricsFactoryTest {

    private static final class FixedState implements ClientThrottleStateProvider {
        @Override public String getMode() { return "COMBINED"; }
        @Override public int getInFlight() { return 0; }
        @Override public int getProactiveLimit() { return Integer.MAX_VALUE; }
        @Override public int getReactiveLimit() { return Integer.MAX_VALUE; }
        @Override public int getEffectiveLimit() { return Integer.MAX_VALUE; }
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty(ClientThrottleMetricsFactory.PROPERTY);
    }

    @Test
    void shouldReturnNoOpWhenConnHashIsNull() {
        ClientThrottleMetrics m = ClientThrottleMetricsFactory.create(null, new FixedState());
        assertSame(NoOpClientThrottleMetrics.INSTANCE, m);
    }

    @Test
    void shouldReturnNoOpWhenStateProviderIsNull() {
        ClientThrottleMetrics m = ClientThrottleMetricsFactory.create("h", null);
        assertSame(NoOpClientThrottleMetrics.INSTANCE, m);
    }

    @Test
    void shouldReturnNoOpWhenPropertySetToNone() {
        System.setProperty(ClientThrottleMetricsFactory.PROPERTY, "none");
        ClientThrottleMetrics m = ClientThrottleMetricsFactory.create("h-none", new FixedState());
        assertSame(NoOpClientThrottleMetrics.INSTANCE, m);
    }

    @Test
    void shouldReturnJmxByDefault() {
        ClientThrottleMetrics m = ClientThrottleMetricsFactory.create("h-default", new FixedState());
        try {
            assertNotNull(m);
            assertTrue(m instanceof JmxClientThrottleMetrics);
        } finally {
            m.close();
        }
    }

    @Test
    void shouldFallBackToJmxWhenOtelRequestedAndAdapterMissing() {
        System.setProperty(ClientThrottleMetricsFactory.PROPERTY, "otel");
        ClientThrottleMetrics m = ClientThrottleMetricsFactory.create("h-otel-fallback", new FixedState());
        try {
            assertTrue(m instanceof JmxClientThrottleMetrics);
        } finally {
            m.close();
        }
    }
}
