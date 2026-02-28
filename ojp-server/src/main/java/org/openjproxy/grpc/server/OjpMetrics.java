package org.openjproxy.grpc.server;

import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import org.openjproxy.xa.pool.commons.CommonsPool2XADataSource;
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
 *   <li>{@code ojp_sql_execution_time_ms} – histogram of SQL execution times, labelled by {@code sql} (truncated SQL text)</li>
 *   <li>{@code ojp_pool_active_connections} – gauge of active connections per datasource (HikariCP)</li>
 *   <li>{@code ojp_pool_idle_connections} – gauge of idle connections per datasource (HikariCP)</li>
 *   <li>{@code ojp_pool_pending_threads} – gauge of threads awaiting a connection per datasource (HikariCP)</li>
 *   <li>{@code ojp_xa_pool_active_sessions} – gauge of active XA sessions per datasource</li>
 *   <li>{@code ojp_xa_pool_idle_sessions} – gauge of idle XA sessions per datasource</li>
 *   <li>{@code ojp_xa_pool_pending_threads} – gauge of threads awaiting an XA session per datasource</li>
 *   <li>{@code ojp_slot_active_slow} – active slow-query execution slots</li>
 *   <li>{@code ojp_slot_active_fast} – active fast-query execution slots</li>
 * </ul>
 */
public class OjpMetrics {

    private static final Logger logger = LoggerFactory.getLogger(OjpMetrics.class);

    /** Pre-compiled pattern for collapsing whitespace runs used by {@link #toSqlSnippet}. */
    private static final java.util.regex.Pattern WHITESPACE_PATTERN = java.util.regex.Pattern.compile("\\s+");

    static final String INSTRUMENTATION_SCOPE = "ojp";

    // Attribute keys
    static final AttributeKey<String> SQL_KEY = AttributeKey.stringKey("sql");
    static final AttributeKey<String> CONN_HASH_KEY = AttributeKey.stringKey("conn_hash");
    static final AttributeKey<String> OUTCOME_KEY = AttributeKey.stringKey("outcome");

    /** Maximum length of the {@code sql} label value to keep Prometheus cardinality bounded. */
    static final int SQL_SNIPPET_MAX_LENGTH = 100;

    // Metric names
    static final String METRIC_CONNECTION_QUEUE_DEPTH = "ojp.connection.queue_depth";
    static final String METRIC_CONNECTION_WAIT_TIME_MS = "ojp.connection.wait_time_ms";
    static final String METRIC_SQL_EXECUTION_TIME_MS = "ojp.sql.execution_time_ms";
    static final String METRIC_POOL_ACTIVE_CONNECTIONS = "ojp.pool.active_connections";
    static final String METRIC_POOL_IDLE_CONNECTIONS = "ojp.pool.idle_connections";
    static final String METRIC_POOL_PENDING_THREADS = "ojp.pool.pending_threads";
    static final String METRIC_SLOT_ACTIVE_SLOW = "ojp.slot.active_slow";
    static final String METRIC_SLOT_ACTIVE_FAST = "ojp.slot.active_fast";
    static final String METRIC_XA_POOL_ACTIVE_SESSIONS = "ojp.xa_pool.active_sessions";
    static final String METRIC_XA_POOL_IDLE_SESSIONS = "ojp.xa_pool.idle_sessions";
    static final String METRIC_XA_POOL_PENDING_THREADS = "ojp.xa_pool.pending_threads";

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

    // XA pool gauge references
    @SuppressWarnings("unused")
    private final ObservableLongGauge xaPoolActiveSessions;
    @SuppressWarnings("unused")
    private final ObservableLongGauge xaPoolIdleSessions;
    @SuppressWarnings("unused")
    private final ObservableLongGauge xaPoolPendingThreads;

    // Datasource registry for HikariCP pool gauges
    private final Map<String, DataSource> datasourceRegistry = new ConcurrentHashMap<>();

    // XA pool datasource registry (CommonsPool2XADataSource instances)
    private final Map<String, CommonsPool2XADataSource> xaPoolRegistry = new ConcurrentHashMap<>();

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
                .setDescription("SQL statement execution time, labelled by sql (truncated SQL text)")
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

        // XA pool gauges: read from registered CommonsPool2XADataSource instances on each scrape
        xaPoolActiveSessions = meter.gaugeBuilder(METRIC_XA_POOL_ACTIVE_SESSIONS)
                .setDescription("Number of active XA sessions in the pool")
                .setUnit("{sessions}")
                .ofLongs()
                .buildWithCallback(measurement -> xaPoolRegistry.forEach((connHash, xaPool) -> {
                    try {
                        measurement.record(xaPool.getNumActive(), Attributes.of(CONN_HASH_KEY, connHash));
                    } catch (Exception e) {
                        logger.trace("Could not read active XA sessions for {}", connHash);
                    }
                }));

        xaPoolIdleSessions = meter.gaugeBuilder(METRIC_XA_POOL_IDLE_SESSIONS)
                .setDescription("Number of idle XA sessions in the pool")
                .setUnit("{sessions}")
                .ofLongs()
                .buildWithCallback(measurement -> xaPoolRegistry.forEach((connHash, xaPool) -> {
                    try {
                        measurement.record(xaPool.getNumIdle(), Attributes.of(CONN_HASH_KEY, connHash));
                    } catch (Exception e) {
                        logger.trace("Could not read idle XA sessions for {}", connHash);
                    }
                }));

        xaPoolPendingThreads = meter.gaugeBuilder(METRIC_XA_POOL_PENDING_THREADS)
                .setDescription("Number of threads awaiting an XA session from the pool")
                .setUnit("{threads}")
                .ofLongs()
                .buildWithCallback(measurement -> xaPoolRegistry.forEach((connHash, xaPool) -> {
                    try {
                        measurement.record(xaPool.getNumWaiters(), Attributes.of(CONN_HASH_KEY, connHash));
                    } catch (Exception e) {
                        logger.trace("Could not read pending threads for XA pool {}", connHash);
                    }
                }));

        logger.info("OjpMetrics initialized with all custom metrics");
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
     * <p>The {@code sql} Prometheus label is set to the first {@value #SQL_SNIPPET_MAX_LENGTH}
     * characters of the normalised (lower-cased, whitespace-collapsed) SQL text, giving
     * human-readable metric labels while bounding Prometheus cardinality.
     *
     * @param sql             the original SQL string (will be normalised and truncated internally)
     * @param executionTimeMs elapsed execution time in milliseconds
     */
    public void sqlExecuted(String sql, long executionTimeMs) {
        sqlExecutionTimeMs.record(executionTimeMs, Attributes.of(SQL_KEY, toSqlSnippet(sql)));
    }

    /**
     * Normalises and truncates a SQL string to produce a bounded Prometheus label value.
     *
     * <p>The result is lower-cased and has all runs of whitespace collapsed to a single space,
     * then truncated to {@value #SQL_SNIPPET_MAX_LENGTH} characters.
     *
     * @param sql raw SQL text; {@code null} is treated as an empty string
     * @return normalised, truncated SQL snippet
     */
    static String toSqlSnippet(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        String normalised = WHITESPACE_PATTERN.matcher(sql.trim()).replaceAll(" ").toLowerCase();
        return normalised.length() <= SQL_SNIPPET_MAX_LENGTH
                ? normalised
                : normalised.substring(0, SQL_SNIPPET_MAX_LENGTH);
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

    // -------------------------------------------------------------------------
    // XA pool registration for pool gauges
    // -------------------------------------------------------------------------

    /**
     * Registers an XA pool datasource so that XA pool gauges include its statistics.
     *
     * @param connHash       the connection hash used as Prometheus label
     * @param xaPoolDataSource the pooled XA datasource (non-CommonsPool2 sources are silently ignored)
     */
    public void registerXaPool(String connHash, Object xaPoolDataSource) {
        if (xaPoolDataSource instanceof CommonsPool2XADataSource pool) {
            xaPoolRegistry.put(connHash, pool);
            logger.debug("Registered XA pool for metrics: {}", connHash);
        } else {
            logger.debug("XA pool datasource for {} is not a CommonsPool2XADataSource, skipping registration", connHash);
        }
    }

    /**
     * De-registers a previously registered XA pool datasource.
     *
     * @param connHash the connection hash to remove
     */
    public void deregisterXaPool(String connHash) {
        xaPoolRegistry.remove(connHash);
        logger.debug("Deregistered XA pool for metrics: {}", connHash);
    }
}
