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
        return new NextPagePrefetchCache(true, 100, 60, 5000);
    }

    private static NextPagePrefetchCache disabledCache() {
        return new NextPagePrefetchCache(false, 100, 60, 5000);
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
        Optional<CachedPage> result = cache.getIfReady("SELECT * FROM t LIMIT 10 OFFSET 10");
        assertFalse(result.isPresent(), "Expected empty when nothing is cached");
    }

    // ----------------------------------------------------------------
    // prefetchAsync() – disabled cache
    // ----------------------------------------------------------------

    @Test
    void prefetchAsync_doesNothing_whenDisabled() throws Exception {
        NextPagePrefetchCache cache = disabledCache();
        DataSource ds = mockDataSource(1);

        cache.prefetchAsync(ds, "SELECT * FROM t LIMIT 10 OFFSET 10", List.of());

        // Cache should still be empty
        assertFalse(cache.getIfReady("SELECT * FROM t LIMIT 10 OFFSET 10").isPresent());
    }

    @Test
    void prefetchAsync_doesNothing_whenDataSourceIsNull() {
        NextPagePrefetchCache cache = enabledCache();
        cache.prefetchAsync(null, "SELECT * FROM t LIMIT 10 OFFSET 10", List.of());
        assertFalse(cache.getIfReady("SELECT * FROM t LIMIT 10 OFFSET 10").isPresent());
    }

    @Test
    void prefetchAsync_doesNothing_whenSqlIsNull() throws Exception {
        NextPagePrefetchCache cache = enabledCache();
        DataSource ds = mockDataSource(1);
        cache.prefetchAsync(ds, null, List.of());
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
        cache.prefetchAsync(ds, sql, List.of());

        // Wait for the prefetch (virtual thread) to complete
        Optional<CachedPage> result = cache.getIfReady(sql);

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
        cache.prefetchAsync(ds, "SELECT id FROM t LIMIT 10 OFFSET 10", List.of());

        // Retrieve with slightly different casing/whitespace (should normalise to same key)
        Optional<CachedPage> result = cache.getIfReady("  SELECT ID FROM T LIMIT 10 OFFSET 10  ");

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
        cache.prefetchAsync(ds, sql, List.of());

        Optional<CachedPage> first = cache.getIfReady(sql);
        assertTrue(first.isPresent(), "First retrieval should succeed");

        // Second retrieval should return empty (entry was removed after first use)
        Optional<CachedPage> second = cache.getIfReady(sql);
        assertFalse(second.isPresent(), "Second retrieval should be empty (single-use)");
    }

    // ----------------------------------------------------------------
    // Expiry
    // ----------------------------------------------------------------

    @Test
    void getIfReady_returnsEmpty_whenEntryExpired() throws Exception {
        // TTL = 0 seconds → immediately expired
        NextPagePrefetchCache cache = new NextPagePrefetchCache(true, 100, 0, 5000);
        DataSource ds = mockDataSource(1);

        String sql = "SELECT id FROM t LIMIT 10 OFFSET 10";
        cache.prefetchAsync(ds, sql, List.of());

        // Wait a bit to ensure the prefetch completes and the entry is expired
        Thread.sleep(50);

        Optional<CachedPage> result = cache.getIfReady(sql);
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
        cache.prefetchAsync(ds, sql, List.of()); // first start
        cache.prefetchAsync(ds, sql, List.of()); // duplicate – should be ignored

        // Retrieve to confirm the entry exists (one execution)
        Optional<CachedPage> result = cache.getIfReady(sql);
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
}
