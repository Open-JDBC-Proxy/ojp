package org.openjproxy.grpc.server;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultinodePoolCoordinatorTest {

    @Test
    void testSingleNodeConfiguration() {
        MultinodePoolCoordinator coordinator = new MultinodePoolCoordinator();
        
        // Single node (empty server list)
        MultinodePoolCoordinator.PoolAllocation allocation = 
                coordinator.calculatePoolSizes("conn1", 20, 5, null);
        
        assertEquals(20, allocation.getCurrentMaxPoolSize());
        assertEquals(5, allocation.getCurrentMinIdle());
        assertEquals(1, allocation.getTotalServers());
        assertEquals(1, allocation.getHealthyServers());
    }

    @Test
    void testMultinodeDivision() {
        MultinodePoolCoordinator coordinator = new MultinodePoolCoordinator();
        
        // Two servers: pool should be divided
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        MultinodePoolCoordinator.PoolAllocation allocation = 
                coordinator.calculatePoolSizes("conn1", 20, 6, servers);
        
        assertEquals(10, allocation.getCurrentMaxPoolSize()); // 20 / 2 = 10
        assertEquals(3, allocation.getCurrentMinIdle()); // 6 / 2 = 3
        assertEquals(2, allocation.getTotalServers());
        assertEquals(2, allocation.getHealthyServers());
    }

    @Test
    void testMultinodeDivisionWithRounding() {
        MultinodePoolCoordinator coordinator = new MultinodePoolCoordinator();
        
        // Three servers: pool should be divided with rounding up
        List<String> servers = Arrays.asList("server1:1059", "server2:1059", "server3:1059");
        MultinodePoolCoordinator.PoolAllocation allocation = 
                coordinator.calculatePoolSizes("conn1", 20, 5, servers);
        
        assertEquals(7, allocation.getCurrentMaxPoolSize()); // ceil(20 / 3) = 7
        assertEquals(2, allocation.getCurrentMinIdle()); // ceil(5 / 3) = 2
        assertEquals(3, allocation.getTotalServers());
    }

    @Test
    void testHealthyServerUpdate() {
        MultinodePoolCoordinator coordinator = new MultinodePoolCoordinator();
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059", "server3:1059");
        MultinodePoolCoordinator.PoolAllocation allocation = 
                coordinator.calculatePoolSizes("conn1", 30, 6, servers);
        
        // Initially 3 servers, each gets 10 max / 2 min
        assertEquals(10, allocation.getCurrentMaxPoolSize());
        assertEquals(2, allocation.getCurrentMinIdle());
        
        // One server goes down, remaining 2 should split the load
        coordinator.updateHealthyServers("conn1", 2);
        allocation = coordinator.getPoolAllocation("conn1");
        
        assertEquals(15, allocation.getCurrentMaxPoolSize()); // ceil(30 / 2) = 15
        assertEquals(3, allocation.getCurrentMinIdle()); // ceil(6 / 2) = 3
        assertEquals(2, allocation.getHealthyServers());
        
        // Server recovers, back to 3 servers
        coordinator.updateHealthyServers("conn1", 3);
        allocation = coordinator.getPoolAllocation("conn1");
        
        assertEquals(10, allocation.getCurrentMaxPoolSize());
        assertEquals(2, allocation.getCurrentMinIdle());
        assertEquals(3, allocation.getHealthyServers());
    }

    @Test
    void testAllServersDown() {
        MultinodePoolCoordinator coordinator = new MultinodePoolCoordinator();
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        MultinodePoolCoordinator.PoolAllocation allocation = 
                coordinator.calculatePoolSizes("conn1", 20, 4, servers);
        
        // All servers marked unhealthy (0 healthy)
        coordinator.updateHealthyServers("conn1", 0);
        allocation = coordinator.getPoolAllocation("conn1");
        
        // Should fall back to original sizes with at least 1 healthy server
        assertEquals(20, allocation.getCurrentMaxPoolSize());
        assertEquals(4, allocation.getCurrentMinIdle());
        assertEquals(1, allocation.getHealthyServers()); // Min 1
    }

    @Test
    void testMultipleConnectionHashes() {
        MultinodePoolCoordinator coordinator = new MultinodePoolCoordinator();
        
        List<String> servers1 = Arrays.asList("server1:1059", "server2:1059");
        List<String> servers2 = Arrays.asList("server1:1059", "server2:1059", "server3:1059");
        
        coordinator.calculatePoolSizes("conn1", 20, 4, servers1);
        coordinator.calculatePoolSizes("conn2", 30, 6, servers2);
        
        MultinodePoolCoordinator.PoolAllocation alloc1 = coordinator.getPoolAllocation("conn1");
        MultinodePoolCoordinator.PoolAllocation alloc2 = coordinator.getPoolAllocation("conn2");
        
        assertEquals(10, alloc1.getCurrentMaxPoolSize());
        assertEquals(10, alloc2.getCurrentMaxPoolSize());
        
        // Update health for conn1 only
        coordinator.updateHealthyServers("conn1", 1);
        
        alloc1 = coordinator.getPoolAllocation("conn1");
        alloc2 = coordinator.getPoolAllocation("conn2");
        
        assertEquals(20, alloc1.getCurrentMaxPoolSize()); // All load on 1 server
        assertEquals(10, alloc2.getCurrentMaxPoolSize()); // Unchanged
    }

    @Test
    void testRemoveAllocation() {
        MultinodePoolCoordinator coordinator = new MultinodePoolCoordinator();
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        coordinator.calculatePoolSizes("conn1", 20, 4, servers);
        
        assertNotNull(coordinator.getPoolAllocation("conn1"));
        
        coordinator.removeAllocation("conn1");
        
        assertNull(coordinator.getPoolAllocation("conn1"));
    }

    @Test
    void testHealthyServerCountBounds() {
        MultinodePoolCoordinator coordinator = new MultinodePoolCoordinator();
        
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        MultinodePoolCoordinator.PoolAllocation allocation = 
                coordinator.calculatePoolSizes("conn1", 20, 4, servers);
        
        // Try to set more healthy servers than total
        coordinator.updateHealthyServers("conn1", 5);
        allocation = coordinator.getPoolAllocation("conn1");
        
        assertEquals(2, allocation.getHealthyServers()); // Capped at total
        
        // Try to set negative healthy servers
        coordinator.updateHealthyServers("conn1", -1);
        allocation = coordinator.getPoolAllocation("conn1");
        
        assertEquals(1, allocation.getHealthyServers()); // Min 1
    }
}
