package org.openjproxy.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Spring Boot {@link EnvironmentPostProcessor} that automatically configures sensible
 * defaults when an OJP JDBC URL is detected.
 *
 * <p>When any datasource URL starts with {@code jdbc:ojp}, this post-processor injects
 * the following defaults for that datasource (only when not already explicitly set by the
 * user):</p>
 * <ul>
 *   <li>{@code spring.datasource[.{name}].driver-class-name=org.openjproxy.jdbc.Driver}</li>
 *   <li>{@code spring.datasource[.{name}].type=org.springframework.jdbc.datasource.SimpleDriverDataSource}</li>
 * </ul>
 *
 * <p>Both the default datasource URL ({@code spring.datasource.url}) and named datasource
 * URLs ({@code spring.datasource.{name}.url}) are supported, e.g.:</p>
 * <pre>
 * spring.datasource.catalog.url=jdbc:ojp[localhost:1059]_postgresql://user&#64;localhost/catalog
 * spring.datasource.checkout.url=jdbc:ojp[localhost:1059]_postgresql://user&#64;localhost/checkout
 * </pre>
 *
 * <p>Setting {@code spring.datasource[.{name}].type} to {@code SimpleDriverDataSource}
 * prevents Spring Boot from wrapping the OJP connection in a local HikariCP pool. OJP
 * manages connection pooling centrally on the proxy server, so a local pool is unnecessary
 * and would consume extra resources.</p>
 *
 * <p>This processor is registered via {@code META-INF/spring.factories} under the
 * {@code org.springframework.boot.env.EnvironmentPostProcessor} key, which is supported
 * across Spring Boot 3.x and 4.x.</p>
 *
 * <p>No action is taken when no OJP URL is present, making this safe to include
 * in projects that conditionally use OJP.</p>
 */
public class OjpEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OjpEnvironmentPostProcessor.class);

    static final String OJP_DATASOURCE_DEFAULTS = "ojpDataSourceDefaults";
    static final String OJP_URL_PREFIX = "jdbc:ojp";
    static final String DRIVER_CLASS_NAME = "spring.datasource.driver-class-name";
    static final String DATASOURCE_TYPE = "spring.datasource.type";
    static final String OJP_DRIVER_CLASS = "org.openjproxy.jdbc.Driver";
    static final String SIMPLE_DRIVER_DATASOURCE = "org.springframework.jdbc.datasource.SimpleDriverDataSource";
    private static final String DATASOURCE_PROP_PREFIX = "spring.datasource.";
    private static final String URL_SUFFIX = ".url";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();

        // Process the default datasource URL
        processUrl(environment, "spring.datasource.url",
                DRIVER_CLASS_NAME, DATASOURCE_TYPE, defaults);

        // Process named datasource URLs (spring.datasource.{name}.url)
        Set<String> seenUrlProperties = new LinkedHashSet<>();
        seenUrlProperties.add("spring.datasource.url");
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String propName : enumerable.getPropertyNames()) {
                    if (isNamedDatasourceUrlProperty(propName) && seenUrlProperties.add(propName)) {
                        String dsPrefix = propName.substring(0, propName.lastIndexOf('.') + 1);
                        processUrl(environment, propName,
                                dsPrefix + "driver-class-name",
                                dsPrefix + "type",
                                defaults);
                    }
                }
            }
        }

        if (!defaults.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(OJP_DATASOURCE_DEFAULTS, defaults));
        }
    }

    private void processUrl(ConfigurableEnvironment environment, String urlProperty,
                            String driverProperty, String typeProperty,
                            Map<String, Object> defaults) {
        String url = environment.getProperty(urlProperty);
        if (url == null || !url.startsWith(OJP_URL_PREFIX)) {
            return;
        }

        log.debug("OJP JDBC URL detected: {}. Applying OJP datasource defaults.", url);

        if (!environment.containsProperty(driverProperty)) {
            defaults.put(driverProperty, OJP_DRIVER_CLASS);
            log.debug("Setting default: {}={}", driverProperty, OJP_DRIVER_CLASS);
        }

        if (!environment.containsProperty(typeProperty)) {
            defaults.put(typeProperty, SIMPLE_DRIVER_DATASOURCE);
            log.debug("Setting default: {}={}", typeProperty, SIMPLE_DRIVER_DATASOURCE);
        }
    }

    private boolean isNamedDatasourceUrlProperty(String name) {
        return name.startsWith(DATASOURCE_PROP_PREFIX)
                && name.endsWith(URL_SUFFIX)
                && !name.equals("spring.datasource.url");
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
