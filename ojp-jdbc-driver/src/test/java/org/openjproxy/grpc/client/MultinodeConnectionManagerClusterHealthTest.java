package org.openjproxy.grpc.client;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultinodeConnectionManagerClusterHealthTest {

    @Test
    void testGenerateClusterHealthAllHealthy() {
        List<ServerEndpoint> endpoints = Arrays.asList(
            new ServerEndpoint("server1", 1059),
            new ServerEndpoint("server2", 1059),
            new ServerEndpoint("server3", 1059)
        );
        
        MultinodeConnectionManager manager = new MultinodeConnectionManager(endpoints);
        
        String clusterHealth = manager.generateClusterHealth();
        
        assertEquals("server1:1059(UP);server2:1059(UP);server3:1059(UP)", clusterHealth);
    }

    @Test
    void testGenerateClusterHealthPartialFailure() {
        List<ServerEndpoint> endpoints = Arrays.asList(
            new ServerEndpoint("server1", 1059),
            new ServerEndpoint("server2", 1059),
            new ServerEndpoint("server3", 1059)
        );
        
        MultinodeConnectionManager manager = new MultinodeConnectionManager(endpoints);
        // Mark server2 as unhealthy
        endpoints.get(1).setHealthy(false);

        String clusterHealth = manager.generateClusterHealth();
        
        assertEquals("server1:1059(UP);server2:1059(DOWN);server3:1059(UP)", clusterHealth);
    }

    @Test
    void testGenerateClusterHealthAllDown() {
        List<ServerEndpoint> endpoints = Arrays.asList(
            new ServerEndpoint("server1", 1059),
            new ServerEndpoint("server2", 1059)
        );
        
        MultinodeConnectionManager manager = new MultinodeConnectionManager(endpoints);
        endpoints.get(0).setHealthy(false);
        endpoints.get(1).setHealthy(false);

        String clusterHealth = manager.generateClusterHealth();
        
        assertEquals("server1:1059(DOWN);server2:1059(DOWN)", clusterHealth);
    }

    @Test
    void testGenerateClusterHealthSingleServer() {
        List<ServerEndpoint> endpoints = List.of(
                new ServerEndpoint("localhost", 1059)
        );
        
        MultinodeConnectionManager manager = new MultinodeConnectionManager(endpoints);
        
        String clusterHealth = manager.generateClusterHealth();
        
        assertEquals("localhost:1059(UP)", clusterHealth);
    }

    @Test
    void testGenerateClusterHealthAfterRecovery() {
        List<ServerEndpoint> endpoints = Arrays.asList(
            new ServerEndpoint("server1", 1059),
            new ServerEndpoint("server2", 1059)
        );
        
        MultinodeConnectionManager manager = new MultinodeConnectionManager(endpoints);
        // Initially server2 is down
        endpoints.get(1).setHealthy(false);

        String healthBeforeRecovery = manager.generateClusterHealth();
        assertEquals("server1:1059(UP);server2:1059(DOWN)", healthBeforeRecovery);
        
        // Server2 recovers
        endpoints.get(1).setHealthy(true);
        
        String healthAfterRecovery = manager.generateClusterHealth();
        assertEquals("server1:1059(UP);server2:1059(UP)", healthAfterRecovery);
    }

    @Test
    void testGenerateClusterHealthVariousPorts() {
        List<ServerEndpoint> endpoints = Arrays.asList(
            new ServerEndpoint("server1", 1059),
            new ServerEndpoint("server2", 8080),
            new ServerEndpoint("server3", 9999)
        );
        
        MultinodeConnectionManager manager = new MultinodeConnectionManager(endpoints);
        
        String clusterHealth = manager.generateClusterHealth();
        
        assertEquals("server1:1059(UP);server2:8080(UP);server3:9999(UP)", clusterHealth);
    }

    @Test
    void testGenerateClusterHealthIPAddresses() {
        List<ServerEndpoint> endpoints = Arrays.asList(
            new ServerEndpoint("192.168.1.1", 1059),
            new ServerEndpoint("192.168.1.2", 1059),
            new ServerEndpoint("192.168.1.3", 1059)
        );
        
        MultinodeConnectionManager manager = new MultinodeConnectionManager(endpoints);
        endpoints.get(1).setHealthy(false);

        String clusterHealth = manager.generateClusterHealth();
        
        assertEquals("192.168.1.1:1059(UP);192.168.1.2:1059(DOWN);192.168.1.3:1059(UP)", clusterHealth);
    }
}
