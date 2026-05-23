package org.openjproxy.jdbc.metrics;

import lombok.extern.slf4j.Slf4j;

/**
 * Selects the {@link ClientThrottleMetrics} implementation based on the
 * {@code ojp.jdbc.metrics} system property.
 *
 * <ul>
 *   <li>{@code jmx}  — default; registers a {@link JmxClientThrottleMetrics} MBean.</li>
 *   <li>{@code none} — returns {@link NoOpClientThrottleMetrics#INSTANCE}.</li>
 *   <li>{@code otel} — reserved for the future {@code ojp-jdbc-driver-otel-metrics}
 *       adapter; if the adapter is not on the classpath, falls back to {@code jmx}
 *       with a single WARN log.</li>
 * </ul>
 *
 * <p>See ADR-010.</p>
 */
@Slf4j
public final class ClientThrottleMetricsFactory {

    /** System property name. */
    public static final String PROPERTY = "ojp.jdbc.metrics";

    /** Default binding when the property is unset or unrecognised. */
    public static final String DEFAULT = "jmx";

    private ClientThrottleMetricsFactory() {
        // utility
    }

    /**
     * Create a metrics sink for the given {@code connHash}.
     *
     * @param connHash       connection-hash identifying the throttle scope (must not be null)
     * @param stateProvider  live snapshot provider for gauge attributes (must not be null)
     */
    public static ClientThrottleMetrics create(String connHash, ClientThrottleStateProvider stateProvider) {
        if (connHash == null || connHash.isEmpty() || stateProvider == null) {
            return NoOpClientThrottleMetrics.INSTANCE;
        }
        String binding = resolveBinding();
        switch (binding) {
            case "none":
                return NoOpClientThrottleMetrics.INSTANCE;
            case "otel":
                // Phase 2 adapter is not yet implemented in driver core; fall back to JMX.
                log.warn("ojp.jdbc.metrics=otel requires the ojp-jdbc-driver-otel-metrics adapter "
                        + "on the classpath; falling back to JMX. See ADR-010.");
                return new JmxClientThrottleMetrics(connHash, stateProvider);
            case "jmx":
            default:
                return new JmxClientThrottleMetrics(connHash, stateProvider);
        }
    }

    private static String resolveBinding() {
        String raw = System.getProperty(PROPERTY, DEFAULT);
        if (raw == null) {
            return DEFAULT;
        }
        String value = raw.trim().toLowerCase();
        if (value.isEmpty()) {
            return DEFAULT;
        }
        return value;
    }
}
