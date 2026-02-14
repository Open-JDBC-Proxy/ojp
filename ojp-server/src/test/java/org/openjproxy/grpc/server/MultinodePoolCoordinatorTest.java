package org.openjproxy.grpc.server;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MultinodePoolCoordinatorTest {
    private static final int TWENTY = 20;
    private static final int FIVE = 5;
    private static final int SIX = 6;
    private static final int TEN = 10;
    private static final int THREE = 3;
    private static final int SEVEN = 7;
    private static final int FIFTEEN = 15;
    private static final int THIRTY = 30;
    private static final int FOUR = 4;
    private static final int EIGHT = 8;
    private static final int SEVENTEEN = 17;
    private static final int FIFTY = 50;
    private static final int FORTY = 40;
    private static final int SIXTY = 60;
    private static final int NINE = 9;
    private static final int ELEVEN = 11;
    private static final int TWENTY_FIVE = 25;
    private static final int THIRTY_FIVE = 35;
    private static final int TWO = 2;
    private static final int ONE = 1;

    @Test
    void testSingleNodeConfiguration() {
        MultinodePoolCoordinator coordinator = new MultinodePoolCoordinator();

        // Single node (empty server list)
        MultinodePoolCoordinator.PoolAllocation allocation =
                coordinator.calculatePoolSizes("conn1", TWENTY, FIVE, null);

        assertEquals(TWENTY, allocation.getCurrentMaxPoolSize());
        assertEquals(FIVE, allocation.getCurrentMinIdle());
        assertEquals(ONE, allocation.getTotalServers());
        assertEquals(ONE, allocation.getHealthyServers());
    }

    @Test
    void testMultinodeDivision() {
        MultinodePoolCoordinator coordinator = new MultinodePoolCoordinator();

        // Two servers: pool should be divided
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        MultinodePoolCoordinator.PoolAllocation allocation =
                coordinator.calculatePoolSizes("conn1", TWENTY, FOUR, servers);

        // All servers marked unhealthy (0 healthy)
        coordinator.updateHealthyServers("conn1", 0);
        allocation = coordinator.getPoolAllocation("conn1");

        // Should fall back to original sizes with at least 1 healthy server
        assertEquals(TWENTY, allocation.getCurrentMaxPoolSize());
        assertEquals(FOUR, allocation.getCurrentMinIdle());
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

        assertEquals(TEN, alloc1.getCurrentMaxPoolSize());
        assertEquals(TEN, alloc2.getCurrentMaxPoolSize());

        // Update health for conn1 only
        coordinator.updateHealthyServers("conn1", 1);

        alloc1 = coordinator.getPoolAllocation("conn1");
        alloc2 = coordinator.getPoolAllocation("conn2");

        assertEquals(20, alloc1.getCurrentMaxPoolSize()); // All load on 1 server
        assertEquals(TEN, alloc2.getCurrentMaxPoolSize()); // Unchanged
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

        assertEquals(TWO, allocation.getHealthyServers()); // Capped at total

        // Try to set negative healthy servers
        coordinator.updateHealthyServers("conn1", -1);
        allocation = coordinator.getPoolAllocation("conn1");

        assertEquals(1, allocation.getHealthyServers()); // Min 1
    }
}
