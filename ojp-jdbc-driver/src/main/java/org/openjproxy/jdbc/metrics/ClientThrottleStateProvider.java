package org.openjproxy.jdbc.metrics;

/**
 * Read-only snapshot provider for the gauges that back
 * {@link ClientThrottleMetricsMXBean}. Decouples the metrics implementation
 * from {@code ClientThrottleManager} so the dependency arrow points one way.
 */
public interface ClientThrottleStateProvider {

    /** Configured mode label (e.g. {@code COMBINED}). */
    String getMode();

    /** Current in-flight request count. */
    int getInFlight();

    /** Current proactive limit. */
    int getProactiveLimit();

    /** Current reactive limit. */
    int getReactiveLimit();

    /** Current effective limit ({@code min(proactive, reactive)} under {@code COMBINED}). */
    int getEffectiveLimit();
}
