package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.Session;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for read/write splitting failover scenarios.
 * 
 * Tests the system's ability to handle:
 * 1. Replica unavailability (failover to other replicas)
 * 2. All replicas down (failover to primary)
 * 3. Replica recovery (resume using replicas)
 * 4. Primary failover scenarios
 */
class ReadWriteFailoverIntegrationTest {

    private ReadWriteDataSourceRegistry registry;
    private SqlClassifier classifier;
    private DataSource primaryDs;
    private DataSource replica1Ds;
    private DataSource replica2Ds;
    private Connection primaryConn;
    private Connection replica1Conn;
    private Connection replica2Conn;
    private Session session;

    @BeforeEach
    void setUp() throws SQLException {
        // Initialize components
        registry = new ReadWriteDataSourceRegistry();
        classifier = new RegexSqlClassifier();
        
        // Create mock datasources and connections
        primaryDs = mock(DataSource.class);
        replica1Ds = mock(DataSource.class);
        replica2Ds = mock(DataSource.class);
        
        primaryConn = mock(Connection.class);
        replica1Conn = mock(Connection.class);
        replica2Conn = mock(Connection.class);
        
        when(primaryDs.getConnection()).thenReturn(primaryConn);
        when(replica1Ds.getConnection()).thenReturn(replica1Conn);
        when(replica2Ds.getConnection()).thenReturn(replica2Conn);
        
        // Configure connection validity (all healthy by default)
        when(primaryConn.isValid(anyInt())).thenReturn(true);
        when(replica1Conn.isValid(anyInt())).thenReturn(true);
        when(replica2Conn.isValid(anyInt())).thenReturn(true);
        
        // Register datasources
        String connHash = "test-connection-hash";
        registry.registerPrimaryWithReplicas(connHash, primaryDs, List.of(replica1Ds, replica2Ds));
        
        // Create session
        session = new Session();
    }

    @Test
    void testFailoverWhenOneReplicaIsDown() throws SQLException {
        // Given: Replica 1 is unhealthy, but replica 2 is healthy
        when(replica1Conn.isValid(anyInt())).thenReturn(false);
        when(replica2Conn.isValid(anyInt())).thenReturn(true);
        
        // When: Create router and route a read query
        ReplicaSelector replicaSelector = new RoundRobinReplicaSelector(
            registry.getReplicas("test-connection-hash"));
        ReadWriteRouter router = new ReadWriteRouter(classifier, replicaSelector, primaryDs);
        
        String sql = "SELECT * FROM products";
        DataSource selectedDs = router.route(sql, session);
        
        // Then: Should route to healthy replica (replica2), not the unhealthy one or primary
        assertEquals(replica2Ds, selectedDs,
            "Should route to healthy replica when one replica is down");
    }

    @Test
    void testFailoverToPrimaryWhenAllReplicasAreDown() throws SQLException {
        // Given: All replicas are unhealthy
        when(replica1Conn.isValid(anyInt())).thenReturn(false);
        when(replica2Conn.isValid(anyInt())).thenReturn(false);
        when(primaryConn.isValid(anyInt())).thenReturn(true);
        
        // When: Route a read query
        ReplicaSelector replicaSelector = new RoundRobinReplicaSelector(
            registry.getReplicas("test-connection-hash"));
        ReadWriteRouter router = new ReadWriteRouter(classifier, replicaSelector, primaryDs);
        
        String sql = "SELECT * FROM orders";
        DataSource selectedDs = router.route(sql, session);
        
        // Then: Should fail over to primary
        assertEquals(primaryDs, selectedDs,
            "Should failover to primary when all replicas are down");
    }

    @Test
    void testRoundRobinFailoverAcrossMultipleReplicas() throws SQLException {
        // Given: First replica is down, second is healthy
        when(replica1Conn.isValid(anyInt())).thenReturn(false);
        when(replica2Conn.isValid(anyInt())).thenReturn(true);
        
        ReplicaSelector replicaSelector = new RoundRobinReplicaSelector(
            registry.getReplicas("test-connection-hash"));
        ReadWriteRouter router = new ReadWriteRouter(classifier, replicaSelector, primaryDs);
        
        // When: Execute multiple read queries
        String sql = "SELECT * FROM users";
        DataSource ds1 = router.route(sql, session);
        DataSource ds2 = router.route(sql, session);
        DataSource ds3 = router.route(sql, session);
        
        // Then: All should route to the healthy replica (not primary)
        assertEquals(replica2Ds, ds1, "First query should route to healthy replica");
        assertEquals(replica2Ds, ds2, "Second query should route to healthy replica");
        assertEquals(replica2Ds, ds3, "Third query should route to healthy replica");
    }

    @Test
    void testReplicaRecovery() throws SQLException {
        // Given: Initially replica 1 is down
        when(replica1Conn.isValid(anyInt())).thenReturn(false);
        when(replica2Conn.isValid(anyInt())).thenReturn(true);
        
        ReplicaSelector replicaSelector = new RoundRobinReplicaSelector(
            registry.getReplicas("test-connection-hash"));
        ReadWriteRouter router = new ReadWriteRouter(classifier, replicaSelector, primaryDs);
        
        String sql = "SELECT * FROM inventory";
        
        // When: First query (replica1 is down)
        DataSource ds1 = router.route(sql, session);
        assertEquals(replica2Ds, ds1, "Should use healthy replica2");
        
        // Given: Replica 1 recovers
        when(replica1Conn.isValid(anyInt())).thenReturn(true);
        
        // When: Subsequent queries
        DataSource ds2 = router.route(sql, session);
        DataSource ds3 = router.route(sql, session);
        
        // Then: Should resume using both replicas in round-robin
        // (Note: Exact order depends on round-robin state, but both should be used)
        assertTrue(ds2 == replica1Ds || ds2 == replica2Ds,
            "Should use one of the healthy replicas");
        assertTrue(ds3 == replica1Ds || ds3 == replica2Ds,
            "Should use one of the healthy replicas");
    }

    @Test
    void testWriteQueriesAlwaysUsePrimaryEvenDuringFailover() throws SQLException {
        // Given: All replicas are down
        when(replica1Conn.isValid(anyInt())).thenReturn(false);
        when(replica2Conn.isValid(anyInt())).thenReturn(false);
        when(primaryConn.isValid(anyInt())).thenReturn(true);
        
        ReplicaSelector replicaSelector = new RoundRobinReplicaSelector(
            registry.getReplicas("test-connection-hash"));
        ReadWriteRouter router = new ReadWriteRouter(classifier, replicaSelector, primaryDs);
        
        // When: Execute write queries
        String insertSql = "INSERT INTO logs (message) VALUES ('test')";
        String updateSql = "UPDATE users SET active = true WHERE id = 1";
        String deleteSql = "DELETE FROM temp_data WHERE created_at < NOW()";
        
        DataSource ds1 = router.route(insertSql, session);
        DataSource ds2 = router.route(updateSql, session);
        DataSource ds3 = router.route(deleteSql, session);
        
        // Then: All write queries should always route to primary
        assertEquals(primaryDs, ds1, "INSERT should always route to primary");
        assertEquals(primaryDs, ds2, "UPDATE should always route to primary");
        assertEquals(primaryDs, ds3, "DELETE should always route to primary");
    }

    @Test
    void testMultipleFailoverAttempts() throws SQLException {
        // Given: Replica 1 fails, then replica 2 fails, then primary is used
        when(replica1Conn.isValid(anyInt())).thenReturn(false);
        when(replica2Conn.isValid(anyInt())).thenReturn(false);
        when(primaryConn.isValid(anyInt())).thenReturn(true);
        
        ReplicaSelector replicaSelector = new RoundRobinReplicaSelector(
            registry.getReplicas("test-connection-hash"));
        ReadWriteRouter router = new ReadWriteRouter(classifier, replicaSelector, primaryDs);
        
        String sql = "SELECT COUNT(*) FROM orders";
        
        // When: Execute multiple queries (should consistently failover to primary)
        for (int i = 0; i < 5; i++) {
            DataSource ds = router.route(sql, session);
            assertEquals(primaryDs, ds,
                "Query " + (i + 1) + " should failover to primary when all replicas are down");
        }
    }

    @Test
    void testPartialFailoverScenario() throws SQLException {
        // Given: We have 2 replicas, 1 is down
        when(replica1Conn.isValid(anyInt())).thenReturn(true);
        when(replica2Conn.isValid(anyInt())).thenReturn(false);
        
        ReplicaSelector replicaSelector = new RoundRobinReplicaSelector(
            registry.getReplicas("test-connection-hash"));
        ReadWriteRouter router = new ReadWriteRouter(classifier, replicaSelector, primaryDs);
        
        String sql = "SELECT * FROM categories";
        
        // When: Execute 10 queries
        int replica1Count = 0;
        int replica2Count = 0;
        int primaryCount = 0;
        
        for (int i = 0; i < 10; i++) {
            DataSource ds = router.route(sql, session);
            if (ds == replica1Ds) replica1Count++;
            else if (ds == replica2Ds) replica2Count++;
            else if (ds == primaryDs) primaryCount++;
        }
        
        // Then: Should use only healthy replica1, never the unhealthy replica2 or primary
        assertTrue(replica1Count > 0, "Should use healthy replica1");
        assertEquals(0, replica2Count, "Should NOT use unhealthy replica2");
        assertEquals(0, primaryCount, "Should NOT fallback to primary when a replica is healthy");
    }
}
