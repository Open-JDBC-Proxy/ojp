package org.openjproxy.jdbc.metrics;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.ServiceLoader;

/**
 * Selects the {@link ClientThrottleMetrics} implementation based on the
 * {@code ojp.jdbc.metrics} system property.
 *
 * <ul>
 *   <li>{@code jmx}  — default; registers a {@link JmxClientThrottleMetrics} MBean.</li>
 *   <li>{@code none} — returns {@link NoOpClientThrottleMetrics#INSTANCE}.</li>
 *   <li>Any other value — looked up via {@link ServiceLoader} for a
 *       {@link ClientThrottleMetricsProvider} whose {@link ClientThrottleMetricsProvider#name() name}
 *       matches (case-insensitive). For example, the {@code ojp-jdbc-driver-otel-metrics}
 *       adapter registers a provider named {@code "otel"}. If no matching provider is
 *       found, falls back to {@code jmx} with a single WARN log.</li>
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
            case "jmx":
                return new JmxClientThrottleMetrics(connHash, stateProvider);
            default:
                return loadProvider(binding, connHash, stateProvider);
        }
    }

    private static ClientThrottleMetrics loadProvider(String binding, String connHash,
                                                      ClientThrottleStateProvider stateProvider) {
        try {
            for (ClientThrottleMetricsProvider provider : ServiceLoader.load(ClientThrottleMetricsProvider.class)) {
                if (provider != null && binding.equalsIgnoreCase(provider.name())) {
                    ClientThrottleMetrics metrics = provider.create(connHash, stateProvider);
                    if (metrics != null) {
                        return metrics;
                    }
                }
            }
        } catch (Throwable t) {
            // ServiceLoader.iterator() can throw ServiceConfigurationError; never let metrics break JDBC.
            log.warn("ServiceLoader lookup for ojp.jdbc.metrics={} failed: {}; falling back to JMX.",
                    binding, t.getMessage());
            return new JmxClientThrottleMetrics(connHash, stateProvider);
        }
        log.warn("ojp.jdbc.metrics={} requires a matching ClientThrottleMetricsProvider on the classpath "
                + "(e.g. ojp-jdbc-driver-otel-metrics for 'otel'); falling back to JMX. See ADR-010.", binding);
        return new JmxClientThrottleMetrics(connHash, stateProvider);
    }

    private static String resolveBinding() {
        String raw = System.getProperty(PROPERTY, DEFAULT);
        if (raw == null) {
            return DEFAULT;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return DEFAULT;
        }
        return value;
    }
}
