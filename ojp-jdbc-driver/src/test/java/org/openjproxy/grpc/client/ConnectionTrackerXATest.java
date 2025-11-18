package org.openjproxy.grpc.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.sql.XAConnection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConnectionTracker XA connection tracking functionality.
 */
class ConnectionTrackerXATest {
    
    private ConnectionTracker tracker;
    private ServerEndpoint server1;
    private ServerEndpoint server2;
    
    @BeforeEach
    void setUp() {
        tracker = new ConnectionTracker();
        server1 = new ServerEndpoint("server1", 10591);
        server2 = new ServerEndpoint("server2", 10592);
    }
    
    @Test
    @DisplayName("Test XA connection registration")
    void testXAConnectionRegistration() {
        XAConnection mockXA = mock(XAConnection.class);
        
        tracker.registerXAConnection("uuid-1", mockXA, server1);
        
        assertEquals(1, tracker.getTotalXAConnections());
        
        List<XAConnectionInfo> idle = tracker.listIdleXaConnections();
        assertEquals(1, idle.size());
        assertEquals("uuid-1", idle.get(0).getConnectionUuid());
        assertEquals(server1, idle.get(0).getBoundServer());
    }
    
    @Test
    @DisplayName("Test XA connection unregistration")
    void testXAConnectionUnregistration() {
        XAConnection mockXA = mock(XAConnection.class);
        
        tracker.registerXAConnection("uuid-1", mockXA, server1);
        assertEquals(1, tracker.getTotalXAConnections());
        
        tracker.unregisterXAConnection("uuid-1");
        assertEquals(0, tracker.getTotalXAConnections());
    }
    
    @Test
    @DisplayName("Test multiple XA connections")
    void testMultipleXAConnections() {
        XAConnection mockXA1 = mock(XAConnection.class);
        XAConnection mockXA2 = mock(XAConnection.class);
        XAConnection mockXA3 = mock(XAConnection.class);
        
        tracker.registerXAConnection("uuid-1", mockXA1, server1);
        tracker.registerXAConnection("uuid-2", mockXA2, server1);
        tracker.registerXAConnection("uuid-3", mockXA3, server2);
        
        assertEquals(3, tracker.getTotalXAConnections());
        
        Map<ServerEndpoint, Integer> counts = tracker.getXAConnectionCounts();
        assertEquals(2, counts.get(server1));
        assertEquals(1, counts.get(server2));
    }
    
    @Test
    @DisplayName("Test listIdleXaConnections excludes active transactions")
    void testListIdleXaConnectionsExcludesActiveTransactions() {
        XAConnection mockXA1 = mock(XAConnection.class);
        XAConnection mockXA2 = mock(XAConnection.class);
        XAConnection mockXA3 = mock(XAConnection.class);
        
        tracker.registerXAConnection("uuid-1", mockXA1, server1);
        tracker.registerXAConnection("uuid-2", mockXA2, server1);
        tracker.registerXAConnection("uuid-3", mockXA3, server2);
        
        // Mark uuid-2 as having active transaction
        tracker.setXAConnectionActiveTransaction("uuid-2", true);
        
        List<XAConnectionInfo> idle = tracker.listIdleXaConnections();
        
        // Should only return 2 (uuid-1 and uuid-3)
        assertEquals(2, idle.size());
        assertTrue(idle.stream().noneMatch(info -> info.getConnectionUuid().equals("uuid-2")));
        assertTrue(idle.stream().anyMatch(info -> info.getConnectionUuid().equals("uuid-1")));
        assertTrue(idle.stream().anyMatch(info -> info.getConnectionUuid().equals("uuid-3")));
    }
    
    @Test
    @DisplayName("Test listIdleXaConnections sorts by last used time (oldest first)")
    void testListIdleXaConnectionsSortedByLastUsedTime() throws InterruptedException {
        XAConnection mockXA1 = mock(XAConnection.class);
        XAConnection mockXA2 = mock(XAConnection.class);
        XAConnection mockXA3 = mock(XAConnection.class);
        
        // Register connections at different times
        tracker.registerXAConnection("uuid-1", mockXA1, server1);
        Thread.sleep(10); // Ensure different timestamps
        
        tracker.registerXAConnection("uuid-2", mockXA2, server1);
        Thread.sleep(10);
        
        tracker.registerXAConnection("uuid-3", mockXA3, server2);
        
        List<XAConnectionInfo> idle = tracker.listIdleXaConnections();
        
        // Should be sorted oldest first
        assertEquals(3, idle.size());
        assertEquals("uuid-1", idle.get(0).getConnectionUuid()); // Oldest
        assertEquals("uuid-2", idle.get(1).getConnectionUuid());
        assertEquals("uuid-3", idle.get(2).getConnectionUuid()); // Newest
    }
    
    @Test
    @DisplayName("Test closeIdleConnection succeeds for idle connection")
    void testCloseIdleConnectionSucceeds() throws SQLException {
        XAConnection mockXA = mock(XAConnection.class);
        
        tracker.registerXAConnection("uuid-1", mockXA, server1);
        
        boolean closed = tracker.closeIdleConnection("uuid-1");
        
        assertTrue(closed);
        verify(mockXA, times(1)).close();
        assertEquals(0, tracker.getTotalXAConnections());
    }
    
    @Test
    @DisplayName("Test closeIdleConnection fails for active transaction")
    void testCloseIdleConnectionFailsForActiveTransaction() throws SQLException {
        XAConnection mockXA = mock(XAConnection.class);
        
        tracker.registerXAConnection("uuid-1", mockXA, server1);
        tracker.setXAConnectionActiveTransaction("uuid-1", true);
        
        boolean closed = tracker.closeIdleConnection("uuid-1");
        
        assertFalse(closed);
        verify(mockXA, never()).close();
        assertEquals(1, tracker.getTotalXAConnections()); // Still tracked
    }
    
    @Test
    @DisplayName("Test closeIdleConnection returns false for non-existent connection")
    void testCloseIdleConnectionReturnsFalseForNonExistent() throws SQLException {
        boolean closed = tracker.closeIdleConnection("non-existent-uuid");
        
        assertFalse(closed);
    }
    
    @Test
    @DisplayName("Test closeIdleConnection throws SQLException if close fails")
    void testCloseIdleConnectionThrowsSQLExceptionOnFailure() throws SQLException {
        XAConnection mockXA = mock(XAConnection.class);
        doThrow(new SQLException("Close failed")).when(mockXA).close();
        
        tracker.registerXAConnection("uuid-1", mockXA, server1);
        
        assertThrows(SQLException.class, () -> tracker.closeIdleConnection("uuid-1"));
    }
    
    @Test
    @DisplayName("Test updateXAConnectionLastUsed updates timestamp")
    void testUpdateXAConnectionLastUsed() throws InterruptedException {
        XAConnection mockXA = mock(XAConnection.class);
        
        tracker.registerXAConnection("uuid-1", mockXA, server1);
        
        List<XAConnectionInfo> before = tracker.listIdleXaConnections();
        long beforeTime = before.get(0).getLastUsedTime();
        
        Thread.sleep(10); // Ensure time passes
        
        tracker.updateXAConnectionLastUsed("uuid-1");
        
        List<XAConnectionInfo> after = tracker.listIdleXaConnections();
        long afterTime = after.get(0).getLastUsedTime();
        
        assertTrue(afterTime > beforeTime, "Last used time should be updated");
    }
    
    @Test
    @DisplayName("Test setXAConnectionActiveTransaction updates status")
    void testSetXAConnectionActiveTransaction() {
        XAConnection mockXA = mock(XAConnection.class);
        
        tracker.registerXAConnection("uuid-1", mockXA, server1);
        
        // Initially idle
        List<XAConnectionInfo> idle = tracker.listIdleXaConnections();
        assertEquals(1, idle.size());
        assertFalse(idle.get(0).hasActiveTransaction());
        
        // Mark as active
        tracker.setXAConnectionActiveTransaction("uuid-1", true);
        
        idle = tracker.listIdleXaConnections();
        assertEquals(0, idle.size()); // Should not appear in idle list
        
        // Mark as idle again
        tracker.setXAConnectionActiveTransaction("uuid-1", false);
        
        idle = tracker.listIdleXaConnections();
        assertEquals(1, idle.size());
        assertFalse(idle.get(0).hasActiveTransaction());
    }
    
    @Test
    @DisplayName("Test clear removes all XA connections")
    void testClearRemovesAllXAConnections() {
        XAConnection mockXA1 = mock(XAConnection.class);
        XAConnection mockXA2 = mock(XAConnection.class);
        
        tracker.registerXAConnection("uuid-1", mockXA1, server1);
        tracker.registerXAConnection("uuid-2", mockXA2, server2);
        
        assertEquals(2, tracker.getTotalXAConnections());
        
        tracker.clear();
        
        assertEquals(0, tracker.getTotalXAConnections());
    }
    
    @Test
    @DisplayName("Test null parameters are handled gracefully")
    void testNullParametersHandledGracefully() throws SQLException {
        // Null parameters should not cause exceptions
        assertDoesNotThrow(() -> tracker.registerXAConnection(null, null, null));
        assertDoesNotThrow(() -> tracker.unregisterXAConnection(null));
        assertDoesNotThrow(() -> tracker.updateXAConnectionLastUsed(null));
        assertDoesNotThrow(() -> tracker.setXAConnectionActiveTransaction(null, true));
        assertDoesNotThrow(() -> tracker.closeIdleConnection(null));
    }
}
