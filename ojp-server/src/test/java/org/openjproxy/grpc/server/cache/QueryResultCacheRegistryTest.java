package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryResultCacheRegistry implementation.
 */
class QueryResultCacheRegistryTest {
    
    private QueryResultCacheRegistry registry;
    
    @BeforeEach
    void setUp() {
        registry = QueryResultCacheRegistry.getInstance();
        registry.clear(); // Start with clean state
    }
    
    @AfterEach
    void tearDown() {
        registry.clear();
    }
    
    @Test
    void testSingletonInstance() {
        QueryResultCacheRegistry instance1 = QueryResultCacheRegistry.getInstance();
        QueryResultCacheRegistry instance2 = QueryResultCacheRegistry.getInstance();
        
        assertSame(instance1, instance2);
    }
    
    @Test
    void testGetOrCreateWithDefaults() {
        String datasourceName = "testdb";
        
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        
        assertNotNull(cache);
        assertTrue(registry.exists(datasourceName));
        assertEquals(1, registry.size());
    }
    
    @Test
    void testGetOrCreateReturnsExisting() {
        String datasourceName = "testdb";
        
        QueryResultCache cache1 = registry.getOrCreate(datasourceName);
        QueryResultCache cache2 = registry.getOrCreate(datasourceName);
        
        assertSame(cache1, cache2);
        assertEquals(1, registry.size());
    }
    
    @Test
    void testGetOrCreateWithCustomSettings() {
        String datasourceName = "testdb";
        int maxEntries = 5000;
        Duration maxAge = Duration.ofMinutes(5);
        long maxSize = 50 * 1024 * 1024;
        
        QueryResultCache cache = registry.getOrCreate(datasourceName, maxEntries, maxAge, maxSize);
        
        assertNotNull(cache);
        assertTrue(registry.exists(datasourceName));
    }
    
    @Test
    void testGetReturnsNull() {
        String datasourceName = "nonexistent";
        
        QueryResultCache cache = registry.get(datasourceName);
        
        assertNull(cache);
    }
    
    @Test
    void testGetReturnsExisting() {
        String datasourceName = "testdb";
        
        registry.getOrCreate(datasourceName);
        QueryResultCache cache = registry.get(datasourceName);
        
        assertNotNull(cache);
    }
    
    @Test
    void testExists() {
        String datasourceName = "testdb";
        
        assertFalse(registry.exists(datasourceName));
        
        registry.getOrCreate(datasourceName);
        
        assertTrue(registry.exists(datasourceName));
    }
    
    @Test
    void testRemove() {
        String datasourceName = "testdb";
        
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        assertTrue(registry.exists(datasourceName));
        
        QueryResultCache removed = registry.remove(datasourceName);
        
        assertSame(cache, removed);
        assertFalse(registry.exists(datasourceName));
        assertEquals(0, registry.size());
    }
    
    @Test
    void testRemoveNonexistent() {
        QueryResultCache removed = registry.remove("nonexistent");
        
        assertNull(removed);
    }
    
    @Test
    void testClear() {
        registry.getOrCreate("db1");
        registry.getOrCreate("db2");
        registry.getOrCreate("db3");
        
        assertEquals(3, registry.size());
        
        registry.clear();
        
        assertEquals(0, registry.size());
        assertFalse(registry.exists("db1"));
        assertFalse(registry.exists("db2"));
        assertFalse(registry.exists("db3"));
    }
    
    @Test
    void testSize() {
        assertEquals(0, registry.size());
        
        registry.getOrCreate("db1");
        assertEquals(1, registry.size());
        
        registry.getOrCreate("db2");
        assertEquals(2, registry.size());
        
        registry.remove("db1");
        assertEquals(1, registry.size());
        
        registry.clear();
        assertEquals(0, registry.size());
    }
    
    @Test
    void testMultipleDatasources() {
        QueryResultCache cache1 = registry.getOrCreate("db1");
        QueryResultCache cache2 = registry.getOrCreate("db2");
        QueryResultCache cache3 = registry.getOrCreate("db3");
        
        assertNotSame(cache1, cache2);
        assertNotSame(cache2, cache3);
        assertNotSame(cache1, cache3);
        
        assertEquals(3, registry.size());
        assertTrue(registry.exists("db1"));
        assertTrue(registry.exists("db2"));
        assertTrue(registry.exists("db3"));
    }
    
    @Test
    void testGetAllStatistics() {
        registry.getOrCreate("db1");
        registry.getOrCreate("db2");
        
        String stats = registry.getAllStatistics();
        
        assertNotNull(stats);
        assertTrue(stats.contains("db1"));
        assertTrue(stats.contains("db2"));
        assertTrue(stats.contains("Cache Statistics"));
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        int datasourcesPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < datasourcesPerThread; j++) {
                        String datasourceName = "db_" + threadId + "_" + j;
                        registry.getOrCreate(datasourceName);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        assertEquals(threadCount * datasourcesPerThread, registry.size());
    }
    
    @Test
    void testConcurrentGetOrCreate() throws InterruptedException {
        int threadCount = 20;
        String datasourceName = "shared_db";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        QueryResultCache[] caches = new QueryResultCache[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    caches[threadId] = registry.getOrCreate(datasourceName);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        // All threads should get the same cache instance
        QueryResultCache firstCache = caches[0];
        for (int i = 1; i < threadCount; i++) {
            assertSame(firstCache, caches[i]);
        }
        
        assertEquals(1, registry.size());
    }
}
