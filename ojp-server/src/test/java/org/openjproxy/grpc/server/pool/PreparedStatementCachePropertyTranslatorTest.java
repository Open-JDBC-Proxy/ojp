package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.ServerConfiguration;
import org.openjproxy.grpc.server.TestPropertyCleanupUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedStatementCachePropertyTranslatorTest {

    @AfterEach
    void clearProperties() {
        TestPropertyCleanupUtils.clearStatementCacheProperties();
    }

    @Test
    void shouldTranslateMySqlNonXaStatementCacheDefaults() {
        ServerConfiguration configuration = new ServerConfiguration();

        Map<String, String> properties = PreparedStatementCachePropertyTranslator.buildNonXaProperties(
                configuration, "jdbc:mysql://localhost:3306/testdb");

        assertEquals("true", properties.get("cachePrepStmts"));
        assertEquals("250", properties.get("prepStmtCacheSize"));
        assertEquals("2048", properties.get("prepStmtCacheSqlLimit"));
        assertEquals("true", properties.get("useServerPrepStmts"));
    }

    @Test
    void shouldDisablePostgresServerPrepareWhenConfiguredFalse() {
        System.setProperty("ojp.connection.pool.statementCache.serverPrepare", "false");

        ServerConfiguration configuration = new ServerConfiguration();
        Map<String, String> properties = PreparedStatementCachePropertyTranslator.buildNonXaProperties(
                configuration, "jdbc:postgresql://localhost:5432/testdb");

        assertEquals("0", properties.get("prepareThreshold"));
    }

    @Test
    void shouldReturnNoPropertiesWhenStatementCacheDisabled() {
        System.setProperty("ojp.connection.pool.statementCache.enabled", "false");

        ServerConfiguration configuration = new ServerConfiguration();
        Map<String, String> properties = PreparedStatementCachePropertyTranslator.buildNonXaProperties(
                configuration, "jdbc:mysql://localhost:3306/testdb");

        assertTrue(properties.isEmpty());
    }

    @Test
    void shouldTranslateXaPropertiesWithXaPrefix() {
        ServerConfiguration configuration = new ServerConfiguration();

        Map<String, String> properties = PreparedStatementCachePropertyTranslator.buildXaProperties(
                configuration, "jdbc:sqlserver://localhost:1433;databaseName=testdb");

        assertEquals("false", properties.get("xa.property.disableStatementPooling"));
        assertEquals("250", properties.get("xa.property.statementPoolingCacheSize"));
    }
}
