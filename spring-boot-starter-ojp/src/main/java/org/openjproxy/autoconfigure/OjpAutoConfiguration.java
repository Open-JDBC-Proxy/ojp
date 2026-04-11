package org.openjproxy.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;

/**
 * Spring Boot auto-configuration for OJP (Open J Proxy).
 *
 * <p>This auto-configuration activates when:</p>
 * <ol>
 *   <li>The OJP JDBC driver class ({@code org.openjproxy.jdbc.Driver}) is on the classpath.</li>
 *   <li>At least one datasource URL ({@code spring.datasource.url} or
 *       {@code spring.datasource.{name}.url}) starts with {@code jdbc:ojp}.</li>
 * </ol>
 *
 * <p>What this auto-configuration does:</p>
 * <ul>
 *   <li>Registers an {@link OjpSystemPropertiesBridge} bean that iterates all
 *       {@code ojp.*} properties from Spring's {@link Environment}, converts their
 *       names from kebab-case to camelCase, and forwards them to JVM system properties,
 *       where the OJP driver's {@code DatasourcePropertiesLoader} reads them with the
 *       highest precedence. Any new OJP property is forwarded automatically without
 *       code changes.</li>
 * </ul>
 *
 * <p>The auto-configuration is ordered before the JDBC {@code DataSourceAutoConfiguration}
 * (both Spring Boot 3.x and 4.x packages are listed) so that system properties are set
 * before any {@code DataSource} bean is created.</p>
 *
 * <p><strong>Minimal {@code application.properties} for single-datasource OJP setup:</strong></p>
 * <pre>
 * spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user&#64;localhost/mydb
 * spring.datasource.username=user
 * spring.datasource.******
 * </pre>
 *
 * <p><strong>Named-datasource setup example:</strong></p>
 * <pre>
 * spring.datasource.catalog.url=jdbc:ojp[localhost:1059]_postgresql://user&#64;localhost/catalog
 * spring.datasource.checkout.url=jdbc:ojp[localhost:1059]_postgresql://user&#64;localhost/checkout
 * </pre>
 *
 * <p>The {@link OjpEnvironmentPostProcessor} automatically sets
 * {@code spring.datasource.driver-class-name} and {@code spring.datasource.type} when
 * the OJP URL is detected, so users do not need to configure those manually.</p>
 */
@AutoConfiguration(beforeName = {
        // Spring Boot 3.x location
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        // Spring Boot 4.x location (moved to spring-boot-jdbc module)
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
@ConditionalOnClass(name = "org.openjproxy.jdbc.Driver")
@Conditional(OnAnyOjpDatasourceUrlCondition.class)
public class OjpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OjpAutoConfiguration.class);

    /**
     * Registers the {@link OjpSystemPropertiesBridge} bean.
     *
     * <p>This bean iterates all {@code ojp.*} properties in the Spring
     * {@link Environment} and forwards them as JVM system properties, making them
     * available to the OJP driver's {@code DatasourcePropertiesLoader} before the
     * first database connection is made.</p>
     *
     * @param environment the Spring environment
     * @return the system properties bridge
     */
    @Bean
    public OjpSystemPropertiesBridge ojpSystemPropertiesBridge(Environment environment) {
        log.debug("Registering OjpSystemPropertiesBridge");
        return new OjpSystemPropertiesBridge(environment);
    }
}
