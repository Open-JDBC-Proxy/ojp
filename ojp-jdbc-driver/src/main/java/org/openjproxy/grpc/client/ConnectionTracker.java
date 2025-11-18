package org.openjproxy.grpc.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks connections and their bound servers in multinode deployments.
 * Uses a simple ConcurrentHashMap for thread-safe tracking.
 * Iteration only happens when needed (e.g., during redistribution).
 * 
 * Extended to support XA connection tracking for redistribution on server recovery.
 */
public class ConnectionTracker {
    
    private static final Logger log = LoggerFactory.getLogger(ConnectionTracker.class);
    
    private final Map<Connection, ServerEndpoint> connectionToServerMap;
    private final Map<String, XAConnectionInfo> xaConnectionMap;
    
    public ConnectionTracker() {
        this.connectionToServerMap = new ConcurrentHashMap<>();
        this.xaConnectionMap = new ConcurrentHashMap<>();
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
        int xaCount = xaConnectionMap.size();
        connectionToServerMap.clear();
        xaConnectionMap.clear();
        log.info("Cleared {} tracked connections and {} XA connections", count, xaCount);
    }
    
    // ============================================================
    // XA Connection Tracking Methods
    // ============================================================
    
    /**
     * Registers an XA connection with metadata for redistribution tracking.
     * 
     * @param connectionUuid Unique identifier for this XA connection
     * @param xaConnection The XA connection instance
     * @param boundServer The server endpoint this connection is bound to
     */
    public void registerXAConnection(String connectionUuid, XAConnection xaConnection, ServerEndpoint boundServer) {
        if (connectionUuid == null || xaConnection == null || boundServer == null) {
            log.warn("Attempted to register XA connection with null parameters");
            return;
        }
        
        XAConnectionInfo info = new XAConnectionInfo(connectionUuid, xaConnection, boundServer, System.currentTimeMillis());
        xaConnectionMap.put(connectionUuid, info);
        log.debug("Registered XA connection {} to {}, total tracked: {}", 
                connectionUuid, boundServer.getAddress(), xaConnectionMap.size());
    }
    
    /**
     * Unregisters an XA connection when it's closed.
     * 
     * @param connectionUuid The connection UUID to unregister
     */
    public void unregisterXAConnection(String connectionUuid) {
        if (connectionUuid == null) {
            return;
        }
        
        XAConnectionInfo removed = xaConnectionMap.remove(connectionUuid);
        if (removed != null) {
            log.debug("Unregistered XA connection {} from {}, total tracked: {}", 
                    connectionUuid, removed.getBoundServer().getAddress(), xaConnectionMap.size());
        }
    }
    
    /**
     * Updates the last used time for an XA connection.
     * Should be called when the connection is used for operations.
     * 
     * @param connectionUuid The connection UUID
     */
    public void updateXAConnectionLastUsed(String connectionUuid) {
        XAConnectionInfo info = xaConnectionMap.get(connectionUuid);
        if (info != null) {
            // Create new info with updated timestamp
            XAConnectionInfo updated = new XAConnectionInfo(
                info.getConnectionUuid(),
                info.getXaConnection(),
                info.getBoundServer(),
                System.currentTimeMillis()
            );
            updated.setActiveTransaction(info.hasActiveTransaction());
            xaConnectionMap.put(connectionUuid, updated);
        }
    }
    
    /**
     * Marks an XA connection as having an active transaction.
     * Connections with active transactions should never be closed for redistribution.
     * 
     * @param connectionUuid The connection UUID
     * @param active true if transaction is active, false otherwise
     */
    public void setXAConnectionActiveTransaction(String connectionUuid, boolean active) {
        XAConnectionInfo info = xaConnectionMap.get(connectionUuid);
        if (info != null) {
            info.setActiveTransaction(active);
            log.debug("XA connection {} active transaction status: {}", connectionUuid, active);
        }
    }
    
    /**
     * Lists all idle XA connections that can be safely closed for redistribution.
     * 
     * A connection is considered idle if:
     * - It does not have an active transaction
     * - It has not been used recently (implementation specific)
     * 
     * @return List of XA connection info for idle connections, sorted by last used time (oldest first)
     */
    public List<XAConnectionInfo> listIdleXaConnections() {
        return xaConnectionMap.values().stream()
                .filter(info -> !info.hasActiveTransaction())
                .sorted((a, b) -> Long.compare(a.getLastUsedTime(), b.getLastUsedTime()))
                .collect(Collectors.toList());
    }
    
    /**
     * Closes an idle XA connection by UUID.
     * 
     * This method:
     * 1. Looks up the connection by UUID
     * 2. Verifies it doesn't have an active transaction
     * 3. Closes the XA connection
     * 4. Unregisters it from tracking
     * 
     * @param connectionUuid The UUID of the connection to close
     * @return true if connection was closed, false if not found or has active transaction
     * @throws SQLException if closing fails
     */
    public boolean closeIdleConnection(String connectionUuid) throws SQLException {
        XAConnectionInfo info = xaConnectionMap.get(connectionUuid);
        
        if (info == null) {
            log.debug("XA connection {} not found for closing", connectionUuid);
            return false;
        }
        
        if (info.hasActiveTransaction()) {
            log.warn("Cannot close XA connection {} - has active transaction", connectionUuid);
            return false;
        }
        
        try {
            log.info("Closing idle XA connection {} bound to {}", 
                    connectionUuid, info.getBoundServer().getAddress());
            info.getXaConnection().close();
            unregisterXAConnection(connectionUuid);
            return true;
        } catch (SQLException e) {
            log.error("Failed to close XA connection {}: {}", connectionUuid, e.getMessage());
            throw e;
        }
    }
    
    /**
     * Gets the count of tracked XA connections per server.
     * 
     * @return Map of server endpoints to XA connection counts
     */
    public Map<ServerEndpoint, Integer> getXAConnectionCounts() {
        Map<ServerEndpoint, Integer> counts = new HashMap<>();
        xaConnectionMap.values().forEach(info -> 
            counts.merge(info.getBoundServer(), 1, Integer::sum));
        return counts;
    }
    
    /**
     * Gets the total number of tracked XA connections.
     * 
     * @return Total XA connection count
     */
    public int getTotalXAConnections() {
        return xaConnectionMap.size();
    }
}
