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

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
        // SQL Server / Oracle: OFFSET … ROWS FETCH NEXT … ROWS ONLY
        "'SELECT id, name FROM users ORDER BY id OFFSET 30 ROWS FETCH NEXT 10 ROWS ONLY', 10, 30, 40",
        // FETCH FIRST … ROWS ONLY with explicit OFFSET 0
        "'SELECT * FROM items OFFSET 0 ROWS FETCH FIRST 50 ROWS ONLY',                   50,  0, 50",
        // MySQL shorthand: LIMIT offset, pageSize
        "'SELECT * FROM products LIMIT 20, 10',                                           10, 20, 30",
        // FETCH FIRST … ROWS ONLY without any OFFSET (first page)
        "'SELECT TOP_N.* FROM (SELECT * FROM t) TOP_N FETCH FIRST 10 ROWS ONLY',         10,  0, 10",
        // FETCH NEXT … ROWS ONLY without any OFFSET (first page)
        "'SELECT * FROM t FETCH NEXT 5 ROWS ONLY',                                         5,  0,  5",
        // Standalone LIMIT without OFFSET (first page)
        "'SELECT * FROM users WHERE active = 1 LIMIT 15',                                 15,  0, 15",
        // Case-insensitive matching
        "'select id from foo limit 5 offset 10',                                            5, 10, 15"
    })
    void detect_recognisesPaginationPatterns(String sql, long pageSize, long currentOffset, long nextOffset) {
        Optional<PageInfo> result = PaginationDetector.detect(sql);

        assertTrue(result.isPresent(), "Expected pagination to be detected in: " + sql);
        assertEquals(pageSize, result.get().getPageSize(), "Page size mismatch");
        assertEquals(currentOffset, result.get().getCurrentOffset(), "Current offset mismatch");
        assertEquals(nextOffset, result.get().getNextPageOffset(), "Next offset mismatch");
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
    // buildNextPageSql() – parameterised round-trip
    // ----------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
        // LIMIT n OFFSET m – first page (offset 0 → 10)
        "'SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 0',          'SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 10'",
        // LIMIT n OFFSET m – second page (offset 10 → 20)
        "'SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 10',         'SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 20'",
        // OFFSET FETCH (SQL Server / Oracle) – first page (offset 0 → 20)
        "'SELECT id FROM t ORDER BY id OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY',  'SELECT id FROM t ORDER BY id OFFSET 20 ROWS FETCH NEXT 20 ROWS ONLY'",
        // OFFSET FETCH (SQL Server / Oracle) – second page (offset 20 → 40)
        "'SELECT id FROM t ORDER BY id OFFSET 20 ROWS FETCH NEXT 20 ROWS ONLY', 'SELECT id FROM t ORDER BY id OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY'",
        // MySQL LIMIT offset, pageSize – first page (offset 0 → 10)
        "'SELECT * FROM products LIMIT 0, 10',                          'SELECT * FROM products LIMIT 10, 10'",
        // FETCH FIRST … ROWS ONLY without OFFSET – inserts OFFSET clause
        "'SELECT * FROM t FETCH FIRST 10 ROWS ONLY',                    'SELECT * FROM t OFFSET 10 ROWS FETCH FIRST 10 ROWS ONLY'",
        // Standalone LIMIT without OFFSET – appends OFFSET clause
        "'SELECT * FROM users LIMIT 5',                                 'SELECT * FROM users LIMIT 5 OFFSET 5'"
    })
    void buildNextPageSql_producesCorrectNextPageQuery(String sql, String expected) {
        PageInfo pageInfo = PaginationDetector.detect(sql).orElseThrow();
        assertEquals(expected, PaginationDetector.buildNextPageSql(sql, pageInfo));
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
