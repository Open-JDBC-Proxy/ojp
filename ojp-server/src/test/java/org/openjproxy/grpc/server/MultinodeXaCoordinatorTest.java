package org.openjproxy.grpc.server;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultinodeXaCoordinatorTest {

    @Test
    void testSingleNodeConfiguration() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();
        
        // Single node (empty server list)
        MultinodeXaCoordinator.XaAllocation allocation = 
                coordinator.calculateXaLimits("conn1", 30, null);
        
        assertEquals(30, allocation.getCurrentMaxTransactions());
        assertEquals(1, allocation.getTotalServers());
        assertEquals(1, allocation.getHealthyServers());
    }

    @Test
    void testMultinodeDivision() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();
        
        // Two servers: XA limits should be divided
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        MultinodeXaCoordinator.XaAllocation allocation = 
                coordinator.calculateXaLimits("conn1", 30, servers);
        
        assertEquals(15, allocation.getCurrentMaxTransactions()); // 30 / 2 = 15
        assertEquals(2, allocation.getTotalServers());
        assertEquals(2, allocation.getHealthyServers());
    }

    @Test
    void testMultinodeDivisionWithRounding() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();
        
        // Three servers: limits should be divided with rounding up
        List<String> servers = Arrays.asList("server1:1059", "server2:1059", "server3:1059");
        MultinodeXaCoordinator.XaAllocation allocation = 
                coordinator.calculateXaLimits("conn1", 50, servers);
        
        assertEquals(17, allocation.getCurrentMaxTransactions()); // ceil(50 / 3) = 17
        assertEquals(3, allocation.getTotalServers());
    }

    @Test
    void testHealthyServerUpdate() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059", "server3:1059");
        MultinodeXaCoordinator.XaAllocation allocation = 
                coordinator.calculateXaLimits("conn1", 30, servers);
        
        // Initially 3 servers, each gets 10 max transactions
        assertEquals(10, allocation.getCurrentMaxTransactions());
        
        // One server goes down, remaining 2 should split the load
        coordinator.updateHealthyServers("conn1", 2);
        allocation = coordinator.getXaAllocation("conn1");
        
        assertEquals(15, allocation.getCurrentMaxTransactions()); // ceil(30 / 2) = 15
        assertEquals(2, allocation.getHealthyServers());
        
        // Server recovers, back to 3 servers
        coordinator.updateHealthyServers("conn1", 3);
        allocation = coordinator.getXaAllocation("conn1");
        
        assertEquals(10, allocation.getCurrentMaxTransactions());
        assertEquals(3, allocation.getHealthyServers());
    }

    @Test
    void testAllServersDown() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        MultinodeXaCoordinator.XaAllocation allocation = 
                coordinator.calculateXaLimits("conn1", 40, servers);
        
        // All servers marked unhealthy (0 healthy)
        coordinator.updateHealthyServers("conn1", 0);
        allocation = coordinator.getXaAllocation("conn1");
        
        // Should fall back to original with at least 1 healthy server
        assertEquals(40, allocation.getCurrentMaxTransactions());
        assertEquals(1, allocation.getHealthyServers()); // Min 1
    }

    @Test
    void testMultipleConnectionHashes() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();
        
        List<String> servers1 = Arrays.asList("server1:1059", "server2:1059");
        List<String> servers2 = Arrays.asList("server1:1059", "server2:1059", "server3:1059");
        
        coordinator.calculateXaLimits("conn1", 40, servers1);
        coordinator.calculateXaLimits("conn2", 60, servers2);
        
        MultinodeXaCoordinator.XaAllocation alloc1 = coordinator.getXaAllocation("conn1");
        MultinodeXaCoordinator.XaAllocation alloc2 = coordinator.getXaAllocation("conn2");
        
        assertEquals(20, alloc1.getCurrentMaxTransactions());
        assertEquals(20, alloc2.getCurrentMaxTransactions());
        
        // Update health for conn1 only
        coordinator.updateHealthyServers("conn1", 1);
        
        alloc1 = coordinator.getXaAllocation("conn1");
        alloc2 = coordinator.getXaAllocation("conn2");
        
        assertEquals(40, alloc1.getCurrentMaxTransactions()); // All load on 1 server
        assertEquals(20, alloc2.getCurrentMaxTransactions()); // Unchanged
    }

    @Test
    void testRemoveAllocation() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        coordinator.calculateXaLimits("conn1", 50, servers);
        
        assertNotNull(coordinator.getXaAllocation("conn1"));
        
        coordinator.removeAllocation("conn1");
        
        assertNull(coordinator.getXaAllocation("conn1"));
    }

    @Test
    void testHealthyServerCountBounds() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        MultinodeXaCoordinator.XaAllocation allocation = 
                coordinator.calculateXaLimits("conn1", 30, servers);
        
        // Try to set more healthy servers than total
        coordinator.updateHealthyServers("conn1", 5);
        allocation = coordinator.getXaAllocation("conn1");
        
        assertEquals(2, allocation.getHealthyServers()); // Capped at total
        
        // Try to set negative healthy servers
        coordinator.updateHealthyServers("conn1", -1);
        allocation = coordinator.getXaAllocation("conn1");
        
        assertEquals(1, allocation.getHealthyServers()); // Min 1
    }

    @Test
    void testRealWorldScenario() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();
        
        // Real-world: 3 nodes, 30 max XA transactions
        List<String> servers = Arrays.asList("node1:1059", "node2:1059", "node3:1059");
        MultinodeXaCoordinator.XaAllocation allocation = 
                coordinator.calculateXaLimits("prod-db", 30, servers);
        
        // Normal operation: 10 transactions per server
        assertEquals(10, allocation.getCurrentMaxTransactions());
        assertEquals(30, allocation.getOriginalMaxTransactions());
        
        // Node 1 goes down
        coordinator.updateHealthyServers("prod-db", 2);
        allocation = coordinator.getXaAllocation("prod-db");
        
        // Remaining nodes handle 15 each
        assertEquals(15, allocation.getCurrentMaxTransactions());
        
        // Node 1 recovers
        coordinator.updateHealthyServers("prod-db", 3);
        allocation = coordinator.getXaAllocation("prod-db");
        
        // Back to normal: 10 each
        assertEquals(10, allocation.getCurrentMaxTransactions());
    }
}
