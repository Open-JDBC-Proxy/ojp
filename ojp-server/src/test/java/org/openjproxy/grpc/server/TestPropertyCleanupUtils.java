package org.openjproxy.grpc.server;

public final class TestPropertyCleanupUtils {
    private TestPropertyCleanupUtils() {
    }

    public static void clearStatementCacheProperties() {
        System.clearProperty("ojp.connection.pool.statementCache.enabled");
        System.clearProperty("ojp.connection.pool.statementCache.maxSize");
        System.clearProperty("ojp.connection.pool.statementCache.sqlLimit");
        System.clearProperty("ojp.connection.pool.statementCache.serverPrepare");
        System.clearProperty("ojp.connection.pool.statementCache.prepareThreshold");
        System.clearProperty("ojp.xa.connection.pool.statementCache.enabled");
        System.clearProperty("ojp.xa.connection.pool.statementCache.maxSize");
        System.clearProperty("ojp.xa.connection.pool.statementCache.sqlLimit");
        System.clearProperty("ojp.xa.connection.pool.statementCache.serverPrepare");
        System.clearProperty("ojp.xa.connection.pool.statementCache.prepareThreshold");
    }
}
