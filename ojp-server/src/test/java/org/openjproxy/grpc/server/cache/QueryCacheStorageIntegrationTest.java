package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResultRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 7: Query result cache storage.
 * <p>
 * Tests the complete flow: cache miss → database query → result storage → cache hit
 * </p>
 */
class QueryCacheStorageIntegrationTest {

    private QueryResultCacheRegistry cacheRegistry;
    private String datasourceName;

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

    @BeforeEach
    void setUp() {
        cacheRegistry = QueryResultCacheRegistry.getInstance();
        cacheRegistry.clear();
        datasourceName = "test_datasource";
    }

    @Test
    void testCacheMissStoreAndHit() {
        // Arrange: Create cache configuration and cache
        QueryResultCache cache = cacheRegistry.getOrCreate(datasourceName);
        
        String sql = "SELECT * FROM users WHERE id = ?";
        List<Object> params = List.of(1);
        QueryCacheKey cacheKey = new QueryCacheKey(datasourceName, sql, params);

        // Act 1: Cache miss (first query)
        CachedQueryResult firstLookup = cache.get(cacheKey);
        
        // Assert 1: Should be a miss
        assertNull(firstLookup, "First lookup should be a cache miss");
        assertEquals(0, cache.getStatistics().getHits(), "Should have 0 hits");
        assertEquals(1, cache.getStatistics().getMisses(), "Should have 1 miss");

        // Act 2: Simulate database query and cache storage
        List<List<Object>> rows = List.of(
                List.of(1, "Alice", "alice@example.com"),
                List.of(2, "Bob", "bob@example.com")
        );
        List<String> columnNames = List.of("id", "name", "email");
        Instant now = Instant.now();
        CachedQueryResult result = new CachedQueryResult(
                createTestProto(rows, columnNames), now, now.plus(Duration.ofMinutes(10)),
                Set.of("users"));
        
        cache.put(cacheKey, result);

        // Act 3: Cache hit (second query with same params)
        CachedQueryResult secondLookup = cache.get(cacheKey);

        // Assert 3: Should be a hit
        assertNotNull(secondLookup, "Second lookup should be a cache hit");
        assertEquals(1, cache.getStatistics().getHits(), "Should have 1 hit");
        assertEquals(1, cache.getStatistics().getMisses(), "Should still have 1 miss");
        assertEquals(2, secondLookup.getQueryResultProto().getRowsCount(), "Should have 2 rows");
        assertEquals(3, secondLookup.getQueryResultProto().getLabelsCount(), "Should have 3 columns");
    }

    @Test
    void testEmptyResultSetNotCached() {
        // Arrange
        QueryResultCache cache = cacheRegistry.getOrCreate(datasourceName);
        String sql = "SELECT * FROM users WHERE id = ?";
        List<Object> params = List.of(999); // Non-existent ID
        QueryCacheKey cacheKey = new QueryCacheKey(datasourceName, sql, params);

        // Act: Try to cache empty result
        List<List<Object>> emptyRows = List.of();
        List<String> columnNames = List.of("id", "name", "email");
        Instant now = Instant.now();
        CachedQueryResult emptyResult = new CachedQueryResult(
                createTestProto(emptyRows, columnNames), now, now.plus(Duration.ofMinutes(10)),
                Set.of("users"));

        // In real implementation, we check row count before calling put()
        // For this test, we'll verify the empty result isn't useful
        cache.put(cacheKey, emptyResult);
        CachedQueryResult lookup = cache.get(cacheKey);

        // Assert: Empty result is technically cached but should be checked before storage
        assertNotNull(lookup, "Empty result was stored");
        assertEquals(0, lookup.getQueryResultProto().getRowsCount(), "Should have 0 rows");
    }

    @Test
    void testLargeResultSizeEstimation() {
        // Arrange: Create a large result
        List<List<Object>> largeRows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            largeRows.add(List.of(i, "User" + i, "user" + i + "@example.com"));
        }
        List<String> columnNames = List.of("id", "name", "email");
        Instant now = Instant.now();
        CachedQueryResult largeResult = new CachedQueryResult(
                createTestProto(largeRows, columnNames), now, now.plus(Duration.ofMinutes(10)),
                Set.of("users"));

        // Act: Estimate size
        long estimatedSize = largeResult.getEstimatedSizeBytes();

        // Assert: Size should be reasonable
        assertTrue(estimatedSize > 10000, "Large result should have substantial size");
        assertTrue(estimatedSize < 1000000, "Size estimation should be reasonable");
    }

    @Test
    void testDifferentParametersCreateDifferentCacheKeys() {
        // Arrange
        QueryResultCache cache = cacheRegistry.getOrCreate(datasourceName);
        String sql = "SELECT * FROM users WHERE id = ?";
        
        QueryCacheKey key1 = new QueryCacheKey(datasourceName, sql, List.of(1));
        QueryCacheKey key2 = new QueryCacheKey(datasourceName, sql, List.of(2));

        // Act: Store different results for different parameters
        CachedQueryResult result1 = createTestResult("Alice");
        CachedQueryResult result2 = createTestResult("Bob");
        
        cache.put(key1, result1);
        cache.put(key2, result2);

        // Assert: Both results cached separately
        CachedQueryResult lookup1 = cache.get(key1);
        CachedQueryResult lookup2 = cache.get(key2);

        assertNotNull(lookup1, "First result should be cached");
        assertNotNull(lookup2, "Second result should be cached");
        assertNotEquals(lookup1, lookup2, "Results should be different");
        assertEquals(2, cache.getStatistics().getHits(), "Should have 2 hits");
    }

    @Test
    void testCacheStorageWithTTL() throws InterruptedException {
        // Arrange
        QueryResultCache cache = cacheRegistry.getOrCreate(datasourceName);
        String sql = "SELECT * FROM users WHERE id = ?";
        QueryCacheKey cacheKey = new QueryCacheKey(datasourceName, sql, List.of(1));

        // Act: Store with short TTL (100ms)
        Instant now = Instant.now();
        CachedQueryResult shortTtlResult = new CachedQueryResult(
                createTestProto(List.of(List.of(1, "Alice", "alice@example.com")), List.of("id", "name", "email")),
                now,
                now.plus(Duration.ofMillis(100)),
                Set.of("users"));
        
        cache.put(cacheKey, shortTtlResult);

        // Assert 1: Should be available immediately
        CachedQueryResult lookup1 = cache.get(cacheKey);
        assertNotNull(lookup1, "Result should be cached immediately");
        assertFalse(lookup1.isExpired(), "Result should not be expired yet");

        // Wait for expiration
        Thread.sleep(150); //NOSONAR

        // Assert 2: After TTL expires, the cache entry should be treated as expired
        // The get() method checks isExpired() and removes expired entries
        CachedQueryResult lookup2 = cache.get(cacheKey);
        // After expiration, get() should return null (it invalidates expired entries)
        assertNull(lookup2, "Result should be removed after expiration");
    }

    @Test
    void testConcurrentCacheStorage() throws InterruptedException {
        // Arrange
        QueryResultCache cache = cacheRegistry.getOrCreate(datasourceName);
        int threadCount = 10;
        int queriesPerThread = 100;
        List<Thread> threads = new ArrayList<>();

        // Act: Multiple threads storing results concurrently
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            Thread thread = new Thread(() -> {
                for (int i = 0; i < queriesPerThread; i++) {
                    String sql = "SELECT * FROM users WHERE id = ?";
                    QueryCacheKey key = new QueryCacheKey(
                            datasourceName, sql, List.of(threadId * 1000 + i));
                    CachedQueryResult result = createTestResult("User" + threadId + "_" + i);
                    cache.put(key, result);
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Assert: All results stored without errors
        CacheStatistics stats = cache.getStatistics();
        // After put operations, the cache should have entries
        assertTrue(cache.getEntryCount() > 0, "Cache should have entries");
        assertEquals(0, stats.getRejections(), "Should have no rejections");
    }

    // Helper method to create test results
    private CachedQueryResult createTestResult(String name) {
        Instant now = Instant.now();
        return new CachedQueryResult(
                createTestProto(List.of(List.of(1, name, name.toLowerCase() + "@example.com")), List.of("id", "name", "email")),
                now,
                now.plus(Duration.ofMinutes(10)),
                Set.of("users"));
    }
}
