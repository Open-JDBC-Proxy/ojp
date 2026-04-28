package org.openjproxy.grpc.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks connections and their bound servers in multinode deployments.
 * Uses a simple ConcurrentHashMap for thread-safe tracking.
 * Iteration only happens when needed (e.g., during redistribution).
 */
public class ConnectionTracker {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTracker.class);

    private final Map<Connection, ServerEndpoint> connectionToServerMap;

    public ConnectionTracker() {
        this.connectionToServerMap = new ConcurrentHashMap<>();
    }

    /**
     * Registers a connection with its bound server.
     *
     * @param connection The connection to register
     * @param server The server endpoint the connection is bound to
     */
    public void register(Connection connection, ServerEndpoint server) {
        if (connection == null || server == null) {
            log.warn("Attempted to register null connection or server");
            return;
        }

        connectionToServerMap.put(connection, server);
        log.debug("Registered connection to {}, total tracked: {}",
                server.getAddress(), connectionToServerMap.size());
    }

    /**
     * Unregisters a connection when it's closed.
     *
     * @param connection The connection to unregister
     */
    public void unregister(Connection connection) {
        if (connection == null) {
            return;
        }

        ServerEndpoint removed = connectionToServerMap.remove(connection);
        if (removed != null) {
            log.debug("Unregistered connection to {}, total tracked: {}",
                    removed.getAddress(), connectionToServerMap.size());
        }
    }

    /**
     * Gets the current distribution of connections across servers.
     * This method iterates over all connections - only call when needed (e.g., during redistribution).
     *
     * @return Map of server endpoints to their list of connections
     */
    public Map<ServerEndpoint, List<Connection>> getDistribution() {
        return connectionToServerMap.entrySet().stream()
                .collect(Collectors.groupingBy(
                    Map.Entry::getValue,
                    Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
    }

    /**
     * Gets the connection count per server.
     * Useful for logging and monitoring without needing full connection lists.
     *
     * @return Map of server endpoints to connection counts
     */
    public Map<ServerEndpoint, Integer> getCounts() {
        Map<ServerEndpoint, Integer> counts = new HashMap<>();
        connectionToServerMap.values().forEach(server ->
            counts.merge(server, 1, Integer::sum));
        return counts;
    }

    /**
     * Gets the total number of tracked connections.
     *
     * @return Total connection count
     */
    public int getTotalConnections() {
        return connectionToServerMap.size();
    }

    /**
     * Gets the server endpoint a connection is bound to.
     *
     * @param connection The connection to query
     * @return The server endpoint, or null if not tracked
     */
    public ServerEndpoint getBoundServer(Connection connection) {
        return connectionToServerMap.get(connection);
    }

    /**
     * Checks if a connection is currently tracked.
     *
     * @param connection The connection to check
     * @return true if tracked, false otherwise
     */
    public boolean isTracked(Connection connection) {
        return connectionToServerMap.containsKey(connection);
    }

    /**
     * Clears all tracked connections.
     * Should only be used during shutdown or testing.
     */
    public void clear() {
        int count = connectionToServerMap.size();
        connectionToServerMap.clear();
        log.info("Cleared {} tracked connections", count);
    }

    /**
     * Inner class to hold connection information for redistribution.
     */
    public static class ConnectionInfo {
        private final String connectionUUID;
        private final String boundServerAddress;
        private final long lastUsedTime;

        public ConnectionInfo(String connectionUUID, String boundServerAddress, long lastUsedTime) {
            this.connectionUUID = connectionUUID;
            this.boundServerAddress = boundServerAddress;
            this.lastUsedTime = lastUsedTime;
        }

        public String getConnectionUUID() {
            return connectionUUID;
        }

        public String getBoundServerAddress() {
            return boundServerAddress;
        }

        public long getLastUsedTime() {
            return lastUsedTime;
        }
    }

    /**
     * Gets all XA connections with their metadata for redistribution.
     * Returns a list of ConnectionInfo objects containing UUID, server address, and last used time.
     *
     * @return List of ConnectionInfo for all tracked XA connections
     */
    public List<ConnectionInfo> getAllXAConnections() {
        // For now, return all connections as we don't distinguish between XA and non-XA
        // In a full implementation, we would filter by connection type
        return connectionToServerMap.entrySet().stream()
                .map(entry -> {
                    Connection conn = entry.getKey();
                    ServerEndpoint server = entry.getValue();
                    // Use System.identityHashCode as a simple UUID proxy
                    String uuid = String.valueOf(System.identityHashCode(conn));
                    String serverAddr = server.getHost() + ":" + server.getPort();
                    // For now, use current time - in a full implementation, track actual last used time
                    long lastUsed = System.currentTimeMillis();
                    return new ConnectionInfo(uuid, serverAddr, lastUsed);
                })
                .collect(Collectors.toList());
    }

    /**
     * Marks an XA connection as invalid by its UUID.
     * This causes the connection pool to naturally replace it without
     * disrupting the server-side session immediately. The pool's validation
     * mechanism will detect the invalid connection and close it properly,
     * triggering session termination.
     *
     * @param connectionUUID The UUID of the connection to mark invalid
     */
    public void markConnectionInvalid(String connectionUUID) throws SQLException {
        // Find the connection by UUID (using identity hash code)
        Connection toMark = null;
        for (Connection conn : connectionToServerMap.keySet()) {
            if (String.valueOf(System.identityHashCode(conn)).equals(connectionUUID)) {
                toMark = conn;
                break;
            }
        }

        if (toMark != null) {
            // Cast to OJP Connection to access markForceInvalid method
            if (toMark instanceof org.openjproxy.jdbc.Connection) {
                ((org.openjproxy.jdbc.Connection) toMark).markForceInvalid();
                toMark.close();
                log.debug("Marked connection {} as invalid for pool replacement", connectionUUID);
            } else {
                log.warn("Connection {} is not an OJP Connection, cannot mark invalid", connectionUUID);
            }
            // Note: Don't remove from tracker - let the pool handle cleanup via unregister()
        } else {
            log.warn("Connection {} not found for marking invalid", connectionUUID);
        }
    }
}
