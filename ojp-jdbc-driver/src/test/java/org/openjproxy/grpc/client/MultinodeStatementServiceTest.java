package org.openjproxy.grpc.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MultinodeStatementService.
 * Tests the multinode functionality without requiring actual server connections.
 */
class MultinodeStatementServiceTest {

    private List<ServerEndpoint> endpoints;
    private MultinodeStatementService service;
    private MultinodeConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        // Create test endpoints
        endpoints = Arrays.asList(
            new ServerEndpoint("server1", 1059),
            new ServerEndpoint("server2", 1059),
            new ServerEndpoint("server3", 1059)
        );
        
        // Create a real connection manager (it won't actually connect in unit tests)
        connectionManager = new MultinodeConnectionManager(endpoints);
    }

    @Test
    void testConstruction() {
        // Test that service can be constructed
        service = new MultinodeStatementService(connectionManager, "jdbc:ojp[server1:1059,server2:1059]_h2:mem:test");
        assertNotNull(service);
    }

    @Test
    void testConstructionWithSingleEndpoint() {
        // Test with single endpoint
        List<ServerEndpoint> singleEndpoint = List.of(
                new ServerEndpoint("localhost", 1059)
        );
        MultinodeConnectionManager singleManager = new MultinodeConnectionManager(singleEndpoint);
        service = new MultinodeStatementService(singleManager, "jdbc:ojp[localhost:1059]_h2:mem:test");
        assertNotNull(service);
    }

    @Test
    void testNullConnectionManagerThrows() {
        // Test that null connection manager throws exception
        assertThrows(NullPointerException.class, () -> {
            new MultinodeStatementService(null, "jdbc:ojp[server1:1059]_h2:mem:test");
        });
    }

    @Test
    void testShutdown() {
        // Setup
        service = new MultinodeStatementService(connectionManager, "jdbc:ojp[server1:1059,server2:1059]_h2:mem:test");
        
        // Execute - should not throw
        assertDoesNotThrow(() -> service.shutdown());
    }

    @Test
    void testMultipleInstances() {
        // Test that multiple service instances can be created
        MultinodeStatementService service1 = new MultinodeStatementService(
            connectionManager, "jdbc:ojp[server1:1059,server2:1059]_h2:mem:test1");
        
        MultinodeStatementService service2 = new MultinodeStatementService(
            connectionManager, "jdbc:ojp[server1:1059,server2:1059]_h2:mem:test2");
        
        assertNotNull(service1);
        assertNotNull(service2);
        assertNotSame(service1, service2);
    }
}
