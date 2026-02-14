package org.openjproxy.grpc.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the unified connection mode where both XA and non-XA connections
 * connect to all servers.
 */
class UnifiedConnectionModeTest {
    
    private MultinodeConnectionManager manager;
    private ServerEndpoint server1;
    private ServerEndpoint server2;
    private ServerEndpoint server3;
    
    @BeforeEach
    void setUp() {
        server1 = new ServerEndpoint("server1", 1059, "default");
        server2 = new ServerEndpoint("server2", 1059, "default");
        server3 = new ServerEndpoint("server3", 1059, "default");
        
        // Create config with default settings
        HealthCheckConfig config = HealthCheckConfig.createDefault();
        
        manager = new MultinodeConnectionManager(
                Arrays.asList(server1, server2, server3),
                -1, 5000, config);
    }
    
    @Test
    void testSessionTrackerExists() {
        SessionTracker tracker = manager.getSessionTracker();
        assertNotNull(tracker, "SessionTracker should not be null");
    }
    
    @Test
    void testSessionBindingRegistersWithTracker() {
        SessionTracker tracker = manager.getSessionTracker();
        
        // Bind a session
        manager.bindSession("session123", "server1:1059");
        
        // Verify session is tracked
        assertTrue(tracker.isTracked("session123"));
        assertEquals(server1, tracker.getBoundServer("session123"));
        assertEquals(1, tracker.getSessionCount(server1));
        assertEquals(1, tracker.getTotalSessions());
    }
    
    @Test
    void testSessionUnbindingUnregistersFromTracker() {
        SessionTracker tracker = manager.getSessionTracker();
        
        // Bind and then unbind
        manager.bindSession("session123", "server1:1059");
        assertEquals(1, tracker.getTotalSessions());
        
        manager.unbindSession("session123");
        
        // Verify session is no longer tracked
        assertFalse(tracker.isTracked("session123"));
        assertEquals(0, tracker.getSessionCount(server1));
        assertEquals(0, tracker.getTotalSessions());
    }
    
    @Test
    void testMultipleSessionBindings() {
        SessionTracker tracker = manager.getSessionTracker();
        
        // Bind multiple sessions to different servers
        manager.bindSession("session1", "server1:1059");
        manager.bindSession("session2", "server1:1059");
        manager.bindSession("session3", "server2:1059");
        manager.bindSession("session4", "server3:1059");
        
        // Verify counts
        assertEquals(2, tracker.getSessionCount(server1));
        assertEquals(1, tracker.getSessionCount(server2));
        assertEquals(1, tracker.getSessionCount(server3));
        assertEquals(4, tracker.getTotalSessions());
    }
    
    @Test
    void testSessionCountsForLoadBalancing() {
        SessionTracker tracker = manager.getSessionTracker();
        
        // Create imbalanced load
        manager.bindSession("session1", "server1:1059");
        manager.bindSession("session2", "server1:1059");
        manager.bindSession("session3", "server2:1059");
        
        // Get session counts
        Map<ServerEndpoint, Integer> counts = tracker.getSessionCounts();
        
        assertEquals(2, counts.get(server1).intValue());
        assertEquals(1, counts.get(server2).intValue());
        assertNull(counts.get(server3)); // No sessions
    }
    
    @Test
    void testConnectionTrackerStillWorks() {
        // Verify backward compatibility - ConnectionTracker still exists
        ConnectionTracker tracker = manager.getConnectionTracker();
        assertNotNull(tracker, "ConnectionTracker should still exist for backward compatibility");
    }
}
