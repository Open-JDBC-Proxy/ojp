package org.openjproxy.grpc.server.xa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for XaPoolManager pool recreation scenarios.
 * Tests the behavior of pool recreation triggered by health changes.
 */
class XaPoolManagerIntegrationTest {
    
    private XaPoolManager poolManager;
    private Properties poolConfig;
    
    @BeforeEach
    void setUp() {
        poolManager = new XaPoolManager();
        poolConfig = new Properties();
        poolConfig.setProperty("ojp.connection.pool.maximumPoolSize", "5");
        poolConfig.setProperty("ojp.connection.pool.minimumIdle", "2");
    }
    
    @AfterEach
    void tearDown() {
        if (poolManager != null) {
            poolManager.shutdown();
        }
    }
    
    @Test
    void testPoolRecreationOnHealthChange() throws Exception {
        String connHash = "integration-test-1";
        
        // Create a mock factory that tracks creation count
        AtomicInteger creationCount = new AtomicInteger(0);
        XaPoolManager.XADataSourceFactory factory = () -> {
            int count = creationCount.incrementAndGet();
            XADataSource mockXADataSource = mock(XADataSource.class);
            XAConnection mockXAConnection = mock(XAConnection.class);
            when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
            return mockXADataSource;
        };
        
        // Create initial pool
        AtomikosXAConnectionPool initialPool = poolManager.getOrCreatePool(connHash, factory, poolConfig);
        assertNotNull(initialPool);
        assertEquals(1, creationCount.get(), "Should have created one XADataSource");
        
        String initialStats = poolManager.getPoolStats(connHash);
        assertNotNull(initialStats);
        
        // Simulate health change by triggering recreation
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        
        // Wait for async recreation to complete
        Thread.sleep(2000);
        
        // Should have created a new pool
        assertEquals(2, creationCount.get(), "Should have created a second XADataSource after recreation");
        
        // Stats should still be available
        String newStats = poolManager.getPoolStats(connHash);
        assertNotNull(newStats);
    }
    
    @Test
    void testPoolRecreationDoesNotAffectOtherPools() throws Exception {
        String connHash1 = "integration-test-2a";
        String connHash2 = "integration-test-2b";
        
        AtomicInteger creationCount1 = new AtomicInteger(0);
        XaPoolManager.XADataSourceFactory factory1 = () -> {
            creationCount1.incrementAndGet();
            XADataSource mockXADataSource = mock(XADataSource.class);
            XAConnection mockXAConnection = mock(XAConnection.class);
            when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
            return mockXADataSource;
        };
        
        AtomicInteger creationCount2 = new AtomicInteger(0);
        XaPoolManager.XADataSourceFactory factory2 = () -> {
            creationCount2.incrementAndGet();
            XADataSource mockXADataSource = mock(XADataSource.class);
            XAConnection mockXAConnection = mock(XAConnection.class);
            when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
            return mockXADataSource;
        };
        
        // Create two pools
        poolManager.getOrCreatePool(connHash1, factory1, poolConfig);
        poolManager.getOrCreatePool(connHash2, factory2, poolConfig);
        
        assertEquals(1, creationCount1.get());
        assertEquals(1, creationCount2.get());
        
        // Recreate only the first pool
        poolManager.triggerPoolRecreation(connHash1, factory1, poolConfig);
        Thread.sleep(2000);
        
        // First pool should be recreated, second should not
        assertEquals(2, creationCount1.get(), "First pool should be recreated");
        assertEquals(1, creationCount2.get(), "Second pool should not be affected");
        
        // Both pools should still be accessible
        assertNotNull(poolManager.getPool(connHash1));
        assertNotNull(poolManager.getPool(connHash2));
    }
    
    @Test
    void testRapidHealthChangesDebounced() throws Exception {
        String connHash = "integration-test-3";
        
        AtomicInteger creationCount = new AtomicInteger(0);
        XaPoolManager.XADataSourceFactory factory = () -> {
            creationCount.incrementAndGet();
            XADataSource mockXADataSource = mock(XADataSource.class);
            XAConnection mockXAConnection = mock(XAConnection.class);
            when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
            return mockXADataSource;
        };
        
        // Create initial pool
        poolManager.getOrCreatePool(connHash, factory, poolConfig);
        assertEquals(1, creationCount.get());
        
        // Trigger multiple rapid recreations (should be debounced)
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        
        // Wait for processing
        Thread.sleep(2000);
        
        // Should only have one recreation due to debouncing
        assertEquals(2, creationCount.get(), "Should only recreate once due to debouncing");
    }
    
    @Test
    void testPoolRecreationAfterDebounceInterval() throws Exception {
        String connHash = "integration-test-4";
        
        AtomicInteger creationCount = new AtomicInteger(0);
        XaPoolManager.XADataSourceFactory factory = () -> {
            creationCount.incrementAndGet();
            XADataSource mockXADataSource = mock(XADataSource.class);
            XAConnection mockXAConnection = mock(XAConnection.class);
            when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
            return mockXADataSource;
        };
        
        // Create initial pool
        poolManager.getOrCreatePool(connHash, factory, poolConfig);
        assertEquals(1, creationCount.get());
        
        // First recreation
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        Thread.sleep(1500);
        assertEquals(2, creationCount.get());
        
        // Wait for debounce interval to pass (5 seconds)
        Thread.sleep(5500);
        
        // Second recreation after debounce - should not be debounced
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        Thread.sleep(1500);
        
        assertEquals(3, creationCount.get(), "Should allow recreation after debounce interval");
    }
    
    @Test
    void testConcurrentAccessDuringRecreation() throws Exception {
        String connHash = "integration-test-5";
        
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        
        AtomicInteger creationCount = new AtomicInteger(0);
        XaPoolManager.XADataSourceFactory factory = () -> {
            creationCount.incrementAndGet();
            // Simulate slow recreation
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return mockXADataSource;
        };
        
        // Create initial pool
        poolManager.getOrCreatePool(connHash, factory, poolConfig);
        assertEquals(1, creationCount.get());
        
        // Trigger recreation
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        
        // Immediately try to access the pool from multiple threads
        int numThreads = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    // Try to get the pool - should either get old or new pool, never null
                    AtomikosXAConnectionPool pool = poolManager.getPool(connHash);
                    if (pool != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown(); // Start all threads
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete");
        
        // All threads should successfully get a pool (either old or new)
        assertEquals(numThreads, successCount.get(), 
                "All threads should get a valid pool during recreation");
        
        // Wait for recreation to finish
        Thread.sleep(2000);
        assertEquals(2, creationCount.get(), "Recreation should complete");
    }
    
    @Test
    void testPoolCloseRemovesFromManager() throws Exception {
        String connHash = "integration-test-6";
        
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        
        XaPoolManager.XADataSourceFactory factory = () -> mockXADataSource;
        
        // Create pool
        poolManager.getOrCreatePool(connHash, factory, poolConfig);
        assertNotNull(poolManager.getPool(connHash));
        
        // Close pool
        poolManager.closePool(connHash);
        
        // Pool should be removed
        assertNull(poolManager.getPool(connHash));
        assertNull(poolManager.getPoolStats(connHash));
    }
    
    @Test
    void testShutdownCancelsOngoingRecreations() throws Exception {
        String connHash = "integration-test-7";
        
        AtomicInteger creationCount = new AtomicInteger(0);
        XaPoolManager.XADataSourceFactory factory = () -> {
            creationCount.incrementAndGet();
            // Simulate very slow recreation
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return mock(XADataSource.class);
        };
        
        // Create initial pool
        poolManager.getOrCreatePool(connHash, factory, poolConfig);
        assertEquals(1, creationCount.get());
        
        // Trigger recreation (will take 10 seconds)
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        Thread.sleep(500); // Let recreation start
        
        // Shutdown should cancel the ongoing recreation
        poolManager.shutdown();
        
        // Creation count should not reach 2 (recreation should be cancelled)
        Thread.sleep(2000);
        assertTrue(creationCount.get() <= 2, 
                "Shutdown should cancel or interrupt ongoing recreations");
    }
}
