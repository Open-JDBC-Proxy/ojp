package org.openjproxy.grpc.server.paging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PaginationDetector}.
 */
class PaginationDetectorTest {

    // ----------------------------------------------------------------
    // detect() – positive cases
    // ----------------------------------------------------------------

    @Test
    void detectLimitOffset_returnsCorrectPageInfo() {
        String sql = "SELECT id, name FROM users ORDER BY id LIMIT 10 OFFSET 20";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertTrue(result.isPresent(), "Expected pagination to be detected");
        assertEquals(20, result.get().getCurrentOffset());
        assertEquals(10, result.get().getPageSize());
        assertEquals(30, result.get().getNextPageOffset());
    }

    @Test
    void detectLimitOffset_firstPage() {
        String sql = "SELECT * FROM orders LIMIT 25 OFFSET 0";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertTrue(result.isPresent());
        assertEquals(0, result.get().getCurrentOffset());
        assertEquals(25, result.get().getPageSize());
        assertTrue(result.get().isFirstPage());
    }

    @Test
    void detectOffsetFetch_sqlServer() {
        String sql = "SELECT id, name FROM users ORDER BY id OFFSET 30 ROWS FETCH NEXT 10 ROWS ONLY";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertTrue(result.isPresent());
        assertEquals(30, result.get().getCurrentOffset());
        assertEquals(10, result.get().getPageSize());
    }

    @Test
    void detectOffsetFetch_fetchFirst() {
        String sql = "SELECT * FROM items OFFSET 0 ROWS FETCH FIRST 50 ROWS ONLY";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertTrue(result.isPresent());
        assertEquals(0, result.get().getCurrentOffset());
        assertEquals(50, result.get().getPageSize());
    }

    @Test
    void detectLimitComma_mysqlShorthand() {
        // MySQL: LIMIT offset, pageSize (first arg = rows to skip, second = rows to return)
        String sql = "SELECT * FROM products LIMIT 20, 10";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertTrue(result.isPresent());
        assertEquals(20, result.get().getCurrentOffset());
        assertEquals(10, result.get().getPageSize());
    }

    @Test
    void detectFetchOnly_noOffset_firstPage() {
        String sql = "SELECT TOP_N.* FROM (SELECT * FROM t) TOP_N FETCH FIRST 10 ROWS ONLY";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertTrue(result.isPresent());
        assertEquals(0, result.get().getCurrentOffset());
        assertEquals(10, result.get().getPageSize());
        assertTrue(result.get().isFirstPage());
    }

    @Test
    void detectFetchNextOnly_noOffset_firstPage() {
        String sql = "SELECT * FROM t FETCH NEXT 5 ROWS ONLY";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertTrue(result.isPresent());
        assertEquals(0, result.get().getCurrentOffset());
        assertEquals(5, result.get().getPageSize());
    }

    @Test
    void detectLimitOnly_noOffset_firstPage() {
        String sql = "SELECT * FROM users WHERE active = 1 LIMIT 15";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertTrue(result.isPresent());
        assertEquals(0, result.get().getCurrentOffset());
        assertEquals(15, result.get().getPageSize());
    }

    @Test
    void detectLimitOffset_caseInsensitive() {
        String sql = "select id from foo limit 5 offset 10";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertTrue(result.isPresent());
        assertEquals(10, result.get().getCurrentOffset());
        assertEquals(5, result.get().getPageSize());
    }

    // ----------------------------------------------------------------
    // detect() – negative cases
    // ----------------------------------------------------------------

    @Test
    void detect_returnsEmpty_forNonPaginatedQuery() {
        String sql = "SELECT id, name FROM users WHERE id = 1";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertFalse(result.isPresent(), "Expected no pagination");
    }

    @Test
    void detect_returnsEmpty_forNullSql() {
        assertFalse(PaginationDetector.detect(null).isPresent());
    }

    @Test
    void detect_returnsEmpty_forBlankSql() {
        assertFalse(PaginationDetector.detect("   ").isPresent());
    }

    @Test
    void detect_limitOnly_notMatchedWhenOffsetPresent() {
        // LIMIT n with an OFFSET keyword somewhere else – should not match Pattern 5
        String sql = "SELECT * FROM t WHERE col > 0 LIMIT 10 OFFSET 5";
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        // Pattern 1 (LIMIT n OFFSET m) should match instead
        assertTrue(result.isPresent());
        assertEquals(5, result.get().getCurrentOffset());
        assertEquals(10, result.get().getPageSize());
    }

    // ----------------------------------------------------------------
    // buildNextPageSql() – LIMIT / OFFSET
    // ----------------------------------------------------------------

    @Test
    void buildNextPage_limitOffset_incrementsOffset() {
        String sql = "SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 0";
        PageInfo pageInfo = PaginationDetector.detect(sql).orElseThrow();

        String nextPage = PaginationDetector.buildNextPageSql(sql, pageInfo);

        assertEquals("SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 10", nextPage);
    }

    @Test
    void buildNextPage_limitOffset_secondPage_givesThirdPageSql() {
        String sql = "SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 10";
        PageInfo pageInfo = PaginationDetector.detect(sql).orElseThrow();

        String nextPage = PaginationDetector.buildNextPageSql(sql, pageInfo);

        assertEquals("SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 20", nextPage);
    }

    // ----------------------------------------------------------------
    // buildNextPageSql() – OFFSET FETCH (SQL Server / Oracle)
    // ----------------------------------------------------------------

    @Test
    void buildNextPage_offsetFetch_incrementsOffset() {
        String sql = "SELECT id FROM t ORDER BY id OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY";
        PageInfo pageInfo = PaginationDetector.detect(sql).orElseThrow();

        String nextPage = PaginationDetector.buildNextPageSql(sql, pageInfo);

        assertEquals("SELECT id FROM t ORDER BY id OFFSET 20 ROWS FETCH NEXT 20 ROWS ONLY", nextPage);
    }

    @Test
    void buildNextPage_offsetFetch_secondPage() {
        String sql = "SELECT id FROM t ORDER BY id OFFSET 20 ROWS FETCH NEXT 20 ROWS ONLY";
        PageInfo pageInfo = PaginationDetector.detect(sql).orElseThrow();

        String nextPage = PaginationDetector.buildNextPageSql(sql, pageInfo);

        assertEquals("SELECT id FROM t ORDER BY id OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY", nextPage);
    }

    // ----------------------------------------------------------------
    // buildNextPageSql() – MySQL LIMIT m, n
    // ----------------------------------------------------------------

    @Test
    void buildNextPage_limitComma_incrementsOffset() {
        // MySQL LIMIT 0, 10: offset=0, pageSize=10 → next: offset=10
        String sql = "SELECT * FROM products LIMIT 0, 10";
        PageInfo pageInfo = PaginationDetector.detect(sql).orElseThrow();

        String nextPage = PaginationDetector.buildNextPageSql(sql, pageInfo);

        assertEquals("SELECT * FROM products LIMIT 10, 10", nextPage);
    }

    // ----------------------------------------------------------------
    // buildNextPageSql() – FETCH ONLY (first-page, no OFFSET)
    // ----------------------------------------------------------------

    @Test
    void buildNextPage_fetchOnly_insertsOffset() {
        String sql = "SELECT * FROM t FETCH FIRST 10 ROWS ONLY";
        PageInfo pageInfo = PaginationDetector.detect(sql).orElseThrow();

        String nextPage = PaginationDetector.buildNextPageSql(sql, pageInfo);

        assertEquals("SELECT * FROM t OFFSET 10 ROWS FETCH FIRST 10 ROWS ONLY", nextPage);
    }

    // ----------------------------------------------------------------
    // buildNextPageSql() – standalone LIMIT (first-page, no OFFSET)
    // ----------------------------------------------------------------

    @Test
    void buildNextPage_limitOnly_appendsOffset() {
        String sql = "SELECT * FROM users LIMIT 5";
        PageInfo pageInfo = PaginationDetector.detect(sql).orElseThrow();

        String nextPage = PaginationDetector.buildNextPageSql(sql, pageInfo);

        assertEquals("SELECT * FROM users LIMIT 5 OFFSET 5", nextPage);
    }

    // ----------------------------------------------------------------
    // buildNextPageSql() – edge cases
    // ----------------------------------------------------------------

    @Test
    void buildNextPage_returnsNull_forNullSql() {
        assertNull(PaginationDetector.buildNextPageSql(null, new PageInfo(0, 10)));
    }

    @Test
    void buildNextPage_returnsNull_forNullPageInfo() {
        assertNull(PaginationDetector.buildNextPageSql("SELECT 1", null));
    }

    // ----------------------------------------------------------------
    // Parameterised – detect then build round-trip
    // ----------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
        "'SELECT a FROM t LIMIT 10 OFFSET 0',  10, 0,  10",
        "'SELECT a FROM t LIMIT 10 OFFSET 10', 10, 10, 20",
        // MySQL LIMIT m,n: first arg = offset, second arg = page-size
        "'SELECT a FROM t LIMIT 5, 20',         20, 5,  25",
        "'SELECT a FROM t OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY', 10, 0, 10",
        "'SELECT a FROM t OFFSET 10 ROWS FETCH FIRST 10 ROWS ONLY', 10, 10, 20"
    })
    void detectAndNextOffset(String sql, long pageSize, long currentOffset, long expectedNextOffset) {
        Optional<PageInfo> pageInfo = PaginationDetector.detect(sql);

        assertTrue(pageInfo.isPresent(), "Expected pagination in: " + sql);
        assertEquals(pageSize, pageInfo.get().getPageSize(), "Page size mismatch");
        assertEquals(currentOffset, pageInfo.get().getCurrentOffset(), "Current offset mismatch");
        assertEquals(expectedNextOffset, pageInfo.get().getNextPageOffset(), "Next offset mismatch");
    }
}
