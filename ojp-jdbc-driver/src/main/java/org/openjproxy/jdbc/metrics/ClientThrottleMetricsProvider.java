package org.openjproxy.jdbc.metrics;

/**
 * SPI for pluggable {@link ClientThrottleMetrics} implementations.
 *
 * <p>Implementations are discovered by {@link ClientThrottleMetricsFactory}
 * via {@link java.util.ServiceLoader} when the driver is configured with
 * {@code -Dojp.jdbc.metrics=otel} (or any future non-built-in binding name).</p>
 *
 * <p>An adapter module — for example {@code ojp-jdbc-driver-otel-metrics} —
 * registers an implementation under
 * {@code META-INF/services/org.openjproxy.jdbc.metrics.ClientThrottleMetricsProvider}.</p>
 *
 * <p>See ADR-010.</p>
 */
public interface ClientThrottleMetricsProvider {

    /**
     * The binding name this provider serves (matches the {@code ojp.jdbc.metrics}
     * system-property value, e.g. {@code "otel"}). Must not be {@code null} or empty.
     */
    String name();

    /**
     * Create a metrics sink for the given {@code connHash}.
     * Must not throw; implementations should log and return a no-op when in doubt.
     */
    ClientThrottleMetrics create(String connHash, ClientThrottleStateProvider stateProvider);
}
