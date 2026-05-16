package org.openjproxy.grpc.server.pool;

import org.openjproxy.grpc.server.ServerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Translates canonical OJP statement cache settings into driver-specific datasource properties.
 */
public final class PreparedStatementCachePropertyTranslator {
    private static final Logger log = LoggerFactory.getLogger(PreparedStatementCachePropertyTranslator.class);
    private static final String XA_PROPERTY_PREFIX = "xa.property.";

    private PreparedStatementCachePropertyTranslator() {
    }

    public static Map<String, String> buildNonXaProperties(ServerConfiguration configuration, String jdbcUrl) {
        if (configuration == null || !configuration.isStatementCacheEnabled()) {
            return Map.of();
        }
        return buildDriverProperties(
                jdbcUrl,
                configuration.getStatementCacheMaxSize(),
                configuration.getStatementCacheSqlLimit(),
                configuration.isStatementCacheServerPrepare(),
                configuration.getStatementCachePrepareThreshold());
    }

    public static Map<String, String> buildXaProperties(ServerConfiguration configuration, String jdbcUrl) {
        if (configuration == null || !configuration.isXaStatementCacheEnabled()) {
            return Map.of();
        }

        Map<String, String> driverProperties = buildDriverProperties(
                jdbcUrl,
                configuration.getXaStatementCacheMaxSize(),
                configuration.getXaStatementCacheSqlLimit(),
                configuration.isXaStatementCacheServerPrepare(),
                configuration.getXaStatementCachePrepareThreshold());

        if (driverProperties.isEmpty()) {
            return Map.of();
        }

        Map<String, String> xaProperties = new HashMap<>();
        for (Map.Entry<String, String> entry : driverProperties.entrySet()) {
            xaProperties.put(XA_PROPERTY_PREFIX + entry.getKey(), entry.getValue());
        }
        return xaProperties;
    }

    private static Map<String, String> buildDriverProperties(String jdbcUrl, int maxSize, int sqlLimit,
                                                             boolean serverPrepare, int prepareThreshold) {
        if (jdbcUrl == null) {
            return Map.of();
        }

        String lowerUrl = jdbcUrl.toLowerCase(Locale.ROOT);
        Map<String, String> properties = new HashMap<>();

        if (lowerUrl.contains(":mysql:") || lowerUrl.contains(":mariadb:")) {
            properties.put("cachePrepStmts", "true");
            properties.put("prepStmtCacheSize", String.valueOf(maxSize));
            properties.put("prepStmtCacheSqlLimit", String.valueOf(sqlLimit));
            properties.put("useServerPrepStmts", String.valueOf(serverPrepare));
            return properties;
        }

        if (lowerUrl.contains(":postgresql:") || lowerUrl.contains(":cockroach:") || lowerUrl.contains(":cockroachdb:")) {
            int effectivePrepareThreshold = serverPrepare ? prepareThreshold : 0;
            properties.put("prepareThreshold", String.valueOf(effectivePrepareThreshold));
            return properties;
        }

        if (lowerUrl.contains(":sqlserver:")) {
            properties.put("disableStatementPooling", "false");
            properties.put("statementPoolingCacheSize", String.valueOf(maxSize));
            return properties;
        }

        if (lowerUrl.contains(":oracle:")) {
            properties.put("oracle.jdbc.implicitStatementCacheSize", String.valueOf(maxSize));
            return properties;
        }

        if (lowerUrl.contains(":db2:")) {
            properties.put("maxStatements", String.valueOf(maxSize));
            return properties;
        }

        log.debug("Statement cache translation not needed for database URL: {}", jdbcUrl);
        return Map.of();
    }
}
