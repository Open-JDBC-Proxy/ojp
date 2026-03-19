package org.openjproxy.autoconfigure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.OjpDriverMetrics;
import org.openjproxy.jdbc.OjpDriverMetricsHolder;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OjpMicrometerAutoConfigurationTest {

    private static final String OJP_URL = "spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OjpAutoConfiguration.class,
                    OjpMicrometerAutoConfiguration.class));

    @AfterEach
    void resetMetricsHolder() {
        OjpDriverMetricsHolder.reset();
    }

    @Test
    void shouldRegisterMicrometerMetricsBeanWhenMeterRegistryIsPresent() {
        contextRunner
                .withPropertyValues(OJP_URL)
                .withUserConfiguration(SimpleMeterRegistryConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(OjpMicrometerDriverMetrics.class);
                    assertThat(OjpDriverMetricsHolder.get()).isInstanceOf(OjpMicrometerDriverMetrics.class);
                });
    }

    @Test
    void shouldNotRegisterMicrometerMetricsBeanWhenNoMeterRegistryPresent() {
        contextRunner
                .withPropertyValues(OJP_URL)
                .run(context -> assertThat(context).doesNotHaveBean(OjpMicrometerDriverMetrics.class));
    }

    @Test
    void shouldNotRegisterMicrometerMetricsBeanWhenNoDatasourceUrlConfigured() {
        contextRunner
                .withUserConfiguration(SimpleMeterRegistryConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(OjpMicrometerDriverMetrics.class));
    }

    @Test
    void shouldRegisterConnectionsCreatedCounter() {
        contextRunner
                .withPropertyValues(OJP_URL)
                .withUserConfiguration(SimpleMeterRegistryConfiguration.class)
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("ojp.driver.connections.created").counter()).isNotNull();
                });
    }

    @Test
    void shouldRegisterConnectionsFailedCounter() {
        contextRunner
                .withPropertyValues(OJP_URL)
                .withUserConfiguration(SimpleMeterRegistryConfiguration.class)
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("ojp.driver.connections.failed").counter()).isNotNull();
                });
    }

    @Test
    void shouldRegisterConnectionsClosedCounter() {
        contextRunner
                .withPropertyValues(OJP_URL)
                .withUserConfiguration(SimpleMeterRegistryConfiguration.class)
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("ojp.driver.connections.closed").counter()).isNotNull();
                });
    }

    @Test
    void shouldRegisterActiveConnectionsGauge() {
        contextRunner
                .withPropertyValues(OJP_URL)
                .withUserConfiguration(SimpleMeterRegistryConfiguration.class)
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("ojp.driver.connections.active").gauge()).isNotNull();
                });
    }

    @Test
    void shouldRegisterStatementsExecutedCounter() {
        contextRunner
                .withPropertyValues(OJP_URL)
                .withUserConfiguration(SimpleMeterRegistryConfiguration.class)
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("ojp.driver.statements.executed").counter()).isNotNull();
                });
    }

    @Test
    void shouldRegisterStatementsFailedCounter() {
        contextRunner
                .withPropertyValues(OJP_URL)
                .withUserConfiguration(SimpleMeterRegistryConfiguration.class)
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("ojp.driver.statements.failed").counter()).isNotNull();
                });
    }

    @Test
    void shouldRegisterStatementExecutionTimeDistributionSummary() {
        contextRunner
                .withPropertyValues(OJP_URL)
                .withUserConfiguration(SimpleMeterRegistryConfiguration.class)
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("ojp.driver.statements.execution.time").summary()).isNotNull();
                });
    }

    @Test
    void shouldNotRegisterMicrometerMetricsBeanWhenNonOjpDatasourceUrlConfigured() {
        contextRunner
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/mydb")
                .withUserConfiguration(SimpleMeterRegistryConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(OjpMicrometerDriverMetrics.class));
    }

    @Configuration
    static class SimpleMeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
