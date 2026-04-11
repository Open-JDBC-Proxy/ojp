package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResultRow;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for QueryResultCache implementation.
 */
class QueryResultCacheTest {
    
    private QueryResultCache cache;
    
    @BeforeEach
    void setUp() {
        cache = new QueryResultCache(1000, Duration.ofMinutes(10), 10 * 1024 * 1024);
    }
    
    /**
     * Helper method to create a test proto from rows and columns
     */
    private static OpQueryResultProto createTestProto(List<List<Object>> rows, List<String> columns) {
        OpQueryResultProto.Builder builder = OpQueryResultProto.newBuilder();
        
        // Add column labels
        for (String column : columns) {
            builder.addLabels(column);
        }
        
        // Add rows
        for (List<Object> row : rows) {
            ResultRow.Builder rowBuilder = ResultRow.newBuilder();
            for (Object value : row) {
                String stringValue = value != null ? value.toString() : "";
                rowBuilder.addColumns(ParameterValue.newBuilder()
                        .setStringValue(stringValue)
                        .build());
            }
            builder.addRows(rowBuilder.build());
        }
        
        return builder.build();
    }
    
    @Test
    void testPutAndGet() {
        QueryCacheKey key = new QueryCacheKey("testdb", "SELECT * FROM users", List.of());
        CachedQueryResult result = createTestResult(List.of("id", "name"), Set.of("users"));
        
        cache.put(key, result);
        CachedQueryResult retrieved = cache.get(key);
        
        assertNotNull(retrieved);
        assertEquals(result, retrieved);
        assertEquals(1, cache.getStatistics().getHits());
        assertEquals(0, cache.getStatistics().getMisses());
    }
    
    @Test
    void testCacheMiss() {
        QueryCacheKey key = new QueryCacheKey("testdb", "SELECT * FROM users", List.of());
        
        CachedQueryResult retrieved = cache.get(key);
        
        assertNull(retrieved);
        assertEquals(0, cache.getStatistics().getHits());
        assertEquals(1, cache.getStatistics().getMisses());
    }
    
    @Test
    void testExpiredEntry() throws InterruptedException {
        // Create cache with 1 second TTL
        QueryResultCache shortCache = new QueryResultCache(1000, Duration.ofSeconds(1), 10 * 1024 * 1024);
        
        QueryCacheKey key = new QueryCacheKey("testdb", "SELECT * FROM users", List.of());
        CachedQueryResult result = createTestResult(List.of("id"), Set.of("users"));
        
        shortCache.put(key, result);
        
        // Wait for expiration
        Thread.sleep(1500);
        
        CachedQueryResult retrieved = shortCache.get(key);
        assertNull(retrieved);
        assertEquals(1, shortCache.getStatistics().getMisses());
    }
    
    @Test
    void testInvalidateByTable() {
        QueryCacheKey key1 = new QueryCacheKey("testdb", "SELECT * FROM users", List.of());
        QueryCacheKey key2 = new QueryCacheKey("testdb", "SELECT * FROM products", List.of());
        QueryCacheKey key3 = new QueryCacheKey("testdb", "SELECT * FROM orders", List.of());
        
        CachedQueryResult result1 = createTestResult(List.of("id"), Set.of("users"));
        CachedQueryResult result2 = createTestResult(List.of("id"), Set.of("products"));
        CachedQueryResult result3 = createTestResult(List.of("id"), Set.of("orders"));
        
        cache.put(key1, result1);
        cache.put(key2, result2);
        cache.put(key3, result3);
        
        // Invalidate users table
        cache.invalidate("testdb", Set.of("users"));
        
        assertNull(cache.get(key1)); // Should be invalidated
        assertNotNull(cache.get(key2)); // Should still be cached
        assertNotNull(cache.get(key3)); // Should still be cached
        
        assertEquals(1, cache.getStatistics().getInvalidations());
    }
    
    @Test
    void testInvalidateMultipleTables() {
        QueryCacheKey key1 = new QueryCacheKey("testdb", "SELECT * FROM users", List.of());
        QueryCacheKey key2 = new QueryCacheKey("testdb", "SELECT * FROM products", List.of());
        
        CachedQueryResult result1 = createTestResult(List.of("id"), Set.of("users"));
        CachedQueryResult result2 = createTestResult(List.of("id"), Set.of("products"));
        
        cache.put(key1, result1);
        cache.put(key2, result2);
        
        // Invalidate both tables
        cache.invalidate("testdb", Set.of("users", "products"));
        
        assertNull(cache.get(key1));
        assertNull(cache.get(key2));
        
        assertEquals(2, cache.getStatistics().getInvalidations());
    }
    
    @Test
    void testInvalidateDifferentDatasource() {
        QueryCacheKey key1 = new QueryCacheKey("db1", "SELECT * FROM users", List.of());
        QueryCacheKey key2 = new QueryCacheKey("db2", "SELECT * FROM users", List.of());
        
        CachedQueryResult result = createTestResult(List.of("id"), Set.of("users"));
        
        cache.put(key1, result);
        cache.put(key2, result);
        
        // Invalidate only db1
        cache.invalidate("db1", Set.of("users"));
        
        assertNull(cache.get(key1)); // Should be invalidated
        assertNotNull(cache.get(key2)); // Should NOT be invalidated (different datasource)
    }
    
    @SneakyThrows
    @Test
    void testInvalidateAll() {
        QueryCacheKey key1 = new QueryCacheKey("testdb", "SELECT 1", List.of());
        QueryCacheKey key2 = new QueryCacheKey("testdb", "SELECT 2", List.of());
        
        CachedQueryResult result = createTestResult(List.of("col"), Set.of("table1"));
        
        cache.put(key1, result);
        cache.put(key2, result);
        
        cache.invalidateAll();
        Thread.sleep(500);//NOSONAR

        assertNull(cache.get(key1));
        assertNull(cache.get(key2));
        assertEquals(0, cache.getEntryCount());
        assertEquals(0, cache.getCurrentSizeBytes());
        // Note: After invalidateAll, the stats are not reset, so invalidations and misses accumulate
    }
    
    @Test
    void testInvalidateDatasource() {
        QueryCacheKey key1 = new QueryCacheKey("db1", "SELECT 1", List.of());
        QueryCacheKey key2 = new QueryCacheKey("db2", "SELECT 1", List.of());
        
        CachedQueryResult result = createTestResult(List.of("col"), Set.of("table1"));
        
        cache.put(key1, result);
        cache.put(key2, result);
        
        cache.invalidateDatasource("db1");
        
        assertNull(cache.get(key1));
        assertNotNull(cache.get(key2));
    }
    
    @SneakyThrows
    @Test
    void testSizeTracking() {
        QueryCacheKey key = new QueryCacheKey("testdb", "SELECT * FROM users", List.of());
        CachedQueryResult result = createTestResult(List.of("id", "name"), Set.of("users"));
        
        long initialSize = cache.getCurrentSizeBytes();
        cache.put(key, result);
        long afterPutSize = cache.getCurrentSizeBytes();
        
        assertTrue(afterPutSize > initialSize);
        
        cache.invalidateAll();
        Thread.sleep(500);//NOSONAR
        assertEquals(0, cache.getCurrentSizeBytes());
    }
    
    @Test
    void testMaxSizeRejection() {
        // Create cache with very small size limit (100 bytes)
        QueryResultCache smallCache = new QueryResultCache(1000, Duration.ofMinutes(10), 100);
        
        QueryCacheKey key = new QueryCacheKey("testdb", "SELECT * FROM users", List.of());
        
        // Create large result that exceeds size limit
        List<List<Object>> largeData = List.of(
            List.of(1, "test".repeat(100)),
            List.of(2, "test".repeat(100)),
            List.of(3, "test".repeat(100))
        );
        CachedQueryResult result = new CachedQueryResult(
            createTestProto(largeData, List.of("id", "name")),
            Instant.now(), Instant.now().plusSeconds(600), Set.of("users")
        );
        
        // Put the large result
        smallCache.put(key, result);
        
        // The result should be rejected due to size, so get() should return null
        assertNull(smallCache.get(key), "Large result should be rejected");
        assertTrue(smallCache.getStatistics().getRejections() > 0, "Should have rejections recorded");
    }
    
    @Test
    void testHitRateCalculation() {
        QueryCacheKey key = new QueryCacheKey("testdb", "SELECT * FROM users", List.of());
        CachedQueryResult result = createTestResult(List.of("id"), Set.of("users"));
        
        cache.put(key, result);
        
        // 3 hits, 2 misses = 60% hit rate
        cache.get(key); // hit
        cache.get(key); // hit
        cache.get(key); // hit
        cache.get(new QueryCacheKey("testdb", "SELECT * FROM products", List.of())); // miss
        cache.get(new QueryCacheKey("testdb", "SELECT * FROM orders", List.of())); // miss
        
        assertEquals(0.6, cache.getStatistics().getHitRate(), 0.01);
        assertEquals(3, cache.getStatistics().getHits());
        assertEquals(2, cache.getStatistics().getMisses());
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        QueryCacheKey key = new QueryCacheKey(
                            "testdb",
                            "SELECT * FROM users WHERE id = " + (threadId * operationsPerThread + j),
                            List.of(threadId, j)
                        );
                        CachedQueryResult result = createTestResult(List.of("id"), Set.of("users"));
                        
                        cache.put(key, result);
                        cache.get(key);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        // Verify no exceptions occurred and operations completed
        long totalOps = threadCount * operationsPerThread;
        assertTrue(cache.getStatistics().getHits() > 0);
        assertTrue(cache.getEntryCount() > 0);
    }
    
    @Test
    void testStatisticsReset() {
        QueryCacheKey key = new QueryCacheKey("testdb", "SELECT * FROM users", List.of());
        CachedQueryResult result = createTestResult(List.of("id"), Set.of("users"));
        
        cache.put(key, result);
        cache.get(key); // hit
        cache.get(new QueryCacheKey("testdb", "SELECT * FROM products", List.of())); // miss
        
        CacheStatistics stats = cache.getStatistics();
        assertTrue(stats.getHits() > 0);
        assertTrue(stats.getMisses() > 0);
        
        stats.reset();
        
        assertEquals(0, stats.getHits());
        assertEquals(0, stats.getMisses());
        assertEquals(0, stats.getEvictions());
        assertEquals(0, stats.getInvalidations());
    }
    
    @Test
    void testCleanUp() {
        QueryCacheKey key = new QueryCacheKey("testdb", "SELECT * FROM users", List.of());
        CachedQueryResult result = createTestResult(List.of("id"), Set.of("users"));
        
        cache.put(key, result);
        cache.cleanUp();
        
        // Should still be present (not expired)
        assertNotNull(cache.get(key));
    }
    
    @Test
    void testEntryCount() {
        assertEquals(0, cache.getEntryCount());
        
        cache.put(new QueryCacheKey("db", "SELECT 1", List.of()), createTestResult(List.of("col"), Set.of("t1")));
        assertEquals(1, cache.getEntryCount());
        
        cache.put(new QueryCacheKey("db", "SELECT 2", List.of()), createTestResult(List.of("col"), Set.of("t2")));
        assertEquals(2, cache.getEntryCount());
        
        cache.invalidateAll();
        assertEquals(0, cache.getEntryCount());
    }
    
    // Helper method to create test results
    private CachedQueryResult createTestResult(List<String> columnNames, Set<String> affectedTables) {
        List<List<Object>> rows = List.of(
            List.of(1, "test"),
            List.of(2, "test2")
        );
        
        return new CachedQueryResult(
            createTestProto(rows, columnNames),
            Instant.now(),
            Instant.now().plusSeconds(600),
            affectedTables
        );
    }
}
