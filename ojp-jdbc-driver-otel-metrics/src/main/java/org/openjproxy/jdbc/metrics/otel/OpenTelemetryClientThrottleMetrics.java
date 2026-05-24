package org.openjproxy.jdbc.metrics.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.jdbc.metrics.ClientThrottleMetrics;
import org.openjproxy.jdbc.metrics.ClientThrottleStateProvider;

/**
 * OpenTelemetry-backed {@link ClientThrottleMetrics}.
 *
 * <p>Publishes the following instruments under meter scope
 * {@code ojp.client.throttle}, all carrying the attribute
 * {@code ojp.connection.hash}:</p>
 *
 * <ul>
 *   <li><b>ojp.client.throttle.inflight</b> (gauge, requests) — current in-flight requests.</li>
 *   <li><b>ojp.client.throttle.limit.proactive</b> (gauge, requests) — current proactive limit.</li>
 *   <li><b>ojp.client.throttle.limit.reactive</b> (gauge, requests) — current reactive limit.</li>
 *   <li><b>ojp.client.throttle.limit.effective</b> (gauge, requests) — current effective limit.</li>
 *   <li><b>ojp.client.throttle.acquired.total</b> (counter, requests) — successful acquisitions.</li>
 *   <li><b>ojp.client.throttle.rejected.total</b> (counter, requests) — fail-fast rejections.</li>
 *   <li><b>ojp.client.throttle.server.overload.total</b> (counter, events) — RESOURCE_EXHAUSTED notifications.</li>
 *   <li><b>ojp.client.throttle.limit.changes.total</b> (counter, events; tagged
 *       {@code direction=increase|decrease}) — AIMD limit changes.</li>
 * </ul>
 *
 * <p>Selected by {@code -Dojp.jdbc.metrics=otel} when this adapter is on the
 * classpath. See ADR-010.</p>
 */
@Slf4j
public final class OpenTelemetryClientThrottleMetrics implements ClientThrottleMetrics {

    static final String METER_NAME = "ojp.client.throttle";
    static final AttributeKey<String> CONN_HASH_KEY = AttributeKey.stringKey("ojp.connection.hash");
    static final AttributeKey<String> DIRECTION_KEY = AttributeKey.stringKey("direction");

    private final Attributes baseAttrs;
    private final Attributes increaseAttrs;
    private final Attributes decreaseAttrs;

    private final LongCounter acquiredCounter;
    private final LongCounter rejectedCounter;
    private final LongCounter serverOverloadCounter;
    private final LongCounter limitChangeCounter;

    private final ObservableLongGauge inFlightGauge;
    private final ObservableLongGauge proactiveLimitGauge;
    private final ObservableLongGauge reactiveLimitGauge;
    private final ObservableLongGauge effectiveLimitGauge;

    private volatile boolean closed;

    public OpenTelemetryClientThrottleMetrics(String connHash, ClientThrottleStateProvider stateProvider) {
        this(GlobalOpenTelemetry.get(), connHash, stateProvider);
    }

    public OpenTelemetryClientThrottleMetrics(OpenTelemetry openTelemetry, String connHash,
                                              ClientThrottleStateProvider stateProvider) {
        if (openTelemetry == null) {
            throw new IllegalArgumentException("openTelemetry must not be null");
        }
        if (connHash == null || connHash.isEmpty()) {
            throw new IllegalArgumentException("connHash must not be null or empty");
        }
        if (stateProvider == null) {
            throw new IllegalArgumentException("stateProvider must not be null");
        }

        this.baseAttrs = Attributes.of(CONN_HASH_KEY, connHash);
        this.increaseAttrs = Attributes.of(CONN_HASH_KEY, connHash, DIRECTION_KEY, "increase");
        this.decreaseAttrs = Attributes.of(CONN_HASH_KEY, connHash, DIRECTION_KEY, "decrease");

        Meter meter = openTelemetry.getMeter(METER_NAME);

        this.acquiredCounter = meter.counterBuilder("ojp.client.throttle.acquired.total")
                .setDescription("Successful client-side throttle acquisitions.")
                .setUnit("requests")
                .build();
        this.rejectedCounter = meter.counterBuilder("ojp.client.throttle.rejected.total")
                .setDescription("Client-side fail-fast rejections.")
                .setUnit("requests")
                .build();
        this.serverOverloadCounter = meter.counterBuilder("ojp.client.throttle.server.overload.total")
                .setDescription("Server-overload notifications (RESOURCE_EXHAUSTED) received from OJP server.")
                .setUnit("events")
                .build();
        this.limitChangeCounter = meter.counterBuilder("ojp.client.throttle.limit.changes.total")
                .setDescription("AIMD limit changes, tagged by direction (increase|decrease).")
                .setUnit("events")
                .build();

        // Capture state provider in async callbacks; the manager (and so the provider) lives
        // for the JVM lifetime, mirroring ClientThrottleManager in driver core.
        this.inFlightGauge = meter.gaugeBuilder("ojp.client.throttle.inflight")
                .ofLongs()
                .setDescription("Current in-flight client-side throttled requests.")
                .setUnit("requests")
                .buildWithCallback(obs -> obs.record(stateProvider.getInFlight(), baseAttrs));
        this.proactiveLimitGauge = meter.gaugeBuilder("ojp.client.throttle.limit.proactive")
                .ofLongs()
                .setDescription("Current proactive limit (derived from server SessionInfo).")
                .setUnit("requests")
                .buildWithCallback(obs -> obs.record(saturate(stateProvider.getProactiveLimit()), baseAttrs));
        this.reactiveLimitGauge = meter.gaugeBuilder("ojp.client.throttle.limit.reactive")
                .ofLongs()
                .setDescription("Current reactive limit (AIMD on server overload).")
                .setUnit("requests")
                .buildWithCallback(obs -> obs.record(saturate(stateProvider.getReactiveLimit()), baseAttrs));
        this.effectiveLimitGauge = meter.gaugeBuilder("ojp.client.throttle.limit.effective")
                .ofLongs()
                .setDescription("Current effective limit (min of proactive and reactive in COMBINED mode).")
                .setUnit("requests")
                .buildWithCallback(obs -> obs.record(saturate(stateProvider.getEffectiveLimit()), baseAttrs));

        log.debug("OpenTelemetry client throttle metrics initialised for connHash={}", connHash);
    }

    /**
     * Driver represents "no limit" as {@link Integer#MAX_VALUE}; emit 0 for the gauge so dashboards
     * do not show a 2-billion bar before SessionInfo arrives.
     */
    private static long saturate(int value) {
        return value == Integer.MAX_VALUE ? 0L : value;
    }

    @Override
    public void recordAcquired() {
        acquiredCounter.add(1, baseAttrs);
    }

    @Override
    public void recordRejected() {
        rejectedCounter.add(1, baseAttrs);
    }

    @Override
    public void recordServerOverload() {
        serverOverloadCounter.add(1, baseAttrs);
    }

    @Override
    public void recordLimitChange(LimitChangeDirection direction) {
        limitChangeCounter.add(1, direction == LimitChangeDirection.INCREASE ? increaseAttrs : decreaseAttrs);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Best-effort: unregister callbacks so a closed manager stops contributing gauge values.
        closeQuietly(inFlightGauge);
        closeQuietly(proactiveLimitGauge);
        closeQuietly(reactiveLimitGauge);
        closeQuietly(effectiveLimitGauge);
    }

    private static void closeQuietly(ObservableLongGauge gauge) {
        if (gauge == null) {
            return;
        }
        try {
            gauge.close();
        } catch (Exception e) {
            log.debug("Closing observable gauge failed: {}", e.getMessage());
        }
    }
}
