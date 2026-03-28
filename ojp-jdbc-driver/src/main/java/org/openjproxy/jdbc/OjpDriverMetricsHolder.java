package org.openjproxy.jdbc;

/**
 * Global holder for the active {@link OjpDriverMetrics} implementation.
 *
 * <p>The OJP JDBC driver uses this holder as a lightweight service-locator to decouple
 * the driver's hot paths from any specific metrics library. By default the holder contains
 * {@link NoOpOjpDriverMetrics#INSTANCE}, so the driver incurs no overhead when no metrics
 * integration has been registered.</p>
 *
 * <p>To activate metrics, integrations such as the {@code spring-boot-starter-ojp} Micrometer
 * auto-configuration call {@link #set(OjpDriverMetrics)} early in the application lifecycle,
 * before the first JDBC connection is created. Example:</p>
 *
 * <pre>
 *   OjpDriverMetricsHolder.set(new OjpMicrometerDriverMetrics(meterRegistry));
 * </pre>
 *
 * <p>All methods are thread-safe. The {@code volatile} field ensures that a newly registered
 * implementation is immediately visible to all threads.</p>
 */
public final class OjpDriverMetricsHolder {

    private static volatile OjpDriverMetrics instance = NoOpOjpDriverMetrics.INSTANCE;

    private OjpDriverMetricsHolder() {
    }

    /**
     * Returns the currently active {@link OjpDriverMetrics} implementation.
     * Never {@code null}.
     *
     * @return the active metrics instance
     */
    public static OjpDriverMetrics get() {
        return instance;
    }

    /**
     * Replaces the active {@link OjpDriverMetrics} implementation.
     *
     * @param metrics the new implementation; must not be {@code null}
     * @throws IllegalArgumentException if {@code metrics} is {@code null}
     */
    public static void set(OjpDriverMetrics metrics) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        instance = metrics;
    }

    /**
     * Resets the holder to the default {@link NoOpOjpDriverMetrics} instance.
     * Primarily intended for use in tests.
     */
    public static void reset() {
        instance = NoOpOjpDriverMetrics.INSTANCE;
    }
}
