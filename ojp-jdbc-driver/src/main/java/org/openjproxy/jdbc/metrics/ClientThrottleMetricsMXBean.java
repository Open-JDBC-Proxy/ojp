package org.openjproxy.jdbc.metrics;

/**
 * JMX-visible view of one connection-hash's client throttling state.
 *
 * <p>Bound under {@code org.openjproxy:type=ClientThrottle,connHash=<hash>}.</p>
 *
 * <p>Attributes are live readings (counters are monotonic; gauges reflect
 * current state at read time).</p>
 */
public interface ClientThrottleMetricsMXBean {

    /** Connection-hash identifying this throttle scope. */
    String getConnHash();

    /** Configured throttle mode (e.g. {@code OFF}, {@code PROACTIVE}, {@code REACTIVE}, {@code COMBINED}). */
    String getMode();

    /** Live in-flight count at this driver instance. */
    int getInFlight();

    /** Current proactive limit (derived from {@code SessionInfo}). */
    int getProactiveLimit();

    /** Current reactive limit (AIMD). */
    int getReactiveLimit();

    /** Effective limit actually enforced — min of proactive and reactive under {@code COMBINED}. */
    int getEffectiveLimit();

    /** Total client-side fail-fast rejections. */
    long getRejectedTotal();

    /** Total successful client-side acquisitions. */
    long getAcquiredTotal();

    /** Total server-overload notifications received (RESOURCE_EXHAUSTED). */
    long getServerOverloadEventsTotal();

    /** Total AIMD limit-increase events. */
    long getLimitIncreaseTotal();

    /** Total AIMD limit-decrease events. */
    long getLimitDecreaseTotal();
}
