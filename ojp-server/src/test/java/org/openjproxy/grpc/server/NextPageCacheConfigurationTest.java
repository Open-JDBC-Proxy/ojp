package org.openjproxy.grpc.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for next-page prefetch cache configuration properties in {@link ServerConfiguration}.
 */
class NextPageCacheConfigurationTest {

    private static final String ENABLED_KEY               = "ojp.server.nextPageCache.enabled";
    private static final String TTL_KEY                   = "ojp.server.nextPageCache.ttlSeconds";
    private static final String MAX_ENTRIES_KEY           = "ojp.server.nextPageCache.maxEntries";
    private static final String WAIT_TIMEOUT_MS_KEY       = "ojp.server.nextPageCache.prefetchWaitTimeoutMs";
    private static final String CLEANUP_INTERVAL_KEY      = "ojp.server.nextPageCache.cleanupIntervalSeconds";

    @BeforeEach
    void clearProperties() {
        System.clearProperty(ENABLED_KEY);
        System.clearProperty(TTL_KEY);
        System.clearProperty(MAX_ENTRIES_KEY);
        System.clearProperty(WAIT_TIMEOUT_MS_KEY);
        System.clearProperty(CLEANUP_INTERVAL_KEY);
    }

    @AfterEach
    void cleanupProperties() {
        clearProperties();
    }

    // ----------------------------------------------------------------
    // Defaults
    // ----------------------------------------------------------------

    @Test
    void defaultConfiguration_nextPageCacheIsDisabled() {
        ServerConfiguration config = new ServerConfiguration();

        assertFalse(config.isNextPageCacheEnabled(),
                "Next-page cache must be disabled by default");
    }

    @Test
    void defaultConfiguration_hasExpectedDefaultValues() {
        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_TTL_SECONDS,
                config.getNextPageCacheTtlSeconds(), "Default TTL mismatch");
        assertEquals(ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_MAX_ENTRIES,
                config.getNextPageCacheMaxEntries(), "Default max-entries mismatch");
        assertEquals(ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_PREFETCH_WAIT_TIMEOUT_MS,
                config.getNextPageCachePrefetchWaitTimeoutMs(), "Default prefetch-wait-timeout mismatch");
        assertEquals(ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_CLEANUP_INTERVAL_SECONDS,
                config.getNextPageCacheCleanupIntervalSeconds(), "Default cleanup-interval mismatch");
    }

    // ----------------------------------------------------------------
    // Enable via system property
    // ----------------------------------------------------------------

    @Test
    void systemProperty_enabled_overridesDefault() {
        System.setProperty(ENABLED_KEY, "true");

        ServerConfiguration config = new ServerConfiguration();

        assertTrue(config.isNextPageCacheEnabled());
    }

    @Test
    void systemProperty_disabled_overridesDefault() {
        System.setProperty(ENABLED_KEY, "false");

        ServerConfiguration config = new ServerConfiguration();

        assertFalse(config.isNextPageCacheEnabled());
    }

    // ----------------------------------------------------------------
    // Custom TTL
    // ----------------------------------------------------------------

    @Test
    void systemProperty_ttlSeconds_isRespected() {
        System.setProperty(TTL_KEY, "120");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(120L, config.getNextPageCacheTtlSeconds());
    }

    @Test
    void systemProperty_invalidTtl_fallsBackToDefault() {
        System.setProperty(TTL_KEY, "not-a-number");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_TTL_SECONDS,
                config.getNextPageCacheTtlSeconds());
    }

    // ----------------------------------------------------------------
    // Custom max entries
    // ----------------------------------------------------------------

    @Test
    void systemProperty_maxEntries_isRespected() {
        System.setProperty(MAX_ENTRIES_KEY, "250");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(250, config.getNextPageCacheMaxEntries());
    }

    @Test
    void systemProperty_invalidMaxEntries_fallsBackToDefault() {
        System.setProperty(MAX_ENTRIES_KEY, "invalid");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_MAX_ENTRIES,
                config.getNextPageCacheMaxEntries());
    }

    // ----------------------------------------------------------------
    // Custom prefetch wait timeout
    // ----------------------------------------------------------------

    @Test
    void systemProperty_prefetchWaitTimeoutMs_isRespected() {
        System.setProperty(WAIT_TIMEOUT_MS_KEY, "10000");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(10000L, config.getNextPageCachePrefetchWaitTimeoutMs());
    }

    @Test
    void systemProperty_invalidPrefetchWaitTimeout_fallsBackToDefault() {
        System.setProperty(WAIT_TIMEOUT_MS_KEY, "bad-value");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_PREFETCH_WAIT_TIMEOUT_MS,
                config.getNextPageCachePrefetchWaitTimeoutMs());
    }

    // ----------------------------------------------------------------
    // Custom cleanup interval
    // ----------------------------------------------------------------

    @Test
    void systemProperty_cleanupIntervalSeconds_isRespected() {
        System.setProperty(CLEANUP_INTERVAL_KEY, "30");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(30L, config.getNextPageCacheCleanupIntervalSeconds());
    }

    @Test
    void systemProperty_invalidCleanupInterval_fallsBackToDefault() {
        System.setProperty(CLEANUP_INTERVAL_KEY, "not-a-number");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_CLEANUP_INTERVAL_SECONDS,
                config.getNextPageCacheCleanupIntervalSeconds());
    }

    @Test
    void defaultCleanupInterval_is60Seconds() {
        assertEquals(60L, ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_CLEANUP_INTERVAL_SECONDS);
    }

    @Test
    void defaultTtlSeconds_is60Seconds() {
        assertEquals(60L, ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_TTL_SECONDS);
    }

    // ----------------------------------------------------------------
    // Per-datasource prefetch wait timeout
    // ----------------------------------------------------------------

    @Test
    void perDatasource_prefetchWaitTimeoutMs_isRespected() {
        System.setProperty("ojp.server.nextPageCache.datasource.my-db.prefetchWaitTimeoutMs", "1500");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(1500L, config.getNextPageCachePrefetchWaitTimeoutMs("my-db"));

        System.clearProperty("ojp.server.nextPageCache.datasource.my-db.prefetchWaitTimeoutMs");
    }

    @Test
    void perDatasource_prefetchWaitTimeoutMs_fallsBackToGlobalDefault_whenNotSet() {
        System.setProperty(WAIT_TIMEOUT_MS_KEY, "8000");

        ServerConfiguration config = new ServerConfiguration();

        // Datasource "unknown" has no per-datasource property set
        assertEquals(8000L, config.getNextPageCachePrefetchWaitTimeoutMs("unknown-ds"));

        System.clearProperty(WAIT_TIMEOUT_MS_KEY);
    }

    @Test
    void perDatasource_prefetchWaitTimeoutMs_fallsBackToGlobalDefault_forNullName() {
        System.setProperty(WAIT_TIMEOUT_MS_KEY, "3000");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(3000L, config.getNextPageCachePrefetchWaitTimeoutMs(null));

        System.clearProperty(WAIT_TIMEOUT_MS_KEY);
    }

    @Test
    void perDatasource_prefetchWaitTimeoutMs_fallsBackToGlobalDefault_forDefaultName() {
        System.setProperty(WAIT_TIMEOUT_MS_KEY, "4000");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(4000L, config.getNextPageCachePrefetchWaitTimeoutMs("default"));

        System.clearProperty(WAIT_TIMEOUT_MS_KEY);
    }

    @Test
    void perDatasource_invalidPrefetchWaitTimeout_fallsBackToGlobalDefault() {
        System.setProperty("ojp.server.nextPageCache.datasource.bad-ds.prefetchWaitTimeoutMs", "not-a-number");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(ServerConfiguration.DEFAULT_NEXT_PAGE_CACHE_PREFETCH_WAIT_TIMEOUT_MS,
                config.getNextPageCachePrefetchWaitTimeoutMs("bad-ds"));

        System.clearProperty("ojp.server.nextPageCache.datasource.bad-ds.prefetchWaitTimeoutMs");
    }

    @Test
    void perDatasource_multipleOverrides_areIndependent() {
        System.setProperty("ojp.server.nextPageCache.datasource.ds-a.prefetchWaitTimeoutMs", "1000");
        System.setProperty("ojp.server.nextPageCache.datasource.ds-b.prefetchWaitTimeoutMs", "2000");
        System.setProperty(WAIT_TIMEOUT_MS_KEY, "9000");

        ServerConfiguration config = new ServerConfiguration();

        assertEquals(1000L, config.getNextPageCachePrefetchWaitTimeoutMs("ds-a"));
        assertEquals(2000L, config.getNextPageCachePrefetchWaitTimeoutMs("ds-b"));
        assertEquals(9000L, config.getNextPageCachePrefetchWaitTimeoutMs("ds-c")); // falls back to global

        System.clearProperty("ojp.server.nextPageCache.datasource.ds-a.prefetchWaitTimeoutMs");
        System.clearProperty("ojp.server.nextPageCache.datasource.ds-b.prefetchWaitTimeoutMs");
        System.clearProperty(WAIT_TIMEOUT_MS_KEY);
    }
}
