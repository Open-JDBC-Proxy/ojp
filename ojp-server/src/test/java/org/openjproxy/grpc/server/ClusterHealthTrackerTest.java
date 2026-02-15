package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterHealthTrackerTest {
    private static final int THREE = 3;
    private static final int TWO = 2;

    private ClusterHealthTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ClusterHealthTracker();
    }

    @Test
    void testParseClusterHealth() {
        String clusterHealth = "192.168.1.1:1059(UP);192.168.1.2:1059(DOWN);192.168.1.3:1059(UP)";

        Map<String, String> healthMap = tracker.parseClusterHealth(clusterHealth);

        assertEquals(THREE, healthMap.size());
        assertEquals("UP", healthMap.get("192.168.1.1:1059"));
        assertEquals("DOWN", healthMap.get("192.168.1.2:1059"));
        assertEquals("UP", healthMap.get("192.168.1.3:1059"));
    }

    @Test
    void testParseClusterHealthEmpty() {
        Map<String, String> healthMap = tracker.parseClusterHealth("");
        assertTrue(healthMap.isEmpty());

        healthMap = tracker.parseClusterHealth(null);
        assertTrue(healthMap.isEmpty());
    }

    @Test
    void testParseClusterHealthInvalidFormat() {
        String clusterHealth = "invalid-format;192.168.1.1:1059(UP)";

        Map<String, String> healthMap = tracker.parseClusterHealth(clusterHealth);

        assertEquals(1, healthMap.size());
        assertEquals("UP", healthMap.get("192.168.1.1:1059"));
    }

    @Test
    void testCountHealthyServers() {
        String clusterHealth = "192.168.1.1:1059(UP);192.168.1.2:1059(DOWN);192.168.1.3:1059(UP)";

        int healthyCount = tracker.countHealthyServers(clusterHealth);

        assertEquals(TWO, healthyCount);
    }

    @Test
    void testCountHealthyServersAllDown() {
        String clusterHealth = "192.168.1.1:1059(DOWN);192.168.1.2:1059(DOWN)";

        int healthyCount = tracker.countHealthyServers(clusterHealth);

        assertEquals(0, healthyCount);
    }

    @Test
    void testCountHealthyServersAllUp() {
        String clusterHealth = "192.168.1.1:1059(UP);192.168.1.2:1059(UP);192.168.1.3:1059(UP)";

        int healthyCount = tracker.countHealthyServers(clusterHealth);

        assertEquals(THREE, healthyCount);
    }

    @Test
    void testHasHealthChangedFirstReport() {
        String connHash = "conn1";
        String clusterHealth = "192.168.1.1:1059(UP);192.168.1.2:1059(UP)";

        boolean changed = tracker.hasHealthChanged(connHash, clusterHealth);

        // First report should trigger change to ensure pool rebalancing on server restart
        assertTrue(changed);
        assertEquals(clusterHealth, tracker.getLastKnownHealth(connHash));
    }

    @Test
    void testHasHealthChangedNoChange() {
        String connHash = "conn1";
        String clusterHealth = "192.168.1.1:1059(UP);192.168.1.2:1059(UP)";

        tracker.hasHealthChanged(connHash, clusterHealth);
        boolean changed = tracker.hasHealthChanged(connHash, clusterHealth);

        assertFalse(changed);
    }

    @Test
    void testHasHealthChangedWithChange() {
        String connHash = "conn1";
        String initialHealth = "192.168.1.1:1059(UP);192.168.1.2:1059(UP)";
        String newHealth = "192.168.1.1:1059(UP);192.168.1.2:1059(DOWN)";

        tracker.hasHealthChanged(connHash, initialHealth);
        boolean changed = tracker.hasHealthChanged(connHash, newHealth);

        assertTrue(changed);
        assertEquals(newHealth, tracker.getLastKnownHealth(connHash));
    }

    @Test
    void testHasHealthChangedServerRecovery() {
        String connHash = "conn1";
        String downHealth = "192.168.1.1:1059(UP);192.168.1.2:1059(DOWN)";
        String recoveredHealth = "192.168.1.1:1059(UP);192.168.1.2:1059(UP)";

        tracker.hasHealthChanged(connHash, downHealth);
        boolean changed = tracker.hasHealthChanged(connHash, recoveredHealth);

        assertTrue(changed);
        assertEquals(recoveredHealth, tracker.getLastKnownHealth(connHash));
    }

    @Test
    void testHasHealthChangedNullConnHash() {
        boolean changed = tracker.hasHealthChanged(null, "192.168.1.1:1059(UP)");
        assertFalse(changed);

        changed = tracker.hasHealthChanged("", "192.168.1.1:1059(UP)");
        assertFalse(changed);
    }

    @Test
    void testRemoveTracking() {
        String connHash = "conn1";
        String clusterHealth = "192.168.1.1:1059(UP);192.168.1.2:1059(UP)";

        tracker.hasHealthChanged(connHash, clusterHealth);
        assertNotNull(tracker.getLastKnownHealth(connHash));

        tracker.removeTracking(connHash);
        assertNull(tracker.getLastKnownHealth(connHash));
    }

    @Test
    void testMultipleConnections() {
        String connHash1 = "conn1";
        String connHash2 = "conn2";
        String health1 = "192.168.1.1:1059(UP);192.168.1.2:1059(UP)";
        String health2 = "192.168.1.3:1059(UP);192.168.1.4:1059(DOWN)";

        tracker.hasHealthChanged(connHash1, health1);
        tracker.hasHealthChanged(connHash2, health2);

        assertEquals(health1, tracker.getLastKnownHealth(connHash1));
        assertEquals(health2, tracker.getLastKnownHealth(connHash2));

        // Change one shouldn't affect the other
        String newHealth1 = "192.168.1.1:1059(DOWN);192.168.1.2:1059(UP)";
        boolean changed = tracker.hasHealthChanged(connHash1, newHealth1);

        assertTrue(changed);
        assertEquals(newHealth1, tracker.getLastKnownHealth(connHash1));
        assertEquals(health2, tracker.getLastKnownHealth(connHash2));
    }

    @Test
    void testParseCaseInsensitive() {
        String clusterHealth = "192.168.1.1:1059(up);192.168.1.2:1059(Down);192.168.1.3:1059(UP)";

        // Count should work case-insensitively
        int healthyCount = tracker.countHealthyServers(clusterHealth);

        assertEquals(TWO, healthyCount);
    }
}
