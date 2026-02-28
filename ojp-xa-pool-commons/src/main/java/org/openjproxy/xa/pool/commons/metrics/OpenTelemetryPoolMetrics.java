package org.openjproxy.xa.pool.commons.metrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenTelemetry-based implementation of PoolMetrics.
 * <p>
 * This implementation exposes Apache Commons Pool 2 XA connection pool metrics
 * to OpenTelemetry, following the same naming convention as HikariCP for consistency.
 * </p>
 * 
 * <h3>Core Pool Metrics (aligned with HikariCP):</h3>
 * <ul>
 *   <li><b>ojp.xa.pool.connections.active</b> - Number of active (borrowed) connections</li>
 *   <li><b>ojp.xa.pool.connections.idle</b> - Number of idle connections in pool</li>
 *   <li><b>ojp.xa.pool.connections.total</b> - Total connections (active + idle)</li>
 *   <li><b>ojp.xa.pool.connections.pending</b> - Number of threads waiting for connections</li>
 *   <li><b>ojp.xa.pool.connections.max</b> - Maximum pool size</li>
 *   <li><b>ojp.xa.pool.connections.min</b> - Minimum idle connections</li>
 * </ul>
 * 
 * <h3>XA-Specific Additional Metrics (from Apache Commons Pool 2):</h3>
 * <ul>
 *   <li><b>ojp.xa.pool.connections.created</b> - Total connections created since pool start</li>
 *   <li><b>ojp.xa.pool.connections.destroyed</b> - Total connections destroyed since pool start</li>
 *   <li><b>ojp.xa.pool.connections.exhausted</b> - Pool exhaustion events (counter)</li>
 *   <li><b>ojp.xa.pool.connections.validation.failed</b> - Validation failures (counter)</li>
 *   <li><b>ojp.xa.pool.connections.leaks.detected</b> - Leak detections (counter)</li>
 *   <li><b>ojp.xa.pool.connections.acquisition.time</b> - Connection acquisition time in ms (counter - sum)</li>
 *   <li><b>ojp.xa.pool.connections.acquisition.count</b> - Number of acquisitions tracked (counter)</li>
 * </ul>
 * 
 * <p><b>Note:</b> Pool utilization can be calculated as: <code>(active / max) * 100</code></p>
 * 
 * <p>All metrics are labeled with <code>pool.name</code> attribute for identification.</p>
 */
public class OpenTelemetryPoolMetrics implements PoolMetrics {
    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryPoolMetrics.class);
    
    private static final String METER_NAME = "ojp.xa.pool";
    private static final AttributeKey<String> POOL_NAME_KEY = AttributeKey.stringKey("pool.name");
    
    private final Meter meter;
    private final String poolName;
    private final Attributes attributes;
    
    // State tracking for observable gauges
    private final AtomicInteger currentActive = new AtomicInteger(0);
    private final AtomicInteger currentIdle = new AtomicInteger(0);
    private final AtomicInteger currentWaiters = new AtomicInteger(0);
    private final AtomicInteger currentMaxTotal = new AtomicInteger(0);
    private final AtomicInteger currentMinIdle = new AtomicInteger(0);
    private final AtomicLong currentCreated = new AtomicLong(0);
    private final AtomicLong currentDestroyed = new AtomicLong(0);
    
    // Counters
    private final LongCounter exhaustedCounter;
    private final LongCounter validationFailureCounter;
    private final LongCounter leakDetectionCounter;
    private final LongCounter acquisitionTimeCounter;
    private final LongCounter acquisitionCountCounter;
    
    /**
     * Creates OpenTelemetry metrics for the specified pool.
     *
     * @param openTelemetry the OpenTelemetry instance
     * @param poolName the name of the pool (used for labeling)
     */
    public OpenTelemetryPoolMetrics(OpenTelemetry openTelemetry, String poolName) {
        if (openTelemetry == null) {
            throw new IllegalArgumentException("openTelemetry cannot be null");
        }
        if (poolName == null || poolName.trim().isEmpty()) {
            throw new IllegalArgumentException("poolName cannot be null or empty");
        }
        
        this.poolName = poolName;
        this.meter = openTelemetry.getMeter(METER_NAME);
        this.attributes = Attributes.of(POOL_NAME_KEY, poolName);
        
        log.info("Initializing OpenTelemetry metrics for XA pool: {}", poolName);
        
        // Create core pool metrics (aligned with HikariCP naming - same suffixes)
        meter.gaugeBuilder("ojp.xa.pool.connections.active")
                .setDescription("Number of active (borrowed) connections")
                .setUnit("connections")
                .buildWithCallback(measurement -> 
                    measurement.record(currentActive.get(), attributes));
        
        meter.gaugeBuilder("ojp.xa.pool.connections.idle")
                .setDescription("Number of idle connections in pool")
                .setUnit("connections")
                .buildWithCallback(measurement -> 
                    measurement.record(currentIdle.get(), attributes));
        
        meter.gaugeBuilder("ojp.xa.pool.connections.total")
                .setDescription("Total connections (active + idle)")
                .setUnit("connections")
                .buildWithCallback(measurement -> {
                    int total = currentActive.get() + currentIdle.get();
                    measurement.record(total, attributes);
                });
        
        meter.gaugeBuilder("ojp.xa.pool.connections.pending")
                .setDescription("Number of threads waiting for connections")
                .setUnit("threads")
                .buildWithCallback(measurement -> 
                    measurement.record(currentWaiters.get(), attributes));
        
        meter.gaugeBuilder("ojp.xa.pool.connections.max")
                .setDescription("Maximum pool size")
                .setUnit("connections")
                .buildWithCallback(measurement -> 
                    measurement.record(currentMaxTotal.get(), attributes));
        
        meter.gaugeBuilder("ojp.xa.pool.connections.min")
                .setDescription("Minimum idle connections")
                .setUnit("connections")
                .buildWithCallback(measurement -> 
                    measurement.record(currentMinIdle.get(), attributes));
        
        // XA-specific additional metrics from Apache Commons Pool 2
        meter.gaugeBuilder("ojp.xa.pool.connections.created")
                .setDescription("Total connections created since pool start")
                .setUnit("connections")
                .buildWithCallback(measurement -> 
                    measurement.record(currentCreated.get(), attributes));
        
        meter.gaugeBuilder("ojp.xa.pool.connections.destroyed")
                .setDescription("Total connections destroyed since pool start")
                .setUnit("connections")
                .buildWithCallback(measurement -> 
                    measurement.record(currentDestroyed.get(), attributes));
        
        // Create counters for events
        this.exhaustedCounter = meter.counterBuilder("ojp.xa.pool.connections.exhausted")
                .setDescription("Pool exhaustion events (when borrowing timed out)")
                .setUnit("events")
                .build();
        
        this.validationFailureCounter = meter.counterBuilder("ojp.xa.pool.connections.validation.failed")
                .setDescription("Connection validation failures")
                .setUnit("failures")
                .build();
        
        this.leakDetectionCounter = meter.counterBuilder("ojp.xa.pool.connections.leaks.detected")
                .setDescription("Connection leak detections")
                .setUnit("leaks")
                .build();
        
        this.acquisitionTimeCounter = meter.counterBuilder("ojp.xa.pool.connections.acquisition.time")
                .setDescription("Total connection acquisition time in milliseconds")
                .setUnit("ms")
                .build();
        
        this.acquisitionCountCounter = meter.counterBuilder("ojp.xa.pool.connections.acquisition.count")
                .setDescription("Number of connection acquisitions tracked")
                .setUnit("acquisitions")
                .build();
        
        log.debug("OpenTelemetry metrics initialized successfully for pool: {}", poolName);
    }
    
    @Override
    public void recordPoolState(
            String poolName,
            int numActive,
            int numIdle,
            int numWaiters,
            int maxTotal,
            int minIdle,
            long createdCount,
            long destroyedCount,
            long borrowedCount,
            long returnedCount) {
        
        // Update gauge values
        currentActive.set(numActive);
        currentIdle.set(numIdle);
        currentWaiters.set(numWaiters);
        currentMaxTotal.set(maxTotal);
        currentMinIdle.set(minIdle);
        currentCreated.set(createdCount);
        currentDestroyed.set(destroyedCount);
        
        log.trace("Recorded pool state for {}: active={}, idle={}, waiters={}, max={}, min={}", 
                poolName, numActive, numIdle, numWaiters, maxTotal, minIdle);
    }
    
    @Override
    public void recordConnectionAcquisitionTime(String poolName, long durationMillis) {
        acquisitionTimeCounter.add(durationMillis, attributes);
        acquisitionCountCounter.add(1, attributes);
        log.trace("Recorded connection acquisition time for {}: {}ms", poolName, durationMillis);
    }
    
    @Override
    public void recordPoolExhaustion(String poolName) {
        exhaustedCounter.add(1, attributes);
        log.debug("Recorded pool exhaustion event for {}", poolName);
    }
    
    @Override
    public void recordValidationFailure(String poolName) {
        validationFailureCounter.add(1, attributes);
        log.debug("Recorded validation failure for {}", poolName);
    }
    
    @Override
    public void recordLeakDetection(String poolName) {
        leakDetectionCounter.add(1, attributes);
        log.debug("Recorded leak detection for {}", poolName);
    }
    
    @Override
    public void close() {
        log.info("Closing OpenTelemetry metrics for pool: {}", poolName);
        // OpenTelemetry meters are managed by the SDK, no explicit cleanup needed
    }
}
