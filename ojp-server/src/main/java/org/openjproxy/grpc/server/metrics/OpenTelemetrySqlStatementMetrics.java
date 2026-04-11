package org.openjproxy.grpc.server.metrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenTelemetry-based implementation of {@link SqlStatementMetrics}.
 *
 * <p>Exports the following metrics, all labelled with {@code sql.statement}:</p>
 * <ul>
 *   <li><b>ojp.sql.execution.time</b> – histogram of execution times (ms) per SQL statement</li>
 *   <li><b>ojp.sql.executions.total</b> – total number of executions per SQL statement</li>
 *   <li><b>ojp.sql.slow.executions.total</b> – number of slow executions per SQL statement</li>
 * </ul>
 *
 * <p>All metrics are labelled with {@code sql.statement} so that consumers can group or
 * filter by individual statement identity.</p>
 */
public class OpenTelemetrySqlStatementMetrics implements SqlStatementMetrics {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetrySqlStatementMetrics.class);

    private static final String METER_NAME = "ojp.sql";
    static final AttributeKey<String> SQL_STATEMENT_KEY = AttributeKey.stringKey("sql.statement");

    private final Meter meter;
    private final DoubleHistogram executionTimeHistogram;
    private final LongCounter executionsTotalCounter;
    private final LongCounter slowExecutionsTotalCounter;

    /**
     * Creates an {@link OpenTelemetrySqlStatementMetrics} backed by the given
     * {@link OpenTelemetry} instance.
     *
     * @param openTelemetry the OpenTelemetry instance to use; must not be {@code null}
     */
    public OpenTelemetrySqlStatementMetrics(OpenTelemetry openTelemetry) {
        if (openTelemetry == null) {
            throw new IllegalArgumentException("openTelemetry cannot be null");
        }

        this.meter = openTelemetry.getMeter(METER_NAME);

        this.executionTimeHistogram = meter.histogramBuilder("ojp.sql.execution.time")
                .setDescription("Execution time of SQL statements in milliseconds, keyed by SQL statement")
                .setUnit("ms")
                .build();

        this.executionsTotalCounter = meter.counterBuilder("ojp.sql.executions.total")
                .setDescription("Total number of SQL statement executions, keyed by SQL statement")
                .setUnit("executions")
                .build();

        this.slowExecutionsTotalCounter = meter.counterBuilder("ojp.sql.slow.executions.total")
                .setDescription("Number of SQL statement executions classified as slow, keyed by SQL statement")
                .setUnit("executions")
                .build();

        log.info("OpenTelemetry SQL statement metrics initialized");
    }

    @Override
    public void recordSqlExecution(String sql, long executionTimeMs, boolean isSlow) {
        if (sql == null || sql.isEmpty()) {
            return;
        }
        Attributes attrs = Attributes.of(SQL_STATEMENT_KEY, sql);
        executionTimeHistogram.record(executionTimeMs, attrs);
        executionsTotalCounter.add(1, attrs);
        if (isSlow) {
            slowExecutionsTotalCounter.add(1, attrs);
        }
        log.trace("Recorded SQL execution: sql={}, time={}ms, slow={}", sql, executionTimeMs, isSlow);
    }

    @Override
    public void close() {
        log.info("Closing OpenTelemetry SQL statement metrics");
        // OpenTelemetry meters are managed by the SDK; no explicit cleanup needed.
    }
}
