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
 * Integration tests for read/write splitting functionality.
 */
class ReadWriteIntegrationTest {

    private ReadWriteDataSourceRegistry registry;
    private SqlClassifier classifier;
    private ReadWriteRouter router;
    private Session session;
    private DataSource primaryDs;
    private DataSource replica1Ds;
    private DataSource replica2Ds;

    @BeforeEach
    void setUp() throws SQLException {
        registry = new ReadWriteDataSourceRegistry();
        classifier = new RegexSqlClassifier();
        
        primaryDs = mock(DataSource.class);
        replica1Ds = mock(DataSource.class);
        replica2Ds = mock(DataSource.class);
        
        Connection primaryConn = mock(Connection.class);
        Connection replica1Conn = mock(Connection.class);
        Connection replica2Conn = mock(Connection.class);
        
        when(primaryDs.getConnection()).thenReturn(primaryConn);
        when(replica1Ds.getConnection()).thenReturn(replica1Conn);
        when(replica2Ds.getConnection()).thenReturn(replica2Conn);
        
        when(primaryConn.isValid(anyInt())).thenReturn(true);
        when(replica1Conn.isValid(anyInt())).thenReturn(true);
        when(replica2Conn.isValid(anyInt())).thenReturn(true);
        
        String connHash = "test-connection-hash";
        registry.registerPrimaryWithReplicas(connHash, primaryDs, List.of(replica1Ds, replica2Ds));
        
        ReplicaSelector replicaSelector = new RoundRobinReplicaSelector(registry.getReplicas(connHash));
        router = new ReadWriteRouter(classifier, replicaSelector, primaryDs);
        
        session = new Session();
    }

    @Test
    void testReadQueryRoutesToReplica() throws SQLException {
        String sql = "SELECT * FROM users WHERE id = 1";
        SqlOperationType operationType = classifier.classify(sql);
        assertEquals(SqlOperationType.READ, operationType);
        
        DataSource selectedDs = router.route(sql, session);
        assertTrue(selectedDs == replica1Ds || selectedDs == replica2Ds);
    }

    @Test
    void testWriteQueryRoutesToPrimary() throws SQLException {
        String sql = "UPDATE users SET name = 'John' WHERE id = 1";
        SqlOperationType operationType = classifier.classify(sql);
        assertEquals(SqlOperationType.WRITE, operationType);
        
        DataSource selectedDs = router.route(sql, session);
        assertEquals(primaryDs, selectedDs);
    }

    @Test
    void testInTransactionQueriesRouteToPrimary() throws SQLException {
        session.setInTransaction(true);
        String sql = "SELECT * FROM users WHERE id = 1";
        DataSource selectedDs = router.route(sql, session);
        assertEquals(primaryDs, selectedDs);
    }

    @Test
    void testStickySessionActivatedAfterWrite() {
        assertFalse(session.isInStickyMode());
        session.recordWriteOperation();
        assertTrue(session.isInStickyMode());
    }

    @Test
    void testReadAfterWriteUsesStickySession() throws SQLException {
        session.recordWriteOperation();
        assertTrue(session.isInStickyMode());
        
        String sql = "SELECT * FROM users WHERE id = 1";
        DataSource selectedDs = router.route(sql, session);
        assertEquals(primaryDs, selectedDs);
    }

    @Test
    void testRoundRobinDistribution() throws SQLException {
        String sql = "SELECT * FROM users";
        DataSource ds1 = router.route(sql, session);
        DataSource ds2 = router.route(sql, session);
        
        assertNotNull(ds1);
        assertNotNull(ds2);
    }

    @Test
    void testUnknownSqlRoutesToPrimary() throws SQLException {
        String sql = "SOME_UNKNOWN_STATEMENT";
        DataSource selectedDs = router.route(sql, session);
        assertEquals(primaryDs, selectedDs);
    }

    @Test
    void testConfigurationEnabledCheck() {
        ReadWriteConfiguration config = ReadWriteConfiguration.builder()
            .primaryName("primary")
            .enabled(true)
            .build();
        assertTrue(config.isEnabled());
    }
}
