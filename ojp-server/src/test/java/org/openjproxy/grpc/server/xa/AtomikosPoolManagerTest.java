package org.openjproxy.grpc.server.xa;

import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AtomikosPoolManager.
 */
class AtomikosPoolManagerTest {

    @Test
    void testGetOrCreatePool() throws SQLException {
        AtomikosPoolManager manager = new AtomikosPoolManager();
        XADataSource mockXADataSource = mock(XADataSource.class);
        Properties poolConfig = new Properties();
        poolConfig.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        poolConfig.setProperty("ojp.connection.pool.minimumIdle", "2");
        
        MultinodePoolCoordinator.PoolAllocation allocation = 
                new MultinodePoolCoordinator.PoolAllocation(10, 2, 1);
        
        // First call creates the pool
        AtomikosPoolManager.PoolHolder pool1 = manager.getOrCreatePool(
                "test-conn-hash", mockXADataSource, poolConfig, allocation);
        
        assertNotNull(pool1);
        assertEquals(allocation, pool1.getAllocation());
        
        // Second call returns the same pool
        AtomikosPoolManager.PoolHolder pool2 = manager.getOrCreatePool(
                "test-conn-hash", mockXADataSource, poolConfig, allocation);
        
        assertSame(pool1, pool2, "Should return same pool instance");
    }
    
    @Test
    void testBorrowAndReturnConnection() throws SQLException {
        AtomikosPoolManager manager = new AtomikosPoolManager();
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        
        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        
        Properties poolConfig = new Properties();
        poolConfig.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        poolConfig.setProperty("ojp.connection.pool.minimumIdle", "2");
        
        MultinodePoolCoordinator.PoolAllocation allocation = 
                new MultinodePoolCoordinator.PoolAllocation(10, 2, 1);
        
        manager.getOrCreatePool("test-conn-hash", mockXADataSource, poolConfig, allocation);
        
        // Borrow connection
        XAConnection borrowed = manager.borrowXAConnection("test-conn-hash", "session1", "branch1");
        assertNotNull(borrowed);
        verify(mockXADataSource, times(1)).getXAConnection();
        
        // Borrow again with same session/branch - should return existing
        XAConnection borrowed2 = manager.borrowXAConnection("test-conn-hash", "session1", "branch1");
        assertSame(borrowed, borrowed2, "Should return same connection for same session/branch");
        verify(mockXADataSource, times(1)).getXAConnection(); // Still only 1 call
        
        // Return connection
        manager.returnXAConnection("test-conn-hash", "session1", "branch1");
        verify(mockXAConnection, times(1)).close();
    }
    
    @Test
    void testRecreatePoolWithSameSizes() throws SQLException {
        AtomikosPoolManager manager = new AtomikosPoolManager(1, new AtomikosPoolFactory(false, 10, 5));
        XADataSource mockXADataSource = mock(XADataSource.class);
        
        Properties poolConfig = new Properties();
        poolConfig.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        poolConfig.setProperty("ojp.connection.pool.minimumIdle", "2");
        
        MultinodePoolCoordinator.PoolAllocation allocation = 
                new MultinodePoolCoordinator.PoolAllocation(10, 2, 2);
        
        AtomikosPoolManager.PoolHolder originalPool = manager.getOrCreatePool(
                "test-conn-hash", mockXADataSource, poolConfig, allocation);
        
        String originalPoolId = originalPool.getPoolId();
        
        // Recreate with same allocation (should skip)
        MultinodePoolCoordinator.PoolAllocation sameAllocation = 
                new MultinodePoolCoordinator.PoolAllocation(10, 2, 2);
        
        manager.recreatePool("test-conn-hash", sameAllocation);
        
        AtomikosPoolManager.PoolHolder poolAfterRecreate = manager.getActivePool("test-conn-hash");
        assertEquals(originalPoolId, poolAfterRecreate.getPoolId(), 
                "Pool should not be recreated when sizes are same");
    }
    
    @Test
    void testRecreatePoolWithDifferentSizes() throws SQLException {
        AtomikosPoolManager manager = new AtomikosPoolManager(1, new AtomikosPoolFactory(false, 10, 5));
        XADataSource mockXADataSource = mock(XADataSource.class);
        
        Properties poolConfig = new Properties();
        poolConfig.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        poolConfig.setProperty("ojp.connection.pool.minimumIdle", "2");
        
        MultinodePoolCoordinator.PoolAllocation allocation = 
                new MultinodePoolCoordinator.PoolAllocation(10, 2, 2);
        
        AtomikosPoolManager.PoolHolder originalPool = manager.getOrCreatePool(
                "test-conn-hash", mockXADataSource, poolConfig, allocation);
        
        String originalPoolId = originalPool.getPoolId();
        
        // Create a new allocation with different sizes (one server down)
        // Don't modify the existing allocation
        MultinodePoolCoordinator.PoolAllocation newAllocation = 
                new MultinodePoolCoordinator.PoolAllocation(10, 2, 2);
        newAllocation.updateHealthyServerCount(1); // Update to 1 healthy server
        
        manager.recreatePool("test-conn-hash", newAllocation);
        
        AtomikosPoolManager.PoolHolder poolAfterRecreate = manager.getActivePool("test-conn-hash");
        assertNotEquals(originalPoolId, poolAfterRecreate.getPoolId(), 
                "Pool should be recreated when sizes change");
        assertEquals(10, poolAfterRecreate.getAllocation().getCurrentMaxPoolSize(), 
                "New pool should have updated max size (10/1)");
    }
    
    @Test
    void testRemovePool() throws SQLException {
        AtomikosPoolManager manager = new AtomikosPoolManager();
        XADataSource mockXADataSource = mock(XADataSource.class);
        
        Properties poolConfig = new Properties();
        poolConfig.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        poolConfig.setProperty("ojp.connection.pool.minimumIdle", "2");
        
        MultinodePoolCoordinator.PoolAllocation allocation = 
                new MultinodePoolCoordinator.PoolAllocation(10, 2, 1);
        
        manager.getOrCreatePool("test-conn-hash", mockXADataSource, poolConfig, allocation);
        assertNotNull(manager.getActivePool("test-conn-hash"));
        
        manager.removePool("test-conn-hash");
        assertNull(manager.getActivePool("test-conn-hash"));
    }
    
    @Test
    void testGetPoolStats() throws SQLException {
        AtomikosPoolManager manager = new AtomikosPoolManager();
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        
        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        
        Properties poolConfig = new Properties();
        poolConfig.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        poolConfig.setProperty("ojp.connection.pool.minimumIdle", "2");
        
        MultinodePoolCoordinator.PoolAllocation allocation = 
                new MultinodePoolCoordinator.PoolAllocation(10, 2, 2);
        
        manager.getOrCreatePool("test-conn-hash", mockXADataSource, poolConfig, allocation);
        
        String stats = manager.getPoolStats("test-conn-hash");
        assertNotNull(stats);
        assertTrue(stats.contains("maxPoolSize=5"), "Stats should show divided max pool size");
        assertTrue(stats.contains("minPoolSize=1"), "Stats should show divided min pool size");
        assertTrue(stats.contains("healthy=2/2"), "Stats should show healthy server count");
    }
    
    @Test
    void testConcurrentBorrowDifferentSessions() throws SQLException {
        AtomikosPoolManager manager = new AtomikosPoolManager();
        XADataSource mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection1 = mock(XAConnection.class);
        XAConnection mockXAConnection2 = mock(XAConnection.class);
        
        when(mockXADataSource.getXAConnection())
                .thenReturn(mockXAConnection1)
                .thenReturn(mockXAConnection2);
        
        Properties poolConfig = new Properties();
        poolConfig.setProperty("ojp.connection.pool.maximumPoolSize", "10");
        poolConfig.setProperty("ojp.connection.pool.minimumIdle", "2");
        
        MultinodePoolCoordinator.PoolAllocation allocation = 
                new MultinodePoolCoordinator.PoolAllocation(10, 2, 1);
        
        manager.getOrCreatePool("test-conn-hash", mockXADataSource, poolConfig, allocation);
        
        // Borrow connections for different sessions
        XAConnection conn1 = manager.borrowXAConnection("test-conn-hash", "session1", "branch1");
        XAConnection conn2 = manager.borrowXAConnection("test-conn-hash", "session2", "branch2");
        
        assertNotSame(conn1, conn2, "Different sessions should get different connections");
        verify(mockXADataSource, times(2)).getXAConnection();
    }
}
