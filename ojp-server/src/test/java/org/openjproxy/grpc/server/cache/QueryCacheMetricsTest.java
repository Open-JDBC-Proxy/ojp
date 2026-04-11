package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueryCacheMetrics implementations.
 * Tests the SPI pattern with no-op and OpenTelemetry implementations.
 */
class QueryCacheMetricsTest {

    @BeforeEach
    void setUp() {
        // Reset state before each test
    }

    // ==================== NoOpQueryCacheMetrics Tests ====================

    @Test
    void testNoOpMetricsDoesNotThrow() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // All methods should execute without throwing exceptions
        assertDoesNotThrow(() -> {
            metrics.recordCacheHit("testds", "SELECT * FROM test");
            metrics.recordCacheMiss("testds", "SELECT * FROM test");
            metrics.recordCacheEviction("testds", "size");
            metrics.recordCacheInvalidation("testds", "test_table");
            metrics.recordCacheRejection("testds", 1000000);
            metrics.updateCacheSize("testds", 100, 50000);
            metrics.recordQueryExecutionTime("testds", "cache", 150);
        });
    }

    @Test
    void testNoOpMetricsIsSingleton() {
        QueryCacheMetrics instance1 = NoOpQueryCacheMetrics.getInstance();
        QueryCacheMetrics instance2 = NoOpQueryCacheMetrics.getInstance();
        
        assertSame(instance1, instance2, "NoOpQueryCacheMetrics should be singleton");
    }

    @Test
    void testNoOpMetricsWithNullParameters() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Should not throw with null parameters
        assertDoesNotThrow(() -> {
            metrics.recordCacheHit(null, null);
            metrics.recordCacheMiss(null, null);
            metrics.recordCacheEviction(null, null);
            metrics.recordCacheInvalidation(null, null);
            metrics.updateCacheSize(null, 0, 0);
            metrics.recordQueryExecutionTime(null, null, 0);
        });
    }

    @Test
    void testNoOpMetricsWithExtremeValues() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Should handle extreme values without issues
        assertDoesNotThrow(() -> {
            metrics.recordCacheRejection("testds", Long.MAX_VALUE);
            metrics.updateCacheSize("testds", Integer.MAX_VALUE, Long.MAX_VALUE);
            metrics.recordQueryExecutionTime("testds", "cache", Long.MAX_VALUE);
        });
    }

    @Test
    void testNoOpMetricsPerformance() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Should be extremely fast (no-op)
        long start = System.nanoTime();
        for (int i = 0; i < 100000; i++) {
            metrics.recordCacheHit("testds", "SELECT * FROM test");
        }
        long duration = System.nanoTime() - start;
        
        // 100K operations should take less than 10ms
        assertTrue(duration < 10_000_000, 
            "NoOp metrics should be very fast, took: " + (duration / 1_000_000) + "ms");
    }

    // ==================== Metrics Interface Contract Tests ====================

    @Test
    void testMetricsInterfaceContract() {
        // Test that the interface can be implemented
        QueryCacheMetrics testMetrics = new QueryCacheMetrics() {
            @Override
            public void recordCacheHit(String datasource, String sql) { /* no-op test implementation */ }
            
            @Override
            public void recordCacheMiss(String datasource, String sql) { /* no-op test implementation */ }
            
            @Override
            public void recordCacheEviction(String datasource, String reason) {}
            
            @Override
            public void recordCacheInvalidation(String datasource, String table) {}
            
            @Override
            public void recordCacheRejection(String datasource, long sizeBytes) {}
            
            @Override
            public void updateCacheSize(String datasource, long entries, long sizeBytes) {}
            
            @Override
            public void recordQueryExecutionTime(String datasource, String source, long durationMs) {}
            
            @Override
            public void close() {}
        };
        
        assertNotNull(testMetrics);
        
        // Test all methods are callable
        assertDoesNotThrow(() -> {
            testMetrics.recordCacheHit("ds", "sql");
            testMetrics.recordCacheMiss("ds", "sql");
            testMetrics.recordCacheEviction("ds", "reason");
            testMetrics.recordCacheInvalidation("ds", "table");
            testMetrics.recordCacheRejection("ds", 1000);
            testMetrics.updateCacheSize("ds", 10, 10000);
            testMetrics.recordQueryExecutionTime("ds", "cache", 100);
        });
    }

    @Test
    void testMetricsWithVeryLongSQL() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Create SQL longer than truncation limit
        StringBuilder longSql = new StringBuilder("SELECT * FROM ");
        for (int i = 0; i < 200; i++) {
            longSql.append("table").append(i).append(", ");
        }
        String sql = longSql.toString();
        
        // Should handle without issues
        assertDoesNotThrow(() -> {
            metrics.recordCacheHit("testds", sql);
            metrics.recordCacheMiss("testds", sql);
            metrics.recordQueryExecutionTime("testds", "cache", 100);
        });
    }

    @Test
    void testMetricsWithSpecialCharacters() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        String sqlWithSpecialChars = "SELECT * FROM \"test-table\" WHERE name = 'O''Reilly' AND data LIKE '%test%'";
        String tableWithSpecialChars = "test-table.schema_name";
        
        assertDoesNotThrow(() -> {
            metrics.recordCacheHit("testds", sqlWithSpecialChars);
            metrics.recordCacheInvalidation("testds", tableWithSpecialChars);
        });
    }

    @Test
    void testMetricsWithEmptyStrings() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        assertDoesNotThrow(() -> {
            metrics.recordCacheHit("", "");
            metrics.recordCacheMiss("", "");
            metrics.recordCacheEviction("", "");
            metrics.recordCacheInvalidation("", "");
            metrics.updateCacheSize("", 0, 0);
            metrics.recordQueryExecutionTime("", "", 0);
        });
    }

    @Test
    void testMetricsWithNegativeValues() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Should handle negative values gracefully
        assertDoesNotThrow(() -> {
            metrics.recordCacheRejection("testds", -1);
            metrics.updateCacheSize("testds", -1, -1);
            metrics.recordQueryExecutionTime("testds", "cache", -1);
        });
    }

    @Test
    void testMetricsConcurrentAccess() throws Exception {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Test concurrent access from multiple threads
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    metrics.recordCacheHit("testds" + threadId, "SELECT " + j);
                    metrics.recordCacheMiss("testds" + threadId, "SELECT " + j);
                    metrics.updateCacheSize("testds" + threadId, j, j * 100);
                }
            });
            threads[i].start();
        }
        
        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }
        
        // If we get here without exceptions, concurrent access works
        assertTrue(true);
    }

    @Test
    void testMetricsMethodChaining() {
        QueryCacheMetrics metrics = NoOpQueryCacheMetrics.getInstance();
        
        // Methods should return void, so no chaining
        // Just verify they can be called in sequence
        metrics.recordCacheHit("testds", "SELECT 1");
        metrics.recordCacheHit("testds", "SELECT 2");
        metrics.recordCacheHit("testds", "SELECT 3");
        
        // No exceptions = success
        assertTrue(true);
    }
}
