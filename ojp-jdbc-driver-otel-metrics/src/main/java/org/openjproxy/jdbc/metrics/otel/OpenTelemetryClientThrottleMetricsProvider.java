package org.openjproxy.jdbc.metrics.otel;

import lombok.extern.slf4j.Slf4j;
import org.openjproxy.jdbc.metrics.ClientThrottleMetrics;
import org.openjproxy.jdbc.metrics.ClientThrottleMetricsProvider;
import org.openjproxy.jdbc.metrics.ClientThrottleStateProvider;
import org.openjproxy.jdbc.metrics.NoOpClientThrottleMetrics;

/**
 * {@link ClientThrottleMetricsProvider} that binds to {@code ojp.jdbc.metrics=otel}.
 *
 * <p>Registered via {@code META-INF/services/org.openjproxy.jdbc.metrics.ClientThrottleMetricsProvider}.
 * Constructs an {@link OpenTelemetryClientThrottleMetrics} backed by
 * {@link io.opentelemetry.api.GlobalOpenTelemetry#get()}.</p>
 *
 * <p>If the OpenTelemetry API is missing at runtime (NoClassDefFoundError) or any unexpected
 * failure occurs, returns {@link NoOpClientThrottleMetrics#INSTANCE} rather than letting the
 * driver propagate a failure — metrics must never break JDBC functionality.</p>
 */
@Slf4j
public final class OpenTelemetryClientThrottleMetricsProvider implements ClientThrottleMetricsProvider {

    @Override
    public String name() {
        return "otel";
    }

    @Override
    public ClientThrottleMetrics create(String connHash, ClientThrottleStateProvider stateProvider) {
        try {
            return new OpenTelemetryClientThrottleMetrics(connHash, stateProvider);
        } catch (NoClassDefFoundError e) {
            log.warn("OpenTelemetry API not available at runtime; falling back to no-op metrics: {}",
                    e.getMessage());
            return NoOpClientThrottleMetrics.INSTANCE;
        } catch (RuntimeException e) {
            log.warn("Failed to initialise OpenTelemetry client throttle metrics for connHash={}: {}",
                    connHash, e.getMessage());
            return NoOpClientThrottleMetrics.INSTANCE;
        }
    }
}
