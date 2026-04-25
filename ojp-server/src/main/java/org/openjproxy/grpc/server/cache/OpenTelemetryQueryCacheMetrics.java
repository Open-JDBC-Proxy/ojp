package org.openjproxy.grpc.server.cache;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenTelemetry-based implementation of {@link QueryCacheMetrics}.
 *
 * <p>Exports the following metrics:</p>
 * <ul>
 *   <li><b>ojp.cache.hits</b> – total number of cache hits per datasource</li>
 *   <li><b>ojp.cache.misses</b> – total number of cache misses per datasource</li>
 *   <li><b>ojp.cache.hit_rate</b> – calculated hit rate (hits / (hits + misses))</li>
 *   <li><b>ojp.cache.evictions</b> – number of cache evictions per datasource</li>
 *   <li><b>ojp.cache.invalidations</b> – number of cache invalidations per datasource</li>
 *   <li><b>ojp.cache.rejections</b> – number of cache rejections (too large) per datasource</li>
 *   <li><b>ojp.cache.size.entries</b> – current number of cache entries per datasource</li>
 *   <li><b>ojp.cache.size.bytes</b> – current cache size in bytes per datasource</li>
 *   <li><b>ojp.query.execution.time</b> – query execution time histogram (source: cache or database)</li>
 * </ul>
 *
 * <p>All metrics are labelled with {@code datasource} to allow grouping per datasource.</p>
 */
public class OpenTelemetryQueryCacheMetrics implements QueryCacheMetrics {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryQueryCacheMetrics.class);

    private static final String METER_NAME = "ojp.cache";
    private static final AttributeKey<String> DATASOURCE_KEY = AttributeKey.stringKey("datasource");
    private static final AttributeKey<String> SQL_STATEMENT_KEY = AttributeKey.stringKey("sql.statement");
    private static final AttributeKey<String> REASON_KEY = AttributeKey.stringKey("reason");
    private static final AttributeKey<String> TABLE_KEY = AttributeKey.stringKey("table");
    private static final AttributeKey<String> SOURCE_KEY = AttributeKey.stringKey("source");

    private final Meter meter;

    // Counters
    private final LongCounter hitsCounter;
    private final LongCounter missesCounter;
    private final LongCounter evictionsCounter;
    private final LongCounter invalidationsCounter;
    private final LongCounter rejectionsCounter;

    // Histograms
    private final DoubleHistogram queryExecutionTimeHistogram;
    private final DoubleHistogram rejectionSizeHistogram;

    // Gauges - track values per datasource
    private final Map<String, AtomicLong> entryCountGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sizeBytesGauges = new ConcurrentHashMap<>();

    /**
     * Creates an {@link OpenTelemetryQueryCacheMetrics} backed by the given
     * {@link OpenTelemetry} instance.
     *
     * @param openTelemetry the OpenTelemetry instance to use; must not be {@code null}
     */
    public OpenTelemetryQueryCacheMetrics(OpenTelemetry openTelemetry) {
        if (openTelemetry == null) {
            throw new IllegalArgumentException("openTelemetry cannot be null");
        }

        this.meter = openTelemetry.getMeter(METER_NAME);

        // Initialize counters
        this.hitsCounter = meter.counterBuilder("ojp.cache.hits")
                .setDescription("Total number of cache hits per datasource")
                .setUnit("hits")
                .build();

        this.missesCounter = meter.counterBuilder("ojp.cache.misses")
                .setDescription("Total number of cache misses per datasource")
                .setUnit("misses")
                .build();

        this.evictionsCounter = meter.counterBuilder("ojp.cache.evictions")
                .setDescription("Number of cache evictions per datasource")
                .setUnit("evictions")
                .build();

        this.invalidationsCounter = meter.counterBuilder("ojp.cache.invalidations")
                .setDescription("Number of cache invalidations per datasource")
                .setUnit("invalidations")
                .build();

        this.rejectionsCounter = meter.counterBuilder("ojp.cache.rejections")
                .setDescription("Number of cache rejections (result too large) per datasource")
                .setUnit("rejections")
                .build();

        // Initialize histograms
        this.queryExecutionTimeHistogram = meter.histogramBuilder("ojp.query.execution.time")
                .setDescription("Query execution time in milliseconds (source: cache or database)")
                .setUnit("ms")
                .build();

        this.rejectionSizeHistogram = meter.histogramBuilder("ojp.cache.rejection.size")
                .setDescription("Size in bytes of rejected cache entries")
                .setUnit("bytes")
                .build();

        // Initialize observable gauges for cache size
        meter.gaugeBuilder("ojp.cache.size.entries")
                .setDescription("Current number of entries in cache per datasource")
                .setUnit("entries")
                .buildWithCallback(measurement -> {
                    entryCountGauges.forEach((datasource, count) -> {
                        measurement.record(count.get(), Attributes.of(DATASOURCE_KEY, datasource));
                    });
                });

        meter.gaugeBuilder("ojp.cache.size.bytes")
                .setDescription("Current cache size in bytes per datasource")
                .setUnit("bytes")
                .buildWithCallback(measurement -> {
                    sizeBytesGauges.forEach((datasource, size) -> {
                        measurement.record(size.get(), Attributes.of(DATASOURCE_KEY, datasource));
                    });
                });

        log.info("OpenTelemetry query cache metrics initialized");
    }

    @Override
    public void recordCacheHit(String datasourceName, String sql) {
        if (datasourceName == null || datasourceName.isEmpty()) {
            return;
        }
        Attributes attrs = Attributes.of(
                DATASOURCE_KEY, datasourceName,
                SQL_STATEMENT_KEY, truncateSql(sql)
        );
        hitsCounter.add(1, attrs);
        log.trace("Recorded cache hit: datasource={}, sql={}", datasourceName, truncateSql(sql));
    }

    @Override
    public void recordCacheMiss(String datasourceName, String sql) {
        if (datasourceName == null || datasourceName.isEmpty()) {
            return;
        }
        Attributes attrs = Attributes.of(
                DATASOURCE_KEY, datasourceName,
                SQL_STATEMENT_KEY, truncateSql(sql)
        );
        missesCounter.add(1, attrs);
        log.trace("Recorded cache miss: datasource={}, sql={}", datasourceName, truncateSql(sql));
    }

    @Override
    public void recordCacheEviction(String datasourceName, String reason) {
        if (datasourceName == null || datasourceName.isEmpty()) {
            return;
        }
        Attributes attrs = Attributes.of(
                DATASOURCE_KEY, datasourceName,
                REASON_KEY, reason
        );
        evictionsCounter.add(1, attrs);
        log.trace("Recorded cache eviction: datasource={}, reason={}", datasourceName, reason);
    }

    @Override
    public void recordCacheInvalidation(String datasourceName, String tableName) {
        if (datasourceName == null || datasourceName.isEmpty()) {
            return;
        }
        Attributes attrs = Attributes.of(
                DATASOURCE_KEY, datasourceName,
                TABLE_KEY, tableName != null ? tableName : "unknown"
        );
        invalidationsCounter.add(1, attrs);
        log.trace("Recorded cache invalidation: datasource={}, table={}", datasourceName, tableName);
    }

    @Override
    public void recordCacheRejection(String datasourceName, long sizeBytes) {
        if (datasourceName == null || datasourceName.isEmpty()) {
            return;
        }
        Attributes attrs = Attributes.of(DATASOURCE_KEY, datasourceName);
        rejectionsCounter.add(1, attrs);
        rejectionSizeHistogram.record(sizeBytes, attrs);
        log.trace("Recorded cache rejection: datasource={}, size={}bytes", datasourceName, sizeBytes);
    }

    @Override
    public void updateCacheSize(String datasourceName, long entryCount, long sizeBytes) {
        if (datasourceName == null || datasourceName.isEmpty()) {
            return;
        }

        // Update gauge values
        entryCountGauges.computeIfAbsent(datasourceName, k -> new AtomicLong(0)).set(entryCount);
        sizeBytesGauges.computeIfAbsent(datasourceName, k -> new AtomicLong(0)).set(sizeBytes);

        log.trace("Updated cache size: datasource={}, entries={}, bytes={}",
                datasourceName, entryCount, sizeBytes);
    }

    @Override
    public void recordQueryExecutionTime(String datasourceName, String source, long timeMs) {
        if (datasourceName == null || datasourceName.isEmpty()) {
            return;
        }
        Attributes attrs = Attributes.of(
                DATASOURCE_KEY, datasourceName,
                SOURCE_KEY, source
        );
        queryExecutionTimeHistogram.record(timeMs, attrs);
        log.trace("Recorded query execution: datasource={}, source={}, time={}ms",
                datasourceName, source, timeMs);
    }

    @Override
    public void close() {
        log.info("Closing OpenTelemetry query cache metrics");
        // OpenTelemetry meters are managed by the SDK; no explicit cleanup needed.
    }

    /**
     * Truncate SQL statement to avoid high cardinality in metrics.
     * Only keeps first 100 characters.
     *
     * @param sql The SQL statement
     * @return Truncated SQL (max 100 chars)
     */
    private String truncateSql(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "unknown";
        }
        return sql.length() > 100 ? sql.substring(0, 100) + "..." : sql;
    }
}
