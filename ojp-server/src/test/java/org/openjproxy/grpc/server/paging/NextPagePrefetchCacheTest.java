package org.openjproxy.grpc.server.paging;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NextPagePrefetchCache}.
 */
class NextPagePrefetchCacheTest {

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static NextPagePrefetchCache enabledCache() {
        return new NextPagePrefetchCache(true, 100, 60, 5000, 0);
    }

    private static NextPagePrefetchCache disabledCache() {
        return new NextPagePrefetchCache(false, 100, 60, 5000, 0);
    }

    /**
     * Creates a mock DataSource that returns a ResultSet with one row
     * containing a single integer column named "id" with value 42.
     */
    private static DataSource mockDataSource(int rowCount) throws Exception {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(meta.getColumnType(1)).thenReturn(Types.INTEGER);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(meta);

        // Simulate 'rowCount' rows
        if (rowCount == 0) {
            when(rs.next()).thenReturn(false);
        } else {
            Boolean[] nexts = new Boolean[rowCount + 1];
            for (int i = 0; i < rowCount; i++) nexts[i] = true;
            nexts[rowCount] = false;
            Boolean first = nexts[0];
            Boolean[] rest = new Boolean[rowCount];
            System.arraycopy(nexts, 1, rest, 0, rowCount);
            when(rs.next()).thenReturn(first, rest);
        }
        when(rs.getObject(1)).thenReturn(42);

        Statement stmt = mock(Statement.class);
        when(stmt.executeQuery(anyString())).thenReturn(rs);

        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(stmt);

        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);

        return ds;
    }

    // ----------------------------------------------------------------
    // isEnabled()
    // ----------------------------------------------------------------

    @Test
    void isEnabled_returnsTrueWhenConstructedEnabled() {
        assertTrue(enabledCache().isEnabled());
    }

    @Test
    void isEnabled_returnsFalseWhenConstructedDisabled() {
        assertFalse(disabledCache().isEnabled());
    }

    // ----------------------------------------------------------------
    // getIfReady() – no entry
    // ----------------------------------------------------------------

    @Test
    void getIfReady_returnsEmpty_whenNothingCached() {
        NextPagePrefetchCache cache = enabledCache();
        Optional<CachedPage> result = cache.getIfReady("ds1", "SELECT * FROM t LIMIT 10 OFFSET 10");
        assertFalse(result.isPresent(), "Expected empty when nothing is cached");
    }

    // ----------------------------------------------------------------
    // prefetchAsync() – disabled cache
    // ----------------------------------------------------------------

    @Test
    void prefetchAsync_doesNothing_whenDisabled() throws Exception {
        NextPagePrefetchCache cache = disabledCache();
        DataSource ds = mockDataSource(1);

        cache.prefetchAsync(ds, "ds1", "SELECT * FROM t LIMIT 10 OFFSET 10", List.of());

        // Cache should still be empty
        assertFalse(cache.getIfReady("ds1", "SELECT * FROM t LIMIT 10 OFFSET 10").isPresent());
    }

    @Test
    void prefetchAsync_doesNothing_whenDataSourceIsNull() {
        NextPagePrefetchCache cache = enabledCache();
        cache.prefetchAsync(null, "ds1", "SELECT * FROM t LIMIT 10 OFFSET 10", List.of());
        assertFalse(cache.getIfReady("ds1", "SELECT * FROM t LIMIT 10 OFFSET 10").isPresent());
    }

    @Test
    void prefetchAsync_doesNothing_whenSqlIsNull() throws Exception {
        NextPagePrefetchCache cache = enabledCache();
        DataSource ds = mockDataSource(1);
        cache.prefetchAsync(ds, "ds1", null, List.of());
        // Nothing to assert – just must not throw
    }

    // ----------------------------------------------------------------
    // prefetchAsync() + getIfReady() – happy path
    // ----------------------------------------------------------------

    @Test
    void prefetchAndGet_returnsRows_forSimpleQuery() throws Exception {
        NextPagePrefetchCache cache = enabledCache();
        DataSource ds = mockDataSource(3);

        String sql = "SELECT id FROM t LIMIT 10 OFFSET 10";
        cache.prefetchAsync(ds, "ds1", sql, List.of());

        // Wait for the prefetch (virtual thread) to complete
        Optional<CachedPage> result = cache.getIfReady("ds1", sql);

        assertTrue(result.isPresent(), "Expected cached page");
        CachedPage page = result.get();
        assertEquals(List.of("id"), page.getColumnLabels());
        assertEquals(3, page.getRows().size());
    }

    @Test
    void prefetchAndGet_cacheKeyIsCaseAndWhitespaceInsensitive() throws Exception {
        NextPagePrefetchCache cache = enabledCache();
        DataSource ds = mockDataSource(1);

        // Prefetch with one form of the SQL
        cache.prefetchAsync(ds, "ds1", "SELECT id FROM t LIMIT 10 OFFSET 10", List.of());

        // Retrieve with slightly different casing/whitespace (should normalise to same key)
        Optional<CachedPage> result = cache.getIfReady("ds1", "  SELECT ID FROM T LIMIT 10 OFFSET 10  ");

        assertTrue(result.isPresent(), "Keys should normalise to the same entry");
    }

    // ----------------------------------------------------------------
    // Single-use semantics
    // ----------------------------------------------------------------

    @Test
    void getIfReady_returnsSingleUse_secondCallEmpty() throws Exception {
        NextPagePrefetchCache cache = enabledCache();
        DataSource ds = mockDataSource(2);

        String sql = "SELECT id FROM t LIMIT 10 OFFSET 10";
        cache.prefetchAsync(ds, "ds1", sql, List.of());

        Optional<CachedPage> first = cache.getIfReady("ds1", sql);
        assertTrue(first.isPresent(), "First retrieval should succeed");

        // Second retrieval should return empty (entry was removed after first use)
        Optional<CachedPage> second = cache.getIfReady("ds1", sql);
        assertFalse(second.isPresent(), "Second retrieval should be empty (single-use)");
    }

    // ----------------------------------------------------------------
    // Expiry
    // ----------------------------------------------------------------

    @Test
    void getIfReady_returnsEmpty_whenEntryExpired() throws Exception {
        // TTL = 0 seconds → immediately expired
        NextPagePrefetchCache cache = new NextPagePrefetchCache(true, 100, 0, 5000, 0);
        DataSource ds = mockDataSource(1);

        String sql = "SELECT id FROM t LIMIT 10 OFFSET 10";
        cache.prefetchAsync(ds, "ds1", sql, List.of());

        // Wait a bit to ensure the prefetch completes and the entry is expired
        Thread.sleep(50);

        Optional<CachedPage> result = cache.getIfReady("ds1", sql);
        assertFalse(result.isPresent(), "Entry should be expired with TTL=0");
    }

    // ----------------------------------------------------------------
    // No-duplicate prefetch
    // ----------------------------------------------------------------

    @Test
    void prefetchAsync_doesNotStartDuplicate_whenKeyAlreadyPresent() throws Exception {
        NextPagePrefetchCache cache = enabledCache();
        DataSource ds = mockDataSource(1);

        String sql = "SELECT id FROM t LIMIT 10 OFFSET 10";
        cache.prefetchAsync(ds, "ds1", sql, List.of()); // first start
        cache.prefetchAsync(ds, "ds1", sql, List.of()); // duplicate – should be ignored

        // Retrieve to confirm the entry exists (one execution)
        Optional<CachedPage> result = cache.getIfReady("ds1", sql);
        assertTrue(result.isPresent());
    }

    // ----------------------------------------------------------------
    // CachedPage
    // ----------------------------------------------------------------

    @Test
    void cachedPage_isNotExpired_whenJustCreated() {
        CachedPage page = new CachedPage(List.of("col"), List.of());
        assertFalse(page.isExpired(60_000), "Freshly created page should not be expired");
    }

    @Test
    void cachedPage_isExpired_withZeroTtl() throws Exception {
        CachedPage page = new CachedPage(List.of("col"), List.of());
        Thread.sleep(10); // small delay so currentTime > createdAt
        assertTrue(page.isExpired(0), "Page should be expired with TTL=0");
    }

    // ----------------------------------------------------------------
    // CLOB / NCLOB return columns
    // ----------------------------------------------------------------

    /**
     * Creates a mock DataSource whose ResultSet returns one row with one CLOB column.
     * The CLOB content is materialised as the {@code String} returned by
     * {@code getCharacterStream()}.
     */
    private static DataSource mockDataSourceWithClob(String clobContent) throws Exception {
        java.io.Reader reader = new java.io.StringReader(clobContent);

        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("description");
        when(meta.getColumnType(1)).thenReturn(Types.CLOB);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(rs.next()).thenReturn(true, false);
        when(rs.getCharacterStream(1)).thenReturn(reader);

        Statement stmt = mock(Statement.class);
        when(stmt.executeQuery(anyString())).thenReturn(rs);

        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(stmt);

        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);
        return ds;
    }

    /**
     * Creates a mock DataSource whose ResultSet returns one row with one NCLOB column.
     */
    private static DataSource mockDataSourceWithNClob(String nclobContent) throws Exception {
        java.io.Reader reader = new java.io.StringReader(nclobContent);

        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("content");
        when(meta.getColumnType(1)).thenReturn(Types.NCLOB);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(rs.next()).thenReturn(true, false);
        when(rs.getNCharacterStream(1)).thenReturn(reader);

        Statement stmt = mock(Statement.class);
        when(stmt.executeQuery(anyString())).thenReturn(rs);

        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(stmt);

        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);
        return ds;
    }

    @Test
    void prefetchAndGet_cachesClobColumns_asString() throws Exception {
        NextPagePrefetchCache cache = enabledCache();
        String clobContent = "This is a large text value stored as CLOB";
        DataSource ds = mockDataSourceWithClob(clobContent);

        String sql = "SELECT description FROM articles LIMIT 10 OFFSET 10";
        cache.prefetchAsync(ds, "ds1", sql, List.of());

        Optional<CachedPage> result = cache.getIfReady("ds1", sql);

        assertTrue(result.isPresent(), "CLOB column query should be cached");
        CachedPage page = result.get();
        assertEquals(1, page.getRows().size());
        assertEquals(clobContent, page.getRows().get(0)[0],
                "CLOB content should be materialised as String");
    }

    @Test
    void prefetchAndGet_cachesNclobColumns_asString() throws Exception {
        NextPagePrefetchCache cache = enabledCache();
        String nclobContent = "Unicode text: こんにちは";
        DataSource ds = mockDataSourceWithNClob(nclobContent);

        String sql = "SELECT content FROM docs LIMIT 10 OFFSET 10";
        cache.prefetchAsync(ds, "ds1", sql, List.of());

        Optional<CachedPage> result = cache.getIfReady("ds1", sql);

        assertTrue(result.isPresent(), "NCLOB column query should be cached");
        CachedPage page = result.get();
        assertEquals(1, page.getRows().size());
        assertEquals(nclobContent, page.getRows().get(0)[0],
                "NCLOB content should be materialised as String");
    }

    @Test
    void prefetchAndGet_handlesNullClobValue() throws Exception {
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("description");
        when(meta.getColumnType(1)).thenReturn(Types.CLOB);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(rs.next()).thenReturn(true, false);
        when(rs.getCharacterStream(1)).thenReturn(null); // NULL CLOB

        Statement stmt = mock(Statement.class);
        when(stmt.executeQuery(anyString())).thenReturn(rs);

        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(stmt);

        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);

        NextPagePrefetchCache cache = enabledCache();
        String sql = "SELECT description FROM t LIMIT 10 OFFSET 10";
        cache.prefetchAsync(ds, "ds1", sql, List.of());

        Optional<CachedPage> result = cache.getIfReady("ds1", sql);

        assertTrue(result.isPresent(), "Null CLOB should be cached as null value");
        assertFalse(result.get().getRows().isEmpty());
        assertNull(result.get().getRows().get(0)[0], "Null CLOB column should be null in cache");
    }

    // ----------------------------------------------------------------
    // Datasource isolation
    // ----------------------------------------------------------------

    @Test
    void prefetchAndGet_isolatesByDatasourceId() throws Exception {
        NextPagePrefetchCache cache = enabledCache();
        DataSource ds1 = mockDataSource(2);
        DataSource ds2 = mockDataSource(5);

        String sql = "SELECT id FROM t LIMIT 10 OFFSET 10";

        // Prefetch same SQL for two different datasources
        cache.prefetchAsync(ds1, "conn-hash-A", sql, List.of());
        cache.prefetchAsync(ds2, "conn-hash-B", sql, List.of());

        // Each datasource gets its own cache entry
        Optional<CachedPage> resultA = cache.getIfReady("conn-hash-A", sql);
        Optional<CachedPage> resultB = cache.getIfReady("conn-hash-B", sql);

        assertTrue(resultA.isPresent(), "Datasource A should have its own cache entry");
        assertTrue(resultB.isPresent(), "Datasource B should have its own cache entry");
        assertEquals(2, resultA.get().getRows().size(), "DS-A should have 2 rows");
        assertEquals(5, resultB.get().getRows().size(), "DS-B should have 5 rows");
    }

    @Test
    void getIfReady_withDifferentDatasourceId_missesCache() throws Exception {
        NextPagePrefetchCache cache = enabledCache();
        DataSource ds = mockDataSource(1);

        String sql = "SELECT id FROM t LIMIT 10 OFFSET 10";
        cache.prefetchAsync(ds, "conn-hash-A", sql, List.of());

        // Asking for the same SQL under a different datasource ID should miss
        Optional<CachedPage> result = cache.getIfReady("conn-hash-B", sql);
        assertFalse(result.isPresent(),
                "Cache miss expected: different datasourceId should not match");
    }

    // ----------------------------------------------------------------
    // Background cleanup scheduler
    // ----------------------------------------------------------------

    @Test
    void shutdown_doesNotThrow_whenSchedulerNotStarted() {
        // cleanupIntervalSeconds=0 → no cleanup task registered
        NextPagePrefetchCache cache = new NextPagePrefetchCache(true, 100, 60, 5000, 0);
        cache.shutdown(); // must not throw
    }

    @Test
    void shutdown_isIdempotent() {
        NextPagePrefetchCache cache = new NextPagePrefetchCache(true, 100, 60, 5000, 30);
        cache.shutdown();
        cache.shutdown(); // second call must not throw
    }

    @Test
    void backgroundCleanup_evictsExpiredEntries() throws Exception {
        // TTL = 0 → all entries expire immediately
        // cleanupInterval = 1 second → scheduler will run
        NextPagePrefetchCache cache = new NextPagePrefetchCache(true, 100, 0, 5000, 1);
        DataSource ds = mockDataSource(2);

        String sql = "SELECT id FROM t LIMIT 10 OFFSET 10";
        cache.prefetchAsync(ds, "ds1", sql, List.of());

        // Wait (with polling) for the background cleanup to reduce the cache size to 0
        long deadline = System.currentTimeMillis() + 5_000;
        while (cache.cacheSize() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertEquals(0, cache.cacheSize(),
                "Background cleanup should have evicted the expired entry");

        cache.shutdown();
    }

    // ----------------------------------------------------------------
    // Per-datasource prefetch wait timeout
    // ----------------------------------------------------------------

    @Test
    void registerDatasourcePrefetchWaitTimeout_ignoresNullId() {
        NextPagePrefetchCache cache = enabledCache();
        // Null datasourceId should be silently ignored (no NullPointerException)
        cache.registerDatasourcePrefetchWaitTimeout(null, 1000);
    }

    @Test
    void getIfReady_usesPerDatasourceTimeout_whenRegistered() throws Exception {
        // enabled, maxEntries=100, ttlSeconds=60, globalTimeoutMs=1, cleanupInterval=0 (disabled)
        NextPagePrefetchCache cache = new NextPagePrefetchCache(true, 100, 60, 1, 0); // global: 1ms
        cache.registerDatasourcePrefetchWaitTimeout("ds-custom", 5_000); // per-ds: 5 s

        DataSource ds = mockDataSource(3);
        String sql = "SELECT id FROM t LIMIT 10 OFFSET 0";
        cache.prefetchAsync(ds, "ds-custom", sql, List.of());

        Optional<CachedPage> result = cache.getIfReady("ds-custom", sql);

        assertTrue(result.isPresent(), "Cache hit expected with per-datasource timeout");
        assertEquals(3, result.get().getRows().size());
    }

    @Test
    void registerDatasourcePrefetchWaitTimeout_replacesExistingValue() throws Exception {
        // enabled, maxEntries=100, ttlSeconds=60, globalTimeoutMs=9999, cleanupInterval=0 (disabled)
        NextPagePrefetchCache cache = new NextPagePrefetchCache(true, 100, 60, 9_999, 0);

        cache.registerDatasourcePrefetchWaitTimeout("ds-x", 1_000);
        cache.registerDatasourcePrefetchWaitTimeout("ds-x", 2_000); // replace

        // Exercise getIfReady to confirm the updated timeout is used without error
        DataSource ds = mockDataSource(1);
        String sql = "SELECT id FROM t LIMIT 5 OFFSET 0";
        cache.prefetchAsync(ds, "ds-x", sql, List.of());

        Optional<CachedPage> result = cache.getIfReady("ds-x", sql);
        assertTrue(result.isPresent());
    }
}
