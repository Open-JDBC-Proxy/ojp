package org.openjproxy.autoconfigure;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.HashSet;
import java.util.Set;

/**
 * Bridges OJP configuration from Spring Boot's {@code application.properties}
 * to JVM system properties.
 *
 * <p>The OJP JDBC driver's {@code DatasourcePropertiesLoader} reads pool and gRPC
 * settings from three sources (in descending priority): environment variables,
 * system properties, and {@code ojp.properties} file. This bridge iterates all
 * {@code ojp.*} and named-pool {@code {poolName}.ojp.*} properties in Spring's
 * {@link Environment} and forwards them to JVM system properties so that settings
 * declared in {@code application.properties} are transparently available to the
 * driver without a separate {@code ojp.properties} file. Any new OJP property is
 * picked up automatically without code changes.</p>
 *
 * <p>Property keys written in Spring's kebab-case format are automatically
 * converted to camelCase before being set as system properties. For named-pool
 * keys the pool-name prefix is preserved verbatim (including any hyphens) and
 * only the property-path segment is converted:</p>
 * <pre>
 *   ojp.connection.pool.maximum-pool-size               →  ojp.connection.pool.maximumPoolSize
 *   ojp.grpc.max-inbound-message-size                   →  ojp.grpc.maxInboundMessageSize
 *   high-performance.ojp.connection.pool.maximum-pool-size
 *                                                       →  high-performance.ojp.connection.pool.maximumPoolSize
 *   postgres.ojp.connection.pool.minimum-idle           →  postgres.ojp.connection.pool.minimumIdle
 * </pre>
 *
 * <p>Only non-null property values are written as system properties, and existing
 * system-property or environment-variable values are never overridden.</p>
 *
 * <p>The datasource name is embedded in the OJP JDBC URL using parentheses notation:
 * {@code jdbc:ojp[localhost:1059(myApp)]_...}. The OJP driver extracts this name
 * automatically when establishing a connection.</p>
 */
public class OjpSystemPropertiesBridge {

    private static final Logger log = LoggerFactory.getLogger(OjpSystemPropertiesBridge.class);
    private static final String OJP_PREFIX = "ojp.";
    private static final String OJP_INFIX = ".ojp.";

    private final Environment environment;

    public OjpSystemPropertiesBridge(Environment environment) {
        this.environment = environment;
    }

    /**
     * Iterates all {@code ojp.*} and {@code {poolName}.ojp.*} keys in the Spring
     * {@link Environment}, converts kebab-case names to camelCase, and sets them as
     * JVM system properties. Only non-null values are written, and existing system
     * properties are preserved.
     */
    @PostConstruct
    public void applySystemProperties() {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            log.warn("Environment is not a ConfigurableEnvironment; OJP system properties will not be applied.");
            return;
        }
        applyOjpSystemProperties(configurableEnvironment);
    }

    /**
     * Iterates all {@code ojp.*} and {@code {poolName}.ojp.*} keys in the supplied
     * {@link ConfigurableEnvironment}, converts kebab-case names to camelCase, and sets
     * them as JVM system properties so the OJP JDBC driver can read them before the first
     * connection is established.
     *
     * <p>This static variant is called by {@link OjpEnvironmentPostProcessor} during
     * Spring Boot's environment-preparation phase — before any beans are created — to
     * guarantee that settings such as {@code ojp.health.check.interval} are available to
     * the driver regardless of bean-initialization order.</p>
     *
     * <p>Only non-null values are written, and existing system properties (e.g. set via
     * {@code -D} JVM flags) are never overridden.</p>
     */
    static void applyOjpSystemProperties(ConfigurableEnvironment configurableEnvironment) {
        Set<String> processed = new HashSet<>();
        for (PropertySource<?> source : configurableEnvironment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                processEnumerableSource(enumerable, processed, configurableEnvironment);
            }
        }
    }

    private static void processEnumerableSource(EnumerablePropertySource<?> source,
                                                Set<String> processed,
                                                ConfigurableEnvironment env) {
        for (String name : source.getPropertyNames()) {
            if (processed.add(name)) {
                forwardOjpProperty(name, env);
            }
        }
    }

    private static void forwardOjpProperty(String name, ConfigurableEnvironment env) {
        String sysKey = toSystemPropertyKey(name);
        if (sysKey == null) {
            return;
        }
        String value = env.getProperty(name);
        if (value != null) {
            setSystemPropertyIfAbsent(sysKey, value);
        }
    }

    /**
     * Derives the JVM system-property key from a Spring property key.
     *
     * <ul>
     *   <li>Default-pool keys ({@code ojp.*}): kebab-to-camelCase is applied to the
     *       entire key.</li>
     *   <li>Named-pool keys ({@code {poolName}.ojp.*}): the pool-name prefix is kept
     *       verbatim and kebab-to-camelCase is applied only to the {@code ojp.*}
     *       segment, preserving hyphens in the pool name (e.g. {@code high-performance})
     *       so they match the name embedded in the JDBC URL.</li>
     *   <li>Any other key: returns {@code null} (not an OJP key, will be ignored).</li>
     * </ul>
     *
     * @param springKey the Spring property key as returned by the {@link Environment}
     * @return the JVM system-property key, or {@code null} if this key is not OJP-related
     */
    static String toSystemPropertyKey(String springKey) {
        if (springKey.startsWith(OJP_PREFIX)) {
            return kebabToCamelCase(springKey);
        }
        int infixIndex = springKey.indexOf(OJP_INFIX);
        if (infixIndex > 0) {
            String poolName = springKey.substring(0, infixIndex);
            String ojpPart = springKey.substring(infixIndex + 1); // "ojp.{rest}"
            return poolName + "." + kebabToCamelCase(ojpPart);
        }
        return null;
    }

    /**
     * Converts a kebab-case property key to camelCase.
     * For example, {@code ojp.connection.pool.maximum-pool-size} becomes
     * {@code ojp.connection.pool.maximumPoolSize}.
     *
     * @param key the property key, potentially containing hyphens
     * @return the same key with each {@code -x} sequence replaced by {@code X}
     */
    static String kebabToCamelCase(String key) {
        StringBuilder result = new StringBuilder(key.length());
        boolean capitalize = false;
        for (char c : key.toCharArray()) {
            if (c == '-') {
                capitalize = true;
            } else {
                result.append(capitalize ? Character.toUpperCase(c) : c);
                capitalize = false;
            }
        }
        return result.toString();
    }

    private static void setSystemPropertyIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
            log.debug("Set OJP system property: {}={}", key, value);
        } else {
            log.debug("Skipped OJP system property (already set): {}", key);
        }
    }
}
