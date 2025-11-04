package org.openjproxy.jdbc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.MultinodeUrlParser.Endpoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MultinodeConnectionManager.
 */
class MultinodeConnectionManagerTest {
    
    private List<Endpoint> endpoints;
    private MultinodeConnectionManager manager;
    
    @BeforeEach
    void setUp() {
        endpoints = new ArrayList<>();
        endpoints.add(new Endpoint("host1", 1059));
        endpoints.add(new Endpoint("host2", 1060));
        endpoints.add(new Endpoint("host3", 1061));
        
        manager = new MultinodeConnectionManager(endpoints);
    }
    
    @Test
    void testConstructorWithNullEndpoints() {
        assertThrows(IllegalArgumentException.class, 
                () -> new MultinodeConnectionManager(null));
    }
    
    @Test
    void testConstructorWithEmptyEndpoints() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultinodeConnectionManager(new ArrayList<>()));
    }
    
    @Test
    void testSelectServerRoundRobin() {
        Set<Endpoint> selectedServers = new HashSet<>();
        
        // Select servers multiple times to verify round-robin
        for (int i = 0; i < 10; i++) {
            Endpoint server = manager.selectServer();
            assertNotNull(server);
            assertTrue(endpoints.contains(server));
            selectedServers.add(server);
        }
        
        // Should have selected all servers
        assertEquals(3, selectedServers.size());
    }
    
    @Test
    void testBindAndGetSession() {
        Endpoint server = endpoints.get(0);
        String sessionId = "session-123";
        
        manager.bindSession(sessionId, server);
        
        Endpoint boundServer = manager.getServerForSession(sessionId);
        assertEquals(server, boundServer);
    }
    
    @Test
    void testBindNullSession() {
        // Should not throw exception
        manager.bindSession(null, endpoints.get(0));
        manager.bindSession("", endpoints.get(0));
        
        assertNull(manager.getServerForSession(null));
        assertNull(manager.getServerForSession(""));
    }
    
    @Test
    void testGetServerForNonExistentSession() {
        Endpoint server = manager.getServerForSession("non-existent");
        assertNull(server);
    }
    
    @Test
    void testUnbindSession() {
        String sessionId = "session-456";
        manager.bindSession(sessionId, endpoints.get(0));
        
        assertNotNull(manager.getServerForSession(sessionId));
        
        manager.unbindSession(sessionId);
        
        assertNull(manager.getServerForSession(sessionId));
    }
    
    @Test
    void testMarkServerUnhealthy() {
        Endpoint server = endpoints.get(0);
        
        manager.markServerUnhealthy(server);
        
        // Server should still be selectable but marked unhealthy
        Endpoint selected = manager.selectServer();
        assertNotNull(selected);
    }
    
    @Test
    void testMarkServerHealthy() {
        Endpoint server = endpoints.get(0);
        
        manager.markServerUnhealthy(server);
        manager.markServerHealthy(server);
        
        // Server should be healthy again
        Endpoint selected = manager.selectServer();
        assertNotNull(selected);
    }
    
    @Test
    void testIsConnectionLevelErrorWithStatusRuntimeException() {
        StatusRuntimeException unavailable = new StatusRuntimeException(Status.UNAVAILABLE);
        assertTrue(manager.isConnectionLevelError(unavailable));
        
        StatusRuntimeException deadlineExceeded = new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
        assertTrue(manager.isConnectionLevelError(deadlineExceeded));
        
        StatusRuntimeException cancelled = new StatusRuntimeException(Status.CANCELLED);
        assertTrue(manager.isConnectionLevelError(cancelled));
        
        StatusRuntimeException unknown = new StatusRuntimeException(Status.UNKNOWN);
        assertTrue(manager.isConnectionLevelError(unknown));
    }
    
    @Test
    void testIsConnectionLevelErrorWithDatabaseError() {
        StatusRuntimeException invalidArgument = new StatusRuntimeException(Status.INVALID_ARGUMENT);
        assertFalse(manager.isConnectionLevelError(invalidArgument));
        
        StatusRuntimeException permissionDenied = new StatusRuntimeException(Status.PERMISSION_DENIED);
        assertFalse(manager.isConnectionLevelError(permissionDenied));
    }
    
    @Test
    void testIsConnectionLevelErrorWithMessage() {
        Exception connectionRefused = new Exception("Connection refused");
        assertTrue(manager.isConnectionLevelError(connectionRefused));
        
        Exception connectionReset = new Exception("Connection reset by peer");
        assertTrue(manager.isConnectionLevelError(connectionReset));
        
        Exception timeout = new Exception("Connection timeout occurred");
        assertTrue(manager.isConnectionLevelError(timeout));
        
        Exception networkError = new Exception("Network error detected");
        assertTrue(manager.isConnectionLevelError(networkError));
        
        Exception brokenPipe = new Exception("Broken pipe error");
        assertTrue(manager.isConnectionLevelError(brokenPipe));
    }
    
    @Test
    void testIsConnectionLevelErrorWithNonConnectionError() {
        Exception databaseError = new Exception("Constraint violation");
        assertFalse(manager.isConnectionLevelError(databaseError));
        
        Exception sqlError = new Exception("Syntax error in SQL");
        assertFalse(manager.isConnectionLevelError(sqlError));
    }
    
    @Test
    void testIsConnectionLevelErrorWithNull() {
        assertFalse(manager.isConnectionLevelError(null));
    }
    
    @Test
    void testGetMaxRetries() {
        assertEquals(2, manager.getMaxRetries());
    }
    
    @Test
    void testCustomRetryConfiguration() {
        MultinodeConnectionManager customManager = new MultinodeConnectionManager(endpoints, 5, 60000L);
        assertEquals(5, customManager.getMaxRetries());
    }
    
    @Test
    void testGetEndpoints() {
        List<Endpoint> result = manager.getEndpoints();
        assertEquals(endpoints.size(), result.size());
        assertTrue(result.containsAll(endpoints));
    }
    
    @Test
    void testMultipleSessionBindings() {
        String session1 = "session-1";
        String session2 = "session-2";
        String session3 = "session-3";
        
        manager.bindSession(session1, endpoints.get(0));
        manager.bindSession(session2, endpoints.get(1));
        manager.bindSession(session3, endpoints.get(2));
        
        assertEquals(endpoints.get(0), manager.getServerForSession(session1));
        assertEquals(endpoints.get(1), manager.getServerForSession(session2));
        assertEquals(endpoints.get(2), manager.getServerForSession(session3));
    }
    
    @Test
    void testRebindSession() {
        String sessionId = "session-rebind";
        
        manager.bindSession(sessionId, endpoints.get(0));
        assertEquals(endpoints.get(0), manager.getServerForSession(sessionId));
        
        // Rebind to different server
        manager.bindSession(sessionId, endpoints.get(1));
        assertEquals(endpoints.get(1), manager.getServerForSession(sessionId));
    }
    
    @Test
    void testSelectServerWithAllUnhealthy() {
        // Mark all servers as unhealthy
        for (Endpoint endpoint : endpoints) {
            manager.markServerUnhealthy(endpoint);
        }
        
        // Should still return a server (circuit breaker pattern)
        Endpoint selected = manager.selectServer();
        assertNotNull(selected);
        assertTrue(endpoints.contains(selected));
    }
    
    @Test
    void testThreadSafetyOfSessionBinding() throws InterruptedException {
        int numThreads = 10;
        Thread[] threads = new Thread[numThreads];
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                String sessionId = "session-" + threadId;
                Endpoint endpoint = endpoints.get(threadId % endpoints.size());
                manager.bindSession(sessionId, endpoint);
                
                Endpoint boundServer = manager.getServerForSession(sessionId);
                assertEquals(endpoint, boundServer);
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
    }
}
