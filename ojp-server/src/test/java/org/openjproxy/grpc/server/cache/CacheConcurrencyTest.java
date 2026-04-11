package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResultRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for cache implementation.
 * Tests thread safety and race condition detection.
 */
public class CacheConcurrencyTest {

    private QueryResultCache cache;
    private final String datasourceName = "testds";
    private static final String TEST_UUID = "test-uuid";

    @BeforeEach
    public void setUp() {
        cache = new QueryResultCache(
            datasourceName,
            1000,  // maxEntries
            Duration.ofMinutes(10),  // maxAge
            10 * 1024 * 1024,  // maxSizeBytes (10MB)
            NoOpQueryCacheMetrics.getInstance()
        );
    }

    /**
     * Helper method to create a test proto from rows and columns
     */
    private OpQueryResultProto createTestProto(List<List<Object>> rows, List<String> columns) {
        OpQueryResultProto.Builder builder = OpQueryResultProto.newBuilder();
        builder.setResultSetUUID(TEST_UUID);
        
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
    public void testConcurrentReads() throws Exception {
        // Pre-populate cache
        QueryCacheKey key = new QueryCacheKey(datasourceName, "SELECT * FROM products", Collections.emptyList());
        CachedQueryResult result = new CachedQueryResult(
            createTestProto(new ArrayList<>(), new ArrayList<>()),
            Instant.now(),
            Instant.now().plus(Duration.ofMinutes(10)),
            Set.of()
        );
        cache.put(key, result);

        int threadCount = 20;
        int readsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < readsPerThread; j++) {
                        CachedQueryResult retrieved = cache.get(key);
                        if (retrieved != null) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Threads should complete within timeout");
        executor.shutdown();

        assertEquals(threadCount * readsPerThread, successCount.get(), 
            "All reads should succeed");
    }

    @Test
    public void testConcurrentWrites() throws Exception {
        int threadCount = 20;
        int writesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < writesPerThread; j++) {
                        QueryCacheKey key = new QueryCacheKey(
                            datasourceName,
                            "SELECT * FROM products WHERE id = " + (threadId * writesPerThread + j),
                            Collections.emptyList()
                        );
                        CachedQueryResult result = new CachedQueryResult(
                            createTestProto(new ArrayList<>(), new ArrayList<>()),
                            Instant.now(),
                            Instant.now().plus(Duration.ofMinutes(10)),
                            Set.of()
                        );
                        cache.put(key, result);
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Threads should complete within timeout");
        executor.shutdown();

        assertEquals(threadCount * writesPerThread, successCount.get(), 
            "All writes should succeed");
        
        // Cache should be consistent
        assertTrue(cache.getEntryCount() > 0, "Cache should have entries");
        assertTrue(cache.getCurrentSizeBytes() > 0, "Cache should have size");
    }

    @Test
    public void testConcurrentReadWrite() throws Exception {
        int readerThreads = 10;
        int writerThreads = 10;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(readerThreads + writerThreads);
        CountDownLatch latch = new CountDownLatch(readerThreads + writerThreads);

        // Start readers
        for (int i = 0; i < readerThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        QueryCacheKey key = new QueryCacheKey(
                            datasourceName,
                            "SELECT * FROM products WHERE id = " + (j % 100),
                            Collections.emptyList()
                        );
                        cache.get(key);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Start writers
        for (int i = 0; i < writerThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        QueryCacheKey key = new QueryCacheKey(
                            datasourceName,
                            "SELECT * FROM products WHERE id = " + (j % 100),
                            Collections.emptyList()
                        );
                        CachedQueryResult result = new CachedQueryResult(
                            createTestProto(new ArrayList<>(), new ArrayList<>()),
                            Instant.now(),
                            Instant.now().plus(Duration.ofMinutes(10)),
                            Set.of()
                        );
                        cache.put(key, result);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();

        // Verify cache is still consistent
        assertTrue(cache.getEntryCount() >= 0);
        assertTrue(cache.getCurrentSizeBytes() >= 0);
    }

    @Test
    public void testConcurrentInvalidations() throws Exception {
        // Pre-populate cache with 100 entries
        for (int i = 0; i < 100; i++) {
            QueryCacheKey key = new QueryCacheKey(
                datasourceName,
                "SELECT * FROM products WHERE id = " + i,
                Collections.emptyList()
            );
            CachedQueryResult result = new CachedQueryResult(
                createTestProto(new ArrayList<>(), new ArrayList<>()),
                Instant.now(),
                Instant.now().plus(Duration.ofMinutes(10)),
                Set.of("products")  // Add affected table so invalidation works
            );
            cache.put(key, result);
        }

        long initialCount = cache.getEntryCount();
        assertTrue(initialCount > 0, "Cache should have entries");

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 10; j++) {
                        cache.invalidate(datasourceName, Set.of("products"));
                        Thread.sleep(1); // Small delay
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // All entries should be invalidated
        assertEquals(0, cache.getEntryCount(), "Cache should be empty after invalidations");
    }

    @Test
    public void testConcurrentMixedOperations() throws Exception {
        int threadCount = 20;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong totalOperations = new AtomicLong(0);

        Random random = new Random();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int operation = random.nextInt(4);
                        QueryCacheKey key = new QueryCacheKey(
                            datasourceName,
                            "SELECT * FROM products WHERE id = " + (j % 50),
                            Collections.emptyList()
                        );

                        switch (operation) {
                            case 0: // Read
                                cache.get(key);
                                break;
                            case 1: // Write
                                CachedQueryResult result = new CachedQueryResult(
                                    createTestProto(new ArrayList<>(), new ArrayList<>()),
                                    Instant.now(),
                                    Instant.now().plus(Duration.ofMinutes(10)),
                                    Set.of()
                                );
                                cache.put(key, result);
                                break;
                            case 2: // Invalidate
                                cache.invalidate(datasourceName, Set.of("products"));
                                break;
                            case 3: // Get stats
                                CacheStatistics stats = cache.getStatistics();
                                assertNotNull(stats);
                                break;
                        }
                        totalOperations.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount * operationsPerThread, totalOperations.get(),
            "All operations should complete");

        // Cache should still be consistent
        assertTrue(cache.getEntryCount() >= 0);
        assertTrue(cache.getCurrentSizeBytes() >= 0);
        
        CacheStatistics stats = cache.getStatistics();
        assertNotNull(stats);
        assertTrue(stats.getHits() + stats.getMisses() > 0, "Should have some hits or misses");
    }

    @Test
    public void testConcurrentStatisticsAccess() throws Exception {
        int threadCount = 20;
        int accessPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Perform operations while reading statistics
        for (int i = 0; i < threadCount / 2; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < accessPerThread; j++) {
                        QueryCacheKey key = new QueryCacheKey(
                            datasourceName,
                            "SELECT " + j,
                            Collections.emptyList()
                        );
                        cache.get(key);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Read statistics concurrently
        for (int i = 0; i < threadCount / 2; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < accessPerThread; j++) {
                        CacheStatistics stats = cache.getStatistics();
                        assertNotNull(stats);
                        assertTrue(stats.getHits() >= 0);
                        assertTrue(stats.getMisses() >= 0);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // Final statistics should be consistent
        CacheStatistics finalStats = cache.getStatistics();
        assertEquals(accessPerThread * (threadCount / 2), finalStats.getMisses(),
            "All reads should be misses (no data in cache)");
    }

    @Test
    public void testNoRaceConditionsInSizeTracking() throws Exception {
        int threadCount = 20;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        QueryCacheKey key = new QueryCacheKey(
                            datasourceName,
                            "SELECT " + (threadId * operationsPerThread + j),
                            Collections.emptyList()
                        );
                        CachedQueryResult result = new CachedQueryResult(
                            createTestProto(
                                List.of(List.of("value" + j)),
                                List.of("column1")
                            ),
                            Instant.now(),
                            Instant.now().plus(Duration.ofMinutes(10)),
                            Set.of()
                        );
                        cache.put(key, result);
                        
                        // Verify size tracking is consistent
                        long count = cache.getEntryCount();
                        long size = cache.getCurrentSizeBytes();
                        assertTrue(count >= 0, "Entry count should not be negative");
                        assertTrue(size >= 0, "Size should not be negative");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // Final verification
        long finalCount = cache.getEntryCount();
        long finalSize = cache.getCurrentSizeBytes();
        
        assertTrue(finalCount > 0, "Should have entries");
        assertTrue(finalSize > 0, "Should have size");
        assertTrue(finalCount <= threadCount * operationsPerThread, 
            "Count should not exceed total puts (with evictions)");
    }

    @Test
    public void testConcurrentRegistryAccess() throws Exception {
        QueryResultCacheRegistry registry = QueryResultCacheRegistry.getInstance();
        
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        String datasource = "testds_" + (threadId % 5);
                        QueryResultCache cache = registry.get(datasource);
                        assertNotNull(cache, "Cache should be created");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // Registry should have created 5 caches
        // Verification would require exposing registry state or using different datasources
    }

    @Test
    public void testStressTest() throws Exception {
        int threadCount = 50;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong errors = new AtomicLong(0);

        Random random = new Random();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        try {
                            int operation = random.nextInt(5);
                            QueryCacheKey key = new QueryCacheKey(
                                datasourceName,
                                "SELECT " + (j % 100),
                                Collections.emptyList()
                            );

                            switch (operation) {
                                case 0:
                                case 1: // Reads (more frequent)
                                    cache.get(key);
                                    break;
                                case 2: // Write
                                    cache.put(key, new CachedQueryResult(
                                        createTestProto(new ArrayList<>(), new ArrayList<>()),
                                        Instant.now(),
                                        Instant.now().plus(Duration.ofMinutes(10)),
                                        Set.of()
                                    ));
                                    break;
                                case 3: // Invalidate
                                    cache.invalidate(datasourceName, Set.of("products"));
                                    break;
                                case 4: // Stats
                                    cache.getStatistics();
                                    break;
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "Stress test should complete");
        executor.shutdown();

        assertEquals(0, errors.get(), "No errors should occur during stress test");
        
        // Cache should still be functional
        CacheStatistics stats = cache.getStatistics();
        assertNotNull(stats);
        assertTrue(stats.getHits() + stats.getMisses() > 0);
    }
}
