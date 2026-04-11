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
 * Integration tests for sticky session behavior in read/write splitting.
 * 
 * Sticky sessions ensure that after a WRITE operation, subsequent READ queries
 * route to the primary for a configurable duration to avoid reading stale data
 * from replicas that may have replication lag.
 * 
 * Tests cover:
 * 1. Sticky mode activation after writes
 * 2. Sticky mode expiration
 * 3. Custom sticky session durations
 * 4. Transaction and sticky session interaction
 */
class ReadWriteStickySessionIntegrationTest {

    private ReadWriteDataSourceRegistry registry;
    private SqlClassifier classifier;
    private ReadWriteRouter router;
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
        
        // Configure connection validity (all healthy)
        when(primaryConn.isValid(anyInt())).thenReturn(true);
        when(replica1Conn.isValid(anyInt())).thenReturn(true);
        when(replica2Conn.isValid(anyInt())).thenReturn(true);
        
        // Register datasources
        String connHash = "test-connection-hash";
        registry.registerPrimaryWithReplicas(connHash, primaryDs, List.of(replica1Ds, replica2Ds));
        
        // Create router
        ReplicaSelector replicaSelector = new RoundRobinReplicaSelector(
            registry.getReplicas(connHash));
        router = new ReadWriteRouter(classifier, replicaSelector, primaryDs);
        
        // Create session
        session = new Session();
    }

    @Test
    void testReadAfterWriteUsesStickySession() throws SQLException {
        // Given: Session is not in sticky mode initially
        assertFalse(session.isInStickyMode(), "Session should not be in sticky mode initially");
        
        // When: Execute a write operation
        String writeSql = "UPDATE products SET price = 99.99 WHERE id = 1";
        session.recordWrite(); // Simulate write operation recording
        
        // Then: Session should now be in sticky mode
        assertTrue(session.isInStickyMode(), "Session should be in sticky mode after write");
        
        // When: Execute a read query immediately after write
        String readSql = "SELECT * FROM products WHERE id = 1";
        DataSource selectedDs = router.route(readSql, session);
        
        // Then: Should route to primary due to sticky session
        assertEquals(primaryDs, selectedDs,
            "Read query should route to primary during sticky session (avoid stale reads)");
    }

    @Test
    void testStickySessionExpiration() throws SQLException, InterruptedException {
        // Given: Execute a write to activate sticky mode
        session.recordWrite();
        assertTrue(session.isInStickyMode(), "Should be in sticky mode after write");
        
        // When: Wait for sticky session to expire (default: 5 seconds)
        Thread.sleep(6000); // Wait 6 seconds
        
        // Then: Session should no longer be in sticky mode
        assertFalse(session.isInStickyMode(),
            "Sticky session should expire after default duration (5 seconds)");
        
        // When: Execute a read query after expiration
        String readSql = "SELECT * FROM orders";
        DataSource selectedDs = router.route(readSql, session);
        
        // Then: Should route to replica (not primary)
        assertTrue(selectedDs == replica1Ds || selectedDs == replica2Ds,
            "Read query should route to replica after sticky session expires");
    }

    @Test
    void testCustomStickySessionDuration() {
        // Given: A custom sticky session duration of 10 seconds
        long customDuration = 10000; // 10 seconds
        session.recordWrite();
        
        // Then: Should be in sticky mode with custom duration
        assertTrue(session.isInStickyMode(customDuration),
            "Should be in sticky mode within custom duration");
        
        // When: Check with shorter duration (2 seconds)
        long shortDuration = 2000; // 2 seconds
        
        // Then: Should not be in sticky mode if we check with a shorter threshold
        // (This depends on implementation - if lastWrite was > 2 seconds ago)
        // For fresh write, should still be in sticky mode
        assertTrue(session.isInStickyMode(shortDuration),
            "Should be in sticky mode within any reasonable duration for fresh write");
    }

    @Test
    void testMultipleWritesExtendStickySession() {
        // Given: Execute first write
        session.recordWrite();
        long firstWriteTime = session.getLastWriteTimestamp();
        
        // When: Wait a bit, then execute another write
        try {
            Thread.sleep(1000); // Wait 1 second
        } catch (InterruptedException e) {
            fail("Sleep interrupted");
        }
        
        session.recordWrite();
        long secondWriteTime = session.getLastWriteTimestamp();
        
        // Then: Second write timestamp should be later
        assertTrue(secondWriteTime > firstWriteTime,
            "Second write should update the last write timestamp");
        
        // And: Should still be in sticky mode
        assertTrue(session.isInStickyMode(),
            "Should remain in sticky mode after multiple writes");
    }

    @Test
    void testStickySessionDoesNotAffectWriteQueries() throws SQLException {
        // Given: Session in sticky mode
        session.recordWrite();
        assertTrue(session.isInStickyMode());
        
        // When: Execute write queries
        String insertSql = "INSERT INTO logs (message) VALUES ('test')";
        String updateSql = "UPDATE users SET last_login = NOW() WHERE id = 1";
        
        DataSource ds1 = router.route(insertSql, session);
        DataSource ds2 = router.route(updateSql, session);
        
        // Then: Write queries should always go to primary (sticky session doesn't affect them)
        assertEquals(primaryDs, ds1, "INSERT should route to primary");
        assertEquals(primaryDs, ds2, "UPDATE should route to primary");
    }

    @Test
    void testTransactionOverridesStickySession() throws SQLException {
        // Given: Session is in sticky mode from a previous write
        session.recordWrite();
        assertTrue(session.isInStickyMode());
        
        // When: Start a transaction
        session.setInTransaction(true);
        
        // And: Execute a read query
        String readSql = "SELECT * FROM accounts WHERE user_id = 1";
        DataSource selectedDs = router.route(readSql, session);
        
        // Then: Should route to primary due to transaction (not just sticky session)
        assertEquals(primaryDs, selectedDs,
            "Queries in transaction should route to primary regardless of sticky session");
    }

    @Test
    void testStickySessionAfterTransactionCommit() throws SQLException, InterruptedException {
        // Given: Execute transaction with write
        session.setInTransaction(true);
        session.recordWrite(); // Write within transaction
        
        // When: Commit transaction
        session.setInTransaction(false);
        
        // Then: Should still be in sticky mode after transaction
        assertTrue(session.isInStickyMode(),
            "Sticky mode should persist after transaction commit");
        
        // When: Execute read query after commit
        String readSql = "SELECT * FROM products";
        DataSource selectedDs = router.route(readSql, session);
        
        // Then: Should route to primary due to sticky session
        assertEquals(primaryDs, selectedDs,
            "Read after transaction commit should use primary during sticky session");
        
        // When: Wait for sticky session to expire
        Thread.sleep(6000);
        
        // Then: After expiration, reads should go to replicas
        assertFalse(session.isInStickyMode(), "Sticky session should expire");
        DataSource selectedDs2 = router.route(readSql, session);
        assertTrue(selectedDs2 == replica1Ds || selectedDs2 == replica2Ds,
            "Read after sticky session expiration should route to replica");
    }

    @Test
    void testNoStickySessionForReadOnlyQueries() throws SQLException {
        // Given: Session with no writes
        assertFalse(session.isInStickyMode());
        assertEquals(0, session.getLastWriteTimestamp(),
            "Last write timestamp should be 0 with no writes");
        
        // When: Execute multiple read queries
        String sql1 = "SELECT * FROM products";
        String sql2 = "SELECT * FROM categories";
        String sql3 = "SELECT * FROM users";
        
        DataSource ds1 = router.route(sql1, session);
        DataSource ds2 = router.route(sql2, session);
        DataSource ds3 = router.route(sql3, session);
        
        // Then: All should route to replicas (no sticky session)
        assertTrue(ds1 == replica1Ds || ds1 == replica2Ds,
            "Read-only query 1 should route to replica");
        assertTrue(ds2 == replica1Ds || ds2 == replica2Ds,
            "Read-only query 2 should route to replica");
        assertTrue(ds3 == replica1Ds || ds3 == replica2Ds,
            "Read-only query 3 should route to replica");
        
        // And: Should never enter sticky mode
        assertFalse(session.isInStickyMode(),
            "Session should not enter sticky mode without writes");
    }

    @Test
    void testWriteReadWriteSequence() throws SQLException, InterruptedException {
        // Scenario: WRITE → READ (sticky) → wait → READ (replica) → WRITE → READ (sticky)
        
        // 1. Execute write
        session.recordWrite();
        String readSql = "SELECT * FROM orders";
        
        // 2. Read immediately after write (sticky mode)
        DataSource ds1 = router.route(readSql, session);
        assertEquals(primaryDs, ds1, "Read after write should use primary (sticky)");
        
        // 3. Wait for expiration
        Thread.sleep(6000);
        
        // 4. Read after expiration (replica)
        DataSource ds2 = router.route(readSql, session);
        assertTrue(ds2 == replica1Ds || ds2 == replica2Ds,
            "Read after sticky expiration should use replica");
        
        // 5. Another write
        session.recordWrite();
        
        // 6. Read immediately after second write (sticky mode again)
        DataSource ds3 = router.route(readSql, session);
        assertEquals(primaryDs, ds3,
            "Read after second write should use primary (sticky mode reactivated)");
    }
}
