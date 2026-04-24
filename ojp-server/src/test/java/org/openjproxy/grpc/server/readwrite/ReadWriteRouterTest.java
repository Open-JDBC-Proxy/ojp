package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.readwrite.SqlClassifier.SqlOperationType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReadWriteRouter.
 */
class ReadWriteRouterTest {
    
    private SqlClassifier sqlClassifier;
    private ReplicaSelector replicaSelector;
    private ReadWriteRouter router;
    
    private DataSource primaryDataSource;
    private DataSource replica1;
    private DataSource replica2;
    private List<DataSource> replicas;
    
    @BeforeEach
    void setUp() {
        sqlClassifier = mock(SqlClassifier.class);
        replicaSelector = mock(ReplicaSelector.class);
        router = new ReadWriteRouter(sqlClassifier, replicaSelector);
        
        primaryDataSource = mock(DataSource.class, "primary");
        replica1 = mock(DataSource.class, "replica1");
        replica2 = mock(DataSource.class, "replica2");
        replicas = Arrays.asList(replica1, replica2);
    }
    
    @Test
    void testConstructor_nullClassifier() {
        assertThrows(IllegalArgumentException.class, () -> 
            new ReadWriteRouter(null, replicaSelector)
        );
    }
    
    @Test
    void testConstructor_nullSelector() {
        assertThrows(IllegalArgumentException.class, () -> 
            new ReadWriteRouter(sqlClassifier, null)
        );
    }
    
    @Test
    void testSelectDataSource_nullPrimary() {
        assertThrows(IllegalArgumentException.class, () -> 
            router.selectDataSource(null, "SELECT * FROM users", null, replicas)
        );
    }
    
    @Test
    void testSelectDataSource_readQuery_routesToReplica() {
        when(sqlClassifier.classify("SELECT * FROM users")).thenReturn(SqlOperationType.READ);
        when(replicaSelector.selectHealthyReplica(replicas)).thenReturn(replica1);
        
        DataSource result = router.selectDataSource(null, "SELECT * FROM users", primaryDataSource, replicas);
        
        assertSame(replica1, result);
        verify(sqlClassifier).classify("SELECT * FROM users");
        verify(replicaSelector).selectHealthyReplica(replicas);
    }
    
    @Test
    void testSelectDataSource_writeQuery_routesToPrimary() {
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.WRITE);
        
        DataSource result = router.selectDataSource(null, "INSERT INTO users VALUES (1)", primaryDataSource, replicas);
        
        assertSame(primaryDataSource, result);
        verify(sqlClassifier).classify("INSERT INTO users VALUES (1)");
        verify(replicaSelector, never()).selectHealthyReplica(any());
    }
    
    @Test
    void testSelectDataSource_unknownQuery_routesToPrimary() {
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.UNKNOWN);
        
        DataSource result = router.selectDataSource(null, "BEGIN", primaryDataSource, replicas);
        
        assertSame(primaryDataSource, result);
        verify(sqlClassifier).classify("BEGIN");
        verify(replicaSelector, never()).selectHealthyReplica(any());
    }
    
    @Test
    void testSelectDataSource_readQuery_allReplicasUnhealthy_fallbackToPrimary() {
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.READ);
        when(replicaSelector.selectHealthyReplica(replicas)).thenReturn(null);
        
        DataSource result = router.selectDataSource(null, "SELECT * FROM users", primaryDataSource, replicas);
        
        assertSame(primaryDataSource, result);
        verify(replicaSelector).selectHealthyReplica(replicas);
    }
    
    @Test
    void testSelectDataSource_noReplicas_routesToPrimary() {
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.READ);
        
        DataSource result = router.selectDataSource(null, "SELECT * FROM users", primaryDataSource, Collections.emptyList());
        
        assertSame(primaryDataSource, result);
        verify(sqlClassifier, never()).classify(anyString());
        verify(replicaSelector, never()).selectHealthyReplica(any());
    }
    
    @Test
    void testSelectDataSource_nullReplicas_routesToPrimary() {
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.READ);
        
        DataSource result = router.selectDataSource(null, "SELECT * FROM users", primaryDataSource, null);
        
        assertSame(primaryDataSource, result);
        verify(sqlClassifier, never()).classify(anyString());
        verify(replicaSelector, never()).selectHealthyReplica(any());
    }
    
    @Test
    void testSelectDataSource_inTransaction_routesToPrimary() throws SQLException {
        Session session = createSessionInTransaction();
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.READ);
        
        DataSource result = router.selectDataSource(session, "SELECT * FROM users", primaryDataSource, replicas);
        
        assertSame(primaryDataSource, result);
        verify(sqlClassifier, never()).classify(anyString());
        verify(replicaSelector, never()).selectHealthyReplica(any());
    }
    
    @Test
    void testSelectDataSource_notInTransaction_readQuery_routesToReplica() throws SQLException {
        Session session = createSessionNotInTransaction();
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.READ);
        when(replicaSelector.selectHealthyReplica(replicas)).thenReturn(replica1);
        
        DataSource result = router.selectDataSource(session, "SELECT * FROM users", primaryDataSource, replicas);
        
        assertSame(replica1, result);
        verify(sqlClassifier).classify("SELECT * FROM users");
        verify(replicaSelector).selectHealthyReplica(replicas);
    }
    
    @Test
    void testSelectDataSource_nullSession_readQuery_routesToReplica() {
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.READ);
        when(replicaSelector.selectHealthyReplica(replicas)).thenReturn(replica2);
        
        DataSource result = router.selectDataSource(null, "SELECT * FROM users", primaryDataSource, replicas);
        
        assertSame(replica2, result);
    }
    
    @Test
    void testSelectDataSource_sessionConnectionException_assumeInTransaction() throws SQLException {
        Session session = createSessionWithConnectionException();
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.READ);
        
        DataSource result = router.selectDataSource(session, "SELECT * FROM users", primaryDataSource, replicas);
        
        // Should route to primary when can't determine transaction state (safe default)
        assertSame(primaryDataSource, result);
        verify(sqlClassifier, never()).classify(anyString());
    }
    
    @Test
    void testSelectDataSource_sessionNullConnection_notInTransaction() {
        Session session = createSessionWithNullConnection();
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.READ);
        when(replicaSelector.selectHealthyReplica(replicas)).thenReturn(replica1);
        
        DataSource result = router.selectDataSource(session, "SELECT * FROM users", primaryDataSource, replicas);
        
        // null connection treated as not in transaction
        assertSame(replica1, result);
    }
    
    @Test
    void testSelectDataSource_selectForUpdate_routesToPrimary() {
        // SELECT FOR UPDATE classified as WRITE
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.WRITE);
        
        DataSource result = router.selectDataSource(null, "SELECT * FROM users FOR UPDATE", primaryDataSource, replicas);
        
        assertSame(primaryDataSource, result);
    }
    
    @Test
    void testSelectDataSource_multipleReads_distributedAcrossReplicas() {
        when(sqlClassifier.classify(anyString())).thenReturn(SqlOperationType.READ);
        when(replicaSelector.selectHealthyReplica(replicas))
            .thenReturn(replica1)
            .thenReturn(replica2)
            .thenReturn(replica1);
        
        DataSource result1 = router.selectDataSource(null, "SELECT * FROM users", primaryDataSource, replicas);
        DataSource result2 = router.selectDataSource(null, "SELECT * FROM orders", primaryDataSource, replicas);
        DataSource result3 = router.selectDataSource(null, "SELECT * FROM products", primaryDataSource, replicas);
        
        assertSame(replica1, result1);
        assertSame(replica2, result2);
        assertSame(replica1, result3);
        verify(replicaSelector, times(3)).selectHealthyReplica(replicas);
    }
    
    // Helper methods to create mock Sessions
    
    private Session createSessionInTransaction() throws SQLException {
        Session session = mock(Session.class);
        Connection conn = mock(Connection.class);
        when(session.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(false); // In transaction
        return session;
    }
    
    private Session createSessionNotInTransaction() throws SQLException {
        Session session = mock(Session.class);
        Connection conn = mock(Connection.class);
        when(session.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true); // Not in transaction
        return session;
    }
    
    private Session createSessionWithConnectionException() throws SQLException {
        Session session = mock(Session.class);
        Connection conn = mock(Connection.class);
        when(session.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenThrow(new SQLException("Connection error"));
        return session;
    }
    
    private Session createSessionWithNullConnection() {
        Session session = mock(Session.class);
        when(session.getConnection()).thenReturn(null);
        return session;
    }
}
