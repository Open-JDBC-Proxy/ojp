package org.openjproxy.grpc.server;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MultinodeXaCoordinatorTest {
    private static final int THIRTY = 30;
    private static final int FIFTEEN = 15;
    private static final int SEVENTEEN = 17;
    private static final int THREE = 3;
    private static final int TEN = 10;
    private static final int FIFTY = 50;
    private static final int FORTY = 40;
    private static final int SIXTY = 60;
    private static final int TWENTY = 20;
    private static final int FIVE = 5;
    private static final int ONE = 1;
    private static final int TWO = 2;
    private static final int FOUR = 4;

    @Test
    void testSingleNodeConfiguration() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();

        // Single node (empty server list)
        MultinodeXaCoordinator.XaAllocation allocation =
                coordinator.calculateXaLimits("conn1", THIRTY, null);

        assertEquals(THIRTY, allocation.getCurrentMaxTransactions());
        assertEquals(ONE, allocation.getTotalServers());
        assertEquals(ONE, allocation.getHealthyServers());
    }

    @Test
    void testMultinodeDivision() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();

        // Two servers: XA limits should be divided
        List<String> servers = Arrays.asList("server1:1059", "server2:1059");
        MultinodeXaCoordinator.XaAllocation allocation =
                coordinator.calculateXaLimits("conn1", 30, servers);

        assertEquals(FIFTEEN, allocation.getCurrentMaxTransactions()); // 30 / 2 = 15
        assertEquals(TWO, allocation.getTotalServers());
        assertEquals(TWO, allocation.getHealthyServers());
    }

    @Test
    void testMultinodeDivisionWithRounding() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();

        // Three servers: limits should be divided with rounding up
        List<String> servers = Arrays.asList("server1:1059", "server2:1059", "server3:1059");
        MultinodeXaCoordinator.XaAllocation allocation =
                coordinator.calculateXaLimits("conn1", 50, servers);

        assertEquals(SEVENTEEN, allocation.getCurrentMaxTransactions()); // ceil(50 / 3) = 17
        assertEquals(THREE, allocation.getTotalServers());
    }

    @Test
    void testHealthyServerUpdate() {
        MultinodeXaCoordinator coordinator = new MultinodeXaCoordinator();

        List<String> servers = Arrays.asList("server1:1059", "server2:1059", "server3:1059");
        MultinodeXaCoordinator.XaAllocation allocation =
                coordinator.calculateXaLimits("conn1", 30, servers);

        // Initially 3 servers, each gets 10 max transactions
        assertEquals(TEN, allocation.getCurrentMaxTransactions());

        // One server goes down, remaining 2 should split the load
        coordinator.updateHealthyServers("conn1", 2);
        allocation = coordinator.getXaAllocation("conn1");

        assertEquals(FIFTEEN, allocation.getCurrentMaxTransactions()); // ceil(30 / 2) = 15
        assertEquals(TWO, allocation.getHealthyServers());

        // Server recovers, back to 3 servers
        coordinator.updateHealthyServers("conn1", 3);
        allocation = coordinator.getXaAllocation("conn1");

        assertEquals(TEN, allocation.getCurrentMaxTransactions());
        assertEquals(THREE, allocation.getHealthyServers());
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
        assertEquals(FORTY, allocation.getCurrentMaxTransactions());
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

        assertEquals(TWENTY, alloc1.getCurrentMaxTransactions());
        assertEquals(TWENTY, alloc2.getCurrentMaxTransactions());

        // Update health for conn1 only
        coordinator.updateHealthyServers("conn1", 1);

        alloc1 = coordinator.getXaAllocation("conn1");
        alloc2 = coordinator.getXaAllocation("conn2");

        assertEquals(FORTY, alloc1.getCurrentMaxTransactions()); // All load on 1 server
        assertEquals(TWENTY, alloc2.getCurrentMaxTransactions()); // Unchanged
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

        assertEquals(TWO, allocation.getHealthyServers()); // Capped at total

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
        assertEquals(TEN, allocation.getCurrentMaxTransactions());
        assertEquals(THIRTY, allocation.getOriginalMaxTransactions());

        // Node 1 goes down
        coordinator.updateHealthyServers("prod-db", 2);
        allocation = coordinator.getXaAllocation("prod-db");

        // Remaining nodes handle 15 each
        assertEquals(FIFTEEN, allocation.getCurrentMaxTransactions());

        // Node 1 recovers
        coordinator.updateHealthyServers("prod-db", 3);
        allocation = coordinator.getXaAllocation("prod-db");

        // Back to normal: 10 each
        assertEquals(TEN, allocation.getCurrentMaxTransactions());
    }
}
