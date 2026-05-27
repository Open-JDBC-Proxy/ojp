package org.openjproxy.jdbc.metrics.otel;

import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.metrics.ClientThrottleMetrics;
import org.openjproxy.jdbc.metrics.ClientThrottleMetricsProvider;
import org.openjproxy.jdbc.metrics.ClientThrottleStateProvider;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryClientThrottleMetricsProviderTest {

    private static final class FixedState implements ClientThrottleStateProvider {
        @Override public String getMode() { return "OFF"; }
        @Override public int getInFlight() { return 0; }
        @Override public int getProactiveLimit() { return Integer.MAX_VALUE; }
        @Override public int getReactiveLimit() { return Integer.MAX_VALUE; }
        @Override public int getEffectiveLimit() { return Integer.MAX_VALUE; }
    }

    @Test
    void shouldExposeOtelNameAndBeDiscoverableViaServiceLoader() {
        boolean found = false;
        for (ClientThrottleMetricsProvider p : ServiceLoader.load(ClientThrottleMetricsProvider.class)) {
            if (p instanceof OpenTelemetryClientThrottleMetricsProvider) {
                assertEquals("otel", p.name());
                found = true;
            }
        }
        assertTrue(found, "OpenTelemetryClientThrottleMetricsProvider must be registered via ServiceLoader");
    }

    @Test
    void shouldReturnMetricsInstanceWhenCreated() {
        OpenTelemetryClientThrottleMetricsProvider provider = new OpenTelemetryClientThrottleMetricsProvider();
        ClientThrottleMetrics m = provider.create("h-x", new FixedState());
        assertNotNull(m);
        // Recording must not throw regardless of which concrete type was returned (real OTel or no-op fallback).
        m.recordAcquired();
        m.recordRejected();
        m.recordServerOverload();
        m.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.INCREASE);
        m.close();
    }
}
