package org.openjproxy.grpc.server.readwrite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for ReadWriteDataSourceRegistry.
 */
class ReadWriteDataSourceRegistryTest {
    
    private ReadWriteDataSourceRegistry registry;
    private DataSource primaryDataSource;
    private DataSource replica1DataSource;
    private DataSource replica2DataSource;
    
    @BeforeEach
    void setUp() {
        registry = new ReadWriteDataSourceRegistry();
        
        // Create test datasources
        primaryDataSource = createTestDataSource("primary");
        replica1DataSource = createTestDataSource("replica1");
        replica2DataSource = createTestDataSource("replica2");
    }
    
    @AfterEach
    void tearDown() {
        // Close datasources to prevent resource leaks
        closeDataSource(primaryDataSource);
        closeDataSource(replica1DataSource);
        closeDataSource(replica2DataSource);
    }
    
    private DataSource createTestDataSource(String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + poolName + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(2);
        config.setPoolName(poolName);
        return new HikariDataSource(config);
    }
    
    private void closeDataSource(DataSource ds) {
        if (ds instanceof HikariDataSource) {
            ((HikariDataSource) ds).close();
        }
    }
    
    @Test
    void testRegisterAndGetReplicas() {
        // Register replicas
        registry.registerReplica("primary1", replica1DataSource);
        registry.registerReplica("primary1", replica2DataSource);
        
        // Verify retrieval
        List<DataSource> replicas = registry.getReplicas("primary1");
        assertNotNull(replicas);
        assertEquals(2, replicas.size());
        assertTrue(replicas.contains(replica1DataSource));
        assertTrue(replicas.contains(replica2DataSource));
    }
    
    @Test
    void testGetReplicasReturnsUnmodifiableList() {
        registry.registerReplica("primary1", replica1DataSource);
        
        List<DataSource> replicas = registry.getReplicas("primary1");
        
        // Attempt to modify should throw exception
        assertThrows(UnsupportedOperationException.class, () -> {
            replicas.add(replica2DataSource);
        });
    }
    
    @Test
    void testGetReplicasForNonExistentPrimary() {
        List<DataSource> replicas = registry.getReplicas("nonexistent");
        assertNotNull(replicas);
        assertTrue(replicas.isEmpty());
    }
    
    @Test
    void testHasReplicas() {
        assertFalse(registry.hasReplicas("primary1"));
        
        registry.registerReplica("primary1", replica1DataSource);
        assertTrue(registry.hasReplicas("primary1"));
        
        assertFalse(registry.hasReplicas("primary2"));
    }
    
    @Test
    void testGetReplicaCount() {
        assertEquals(0, registry.getReplicaCount("primary1"));
        
        registry.registerReplica("primary1", replica1DataSource);
        assertEquals(1, registry.getReplicaCount("primary1"));
        
        registry.registerReplica("primary1", replica2DataSource);
        assertEquals(2, registry.getReplicaCount("primary1"));
    }
    
    @Test
    void testRegisterPrimaryMapping() {
        registry.registerPrimaryMapping("conn-hash-1", "primary1");
        
        String primaryName = registry.getPrimaryName("conn-hash-1");
        assertEquals("primary1", primaryName);
    }
    
    @Test
    void testGetPrimaryNameForNonExistentConnection() {
        String primaryName = registry.getPrimaryName("nonexistent");
        assertNull(primaryName);
    }
    
    @Test
    void testRegisterPrimaryMappingWithNullValues() {
        assertThrows(IllegalArgumentException.class, () -> {
            registry.registerPrimaryMapping(null, "primary1");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            registry.registerPrimaryMapping("conn-hash-1", null);
        });
    }
    
    @Test
    void testRegisterReplicaWithNullValues() {
        assertThrows(IllegalArgumentException.class, () -> {
            registry.registerReplica(null, replica1DataSource);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            registry.registerReplica("primary1", null);
        });
    }
    
    @Test
    void testClearReplicas() {
        registry.registerReplica("primary1", replica1DataSource);
        registry.registerReplica("primary1", replica2DataSource);
        
        assertTrue(registry.hasReplicas("primary1"));
        
        registry.clearReplicas("primary1");
        
        assertFalse(registry.hasReplicas("primary1"));
        assertEquals(0, registry.getReplicaCount("primary1"));
    }
    
    @Test
    void testRemovePrimaryMapping() {
        registry.registerPrimaryMapping("conn-hash-1", "primary1");
        assertEquals("primary1", registry.getPrimaryName("conn-hash-1"));
        
        registry.removePrimaryMapping("conn-hash-1");
        assertNull(registry.getPrimaryName("conn-hash-1"));
    }
    
    @Test
    void testClear() {
        // Setup
        registry.registerPrimaryMapping("conn-hash-1", "primary1");
        registry.registerReplica("primary1", replica1DataSource);
        
        // Verify setup
        assertNotNull(registry.getPrimaryName("conn-hash-1"));
        assertTrue(registry.hasReplicas("primary1"));
        
        // Clear all
        registry.clear();
        
        // Verify everything is cleared
        assertNull(registry.getPrimaryName("conn-hash-1"));
        assertFalse(registry.hasReplicas("primary1"));
    }
    
    @Test
    void testMultiplePrimariesWithReplicas() {
        // Register replicas for multiple primaries
        registry.registerReplica("primary1", replica1DataSource);
        registry.registerReplica("primary2", replica2DataSource);
        
        // Verify isolation
        assertEquals(1, registry.getReplicaCount("primary1"));
        assertEquals(1, registry.getReplicaCount("primary2"));
        
        List<DataSource> primary1Replicas = registry.getReplicas("primary1");
        List<DataSource> primary2Replicas = registry.getReplicas("primary2");
        
        assertTrue(primary1Replicas.contains(replica1DataSource));
        assertFalse(primary1Replicas.contains(replica2DataSource));
        
        assertTrue(primary2Replicas.contains(replica2DataSource));
        assertFalse(primary2Replicas.contains(replica1DataSource));
    }
}
