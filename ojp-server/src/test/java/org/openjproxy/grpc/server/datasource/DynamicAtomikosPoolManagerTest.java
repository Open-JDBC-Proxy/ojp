package org.openjproxy.grpc.server.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openjproxy.grpc.server.xa.AtomikosXAConnectionPool;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DynamicAtomikosPoolManager.
 * Tests pool size calculations and pool recreation behavior.
 */
class DynamicAtomikosPoolManagerTest {

    @Mock
    private XADataSource mockXADataSource;
    
    @Mock
    private XAConnection mockXAConnection;

    private DynamicAtomikosPoolManager poolManager;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws SQLException {
        mocks = MockitoAnnotations.openMocks(this);
        poolManager = new DynamicAtomikosPoolManager();
        
        // Setup mock XADataSource to return XAConnection
        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (poolManager != null) {
            poolManager.closeAll();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void testCalculatePerServerSizes_SingleServer() {
        // Single server should get all capacity
        DynamicAtomikosPoolManager.PerServerSizes sizes = 
                poolManager.calculatePerServerSizes(32, 8, 1);
        
        assertEquals(32, sizes.perServerMax);
        assertEquals(8, sizes.perServerMin);
    }

    @Test
    void testCalculatePerServerSizes_EvenDivision() {
        // 4 servers, evenly divisible
        DynamicAtomikosPoolManager.PerServerSizes sizes = 
                poolManager.calculatePerServerSizes(32, 8, 4);
        
        assertEquals(8, sizes.perServerMax); // 32 / 4 = 8
        assertEquals(2, sizes.perServerMin); // 8 / 4 = 2
    }

    @Test
    void testCalculatePerServerSizes_RoundingUp() {
        // 3 servers, requires rounding up
        DynamicAtomikosPoolManager.PerServerSizes sizes = 
                poolManager.calculatePerServerSizes(32, 8, 3);
        
        assertEquals(11, sizes.perServerMax); // ceil(32 / 3) = 11
        assertEquals(3, sizes.perServerMin);  // ceil(8 / 3) = 3
    }

    @Test
    void testCalculatePerServerSizes_MinimumOne() {
        // Many servers, should ensure at least 1 connection per server
        DynamicAtomikosPoolManager.PerServerSizes sizes = 
                poolManager.calculatePerServerSizes(5, 2, 10);
        
        assertEquals(1, sizes.perServerMax); // max(1, ceil(5/10)) = 1
        assertEquals(1, sizes.perServerMin); // max(1, ceil(2/10)) = 1
    }

    @Test
    void testCalculatePerServerSizes_ZeroServers() {
        // Edge case: 0 servers should default to 1
        DynamicAtomikosPoolManager.PerServerSizes sizes = 
                poolManager.calculatePerServerSizes(20, 5, 0);
        
        assertEquals(20, sizes.perServerMax); // Treated as 1 server
        assertEquals(5, sizes.perServerMin);
    }

    @Test
    void testCalculatePerServerSizes_ExampleFromRequirements() {
        // Example from requirements: configured min=8 max=32, 4 servers up
        DynamicAtomikosPoolManager.PerServerSizes sizes = 
                poolManager.calculatePerServerSizes(32, 8, 4);
        
        assertEquals(8, sizes.perServerMax);  // perServerMax = ceil(32 / 4) = 8
        assertEquals(2, sizes.perServerMin);  // perServerMin = ceil(8 / 4) = 2
    }

    @Test
    void testGetOrCreatePool_SingleNode() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        config.setProperty("ojp.connection.pool.minimumIdle", "5");
        
        // Single node (null server list)
        AtomikosXAConnectionPool pool = poolManager.getOrCreatePool(
                "conn1", mockXADataSource, config, null);
        
        assertNotNull(pool);
        assertEquals(pool, poolManager.getPool("conn1"));
    }

    @Test
    void testGetOrCreatePool_MultiNode() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        config.setProperty("ojp.connection.pool.minimumIdle", "4");
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        
        AtomikosXAConnectionPool pool = poolManager.getOrCreatePool(
                "conn1", mockXADataSource, config, servers);
        
        assertNotNull(pool);
        // Pool should be created with divided sizes (10 max, 2 min per server)
    }

    @Test
    void testGetOrCreatePool_ReturnsExisting() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        config.setProperty("ojp.connection.pool.minimumIdle", "5");
        
        AtomikosXAConnectionPool pool1 = poolManager.getOrCreatePool(
                "conn1", mockXADataSource, config, null);
        
        AtomikosXAConnectionPool pool2 = poolManager.getOrCreatePool(
                "conn1", mockXADataSource, config, null);
        
        assertSame(pool1, pool2); // Should return same instance
    }

    @Test
    void testRecreatePoolForNewMembership_ServerIncrease() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "30");
        config.setProperty("ojp.connection.pool.minimumIdle", "6");
        
        // Start with 2 servers
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        AtomikosXAConnectionPool initialPool = poolManager.getOrCreatePool(
                "conn1", mockXADataSource, config, servers);
        
        assertNotNull(initialPool);
        
        // Increase to 3 servers
        poolManager.recreatePoolForNewMembership("conn1", 3);
        
        AtomikosXAConnectionPool newPool = poolManager.getPool("conn1");
        assertNotNull(newPool);
        assertNotSame(initialPool, newPool); // Should be a new pool instance
    }

    @Test
    void testRecreatePoolForNewMembership_ServerDecrease() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "30");
        config.setProperty("ojp.connection.pool.minimumIdle", "6");
        
        // Start with 3 servers
        List<String> servers = Arrays.asList("server1:1059", "server2:1059", "server3:1059");
        AtomikosXAConnectionPool initialPool = poolManager.getOrCreatePool(
                "conn1", mockXADataSource, config, servers);
        
        assertNotNull(initialPool);
        
        // Decrease to 2 servers
        poolManager.recreatePoolForNewMembership("conn1", 2);
        
        AtomikosXAConnectionPool newPool = poolManager.getPool("conn1");
        assertNotNull(newPool);
        assertNotSame(initialPool, newPool); // Should be a new pool instance
    }

    @Test
    void testRecreatePoolForNewMembership_NoChange() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        config.setProperty("ojp.connection.pool.minimumIdle", "5");
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        AtomikosXAConnectionPool initialPool = poolManager.getOrCreatePool(
                "conn1", mockXADataSource, config, servers);
        
        // Try to recreate with same server count
        poolManager.recreatePoolForNewMembership("conn1", 2);
        
        AtomikosXAConnectionPool samePool = poolManager.getPool("conn1");
        assertSame(initialPool, samePool); // Should not recreate
    }

    @Test
    void testRecreatePoolForNewMembership_NonExistentPool() throws SQLException {
        // Should handle gracefully without throwing exception
        assertDoesNotThrow(() -> 
            poolManager.recreatePoolForNewMembership("nonexistent", 3));
    }

    @Test
    void testUpdateServerMembership_AllPools() throws SQLException {
        Properties config1 = new Properties();
        config1.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        config1.setProperty("ojp.connection.pool.minimumIdle", "4");
        
        Properties config2 = new Properties();
        config2.setProperty("ojp.connection.pool.maximumPoolSize", "30");
        config2.setProperty("ojp.connection.pool.minimumIdle", "6");
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        
        // Create two pools
        poolManager.getOrCreatePool("conn1", mockXADataSource, config1, servers);
        poolManager.getOrCreatePool("conn2", mockXADataSource, config2, servers);
        
        // Update membership to 3 servers - should recreate both pools
        poolManager.updateServerMembership(3);
        
        // Both pools should still exist
        assertNotNull(poolManager.getPool("conn1"));
        assertNotNull(poolManager.getPool("conn2"));
    }

    @Test
    void testBorrowConnection() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        config.setProperty("ojp.connection.pool.minimumIdle", "5");
        
        poolManager.getOrCreatePool("conn1", mockXADataSource, config, null);
        
        XAConnection connection = poolManager.borrowConnection("conn1", "session1", "branch1");
        assertNotNull(connection);
    }

    @Test
    void testBorrowConnection_PoolNotFound() {
        SQLException exception = assertThrows(SQLException.class, () -> 
            poolManager.borrowConnection("nonexistent", "session1", "branch1"));
        
        assertTrue(exception.getMessage().contains("No XA pool found"));
    }

    @Test
    void testReturnConnection() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        config.setProperty("ojp.connection.pool.minimumIdle", "5");
        
        poolManager.getOrCreatePool("conn1", mockXADataSource, config, null);
        
        // Should not throw exception
        assertDoesNotThrow(() -> 
            poolManager.returnConnection("conn1", "session1", "branch1"));
    }

    @Test
    void testClosePool() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        config.setProperty("ojp.connection.pool.minimumIdle", "5");
        
        poolManager.getOrCreatePool("conn1", mockXADataSource, config, null);
        assertNotNull(poolManager.getPool("conn1"));
        
        poolManager.closePool("conn1");
        assertNull(poolManager.getPool("conn1"));
    }

    @Test
    void testCloseAll() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "20");
        config.setProperty("ojp.connection.pool.minimumIdle", "5");
        
        poolManager.getOrCreatePool("conn1", mockXADataSource, config, null);
        poolManager.getOrCreatePool("conn2", mockXADataSource, config, null);
        
        poolManager.closeAll();
        
        assertNull(poolManager.getPool("conn1"));
        assertNull(poolManager.getPool("conn2"));
    }

    @Test
    void testDefaultPoolSizes() throws SQLException {
        // Test with empty properties - should use defaults
        Properties config = new Properties();
        
        AtomikosXAConnectionPool pool = poolManager.getOrCreatePool(
                "conn1", mockXADataSource, config, null);
        
        assertNotNull(pool);
        // Pool should be created with default sizes (20 max, 5 min)
    }

    @Test
    void testInvalidPoolSizeProperties() throws SQLException {
        Properties config = new Properties();
        config.setProperty("ojp.connection.pool.maximumPoolSize", "invalid");
        config.setProperty("ojp.connection.pool.minimumIdle", "also-invalid");
        
        // Should handle gracefully and use defaults
        AtomikosXAConnectionPool pool = poolManager.getOrCreatePool(
                "conn1", mockXADataSource, config, null);
        
        assertNotNull(pool);
    }

    @Test
    void testMultipleServerScenarios() {
        // Test various server counts for correct calculations
        assertPerServerSizes(40, 10, 1, 40, 10);  // Single server
        assertPerServerSizes(40, 10, 2, 20, 5);   // 2 servers, even split
        assertPerServerSizes(40, 10, 3, 14, 4);   // 3 servers, round up
        assertPerServerSizes(40, 10, 4, 10, 3);   // 4 servers
        assertPerServerSizes(40, 10, 5, 8, 2);    // 5 servers
        assertPerServerSizes(100, 25, 7, 15, 4);  // 7 servers
    }

    private void assertPerServerSizes(int configMax, int configMin, int servers, 
                                     int expectedMax, int expectedMin) {
        DynamicAtomikosPoolManager.PerServerSizes sizes = 
                poolManager.calculatePerServerSizes(configMax, configMin, servers);
        
        assertEquals(expectedMax, sizes.perServerMax, 
                String.format("Max failed for config=%d, servers=%d", configMax, servers));
        assertEquals(expectedMin, sizes.perServerMin,
                String.format("Min failed for config=%d, servers=%d", configMin, servers));
    }
}
