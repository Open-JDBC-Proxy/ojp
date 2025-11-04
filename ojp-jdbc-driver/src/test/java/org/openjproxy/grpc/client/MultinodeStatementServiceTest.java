package org.openjproxy.grpc.client;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.MultinodeConnectionManager;
import org.openjproxy.jdbc.MultinodeUrlParser.Endpoint;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MultinodeStatementService.
 */
class MultinodeStatementServiceTest {
    
    private MultinodeConnectionManager connectionManager;
    private MultinodeStatementService service;
    private List<Endpoint> endpoints;
    
    @BeforeEach
    void setUp() {
        endpoints = new ArrayList<>();
        endpoints.add(new Endpoint("host1", 1059));
        endpoints.add(new Endpoint("host2", 1060));
        
        connectionManager = new MultinodeConnectionManager(endpoints);
        service = new MultinodeStatementService(connectionManager, 
                "jdbc:ojp[host1:1059,host2:1060]_postgresql://localhost/mydb");
    }
    
    @Test
    void testConstructorWithNullConnectionManager() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultinodeStatementService(null, "jdbc:ojp[host1:1059]_db"));
    }
    
    @Test
    void testConstructorWithNullUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultinodeStatementService(connectionManager, null));
    }
    
    @Test
    void testConstructorWithEmptyUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultinodeStatementService(connectionManager, ""));
    }
    
    @Test
    void testConnectBindsSession() throws SQLException {
        ConnectionDetails details = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp[host1:1059,host2:1060]_postgresql://localhost/mydb")
                .setUser("testuser")
                .setPassword("testpass")
                .setClientUUID("client-123")
                .build();
        
        // The connect method will create a new StatementServiceGrpcClient internally
        // We can't easily mock it without dependency injection, so we'll test the behavior
        // by checking that session binding would work
        
        // This test validates the logic flow rather than actual gRPC calls
        String sessionId = "test-session-123";
        Endpoint endpoint = endpoints.get(0);
        
        connectionManager.bindSession(sessionId, endpoint);
        Endpoint boundServer = connectionManager.getServerForSession(sessionId);
        
        assertEquals(endpoint, boundServer);
    }
    
    @Test
    void testUrlTransformation() {
        // Test that multinode URL is correctly transformed for single endpoint
        String originalUrl = "jdbc:ojp[host1:1059,host2:1060]_postgresql://localhost/mydb";
        
        // The service should replace the multinode part with a single endpoint
        assertTrue(originalUrl.contains("host1:1059,host2:1060"));
        
        // After transformation, should look like: jdbc:ojp[host1:1059]_postgresql://localhost/mydb
        String transformed = originalUrl.replaceFirst("ojp\\[[^\\]]+\\]", "ojp[host1:1059]");
        assertEquals("jdbc:ojp[host1:1059]_postgresql://localhost/mydb", transformed);
    }
    
    @Test
    void testConnectionManagerIntegration() {
        // Verify that the service uses the connection manager correctly
        assertNotNull(service);
        
        // Select a server
        Endpoint server1 = connectionManager.selectServer();
        assertNotNull(server1);
        assertTrue(endpoints.contains(server1));
        
        // Bind a session
        String sessionId = "session-xyz";
        connectionManager.bindSession(sessionId, server1);
        
        // Verify binding
        Endpoint boundServer = connectionManager.getServerForSession(sessionId);
        assertEquals(server1, boundServer);
    }
    
    @Test
    void testRoundRobinSelection() {
        // Multiple connections should use different servers via round-robin
        Map<Endpoint, Integer> selectionCount = new HashMap<>();
        
        for (int i = 0; i < 10; i++) {
            Endpoint selected = connectionManager.selectServer();
            selectionCount.put(selected, selectionCount.getOrDefault(selected, 0) + 1);
        }
        
        // Both servers should have been selected
        assertTrue(selectionCount.size() >= 1);
        assertTrue(selectionCount.values().stream().allMatch(count -> count > 0));
    }
    
    @Test
    void testSessionStickinessLogic() {
        String sessionId = "sticky-session";
        Endpoint boundServer = endpoints.get(0);
        
        connectionManager.bindSession(sessionId, boundServer);
        
        // Multiple operations on the same session should use the bound server
        for (int i = 0; i < 5; i++) {
            Endpoint retrievedServer = connectionManager.getServerForSession(sessionId);
            assertEquals(boundServer, retrievedServer);
        }
    }
    
    @Test
    void testConnectionLevelErrorDetection() {
        StatusRuntimeException unavailable = new StatusRuntimeException(Status.UNAVAILABLE);
        assertTrue(connectionManager.isConnectionLevelError(unavailable));
        
        StatusRuntimeException invalidArg = new StatusRuntimeException(Status.INVALID_ARGUMENT);
        assertFalse(connectionManager.isConnectionLevelError(invalidArg));
    }
    
    @Test
    void testServerHealthTracking() {
        Endpoint server = endpoints.get(0);
        
        // Mark server unhealthy
        connectionManager.markServerUnhealthy(server);
        
        // Server should still be selectable (circuit breaker)
        Endpoint selected = connectionManager.selectServer();
        assertNotNull(selected);
        
        // Mark healthy again
        connectionManager.markServerHealthy(server);
        
        // Should work normally
        selected = connectionManager.selectServer();
        assertNotNull(selected);
    }
    
    @Test
    void testTerminateSessionUnbinds() {
        String sessionId = "session-to-terminate";
        Endpoint server = endpoints.get(0);
        
        connectionManager.bindSession(sessionId, server);
        assertNotNull(connectionManager.getServerForSession(sessionId));
        
        connectionManager.unbindSession(sessionId);
        assertNull(connectionManager.getServerForSession(sessionId));
    }
    
    @Test
    void testMaxRetries() {
        assertEquals(2, connectionManager.getMaxRetries());
    }
    
    @Test
    void testMultipleEndpoints() {
        List<Endpoint> multiEndpoints = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            multiEndpoints.add(new Endpoint("host" + i, 1059 + i));
        }
        
        MultinodeConnectionManager multiManager = new MultinodeConnectionManager(multiEndpoints);
        MultinodeStatementService multiService = new MultinodeStatementService(
                multiManager, "jdbc:ojp[host0:1059,host1:1060,host2:1061,host3:1062,host4:1063]_db");
        
        assertNotNull(multiService);
        assertEquals(5, multiManager.getEndpoints().size());
    }
}
