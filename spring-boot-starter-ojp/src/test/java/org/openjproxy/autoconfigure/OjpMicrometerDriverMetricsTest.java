package org.openjproxy.autoconfigure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OjpMicrometerDriverMetricsTest {

    private MeterRegistry registry;
    private OjpMicrometerDriverMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OjpMicrometerDriverMetrics(registry);
    }

    @Test
    void onConnectionCreated_incrementsCreatedCounterAndActiveGauge() {
        metrics.onConnectionCreated();

        Counter created = registry.find("ojp.driver.connections.created").counter();
        Gauge active = registry.find("ojp.driver.connections.active").gauge();

        assertThat(created).isNotNull();
        assertThat(created.count()).isEqualTo(1.0);
        assertThat(active).isNotNull();
        assertThat(active.value()).isEqualTo(1.0);
    }

    @Test
    void onConnectionFailed_incrementsFailedCounter() {
        metrics.onConnectionFailed();

        Counter failed = registry.find("ojp.driver.connections.failed").counter();
        assertThat(failed).isNotNull();
        assertThat(failed.count()).isEqualTo(1.0);
    }

    @Test
    void onConnectionClosed_incrementsClosedCounterAndDecrementsActiveGauge() {
        metrics.onConnectionCreated();
        metrics.onConnectionCreated();
        metrics.onConnectionClosed();

        Counter closed = registry.find("ojp.driver.connections.closed").counter();
        Gauge active = registry.find("ojp.driver.connections.active").gauge();

        assertThat(closed).isNotNull();
        assertThat(closed.count()).isEqualTo(1.0);
        assertThat(active).isNotNull();
        assertThat(active.value()).isEqualTo(1.0);
    }

    @Test
    void onStatementExecuted_incrementsExecutedCounterAndRecordsTime() {
        metrics.onStatementExecuted(42L);

        Counter executed = registry.find("ojp.driver.statements.executed").counter();
        DistributionSummary execTime = registry.find("ojp.driver.statements.execution.time").summary();

        assertThat(executed).isNotNull();
        assertThat(executed.count()).isEqualTo(1.0);
        assertThat(execTime).isNotNull();
        assertThat(execTime.count()).isEqualTo(1L);
        assertThat(execTime.totalAmount()).isEqualTo(42.0);
    }

    @Test
    void onStatementFailed_incrementsFailedCounter() {
        metrics.onStatementFailed();

        Counter failed = registry.find("ojp.driver.statements.failed").counter();
        assertThat(failed).isNotNull();
        assertThat(failed.count()).isEqualTo(1.0);
    }

    @Test
    void activeGaugeTracksMultipleConnectionsCorrectly() {
        metrics.onConnectionCreated();
        metrics.onConnectionCreated();
        metrics.onConnectionCreated();
        metrics.onConnectionClosed();

        Gauge active = registry.find("ojp.driver.connections.active").gauge();
        assertThat(active).isNotNull();
        assertThat(active.value()).isEqualTo(2.0);
    }

    @Test
    void allMetersRegisteredOnConstruction() {
        assertThat(registry.find("ojp.driver.connections.created").counter()).isNotNull();
        assertThat(registry.find("ojp.driver.connections.failed").counter()).isNotNull();
        assertThat(registry.find("ojp.driver.connections.closed").counter()).isNotNull();
        assertThat(registry.find("ojp.driver.connections.active").gauge()).isNotNull();
        assertThat(registry.find("ojp.driver.statements.executed").counter()).isNotNull();
        assertThat(registry.find("ojp.driver.statements.failed").counter()).isNotNull();
        assertThat(registry.find("ojp.driver.statements.execution.time").summary()).isNotNull();
    }
}
