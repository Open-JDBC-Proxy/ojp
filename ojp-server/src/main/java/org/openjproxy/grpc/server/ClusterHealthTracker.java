package org.openjproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks cluster health status reported by clients and detects changes.
 * Each connection hash has its own cluster health state that is monitored
 * for changes to trigger connection pool rebalancing.
 */
@Slf4j
public class ClusterHealthTracker {

    // Maps connHash to the last known cluster health string
    private final Map<String, String> lastKnownHealth = new ConcurrentHashMap<>();

    /**
     * Parses a cluster health string into a map of server -> health status.
     * Format: "host1:port1(UP);host2:port2(DOWN);host3:port3(UP)"
     *
     * @param clusterHealth The cluster health string
     * @return Map of server address to health status (UP/DOWN)
     */
    public Map<String, String> parseClusterHealth(String clusterHealth) {
        Map<String, String> healthMap = new HashMap<>();

        if (clusterHealth == null || clusterHealth.isEmpty()) {
            return healthMap;
        }

        String[] serverEntries = clusterHealth.split(";");
        for (String entry : serverEntries) {
            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }

            // Parse format: "host:port(STATUS)"
            int statusStart = entry.lastIndexOf('(');
            int statusEnd = entry.lastIndexOf(')');

            if (statusStart > 0 && statusEnd > statusStart) {
                String serverAddress = entry.substring(0, statusStart);
                String status = entry.substring(statusStart + 1, statusEnd);
                healthMap.put(serverAddress, status);
            } else {
                log.warn("Invalid cluster health entry format: {}", entry);
            }
        }

        return healthMap;
    }

    /**
     * Counts the number of healthy servers (UP status) in the cluster health.
     *
     * @param clusterHealth The cluster health string
     * @return Number of servers with UP status
     */
    public int countHealthyServers(String clusterHealth) {
        Map<String, String> healthMap = parseClusterHealth(clusterHealth);
        return (int) healthMap.values().stream()
                .filter(status -> "UP".equalsIgnoreCase(status))
                .count();
    }

    /**
     * Checks if the cluster health has changed for a connection hash.
     * Returns true if this is a new connHash or if the health status has changed.
     * Updates the last known health if a change is detected.
     *
     * Thread-safe: Uses ConcurrentHashMap.compute() for atomic check-and-update
     * to prevent race conditions where multiple threads detect the same health change.
     *
     * @param connHash Connection hash
     * @param currentClusterHealth Current cluster health string
     * @return true if cluster health has changed, false otherwise
     */
    public boolean hasHealthChanged(String connHash, String currentClusterHealth) {
        if (connHash == null || connHash.isEmpty()) {
            return false;
        }

        // Normalize empty/null cluster health to empty string for comparison
        final String normalizedCurrent = currentClusterHealth == null ? "" : currentClusterHealth;

        // Use a holder to return the result from the compute function
        final boolean[] hasChanged = new boolean[1];

        // Atomic check-and-update using compute()
        lastKnownHealth.compute(connHash, (key, lastHealth) -> {
            if (lastHealth == null) {
                // First time seeing this connHash, store the health and ALWAYS trigger rebalancing
                // This ensures pools are properly configured even when servers restart (new tracker state)
                log.debug("First cluster health report for connHash {}: {} - triggering pool size verification",
                         connHash, normalizedCurrent);
                hasChanged[0] = true;
                return normalizedCurrent;
            }

            if (!lastHealth.equals(normalizedCurrent)) {
                // Health has changed
                log.info("Cluster health changed for connHash {}: {} -> {}",
                        connHash, lastHealth, normalizedCurrent);
                hasChanged[0] = true;
                return normalizedCurrent;
            }

            // No change
            hasChanged[0] = false;
            return lastHealth;
        });

        return hasChanged[0];
    }

    /**
     * Removes tracking for a connection hash (e.g., when connection is closed).
     *
     * @param connHash Connection hash to remove
     */
    public void removeTracking(String connHash) {
        if (connHash != null) {
            lastKnownHealth.remove(connHash);
            log.debug("Removed cluster health tracking for connHash {}", connHash);
        }
    }

    /**
     * Gets the last known cluster health for a connection hash.
     *
     * @param connHash Connection hash
     * @return Last known cluster health string, or null if not tracked
     */
    public String getLastKnownHealth(String connHash) {
        return lastKnownHealth.get(connHash);
    }
}
