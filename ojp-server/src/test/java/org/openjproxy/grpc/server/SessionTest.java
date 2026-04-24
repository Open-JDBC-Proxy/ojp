package org.openjproxy.grpc.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for Session class, focusing on read/write splitting features.
 */
class SessionTest {
    
    private Connection connection;
    private Session session;
    
    @BeforeEach
    void setUp() throws SQLException {
        // Create H2 in-memory database connection
        connection = DriverManager.getConnection("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
        session = new Session(connection, "test-connection-hash", "test-client-uuid");
    }
    
    @AfterEach
    void tearDown() throws SQLException {
        if (session != null) {
            session.terminate();
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
    
    @Test
    void testTransactionStateTracking() {
        // Initially not in transaction
        assertFalse(session.isInTransaction());
        
        // Set in transaction
        session.setInTransaction(true);
        assertTrue(session.isInTransaction());
        
        // Clear transaction
        session.setInTransaction(false);
        assertFalse(session.isInTransaction());
    }
    
    @Test
    void testRecordWriteOperation() {
        // Initially no write timestamp
        assertEquals(0, session.getLastWriteTimestamp());
        
        // Record write
        session.recordWriteOperation();
        
        // Verify timestamp is set
        assertTrue(session.getLastWriteTimestamp() > 0);
        long firstWrite = session.getLastWriteTimestamp();
        
        // Record another write
        try {
            Thread.sleep(10); // Small delay to ensure different timestamp //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        session.recordWriteOperation();
        
        // Verify timestamp updated
        assertTrue(session.getLastWriteTimestamp() > firstWrite);
    }
    
    @Test
    void testStickyModeWithDefaultDuration() {
        // Initially not in sticky mode
        assertFalse(session.isInStickyMode());
        
        // Record write - should enter sticky mode
        session.recordWriteOperation();
        assertTrue(session.isInStickyMode());
        
        // Wait for default sticky duration (5 seconds) + buffer
        // For testing, we'll just verify the logic without waiting
        assertFalse(session.isInStickyMode(0)); // 0ms sticky = always expired
    }
    
    @Test
    void testStickyModeWithCustomDuration() {
        // Record write
        session.recordWriteOperation();
        
        // Should be in sticky mode with 10 second window
        assertTrue(session.isInStickyMode(10000));
        
        // Should not be in sticky mode with 0 second window
        assertFalse(session.isInStickyMode(0));
    }
    
    @Test
    void testStickyModeExpiration() throws InterruptedException {
        // Record write
        session.recordWriteOperation();
        
        // Should be in sticky mode with 100ms window
        assertTrue(session.isInStickyMode(100));
        
        // Wait for expiration
        Thread.sleep(150);
        
        // Should no longer be in sticky mode
        assertFalse(session.isInStickyMode(100));
    }
    
    @Test
    void testClearStickySession() {
        // Record write
        session.recordWriteOperation();
        assertTrue(session.isInStickyMode());
        
        // Clear sticky session
        session.clearStickySession();
        
        // Should no longer be in sticky mode
        assertFalse(session.isInStickyMode());
        assertEquals(0, session.getLastWriteTimestamp());
    }
    
    @Test
    void testStickyModeWithNoWriteOperation() {
        // Without any write operation, never in sticky mode
        assertFalse(session.isInStickyMode());
        assertFalse(session.isInStickyMode(10000));
    }
    
    @Test
    void testActivityTracking() {
        long initialActivity = session.getLastActivityTime();
        
        // Update activity
        session.updateActivity();
        
        // Verify activity time updated
        assertTrue(session.getLastActivityTime() >= initialActivity);
    }
    
    @Test
    void testSessionBasicProperties() {
        // Verify basic session properties
        assertNotNull(session.getSessionUUID());
        assertEquals("test-connection-hash", session.getConnectionHash());
        assertEquals("test-client-uuid", session.getClientUUID());
        assertFalse(session.isXA());
        assertNotNull(session.getConnection());
    }
    
    @Test
    void testTransactionAndStickyModeCombination() {
        // Set in transaction and record write
        session.setInTransaction(true);
        session.recordWriteOperation();
        
        // Both should be active
        assertTrue(session.isInTransaction());
        assertTrue(session.isInStickyMode());
        
        // End transaction
        session.setInTransaction(false);
        
        // Transaction ended but still in sticky mode
        assertFalse(session.isInTransaction());
        assertTrue(session.isInStickyMode());
    }
}
