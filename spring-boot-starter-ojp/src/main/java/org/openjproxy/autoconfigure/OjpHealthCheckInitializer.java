package org.openjproxy.autoconfigure;

import jakarta.annotation.PostConstruct;
import org.openjproxy.grpc.client.MultinodeUrlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Eagerly initializes the OJP {@link org.openjproxy.grpc.client.MultinodeConnectionManager}
 * — and therefore its health-check scheduler — at Spring Boot startup.
 *
 * <p>Without this initializer the {@code MultinodeConnectionManager} is created
 * <em>lazily</em>, only on the first JDBC connection.  If the application does not
 * make a database call immediately after startup the health-check thread never
 * begins, even though all {@code ojp.*} properties have been correctly forwarded to
 * JVM system properties by {@link OjpSystemPropertiesBridge}.</p>
 *
 * <p>This bean depends on {@link OjpSystemPropertiesBridge} (injected as a
 * constructor parameter) so that Spring guarantees all {@code ojp.*} system
 * properties are set before the {@link MultinodeUrlParser#getOrCreateStatementService}
 * call is made.  The combination ensures:</p>
 * <ol>
 *   <li>Properties are bridged from {@code application.yaml} to JVM system properties.</li>
 *   <li>The {@code MultinodeConnectionManager} is created with those correct values.</li>
 *   <li>The health-check scheduler starts immediately at application startup.</li>
 * </ol>
 *
 * <p>Both the default datasource URL ({@code spring.datasource.url}) and named
 * datasource URLs ({@code spring.datasource.{name}.url}) are handled.</p>
 *
 * <p>Any failure during eager initialization is caught and logged as a warning so
 * that a misconfigured URL or unreachable server cannot prevent the application
 * from starting.</p>
 */
public class OjpHealthCheckInitializer {

    private static final Logger log = LoggerFactory.getLogger(OjpHealthCheckInitializer.class);

    private static final String OJP_URL_PREFIX = "jdbc:ojp";
    private static final String DEFAULT_DATASOURCE_URL_PROPERTY = "spring.datasource.url";
    private static final String DATASOURCE_PREFIX = "spring.datasource.";
    private static final String URL_SUFFIX = ".url";

    private final Environment environment;

    public OjpHealthCheckInitializer(Environment environment) {
        this.environment = environment;
    }

    /**
     * Scans all configured datasource URLs and eagerly creates a
     * {@link org.openjproxy.grpc.client.MultinodeConnectionManager} for each OJP
     * URL found, starting the health-check scheduler immediately.
     */
    @PostConstruct
    public void initializeHealthCheck() {
        Set<String> seen = new LinkedHashSet<>();

        // Default datasource
        initializeForUrl(DEFAULT_DATASOURCE_URL_PROPERTY, seen);

        // Named datasources (spring.datasource.{name}.url)
        if (environment instanceof ConfigurableEnvironment configurableEnvironment) {
            for (PropertySource<?> source : configurableEnvironment.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String propName : enumerable.getPropertyNames()) {
                        if (isNamedDatasourceUrlProperty(propName)) {
                            initializeForUrl(propName, seen);
                        }
                    }
                }
            }
        }
    }

    private void initializeForUrl(String urlProperty, Set<String> seen) {
        if (!seen.add(urlProperty)) {
            return;
        }
        String url = environment.getProperty(urlProperty);
        if (url == null || !url.startsWith(OJP_URL_PREFIX)) {
            return;
        }
        try {
            log.info("Eagerly initializing OJP connection manager and health check for {}", urlProperty);
            MultinodeUrlParser.getOrCreateStatementService(url);
        } catch (Exception e) {
            log.warn("Failed to eagerly initialize OJP health check for {}: {}", urlProperty, e.getMessage());
        }
    }

    private static boolean isNamedDatasourceUrlProperty(String name) {
        if (!name.startsWith(DATASOURCE_PREFIX) || !name.endsWith(URL_SUFFIX)) {
            return false;
        }
        // Require a non-empty, dot-free middle segment between the prefix and suffix:
        //   "spring.datasource.catalog.url" → middle = "catalog" ✓
        //   "spring.datasource.url"         → middleEnd < middleStart → false ✓
        //   "spring.datasource.foo.bar.url" → middle = "foo.bar" contains dot → false ✓
        int middleStart = DATASOURCE_PREFIX.length();
        int middleEnd = name.length() - URL_SUFFIX.length();
        if (middleEnd <= middleStart) {
            // No room for a middle segment (e.g. exactly "spring.datasource.url")
            return false;
        }
        String middle = name.substring(middleStart, middleEnd);
        return !middle.contains(".");
    }
}
