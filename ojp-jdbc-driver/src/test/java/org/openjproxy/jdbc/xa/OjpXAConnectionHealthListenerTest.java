package org.openjproxy.jdbc.xa;

import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.openjproxy.grpc.client.ConnectionTracker;
import org.openjproxy.grpc.client.MultinodeConnectionManager;
import org.openjproxy.grpc.client.MultinodeStatementService;
import org.openjproxy.grpc.client.ServerEndpoint;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OjpXAConnection health listener registration.
 * Test 8: OjpXAConnection registers/removes health listener.
 */
class OjpXAConnectionHealthListenerTest {
    
    private MultinodeStatementService mockStatementService;
    private MultinodeConnectionManager mockConnectionManager;
    private ConnectionTracker mockTracker;
    private List<String> serverEndpoints;
    
    @BeforeEach
    void setUp() {
        mockStatementService = mock(MultinodeStatementService.class);
        mockConnectionManager = mock(MultinodeConnectionManager.class);
        mockTracker = mock(ConnectionTracker.class);
        
        serverEndpoints = Arrays.asList("localhost:10591", "localhost:10592");
        
        when(mockStatementService.getConnectionManager()).thenReturn(mockConnectionManager);
        when(mockConnectionManager.getConnectionTracker()).thenReturn(mockTracker);
    }
    
    @Test
    @DisplayName("Test OjpXAConnection registers as health listener on creation")
    void testRegistersHealthListenerOnCreation() {
        // Create XA connection
        OjpXAConnection xaConnection = new OjpXAConnection(
            mockStatementService, 
            "jdbc:postgresql://localhost:5432/testdb",
            "user",
            "password",
            new Properties(),
            serverEndpoints
        );
        
        // Verify: addHealthListener was called
        verify(mockConnectionManager, times(1)).addHealthListener(xaConnection);
    }
    
    @Test
    @DisplayName("Test OjpXAConnection unregisters health listener on close")
    void testUnregistersHealthListenerOnClose() throws SQLException {
        // Create XA connection
        OjpXAConnection xaConnection = new OjpXAConnection(
            mockStatementService,
            "jdbc:postgresql://localhost:5432/testdb",
            "user",
            "password",
            new Properties(),
            serverEndpoints
        );
        
        // Close the connection
        xaConnection.close();
        
        // Verify: removeHealthListener was called
        verify(mockConnectionManager, times(1)).removeHealthListener(xaConnection);
    }
    
    @Test
    @DisplayName("Test OjpXAConnection registers with tracker after session creation")
    void testRegistersWithTrackerAfterSessionCreation() throws SQLException {
        // Setup mock session info
        SessionInfo mockSessionInfo = SessionInfo.newBuilder()
            .setSessionUUID("test-session-uuid")
            .setTargetServer("localhost:10591")
            .build();
        
        when(mockStatementService.connect(any())).thenReturn(mockSessionInfo);
        
        // Setup server endpoints
        ServerEndpoint endpoint1 = new ServerEndpoint("localhost", 10591);
        ServerEndpoint endpoint2 = new ServerEndpoint("localhost", 10592);
        when(mockConnectionManager.getServerEndpoints()).thenReturn(Arrays.asList(endpoint1, endpoint2));
        
        // Create XA connection
        OjpXAConnection xaConnection = new OjpXAConnection(
            mockStatementService,
            "jdbc:postgresql://localhost:5432/testdb",
            "user",
            "password",
            new Properties(),
            serverEndpoints
        );
        
        // Trigger session creation by getting XA resource
        xaConnection.getXAResource();
        
        // Verify: registerXAConnection was called with correct parameters
        verify(mockTracker, times(1)).registerXAConnection(
            eq(xaConnection.getConnectionUuid()),
            eq(xaConnection),
            eq(endpoint1)
        );
    }
    
    @Test
    @DisplayName("Test OjpXAConnection unregisters from tracker on close")
    void testUnregistersFromTrackerOnClose() throws SQLException {
        // Create XA connection
        OjpXAConnection xaConnection = new OjpXAConnection(
            mockStatementService,
            "jdbc:postgresql://localhost:5432/testdb",
            "user",
            "password",
            new Properties(),
            serverEndpoints
        );
        
        String uuid = xaConnection.getConnectionUuid();
        
        // Close the connection
        xaConnection.close();
        
        // Verify: unregisterXAConnection was called with correct UUID
        verify(mockTracker, times(1)).unregisterXAConnection(uuid);
    }
    
    @Test
    @DisplayName("Test connection UUID is unique for each instance")
    void testConnectionUuidIsUnique() {
        OjpXAConnection xaConnection1 = new OjpXAConnection(
            mockStatementService,
            "jdbc:postgresql://localhost:5432/testdb",
            "user",
            "password",
            new Properties(),
            serverEndpoints
        );
        
        OjpXAConnection xaConnection2 = new OjpXAConnection(
            mockStatementService,
            "jdbc:postgresql://localhost:5432/testdb",
            "user",
            "password",
            new Properties(),
            serverEndpoints
        );
        
        assertNotNull(xaConnection1.getConnectionUuid());
        assertNotNull(xaConnection2.getConnectionUuid());
        assertNotEquals(xaConnection1.getConnectionUuid(), xaConnection2.getConnectionUuid());
    }
    
    @Test
    @DisplayName("Test close is idempotent")
    void testCloseIsIdempotent() throws SQLException {
        OjpXAConnection xaConnection = new OjpXAConnection(
            mockStatementService,
            "jdbc:postgresql://localhost:5432/testdb",
            "user",
            "password",
            new Properties(),
            serverEndpoints
        );
        
        // Close twice
        xaConnection.close();
        xaConnection.close();
        
        // Verify: unregister only called once
        verify(mockConnectionManager, times(1)).removeHealthListener(xaConnection);
        verify(mockTracker, times(1)).unregisterXAConnection(xaConnection.getConnectionUuid());
    }
    
    @Test
    @DisplayName("Test handles null connection manager gracefully")
    void testHandlesNullConnectionManagerGracefully() {
        // Return null connection manager
        when(mockStatementService.getConnectionManager()).thenReturn(null);
        
        // Should not throw exception
        assertDoesNotThrow(() -> {
            OjpXAConnection xaConnection = new OjpXAConnection(
                mockStatementService,
                "jdbc:postgresql://localhost:5432/testdb",
                "user",
                "password",
                new Properties(),
                serverEndpoints
            );
            xaConnection.close();
        });
    }
}
