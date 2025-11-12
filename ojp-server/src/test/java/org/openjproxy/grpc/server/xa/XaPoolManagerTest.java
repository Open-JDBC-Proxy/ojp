package org.openjproxy.grpc.server.xa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
 * Unit tests for XaPoolManager.
 */
class XaPoolManagerTest {
    
    private XaPoolManager poolManager;
    private static final long TEST_DEBOUNCE_MS = 500; // Short debounce for testing
    private static final long TEST_TIMEOUT_MS = 5000;
    
    @BeforeEach
    void setUp() {
        poolManager = new XaPoolManager(TEST_DEBOUNCE_MS, TEST_TIMEOUT_MS);
    }
    
    @AfterEach
    void tearDown() {
        if (poolManager != null) {
            poolManager.shutdown();
        }
    }
    
    @Test
    void testGetOrCreatePool_CreatesNewPool() throws SQLException {
        String connHash = "test-hash-1";
        Properties poolConfig = new Properties();
        poolConfig.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        
        XADataSource mockXADataSource = mock(XADataSource.class);
        XaPoolManager.XADataSourceFactory factory = () -> mockXADataSource;
        
        AtomikosXAConnectionPool pool = poolManager.getOrCreatePool(connHash, factory, poolConfig);
        
        assertNotNull(pool);
        assertNotNull(poolManager.getPoolStats(connHash));
    }
    
    @Test
    void testGetOrCreatePool_ReturnsExistingPool() throws SQLException {
        String connHash = "test-hash-2";
        Properties poolConfig = new Properties();
        
        XADataSource mockXADataSource = mock(XADataSource.class);
        XaPoolManager.XADataSourceFactory factory = () -> mockXADataSource;
        
        AtomikosXAConnectionPool pool1 = poolManager.getOrCreatePool(connHash, factory, poolConfig);
        AtomikosXAConnectionPool pool2 = poolManager.getOrCreatePool(connHash, factory, poolConfig);
        
        assertSame(pool1, pool2, "Should return the same pool instance");
    }
    
    @Test
    void testGetPool_ReturnsNullForNonExistentPool() {
        String connHash = "non-existent";
        
        AtomikosXAConnectionPool pool = poolManager.getPool(connHash);
        
        assertNull(pool);
    }
    
    @Test
    void testGetPool_ReturnsExistingPool() throws SQLException {
        String connHash = "test-hash-3";
        Properties poolConfig = new Properties();
        
        XADataSource mockXADataSource = mock(XADataSource.class);
        XaPoolManager.XADataSourceFactory factory = () -> mockXADataSource;
        
        AtomikosXAConnectionPool createdPool = poolManager.getOrCreatePool(connHash, factory, poolConfig);
        AtomikosXAConnectionPool retrievedPool = poolManager.getPool(connHash);
        
        assertSame(createdPool, retrievedPool);
    }
    
    @Test
    void testBorrowConnection_ThrowsExceptionForNonExistentPool() {
        String connHash = "non-existent";
        
        SQLException exception = assertThrows(SQLException.class, () -> {
            poolManager.borrowConnection(connHash, "session1", "branch1");
        });
        
        assertTrue(exception.getMessage().contains("XA pool not found"));
    }
    
    @Test
    void testClosePool_RemovesPool() throws SQLException {
        String connHash = "test-hash-4";
        Properties poolConfig = new Properties();
        
        XADataSource mockXADataSource = mock(XADataSource.class);
        XaPoolManager.XADataSourceFactory factory = () -> mockXADataSource;
        
        poolManager.getOrCreatePool(connHash, factory, poolConfig);
        assertNotNull(poolManager.getPool(connHash));
        
        poolManager.closePool(connHash);
        
        assertNull(poolManager.getPool(connHash));
        assertNull(poolManager.getPoolStats(connHash));
    }
    
    @Test
    void testTriggerPoolRecreation_CreatesNewPool() throws Exception {
        String connHash = "test-hash-5";
        Properties poolConfig = new Properties();
        
        AtomicInteger creationCount = new AtomicInteger(0);
        XaPoolManager.XADataSourceFactory factory = () -> {
            creationCount.incrementAndGet();
            return mock(XADataSource.class);
        };
        
        // Create initial pool
        poolManager.getOrCreatePool(connHash, factory, poolConfig);
        assertEquals(1, creationCount.get());
        
        // Trigger recreation
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        
        // Wait for async recreation to complete
        Thread.sleep(1000);
        
        // Should have created a new pool
        assertEquals(2, creationCount.get());
    }
    
    @Test
    void testTriggerPoolRecreation_Debouncing() throws Exception {
        String connHash = "test-hash-6";
        Properties poolConfig = new Properties();
        
        AtomicInteger creationCount = new AtomicInteger(0);
        XaPoolManager.XADataSourceFactory factory = () -> {
            creationCount.incrementAndGet();
            return mock(XADataSource.class);
        };
        
        // Create initial pool
        poolManager.getOrCreatePool(connHash, factory, poolConfig);
        assertEquals(1, creationCount.get());
        
        // Trigger recreation multiple times rapidly
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig); // Should be debounced
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig); // Should be debounced
        
        // Wait for async recreation to complete
        Thread.sleep(1000);
        
        // Should have created only one new pool (debounced the others)
        assertEquals(2, creationCount.get());
    }
    
    @Test
    void testTriggerPoolRecreation_MultipleAfterDebounce() throws Exception {
        String connHash = "test-hash-7";
        Properties poolConfig = new Properties();
        
        AtomicInteger creationCount = new AtomicInteger(0);
        XaPoolManager.XADataSourceFactory factory = () -> {
            creationCount.incrementAndGet();
            return mock(XADataSource.class);
        };
        
        // Create initial pool
        poolManager.getOrCreatePool(connHash, factory, poolConfig);
        assertEquals(1, creationCount.get());
        
        // Trigger recreation
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        Thread.sleep(1000);
        assertEquals(2, creationCount.get());
        
        // Wait for debounce interval to pass (5 seconds + buffer)
        Thread.sleep(5500);
        
        // Trigger another recreation - should not be debounced
        poolManager.triggerPoolRecreation(connHash, factory, poolConfig);
        Thread.sleep(1000);
        
        assertEquals(3, creationCount.get());
    }
    
    @Test
    void testConcurrentAccess() throws Exception {
        String connHash = "test-hash-8";
        Properties poolConfig = new Properties();
        
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        
        XaPoolManager.XADataSourceFactory factory = () -> mockXADataSource;
        
        // Create pool
        poolManager.getOrCreatePool(connHash, factory, poolConfig);
        
        // Test concurrent reads
        int numThreads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await();
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
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete");
        assertEquals(numThreads, successCount.get(), "All threads should successfully get the pool");
    }
    
    @Test
    void testShutdown_ClosesAllPools() throws SQLException {
        Properties poolConfig = new Properties();
        
        XADataSource mockXADataSource = mock(XADataSource.class);
        XaPoolManager.XADataSourceFactory factory = () -> mockXADataSource;
        
        // Create multiple pools
        poolManager.getOrCreatePool("hash1", factory, poolConfig);
        poolManager.getOrCreatePool("hash2", factory, poolConfig);
        poolManager.getOrCreatePool("hash3", factory, poolConfig);
        
        assertNotNull(poolManager.getPool("hash1"));
        assertNotNull(poolManager.getPool("hash2"));
        assertNotNull(poolManager.getPool("hash3"));
        
        // Shutdown
        poolManager.shutdown();
        
        // All pools should be removed
        assertNull(poolManager.getPool("hash1"));
        assertNull(poolManager.getPool("hash2"));
        assertNull(poolManager.getPool("hash3"));
    }
    
    @Test
    void testGetPoolStats_ReturnsNullForNonExistentPool() {
        String stats = poolManager.getPoolStats("non-existent");
        assertNull(stats);
    }
}
