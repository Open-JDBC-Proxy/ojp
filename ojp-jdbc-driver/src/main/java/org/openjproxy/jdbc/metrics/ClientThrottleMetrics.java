package org.openjproxy.jdbc.metrics;

/**
 * Driver-side throttling metrics sink.
 *
 * <p>Implementations may publish to JMX, OpenTelemetry, or do nothing.
 * Selected at runtime by {@link ClientThrottleMetricsFactory} based on the
 * {@code ojp.jdbc.metrics} system property.</p>
 *
 * <p>All methods must be safe to call from many JDBC client threads
 * concurrently and must not throw.</p>
 *
 * <p>See ADR-010 and {@code documents/analysis/THROTTLING_METRICS_ANALYSIS.md} §5.</p>
 */
public interface ClientThrottleMetrics {

    /** Record one successful client-side throttle acquisition. */
    void recordAcquired();

    /** Record one client-side fail-fast rejection (no server round-trip). */
    void recordRejected();

    /** Record a server-overload notification (RESOURCE_EXHAUSTED from the server). */
    void recordServerOverload();

    /** Record an AIMD limit change. */
    void recordLimitChange(LimitChangeDirection direction);

    /** Release any registered resources (e.g. unregister MBeans). Must be idempotent. */
    void close();

    /** Direction tag for {@link #recordLimitChange(LimitChangeDirection)}. */
    enum LimitChangeDirection {
        INCREASE,
        DECREASE
    }
}
