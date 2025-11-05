package org.openjproxy.grpc.client;

import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for targetServer field and session stickiness binding.
 * Tests the new bindSession, getBoundTargetServer, and unbindSession functionality.
 */
class MultinodeTargetServerBindingTest {

    private List<ServerEndpoint> endpoints;
    private MultinodeConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        // Create test endpoints
        endpoints = Arrays.asList(
            new ServerEndpoint("server1", 10591),
            new ServerEndpoint("server2", 10592),
            new ServerEndpoint("server3", 10593)
        );
        
        // Create a connection manager
        connectionManager = new MultinodeConnectionManager(endpoints);
    }

    @Test
    void testBindSession() {
        // Test binding a session to a target server
        String sessionUUID = "test-session-uuid-123";
        String targetServer = "server1:10591";
        
        connectionManager.bindSession(sessionUUID, targetServer);
        
        // Verify the session is bound
        String boundServer = connectionManager.getBoundTargetServer(sessionUUID);
        assertNotNull(boundServer, "Session should be bound to a server");
        assertEquals(targetServer, boundServer, "Session should be bound to the correct server");
    }

    @Test
    void testBindSessionWithNullSessionUUID() {
        // Test that binding with null sessionUUID doesn't throw
        assertDoesNotThrow(() -> {
            connectionManager.bindSession(null, "server1:10591");
        });
        
        // Verify nothing is bound
        assertNull(connectionManager.getBoundTargetServer(null));
    }

    @Test
    void testBindSessionWithEmptySessionUUID() {
        // Test that binding with empty sessionUUID doesn't throw
        assertDoesNotThrow(() -> {
            connectionManager.bindSession("", "server1:10591");
        });
        
        // Verify nothing is bound
        assertNull(connectionManager.getBoundTargetServer(""));
    }

    @Test
    void testBindSessionWithNullTargetServer() {
        // Test that binding with null targetServer doesn't throw
        String sessionUUID = "test-session-uuid";
        assertDoesNotThrow(() -> {
            connectionManager.bindSession(sessionUUID, null);
        });
        
        // Verify nothing is bound (null targetServer should be ignored)
        assertNull(connectionManager.getBoundTargetServer(sessionUUID));
    }

    @Test
    void testBindSessionWithInvalidTargetServer() {
        // Test binding with a targetServer that doesn't match any endpoint
        String sessionUUID = "test-session-uuid";
        String invalidTargetServer = "unknown-server:9999";
        
        connectionManager.bindSession(sessionUUID, invalidTargetServer);
        
        // Verify the session is not bound (invalid server should be ignored)
        assertNull(connectionManager.getBoundTargetServer(sessionUUID));
    }

    @Test
    void testGetServerForSessionNotBound() {
        // Test getting server for a session that's not bound
        String sessionUUID = "non-existent-session";
        
        String boundServer = connectionManager.getBoundTargetServer(sessionUUID);
        assertNull(boundServer, "Non-existent session should return null");
    }

    @Test
    void testGetServerForSessionWithNullUUID() {
        // Test getting server with null sessionUUID
        assertNull(connectionManager.getBoundTargetServer(null));
    }

    @Test
    void testGetServerForSessionWithEmptyUUID() {
        // Test getting server with empty sessionUUID
        assertNull(connectionManager.getBoundTargetServer(""));
    }

    @Test
    void testUnbindSession() {
        // Setup: bind a session
        String sessionUUID = "test-session-uuid";
        String targetServer = "server2:10592";
        
        connectionManager.bindSession(sessionUUID, targetServer);
        
        // Verify it's bound
        assertNotNull(connectionManager.getBoundTargetServer(sessionUUID));
        
        // Unbind the session
        connectionManager.unbindSession(sessionUUID);
        
        // Verify it's no longer bound
        assertNull(connectionManager.getBoundTargetServer(sessionUUID));
    }

    @Test
    void testUnbindSessionNotBound() {
        // Test unbinding a session that was never bound - should not throw
        assertDoesNotThrow(() -> {
            connectionManager.unbindSession("non-existent-session");
        });
    }

    @Test
    void testUnbindSessionWithNullUUID() {
        // Test unbinding with null sessionUUID - should not throw
        assertDoesNotThrow(() -> {
            connectionManager.unbindSession(null);
        });
    }

    @Test
    void testTerminateSessionRemovesBinding() {
        // Setup: bind a session
        String sessionUUID = "test-session-uuid";
        String targetServer = "server1:10591";
        String connHash = "test-conn-hash";
        String clientUUID = "test-client-uuid";
        
        connectionManager.bindSession(sessionUUID, targetServer);
        
        // Verify it's bound
        assertNotNull(connectionManager.getBoundTargetServer(sessionUUID));
        
        // Create SessionInfo and terminate
        SessionInfo sessionInfo = SessionInfo.newBuilder()
                .setSessionUUID(sessionUUID)
                .setConnHash(connHash)
                .setClientUUID(clientUUID)
                .setTargetServer(targetServer)
                .build();
        
        connectionManager.terminateSession(sessionInfo);
        
        // Verify the session is unbound
        assertNull(connectionManager.getBoundTargetServer(sessionUUID));
    }

    @Test
    void testMultipleSessionsBindingIndependently() {
        // Test that multiple sessions can be bound independently
        String session1 = "session-1";
        String session2 = "session-2";
        String session3 = "session-3";
        
        String server1 = "server1:10591";
        String server2 = "server2:10592";
        String server3 = "server3:10593";
        
        // Bind all sessions
        connectionManager.bindSession(session1, server1);
        connectionManager.bindSession(session2, server2);
        connectionManager.bindSession(session3, server3);
        
        // Verify each session is bound correctly
        assertEquals(server1, connectionManager.getBoundTargetServer(session1));
        assertEquals(server2, connectionManager.getBoundTargetServer(session2));
        assertEquals(server3, connectionManager.getBoundTargetServer(session3));
        
        // Unbind one session
        connectionManager.unbindSession(session2);
        
        // Verify only session2 is unbound
        assertEquals(server1, connectionManager.getBoundTargetServer(session1));
        assertNull(connectionManager.getBoundTargetServer(session2));
        assertEquals(server3, connectionManager.getBoundTargetServer(session3));
    }

    @Test
    void testRebindSessionToNewServer() {
        // Test that rebinding a session to a new server updates the binding
        String sessionUUID = "test-session-uuid";
        String server1 = "server1:10591";
        String server2 = "server2:10592";
        
        // Initial bind
        connectionManager.bindSession(sessionUUID, server1);
        assertEquals(server1, connectionManager.getBoundTargetServer(sessionUUID));
        
        // Rebind to different server
        connectionManager.bindSession(sessionUUID, server2);
        assertEquals(server2, connectionManager.getBoundTargetServer(sessionUUID));
    }

    @Test
    void testBackwardCompatibility() {
        // Test that the system works when targetServer is not set (empty string)
        String sessionUUID = "backward-compat-session";
        
        // Simulate server response without targetServer
        SessionInfo sessionInfo = SessionInfo.newBuilder()
                .setSessionUUID(sessionUUID)
                .setConnHash("test-hash")
                .setClientUUID("test-client")
                .setTargetServer("")  // Empty targetServer
                .build();
        
        // With empty targetServer, bindSession should handle gracefully
        connectionManager.bindSession(sessionInfo.getSessionUUID(), sessionInfo.getTargetServer());
        
        // Session should not be bound (empty targetServer is ignored)
        assertNull(connectionManager.getBoundTargetServer(sessionUUID));
    }

    @Test
    void testSessionBindingPersistence() {
        // Test that session bindings persist across multiple operations
        String sessionUUID = "persistent-session";
        String targetServer = "server1:10591";
        
        connectionManager.bindSession(sessionUUID, targetServer);
        
        // Query the binding multiple times
        for (int i = 0; i < 10; i++) {
            String boundServer = connectionManager.getBoundTargetServer(sessionUUID);
            assertEquals(targetServer, boundServer, 
                "Session binding should persist across multiple queries (iteration " + i + ")");
        }
    }

    @Test
    void testConcurrentSessionBindings() {
        // Test that session bindings work correctly with many sessions
        int numSessions = 100;
        
        for (int i = 0; i < numSessions; i++) {
            String sessionUUID = "session-" + i;
            String targetServer = endpoints.get(i % endpoints.size()).getHost() + ":" + 
                                  endpoints.get(i % endpoints.size()).getPort();
            
            connectionManager.bindSession(sessionUUID, targetServer);
        }
        
        // Verify all sessions are bound correctly
        for (int i = 0; i < numSessions; i++) {
            String sessionUUID = "session-" + i;
            String expectedServer = endpoints.get(i % endpoints.size()).getHost() + ":" + 
                                    endpoints.get(i % endpoints.size()).getPort();
            
            assertEquals(expectedServer, connectionManager.getBoundTargetServer(sessionUUID),
                "Session " + i + " should be bound to correct server");
        }
    }

    @Test
    void testTerminateSessionWithNullSessionInfo() {
        // Test that terminating with null sessionInfo doesn't throw
        assertDoesNotThrow(() -> {
            connectionManager.terminateSession(null);
        });
    }
}
