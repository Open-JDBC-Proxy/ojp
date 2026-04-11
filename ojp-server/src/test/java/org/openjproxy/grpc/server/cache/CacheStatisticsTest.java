package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CacheStatistics implementation.
 */
class CacheStatisticsTest {
    
    private CacheStatistics statistics;
    
    @BeforeEach
    void setUp() {
        statistics = new CacheStatistics();
    }
    
    @Test
    void testInitialState() {
        assertEquals(0, statistics.getHits());
        assertEquals(0, statistics.getMisses());
        assertEquals(0, statistics.getEvictions());
        assertEquals(0, statistics.getInvalidations());
        assertEquals(0, statistics.getRejections());
        assertEquals(0.0, statistics.getHitRate());
    }
    
    @Test
    void testRecordHit() {
        statistics.recordHit();
        assertEquals(1, statistics.getHits());
        
        statistics.recordHit();
        assertEquals(2, statistics.getHits());
    }
    
    @Test
    void testRecordMiss() {
        statistics.recordMiss();
        assertEquals(1, statistics.getMisses());
        
        statistics.recordMiss();
        assertEquals(2, statistics.getMisses());
    }
    
    @Test
    void testRecordEviction() {
        statistics.recordEviction();
        assertEquals(1, statistics.getEvictions());
    }
    
    @Test
    void testRecordInvalidation() {
        statistics.recordInvalidation();
        assertEquals(1, statistics.getInvalidations());
    }
    
    @Test
    void testRecordRejection() {
        statistics.recordRejection();
        assertEquals(1, statistics.getRejections());
    }
    
    @Test
    void testHitRateCalculation() {
        // 0 operations = 0% hit rate
        assertEquals(0.0, statistics.getHitRate());
        
        // 1 hit, 0 misses = 100% hit rate
        statistics.recordHit();
        assertEquals(1.0, statistics.getHitRate());
        
        // 1 hit, 1 miss = 50% hit rate
        statistics.recordMiss();
        assertEquals(0.5, statistics.getHitRate());
        
        // 3 hits, 1 miss = 75% hit rate
        statistics.recordHit();
        statistics.recordHit();
        assertEquals(0.75, statistics.getHitRate());
    }
    
    @Test
    void testReset() {
        statistics.recordHit();
        statistics.recordHit();
        statistics.recordMiss();
        statistics.recordEviction();
        statistics.recordInvalidation();
        statistics.recordRejection();
        
        assertTrue(statistics.getHits() > 0);
        assertTrue(statistics.getMisses() > 0);
        
        statistics.reset();
        
        assertEquals(0, statistics.getHits());
        assertEquals(0, statistics.getMisses());
        assertEquals(0, statistics.getEvictions());
        assertEquals(0, statistics.getInvalidations());
        assertEquals(0, statistics.getRejections());
        assertEquals(0.0, statistics.getHitRate());
    }
    
    @Test
    void testToString() {
        statistics.recordHit();
        statistics.recordHit();
        statistics.recordMiss();
        statistics.recordEviction();
        
        String str = statistics.toString();
        
        assertNotNull(str);
        assertTrue(str.contains("hits=2"));
        assertTrue(str.contains("misses=1"));
        assertTrue(str.contains("hitRate=66.67%"));
        assertTrue(str.contains("evictions=1"));
    }
    
    @Test
    void testThreadSafety() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 1000;
        
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    statistics.recordHit();
                    statistics.recordMiss();
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertEquals(threadCount * operationsPerThread, statistics.getHits());
        assertEquals(threadCount * operationsPerThread, statistics.getMisses());
        assertEquals(0.5, statistics.getHitRate());
    }
}
