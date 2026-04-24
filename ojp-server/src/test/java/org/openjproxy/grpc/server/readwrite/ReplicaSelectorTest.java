package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RoundRobinReplicaSelector.
 */
class ReplicaSelectorTest {
    
    private RoundRobinReplicaSelector selector;
    
    @BeforeEach
    void setUp() {
        selector = new RoundRobinReplicaSelector();
    }
    
    @Test
    void testSelectReplica_nullList() {
        assertNull(selector.selectReplica(null));
    }
    
    @Test
    void testSelectReplica_emptyList() {
        assertNull(selector.selectReplica(Collections.emptyList()));
    }
    
    @Test
    void testSelectReplica_singleReplica() {
        DataSource ds = mock(DataSource.class);
        List<DataSource> replicas = Collections.singletonList(ds);
        
        // Should always return the same datasource
        assertSame(ds, selector.selectReplica(replicas));
        assertSame(ds, selector.selectReplica(replicas));
        assertSame(ds, selector.selectReplica(replicas));
    }
    
    @Test
    void testSelectReplica_roundRobinDistribution() {
        DataSource ds1 = mock(DataSource.class, "ds1");
        DataSource ds2 = mock(DataSource.class, "ds2");
        DataSource ds3 = mock(DataSource.class, "ds3");
        List<DataSource> replicas = Arrays.asList(ds1, ds2, ds3);
        
        selector.reset();
        
        // Should rotate through all replicas
        assertSame(ds1, selector.selectReplica(replicas));
        assertSame(ds2, selector.selectReplica(replicas));
        assertSame(ds3, selector.selectReplica(replicas));
        assertSame(ds1, selector.selectReplica(replicas));
        assertSame(ds2, selector.selectReplica(replicas));
        assertSame(ds3, selector.selectReplica(replicas));
    }
    
    @Test
    void testReset() {
        DataSource ds1 = mock(DataSource.class, "ds1");
        DataSource ds2 = mock(DataSource.class, "ds2");
        List<DataSource> replicas = Arrays.asList(ds1, ds2);
        
        selector.reset();
        
        // First call
        assertSame(ds1, selector.selectReplica(replicas));
        assertSame(ds2, selector.selectReplica(replicas));
        
        // Reset should start from beginning
        selector.reset();
        assertSame(ds1, selector.selectReplica(replicas));
        assertSame(ds2, selector.selectReplica(replicas));
    }
    
    @Test
    void testSelectHealthyReplica_nullList() {
        assertNull(selector.selectHealthyReplica(null));
    }
    
    @Test
    void testSelectHealthyReplica_emptyList() {
        assertNull(selector.selectHealthyReplica(Collections.emptyList()));
    }
    
    @Test
    void testSelectHealthyReplica_allHealthy() throws SQLException {
        DataSource ds1 = createHealthyDataSource();
        DataSource ds2 = createHealthyDataSource();
        DataSource ds3 = createHealthyDataSource();
        List<DataSource> replicas = Arrays.asList(ds1, ds2, ds3);
        
        selector.reset();
        
        // Should return first healthy (ds1), then rotate
        assertSame(ds1, selector.selectHealthyReplica(replicas));
        assertSame(ds2, selector.selectHealthyReplica(replicas));
        assertSame(ds3, selector.selectHealthyReplica(replicas));
        assertSame(ds1, selector.selectHealthyReplica(replicas));
    }
    
    @Test
    void testSelectHealthyReplica_someUnhealthy() throws SQLException {
        DataSource ds1 = createUnhealthyDataSource(); // Unhealthy
        DataSource ds2 = createHealthyDataSource();   // Healthy
        DataSource ds3 = createUnhealthyDataSource(); // Unhealthy
        List<DataSource> replicas = Arrays.asList(ds1, ds2, ds3);
        
        selector.reset();
        
        // Should skip unhealthy replicas and return ds2
        assertSame(ds2, selector.selectHealthyReplica(replicas));
        assertSame(ds2, selector.selectHealthyReplica(replicas));
    }
    
    @Test
    void testSelectHealthyReplica_allUnhealthy() throws SQLException {
        DataSource ds1 = createUnhealthyDataSource();
        DataSource ds2 = createUnhealthyDataSource();
        DataSource ds3 = createUnhealthyDataSource();
        List<DataSource> replicas = Arrays.asList(ds1, ds2, ds3);
        
        // Should return null when all replicas are unhealthy
        assertNull(selector.selectHealthyReplica(replicas));
    }
    
    @Test
    void testSelectHealthyReplica_connectionException() throws SQLException {
        DataSource ds1 = createConnectionExceptionDataSource();
        DataSource ds2 = createHealthyDataSource();
        List<DataSource> replicas = Arrays.asList(ds1, ds2);
        
        // Should skip ds1 (exception) and return ds2
        assertSame(ds2, selector.selectHealthyReplica(replicas));
    }
    
    @Test
    void testSelectHealthyReplica_singleHealthy() throws SQLException {
        DataSource ds = createHealthyDataSource();
        List<DataSource> replicas = Collections.singletonList(ds);
        
        // Should return the single healthy replica
        assertSame(ds, selector.selectHealthyReplica(replicas));
    }
    
    @Test
    void testSelectHealthyReplica_singleUnhealthy() throws SQLException {
        DataSource ds = createUnhealthyDataSource();
        List<DataSource> replicas = Collections.singletonList(ds);
        
        // Should return null when single replica is unhealthy
        assertNull(selector.selectHealthyReplica(replicas));
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException {
        DataSource ds1 = mock(DataSource.class, "ds1");
        DataSource ds2 = mock(DataSource.class, "ds2");
        DataSource ds3 = mock(DataSource.class, "ds3");
        List<DataSource> replicas = Arrays.asList(ds1, ds2, ds3);
        
        selector.reset();
        
        int threadCount = 10;
        int callsPerThread = 100;
        List<Thread> threads = new ArrayList<>();
        
        // Create threads that call selectReplica concurrently
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(() -> {
                for (int j = 0; j < callsPerThread; j++) {
                    DataSource selected = selector.selectReplica(replicas);
                    assertNotNull(selected);
                    assertTrue(replicas.contains(selected));
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // No assertions needed - test passes if no exceptions thrown
    }
    
    // Helper methods to create mock DataSources
    
    private DataSource createHealthyDataSource() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.isValid(anyInt())).thenReturn(true);
        return ds;
    }
    
    private DataSource createUnhealthyDataSource() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.isValid(anyInt())).thenReturn(false);
        return ds;
    }
    
    private DataSource createConnectionExceptionDataSource() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("Connection failed"));
        return ds;
    }
}
