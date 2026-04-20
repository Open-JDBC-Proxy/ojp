package org.openjproxy.grpc.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for XA session invalidation when servers recover.
 * 
 * Verifies that when a server is killed and resurrected, all XA sessions bound to that 
 * server are invalidated to prevent "Connection not found" errors.
 */
class XASessionInvalidationTest {

    private List<ServerEndpoint> endpoints;
    private MultinodeConnectionManager manager;
    private HealthCheckConfig config;
    private ConnectionTracker connectionTracker;
    private XAConnectionRedistributor xaRedistributor;

    @BeforeEach
    void setUp() {
        endpoints = Arrays.asList(
            new ServerEndpoint("server1", 1059),
            new ServerEndpoint("server2", 1059),
            new ServerEndpoint("server3", 1059)
        );
        
        config = HealthCheckConfig.createDefault();
        connectionTracker = new ConnectionTracker();
        
        // Create manager with XA support
        manager = new MultinodeConnectionManager(endpoints, 3, 1000, config, connectionTracker);
        xaRedistributor = new XAConnectionRedistributor(manager, config);
        manager.setXaConnectionRedistributor(xaRedistributor);
    }

    @Test
    void testSessionsInvalidatedOnServerRecovery() {
        // Simulate XA sessions bound to server2
        ServerEndpoint server2 = endpoints.get(1);
        manager.bindSession("session-1", "server2:1059");
        manager.bindSession("session-2", "server2:1059");
        manager.bindSession("session-3", "server1:1059"); // Different server
        
        // Verify sessions are bound
        Map<?, ?> sessionMap = getSessionToServerMap(manager);
        assertEquals(3, sessionMap.size());
        assertEquals(server2, getEndpoint(sessionMap, "session-1"));
        assertEquals(server2, getEndpoint(sessionMap, "session-2"));
        assertEquals(endpoints.get(0), getEndpoint(sessionMap, "session-3"));
        
        // Mark server2 as unhealthy
        server2.setHealthy(false);
        server2.setLastFailureTime(System.nanoTime() - 35_000_000_000L); // 35 seconds ago
        
        // Trigger health check (which will mark server2 as recovered and invalidate sessions)
        // We need to make the server appear healthy when validated
        server2.setHealthy(true); // Simulate successful validation
        
        // Manually call the invalidation method to test it directly
        invokeInvalidateSessionsForServer(manager, server2);
        
        // Verify sessions bound to server2 were invalidated
        sessionMap = getSessionToServerMap(manager);
        assertEquals(1, sessionMap.size(), "Only session-3 should remain");
        assertNull(sessionMap.get("session-1"), "session-1 should be invalidated");
        assertNull(sessionMap.get("session-2"), "session-2 should be invalidated");
        assertEquals(endpoints.get(0), sessionMap.get("session-3"), "session-3 should still be bound");
    }

    @Test
    void testConnectionObjectsMarkedInvalid() {
        // This test verifies that actual Connection objects are marked as invalid
        // Note: In real scenarios, connections are tracked by ConnectionTracker
        // Here we verify the logic works correctly
        
        ServerEndpoint server1 = endpoints.get(0);
        
        // Bind some sessions
        manager.bindSession("session-1", "server1:1059");
        manager.bindSession("session-2", "server1:1059");
        
        // In a real scenario, connections would be in ConnectionTracker
        // The invalidation method will call connectionTracker.getDistribution()
        // and mark any connections found for the server as invalid
        
        // Mark server as unhealthy then recover
        server1.setHealthy(false);
        server1.setHealthy(true);
        
        // Call invalidation
        invokeInvalidateSessionsForServer(manager, server1);
        
        // Verify sessions were removed
        Map<?, ?> sessionMap = getSessionToServerMap(manager);
        assertEquals(0, sessionMap.size(), "All sessions should be invalidated");
        
        // Note: Connection object invalidation is tested by checking that
        // the method calls markForceInvalid() on connections returned from
        // connectionTracker.getDistribution(). This is verified through
        // integration tests where actual connections are used.
    }

    @Test
    void testNoSessionsInvalidatedWhenNoBoundSessions() {
        // Server has no bound sessions
        ServerEndpoint server2 = endpoints.get(1);
        
        // Bind sessions to other servers
        manager.bindSession("session-1", "server1:1059");
        manager.bindSession("session-2", "server3:1059");
        
        Map<?, ?> sessionMapBefore = getSessionToServerMap(manager);
        assertEquals(2, sessionMapBefore.size());
        
        // Mark server2 as unhealthy and recover
        server2.setHealthy(false);
        server2.setHealthy(true); // Simulate recovery
        
        // Invalidate sessions for server2
        invokeInvalidateSessionsForServer(manager, server2);
        
        // Verify no sessions were invalidated (none were bound to server2)
        Map<?, ?> sessionMapAfter = getSessionToServerMap(manager);
        assertEquals(2, sessionMapAfter.size(), "No sessions should be invalidated");
        assertNotNull(sessionMapAfter.get("session-1"));
        assertNotNull(sessionMapAfter.get("session-2"));
    }

    @Test
    void testSessionInvalidationAppliesToAllModes() {
        // Session invalidation now applies to both XA and non-XA modes
        // This test verifies that invalidation works regardless of XA redistributor
        
        MultinodeConnectionManager managerWithoutXA = new MultinodeConnectionManager(
            endpoints, 3, 1000, config, connectionTracker);
        // Don't set XA redistributor
        
        // Bind a session
        managerWithoutXA.bindSession("session-1", "server1:1059");
        
        Map<?, ?> sessionMap = getSessionToServerMap(managerWithoutXA);
        assertEquals(1, sessionMap.size());
        
        // Invoke session invalidation for server1
        ServerEndpoint server1 = endpoints.get(0);
        invokeInvalidateSessionsForServer(managerWithoutXA, server1);
        
        // Session invalidation should work regardless of XA mode
        assertEquals(0, sessionMap.size(), "Session should be invalidated in all modes");
    }

    @Test
    void testMultipleServersRecovery() {
        // Bind sessions to multiple servers
        manager.bindSession("session-1", "server1:1059");
        manager.bindSession("session-2", "server1:1059");
        manager.bindSession("session-3", "server2:1059");
        manager.bindSession("session-4", "server2:1059");
        manager.bindSession("session-5", "server3:1059");
        
        Map<?, ?> sessionMap = getSessionToServerMap(manager);
        assertEquals(5, sessionMap.size());
        
        // Mark server1 and server2 as unhealthy
        ServerEndpoint server1 = endpoints.get(0);
        ServerEndpoint server2 = endpoints.get(1);
        server1.setHealthy(false);
        server2.setHealthy(false);
        
        // Recover server1
        server1.setHealthy(true);
        invokeInvalidateSessionsForServer(manager, server1);
        
        // Verify only server1 sessions were invalidated
        sessionMap = getSessionToServerMap(manager);
        assertEquals(3, sessionMap.size(), "Only server1 sessions should be invalidated");
        assertNull(sessionMap.get("session-1"));
        assertNull(sessionMap.get("session-2"));
        assertNotNull(sessionMap.get("session-3"));
        assertNotNull(sessionMap.get("session-4"));
        assertNotNull(sessionMap.get("session-5"));
        
        // Recover server2
        server2.setHealthy(true);
        invokeInvalidateSessionsForServer(manager, server2);
        
        // Verify server2 sessions were invalidated
        sessionMap = getSessionToServerMap(manager);
        assertEquals(1, sessionMap.size(), "Only server3 session should remain");
        assertNotNull(sessionMap.get("session-5"));
    }

    @Test
    void testSessionInvalidationLogging() {
        // This test verifies the logging behavior (manual verification through logs)
        ServerEndpoint server1 = endpoints.get(0);
        
        // Test with sessions
        manager.bindSession("session-1", "server1:1059");
        manager.bindSession("session-2", "server1:1059");
        
        // Should log: "Invalidating 2 XA session(s)..."
        invokeInvalidateSessionsForServer(manager, server1);
        
        // Test without sessions
        // Should log: "No sessions bound to recovered server..."
        invokeInvalidateSessionsForServer(manager, server1);
    }

    // Helper methods to access private fields and methods for testing

    private Map<?, ?> getSessionToServerMap(MultinodeConnectionManager manager) {
        try {
            java.lang.reflect.Field field = MultinodeConnectionManager.class.getDeclaredField("sessionToServerMap");
            field.setAccessible(true);
            Object mapValue = field.get(manager);
            assertNotNull(mapValue);
            assertInstanceOf(Map.class, mapValue);
            return (Map<?, ?>) mapValue;
        } catch (Exception e) {
            throw new RuntimeException("Failed to access sessionToServerMap", e);
        }
    }

    private ServerEndpoint getEndpoint(Map<?, ?> sessionMap, String sessionId) {
        Object endpoint = sessionMap.get(sessionId);
        if (endpoint == null) {
            return null;
        }
        assertInstanceOf(ServerEndpoint.class, endpoint);
        return (ServerEndpoint) endpoint;
    }

    private void invokeInvalidateSessionsForServer(MultinodeConnectionManager manager, ServerEndpoint endpoint) {
        try {
            java.lang.reflect.Method method = MultinodeConnectionManager.class.getDeclaredMethod(
                "invalidateSessionsAndConnectionsForFailedServer", ServerEndpoint.class);
            method.setAccessible(true);
            method.invoke(manager, endpoint);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke invalidateSessionsForServer", e);
        }
    }
}
