package org.openjproxy.grpc.server;

import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OJP custom metrics beyond the default gRPC instrumentation.
 *
 * <p>Exposes the following metrics via Prometheus:
 * <ul>
 *   <li>{@code ojp_connection_queue_depth} – threads currently waiting to acquire a pooled connection</li>
 *   <li>{@code ojp_connection_wait_time_ms} – histogram of connection acquisition wait times (ms)</li>
 *   <li>{@code ojp_sql_execution_time_ms} – histogram of SQL execution times, labelled by {@code sql_hash}</li>
 *   <li>{@code ojp_pool_active_connections} – gauge of active connections per datasource</li>
 *   <li>{@code ojp_pool_idle_connections} – gauge of idle connections per datasource</li>
 *   <li>{@code ojp_pool_pending_threads} – gauge of threads awaiting a connection per datasource</li>
 *   <li>{@code ojp_slot_active_slow} – active slow-query execution slots</li>
 *   <li>{@code ojp_slot_active_fast} – active fast-query execution slots</li>
 * </ul>
 */
public class OjpMetrics {

    private static final Logger logger = LoggerFactory.getLogger(OjpMetrics.class);

    static final String INSTRUMENTATION_SCOPE = "ojp";

    // Attribute keys
    static final AttributeKey<String> SQL_HASH_KEY = AttributeKey.stringKey("sql_hash");
    static final AttributeKey<String> CONN_HASH_KEY = AttributeKey.stringKey("conn_hash");
    static final AttributeKey<String> OUTCOME_KEY = AttributeKey.stringKey("outcome");

    // Metric names
    static final String METRIC_CONNECTION_QUEUE_DEPTH = "ojp.connection.queue_depth";
    static final String METRIC_CONNECTION_WAIT_TIME_MS = "ojp.connection.wait_time_ms";
    static final String METRIC_SQL_EXECUTION_TIME_MS = "ojp.sql.execution_time_ms";
    static final String METRIC_POOL_ACTIVE_CONNECTIONS = "ojp.pool.active_connections";
    static final String METRIC_POOL_IDLE_CONNECTIONS = "ojp.pool.idle_connections";
    static final String METRIC_POOL_PENDING_THREADS = "ojp.pool.pending_threads";
    static final String METRIC_SLOT_ACTIVE_SLOW = "ojp.slot.active_slow";
    static final String METRIC_SLOT_ACTIVE_FAST = "ojp.slot.active_fast";

    // Instruments
    private final LongUpDownCounter connectionQueueDepth;
    private final LongHistogram connectionWaitTimeMs;
    private final LongHistogram sqlExecutionTimeMs;
    private final LongUpDownCounter slotActiveSlow;
    private final LongUpDownCounter slotActiveFast;

    // Pool gauge references – kept so that OpenTelemetry doesn't GC the callbacks
    @SuppressWarnings("unused")
    private final ObservableLongGauge poolActiveConnections;
    @SuppressWarnings("unused")
    private final ObservableLongGauge poolIdleConnections;
    @SuppressWarnings("unused")
    private final ObservableLongGauge poolPendingThreads;

    // Datasource registry for pool gauges
    private final Map<String, DataSource> datasourceRegistry = new ConcurrentHashMap<>();

    /**
     * Creates OJP metrics bound to the given {@link OpenTelemetry} instance.
     *
     * @param openTelemetry the OpenTelemetry instance (must share the same SDK as the gRPC telemetry)
     */
    public OjpMetrics(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_SCOPE);

        connectionQueueDepth = meter.upDownCounterBuilder(METRIC_CONNECTION_QUEUE_DEPTH)
                .setDescription("Number of threads currently waiting to acquire a pooled connection")
                .setUnit("{threads}")
                .build();

        connectionWaitTimeMs = meter.histogramBuilder(METRIC_CONNECTION_WAIT_TIME_MS)
                .setDescription("Time spent waiting to acquire a connection from the pool")
                .setUnit("ms")
                .ofLongs()
                .build();

        sqlExecutionTimeMs = meter.histogramBuilder(METRIC_SQL_EXECUTION_TIME_MS)
                .setDescription("SQL statement execution time, labelled by sql_hash")
                .setUnit("ms")
                .ofLongs()
                .build();

        slotActiveSlow = meter.upDownCounterBuilder(METRIC_SLOT_ACTIVE_SLOW)
                .setDescription("Number of currently active slow-query execution slots")
                .setUnit("{slots}")
                .build();

        slotActiveFast = meter.upDownCounterBuilder(METRIC_SLOT_ACTIVE_FAST)
                .setDescription("Number of currently active fast-query execution slots")
                .setUnit("{slots}")
                .build();

        // Pool gauges: read from registered HikariCP datasources on each scrape
        poolActiveConnections = meter.gaugeBuilder(METRIC_POOL_ACTIVE_CONNECTIONS)
                .setDescription("Number of active connections in the pool")
                .setUnit("{connections}")
                .ofLongs()
                .buildWithCallback(measurement -> datasourceRegistry.forEach((connHash, ds) -> {
                    if (ds instanceof HikariDataSource hikari) {
                        try {
                            measurement.record(
                                    hikari.getHikariPoolMXBean().getActiveConnections(),
                                    Attributes.of(CONN_HASH_KEY, connHash));
                        } catch (Exception e) {
                            logger.trace("Could not read active connections for {}", connHash);
                        }
                    }
                }));

        poolIdleConnections = meter.gaugeBuilder(METRIC_POOL_IDLE_CONNECTIONS)
                .setDescription("Number of idle connections in the pool")
                .setUnit("{connections}")
                .ofLongs()
                .buildWithCallback(measurement -> datasourceRegistry.forEach((connHash, ds) -> {
                    if (ds instanceof HikariDataSource hikari) {
                        try {
                            measurement.record(
                                    hikari.getHikariPoolMXBean().getIdleConnections(),
                                    Attributes.of(CONN_HASH_KEY, connHash));
                        } catch (Exception e) {
                            logger.trace("Could not read idle connections for {}", connHash);
                        }
                    }
                }));

        poolPendingThreads = meter.gaugeBuilder(METRIC_POOL_PENDING_THREADS)
                .setDescription("Number of threads awaiting a connection from the pool")
                .setUnit("{threads}")
                .ofLongs()
                .buildWithCallback(measurement -> datasourceRegistry.forEach((connHash, ds) -> {
                    if (ds instanceof HikariDataSource hikari) {
                        try {
                            measurement.record(
                                    hikari.getHikariPoolMXBean().getThreadsAwaitingConnection(),
                                    Attributes.of(CONN_HASH_KEY, connHash));
                        } catch (Exception e) {
                            logger.trace("Could not read pending threads for {}", connHash);
                        }
                    }
                }));

        logger.info("OjpMetrics initialized with {} custom metrics", 8);
    }

    // -------------------------------------------------------------------------
    // Connection metrics
    // -------------------------------------------------------------------------

    /**
     * Signals that a thread is about to wait for a connection.
     * Must be paired with a call to {@link #connectionAcquired(String, long, boolean)}.
     *
     * @param connHash connection hash for labelling
     */
    public void connectionWaitStarted(String connHash) {
        connectionQueueDepth.add(1, Attributes.of(CONN_HASH_KEY, connHash));
    }

    /**
     * Records the outcome of a connection acquisition attempt.
     *
     * @param connHash    connection hash for labelling
     * @param waitTimeMs  total time (ms) spent waiting for the connection
     * @param success     {@code true} if the connection was acquired, {@code false} on timeout/error
     */
    public void connectionAcquired(String connHash, long waitTimeMs, boolean success) {
        connectionQueueDepth.add(-1, Attributes.of(CONN_HASH_KEY, connHash));
        connectionWaitTimeMs.record(
                waitTimeMs,
                Attributes.of(CONN_HASH_KEY, connHash, OUTCOME_KEY, success ? "success" : "failure"));
    }

    // -------------------------------------------------------------------------
    // SQL execution metrics
    // -------------------------------------------------------------------------

    /**
     * Records the execution time of a SQL statement.
     *
     * @param sqlHash       the xxHash of the normalised SQL string (see {@code SqlStatementXXHash})
     * @param executionTimeMs elapsed execution time in milliseconds
     */
    public void sqlExecuted(String sqlHash, long executionTimeMs) {
        sqlExecutionTimeMs.record(executionTimeMs, Attributes.of(SQL_HASH_KEY, sqlHash));
    }

    // -------------------------------------------------------------------------
    // Slow-query slot metrics
    // -------------------------------------------------------------------------

    /** Signals that a slow-query slot has been acquired. */
    public void slowSlotAcquired() {
        slotActiveSlow.add(1);
    }

    /** Signals that a slow-query slot has been released. */
    public void slowSlotReleased() {
        slotActiveSlow.add(-1);
    }

    /** Signals that a fast-query slot has been acquired. */
    public void fastSlotAcquired() {
        slotActiveFast.add(1);
    }

    /** Signals that a fast-query slot has been released. */
    public void fastSlotReleased() {
        slotActiveFast.add(-1);
    }

    // -------------------------------------------------------------------------
    // Datasource registration for pool gauges
    // -------------------------------------------------------------------------

    /**
     * Registers a datasource so that pool gauges include its statistics.
     *
     * @param connHash   the connection hash used as Prometheus label
     * @param dataSource the datasource (non-HikariCP sources are silently ignored)
     */
    public void registerDatasource(String connHash, DataSource dataSource) {
        datasourceRegistry.put(connHash, dataSource);
        logger.debug("Registered datasource for metrics: {}", connHash);
    }

    /**
     * De-registers a previously registered datasource.
     *
     * @param connHash the connection hash to remove
     */
    public void deregisterDatasource(String connHash) {
        datasourceRegistry.remove(connHash);
        logger.debug("Deregistered datasource for metrics: {}", connHash);
    }
}
