package org.openjproxy.grpc.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.sql.XAConnection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for XAConnectionRedistributor.
 * Tests redistribution policy, filtering, caps, and error handling.
 */
class XAConnectionRedistributorTest {
    
    private ConnectionTracker connectionTracker;
    private HealthCheckConfig config;
    private XAConnectionRedistributor redistributor;
    
    @BeforeEach
    void setUp() {
        connectionTracker = mock(ConnectionTracker.class);
        config = mock(HealthCheckConfig.class);
        redistributor = new XAConnectionRedistributor(connectionTracker, config);
    }
    
    @Test
    @DisplayName("Test 1: No-op when redistribution disabled")
    void testRedistributionDisabled() {
        // Setup: redistribution disabled
        when(config.isRedistributionEnabled()).thenReturn(false);
        
        ServerEndpoint recoveredEndpoint = new ServerEndpoint("server1", 10591);
        
        // Execute
        redistributor.redistribute(recoveredEndpoint);
        
        // Verify: tracker methods never called
        verify(connectionTracker, never()).listIdleXaConnections();
        verify(config, times(1)).isRedistributionEnabled();
    }
    
    @Test
    @DisplayName("Test 2: No idle candidates")
    void testNoIdleCandidates() throws SQLException {
        // Setup: enabled but no idle connections
        when(config.isRedistributionEnabled()).thenReturn(true);
        when(connectionTracker.listIdleXaConnections()).thenReturn(new ArrayList<>());
        
        ServerEndpoint recoveredEndpoint = new ServerEndpoint("server1", 10591);
        
        // Execute
        redistributor.redistribute(recoveredEndpoint);
        
        // Verify: listed idle connections but didn't try to close any
        verify(connectionTracker, times(1)).listIdleXaConnections();
        verify(connectionTracker, never()).closeIdleConnection(anyString());
    }
    
    @Test
    @DisplayName("Test 3: Candidate filtering and selection")
    void testCandidateFilteringAndSelection() throws SQLException {
        // Setup: 10 idle connections, 3 bound to recovered server
        ServerEndpoint recoveredServer = new ServerEndpoint("server1", 10591);
        ServerEndpoint otherServer = new ServerEndpoint("server2", 10592);
        
        List<XAConnectionInfo> allIdleConnections = new ArrayList<>();
        
        // 3 connections on recovered server (should be filtered out)
        for (int i = 0; i < 3; i++) {
            XAConnection mockXA = mock(XAConnection.class);
            XAConnectionInfo info = new XAConnectionInfo(
                "conn-recovered-" + i, mockXA, recoveredServer, System.currentTimeMillis() - (i * 1000));
            allIdleConnections.add(info);
        }
        
        // 7 connections on other server (candidates for closing)
        for (int i = 0; i < 7; i++) {
            XAConnection mockXA = mock(XAConnection.class);
            XAConnectionInfo info = new XAConnectionInfo(
                "conn-other-" + i, mockXA, otherServer, System.currentTimeMillis() - (i * 1000));
            allIdleConnections.add(info);
        }
        
        when(config.isRedistributionEnabled()).thenReturn(true);
        when(config.getIdleRebalanceFraction()).thenReturn(0.3); // 30%
        when(config.getMaxClosePerRecovery()).thenReturn(10); // Large enough not to limit
        when(connectionTracker.listIdleXaConnections()).thenReturn(allIdleConnections);
        when(connectionTracker.closeIdleConnection(anyString())).thenReturn(true);
        
        // Execute
        redistributor.redistribute(recoveredServer);
        
        // Verify: 7 candidates * 0.3 = 2.1 -> ceil(2.1) = 3 connections closed
        verify(connectionTracker, times(3)).closeIdleConnection(startsWith("conn-other-"));
    }
    
    @Test
    @DisplayName("Test 4: Max cap enforcement")
    void testMaxCapEnforcement() throws SQLException {
        // Setup: many candidates but maxClosePerRecovery limits closes
        ServerEndpoint recoveredServer = new ServerEndpoint("server1", 10591);
        ServerEndpoint otherServer = new ServerEndpoint("server2", 10592);
        
        List<XAConnectionInfo> allIdleConnections = new ArrayList<>();
        
        // 100 connections on other server
        for (int i = 0; i < 100; i++) {
            XAConnection mockXA = mock(XAConnection.class);
            XAConnectionInfo info = new XAConnectionInfo(
                "conn-" + i, mockXA, otherServer, System.currentTimeMillis() - (i * 1000));
            allIdleConnections.add(info);
        }
        
        when(config.isRedistributionEnabled()).thenReturn(true);
        when(config.getIdleRebalanceFraction()).thenReturn(0.5); // 50%
        when(config.getMaxClosePerRecovery()).thenReturn(5); // Cap at 5
        when(connectionTracker.listIdleXaConnections()).thenReturn(allIdleConnections);
        when(connectionTracker.closeIdleConnection(anyString())).thenReturn(true);
        
        // Execute
        redistributor.redistribute(recoveredServer);
        
        // Verify: Should close only 5 (maxClosePerRecovery), not 50 (100 * 0.5)
        verify(connectionTracker, times(5)).closeIdleConnection(anyString());
    }
    
    @Test
    @DisplayName("Test 5: Does not close connections already bound to recovered endpoint")
    void testDoesNotCloseConnectionsOnRecoveredEndpoint() throws SQLException {
        // Setup: all idle connections already on recovered server
        ServerEndpoint recoveredServer = new ServerEndpoint("server1", 10591);
        
        List<XAConnectionInfo> allIdleConnections = new ArrayList<>();
        
        // 10 connections all on recovered server
        for (int i = 0; i < 10; i++) {
            XAConnection mockXA = mock(XAConnection.class);
            XAConnectionInfo info = new XAConnectionInfo(
                "conn-" + i, mockXA, recoveredServer, System.currentTimeMillis() - (i * 1000));
            allIdleConnections.add(info);
        }
        
        when(config.isRedistributionEnabled()).thenReturn(true);
        when(config.getIdleRebalanceFraction()).thenReturn(0.3);
        when(config.getMaxClosePerRecovery()).thenReturn(10);
        when(connectionTracker.listIdleXaConnections()).thenReturn(allIdleConnections);
        
        // Execute
        redistributor.redistribute(recoveredServer);
        
        // Verify: No connections closed (all already on recovered server)
        verify(connectionTracker, never()).closeIdleConnection(anyString());
    }
    
    @Test
    @DisplayName("Test 6: Resilient to tracker errors")
    void testResilientToTrackerErrors() throws SQLException {
        // Setup: some closes succeed, some fail
        ServerEndpoint recoveredServer = new ServerEndpoint("server1", 10591);
        ServerEndpoint otherServer = new ServerEndpoint("server2", 10592);
        
        List<XAConnectionInfo> allIdleConnections = new ArrayList<>();
        
        // 5 connections on other server
        for (int i = 0; i < 5; i++) {
            XAConnection mockXA = mock(XAConnection.class);
            XAConnectionInfo info = new XAConnectionInfo(
                "conn-" + i, mockXA, otherServer, System.currentTimeMillis() - (i * 1000));
            allIdleConnections.add(info);
        }
        
        when(config.isRedistributionEnabled()).thenReturn(true);
        when(config.getIdleRebalanceFraction()).thenReturn(0.6); // 60% of 5 = 3
        when(config.getMaxClosePerRecovery()).thenReturn(10);
        when(connectionTracker.listIdleXaConnections()).thenReturn(allIdleConnections);
        
        // First two succeed, third fails, should continue
        when(connectionTracker.closeIdleConnection("conn-0")).thenReturn(true);
        when(connectionTracker.closeIdleConnection("conn-1")).thenReturn(true);
        when(connectionTracker.closeIdleConnection("conn-2"))
            .thenThrow(new SQLException("Connection close failed"));
        
        // Execute - should not throw
        assertDoesNotThrow(() -> redistributor.redistribute(recoveredServer));
        
        // Verify: All three attempts made despite one failure
        verify(connectionTracker, times(3)).closeIdleConnection(anyString());
    }
    
    @Test
    @DisplayName("Test null recovered endpoint")
    void testNullRecoveredEndpoint() {
        when(config.isRedistributionEnabled()).thenReturn(true);
        
        // Execute - should not throw
        assertDoesNotThrow(() -> redistributor.redistribute(null));
        
        // Verify: tracker not called
        verify(connectionTracker, never()).listIdleXaConnections();
    }
    
    @Test
    @DisplayName("Test zero fraction calculation")
    void testZeroFractionCalculation() throws SQLException {
        // Setup: fraction set to 0.01, only 1 candidate
        ServerEndpoint recoveredServer = new ServerEndpoint("server1", 10591);
        ServerEndpoint otherServer = new ServerEndpoint("server2", 10592);
        
        List<XAConnectionInfo> allIdleConnections = new ArrayList<>();
        XAConnection mockXA = mock(XAConnection.class);
        allIdleConnections.add(new XAConnectionInfo("conn-1", mockXA, otherServer, System.currentTimeMillis()));
        
        when(config.isRedistributionEnabled()).thenReturn(true);
        when(config.getIdleRebalanceFraction()).thenReturn(0.01); // 1% of 1 = 0.01 -> ceil = 1
        when(config.getMaxClosePerRecovery()).thenReturn(10);
        when(connectionTracker.listIdleXaConnections()).thenReturn(allIdleConnections);
        when(connectionTracker.closeIdleConnection(anyString())).thenReturn(true);
        
        // Execute
        redistributor.redistribute(recoveredServer);
        
        // Verify: ceil(1 * 0.01) = ceil(0.01) = 1 connection closed
        verify(connectionTracker, times(1)).closeIdleConnection("conn-1");
    }
    
    @Test
    @DisplayName("Test getConfig returns config")
    void testGetConfig() {
        assertEquals(config, redistributor.getConfig());
    }
}
