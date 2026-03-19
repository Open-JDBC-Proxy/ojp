package org.openjproxy.autoconfigure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.openjproxy.jdbc.OjpDriverMetrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer-based implementation of {@link OjpDriverMetrics}.
 *
 * <p>Registers the following meters with the provided {@link MeterRegistry}:</p>
 * <ul>
 *   <li><b>ojp.driver.connections.created</b> – counter: total JDBC connections successfully opened</li>
 *   <li><b>ojp.driver.connections.failed</b> – counter: total JDBC connection attempts that failed</li>
 *   <li><b>ojp.driver.connections.closed</b> – counter: total JDBC connections closed</li>
 *   <li><b>ojp.driver.connections.active</b> – gauge: current number of open JDBC connections</li>
 *   <li><b>ojp.driver.statements.executed</b> – counter: total SQL statements executed successfully</li>
 *   <li><b>ojp.driver.statements.failed</b> – counter: total SQL statement executions that failed</li>
 *   <li><b>ojp.driver.statements.execution.time</b> – distribution summary: client-side
 *       round-trip time of SQL statement executions in milliseconds</li>
 * </ul>
 *
 * <p>An instance of this class is created and registered with {@link org.openjproxy.jdbc.OjpDriverMetricsHolder}
 * by {@link OjpMicrometerAutoConfiguration} when both the OJP driver and a
 * {@link MeterRegistry} bean are present on the classpath.</p>
 */
public class OjpMicrometerDriverMetrics implements OjpDriverMetrics {

    private final Counter connectionsCreated;
    private final Counter connectionsFailed;
    private final Counter connectionsClosed;
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final Counter statementsExecuted;
    private final Counter statementsFailed;
    private final DistributionSummary statementExecutionTime;

    /**
     * Creates a new {@link OjpMicrometerDriverMetrics} and registers all meters with the
     * given {@link MeterRegistry}.
     *
     * @param registry the Micrometer registry to register meters with; must not be {@code null}
     */
    public OjpMicrometerDriverMetrics(MeterRegistry registry) {
        this.connectionsCreated = Counter.builder("ojp.driver.connections.created")
                .description("Total number of OJP JDBC connections successfully opened")
                .register(registry);

        this.connectionsFailed = Counter.builder("ojp.driver.connections.failed")
                .description("Total number of OJP JDBC connection attempts that failed")
                .register(registry);

        this.connectionsClosed = Counter.builder("ojp.driver.connections.closed")
                .description("Total number of OJP JDBC connections closed")
                .register(registry);

        Gauge.builder("ojp.driver.connections.active", activeConnections, AtomicLong::doubleValue)
                .description("Current number of open OJP JDBC connections")
                .register(registry);

        this.statementsExecuted = Counter.builder("ojp.driver.statements.executed")
                .description("Total number of OJP SQL statements executed successfully")
                .register(registry);

        this.statementsFailed = Counter.builder("ojp.driver.statements.failed")
                .description("Total number of OJP SQL statement executions that failed")
                .register(registry);

        this.statementExecutionTime = DistributionSummary.builder("ojp.driver.statements.execution.time")
                .description("Client-side round-trip execution time of OJP SQL statements in milliseconds")
                .baseUnit("ms")
                .register(registry);
    }

    @Override
    public void onConnectionCreated() {
        connectionsCreated.increment();
        activeConnections.incrementAndGet();
    }

    @Override
    public void onConnectionFailed() {
        connectionsFailed.increment();
    }

    @Override
    public void onConnectionClosed() {
        connectionsClosed.increment();
        activeConnections.decrementAndGet();
    }

    @Override
    public void onStatementExecuted(long durationMs) {
        statementsExecuted.increment();
        statementExecutionTime.record(durationMs);
    }

    @Override
    public void onStatementFailed() {
        statementsFailed.increment();
    }
}
