package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.ActionContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for read/write splitting functionality.
 * 
 * These tests validate the end-to-end flow of:
 * 1. Configuration parsing
 * 2. SQL classification  
 * 3. Routing decisions
 * 4. Transaction state tracking
 * 5. Sticky session behavior
 * 
 * Note: These tests use mocked datasources since the current implementation
 * focuses on state tracking infrastructure. Full routing integration will
 * require connection management refactoring in future phases.
 */
class ReadWriteIntegrationTest {

    private ReadWriteDataSourceRegistry registry;
    private SqlClassifier classifier;
    private ReadWriteRouter router;
    private Session session;
    private DataSource primaryDs;
    private DataSource replica1Ds;
    private DataSource replica2Ds;
    private Connection primaryConn;
    private Connection replica1Conn;
    private Connection replica2Conn;

    @BeforeEach
    void setUp() throws SQLException {
        // Initialize registry
        registry = new ReadWriteDataSourceRegistry();
        
        // Initialize classifier
        classifier = new RegexSqlClassifier();
        
        // Create mock datasources
        primaryDs = mock(DataSource.class);
        replica1Ds = mock(DataSource.class);
        replica2Ds = mock(DataSource.class);
        
        // Create mock connections
        primaryConn = mock(Connection.class);
        replica1Conn = mock(Connection.class);
        replica2Conn = mock(Connection.class);
        
        when(primaryDs.getConnection()).thenReturn(primaryConn);
        when(replica1Ds.getConnection()).thenReturn(replica1Conn);
        when(replica2Ds.getConnection()).thenReturn(replica2Conn);
        
        // Configure connection validity
        when(primaryConn.isValid(anyInt())).thenReturn(true);
        when(replica1Conn.isValid(anyInt())).thenReturn(true);
        when(replica2Conn.isValid(anyInt())).thenReturn(true);
        
        // Register datasources
        String connHash = "test-connection-hash";
        registry.registerPrimaryWithReplicas(connHash, primaryDs, List.of(replica1Ds, replica2Ds));
        
        // Create router
        ReplicaSelector replicaSelector = new RoundRobinReplicaSelector(registry.getReplicas(connHash));
        router = new ReadWriteRouter(classifier, replicaSelector, primaryDs);
        
        // Create session
        session = new Session();
    }

    @Test
    void testReadQueryRoutesToReplica() throws SQLException {
        // Given: A read query
        String sql = "SELECT * FROM users WHERE id = 1";
        
        // When: Classify the SQL
        SqlOperationType operationType = classifier.classify(sql);
        
        // Then: Should be classified as READ
        assertEquals(SqlOperationType.READ, operationType);
        
        // When: Route the query
        DataSource selectedDs = router.route(sql, session);
        
        // Then: Should route to a replica (not primary)
        assertTrue(selectedDs == replica1Ds || selectedDs == replica2Ds,
            "Read query should route to replica, not primary");
    }

    @Test
    void testWriteQueryRoutesToPrimary() throws SQLException {
        // Given: A write query
        String sql = "UPDATE users SET name = 'John' WHERE id = 1";
        
        // When: Classify the SQL
        SqlOperationType operationType = classifier.classify(sql);
        
        // Then: Should be classified as WRITE
        assertEquals(SqlOperationType.WRITE, operationType);
        
        // When: Route the query
        DataSource selectedDs = router.route(sql, session);
        
        // Then: Should route to primary
        assertEquals(primaryDs, selectedDs);
    }

    @Test
    void testInTransactionQueriesRouteToP...

<truncated - output limit reached. Skipping rest>
