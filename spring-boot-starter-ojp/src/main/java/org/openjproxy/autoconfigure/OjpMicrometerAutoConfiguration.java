package org.openjproxy.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.openjproxy.jdbc.OjpDriverMetricsHolder;

/**
 * Spring Boot auto-configuration that bridges OJP driver metrics to Micrometer.
 *
 * <p>This auto-configuration activates when <em>all</em> of the following are true:</p>
 * <ol>
 *   <li>The OJP JDBC driver ({@code org.openjproxy.jdbc.Driver}) is on the classpath.</li>
 *   <li>At least one datasource URL starts with {@code jdbc:ojp}.</li>
 *   <li>Micrometer's {@link MeterRegistry} class is on the classpath.</li>
 *   <li>A {@link MeterRegistry} bean is present in the Spring application context
 *       (typically provided by {@code spring-boot-starter-actuator}).</li>
 * </ol>
 *
 * <p>When active, this configuration registers an {@link OjpMicrometerDriverMetrics} bean
 * and installs it as the active driver metrics implementation via
 * {@link OjpDriverMetricsHolder#set(org.openjproxy.jdbc.OjpDriverMetrics)}. The following
 * Micrometer meters are then populated:</p>
 * <ul>
 *   <li>{@code ojp.driver.connections.created} – counter</li>
 *   <li>{@code ojp.driver.connections.failed} – counter</li>
 *   <li>{@code ojp.driver.connections.closed} – counter</li>
 *   <li>{@code ojp.driver.connections.active} – gauge</li>
 *   <li>{@code ojp.driver.statements.executed} – counter</li>
 *   <li>{@code ojp.driver.statements.failed} – counter</li>
 *   <li>{@code ojp.driver.statements.execution.time} – distribution summary (ms)</li>
 * </ul>
 *
 * <p>These metrics are automatically exported to any Micrometer backend that the application
 * has configured (e.g. Prometheus via {@code micrometer-registry-prometheus}, Datadog,
 * InfluxDB, etc.) without any additional configuration.</p>
 *
 * <p>To disable OJP Micrometer metrics while keeping other actuator metrics active, exclude
 * this auto-configuration class:</p>
 * <pre>
 * &#64;SpringBootApplication(exclude = OjpMicrometerAutoConfiguration.class)
 * </pre>
 */
@AutoConfiguration(after = OjpAutoConfiguration.class)
@ConditionalOnClass({MeterRegistry.class, org.openjproxy.jdbc.Driver.class})
@Conditional(OnAnyOjpDatasourceUrlCondition.class)
@ConditionalOnBean(MeterRegistry.class)
public class OjpMicrometerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OjpMicrometerAutoConfiguration.class);

    /**
     * Creates and registers the {@link OjpMicrometerDriverMetrics} bean.
     *
     * <p>The bean is installed into {@link OjpDriverMetricsHolder} so that the OJP JDBC driver
     * records connection and statement metrics for every subsequent operation.</p>
     *
     * @param registry the Micrometer registry provided by Spring Boot Actuator
     * @return the metrics implementation
     */
    @Bean
    @ConditionalOnMissingBean(OjpMicrometerDriverMetrics.class)
    public OjpMicrometerDriverMetrics ojpMicrometerDriverMetrics(MeterRegistry registry) {
        log.info("Registering OJP Micrometer driver metrics");
        OjpMicrometerDriverMetrics metrics = new OjpMicrometerDriverMetrics(registry);
        OjpDriverMetricsHolder.set(metrics);
        return metrics;
    }
}
