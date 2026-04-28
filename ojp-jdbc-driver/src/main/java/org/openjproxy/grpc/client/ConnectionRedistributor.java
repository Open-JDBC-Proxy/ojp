package org.openjproxy.grpc.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles connection redistribution when a failed server recovers.
 * Implements balanced closure algorithm to rebalance connections across all healthy servers.
 */
public class ConnectionRedistributor {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRedistributor.class);

    private final ConnectionTracker connectionTracker;
    private final HealthCheckConfig config;

    public ConnectionRedistributor(ConnectionTracker connectionTracker, HealthCheckConfig config) {
        this.connectionTracker = connectionTracker;
        this.config = config;
    }

    /**
     * Rebalances connections when servers recover.
     * Marks connections from overloaded servers for closure in a balanced manner.
     *
     * @param recoveredServers List of servers that have recovered
     * @param allHealthyServers List of all currently healthy servers (including recovered)
     */
    public void rebalance(List<ServerEndpoint> recoveredServers, List<ServerEndpoint> allHealthyServers) {
        if (!config.isRedistributionEnabled()) {
            log.info("Redistribution is disabled, skipping rebalance");
            return;
        }

        if (recoveredServers == null || recoveredServers.isEmpty()) {
            log.debug("No recovered servers to rebalance");
            return;
        }

        if (allHealthyServers == null || allHealthyServers.size() < 2) {
            log.debug("Not enough healthy servers for rebalancing (need at least 2)");
            return;
        }

        log.info("Starting connection redistribution for {} recovered server(s)", recoveredServers.size());

        // Get current distribution of connections
        Map<ServerEndpoint, List<Connection>> distribution = connectionTracker.getDistribution();
        int totalConnections = connectionTracker.getTotalConnections();

        if (totalConnections == 0) {
            log.info("No connections to redistribute");
            return;
        }

        // Calculate target connections per server
        int targetPerServer = totalConnections / allHealthyServers.size();
        int remainder = totalConnections % allHealthyServers.size();

        log.info("Redistribution plan: {} total connections, {} healthy servers, target ~{} per server",
                totalConnections, allHealthyServers.size(), targetPerServer);

        // For each recovered server, calculate how many connections it should receive
        int totalToClose = 0;
        for (ServerEndpoint recovered : recoveredServers) {
            int currentCount = distribution.getOrDefault(recovered, new ArrayList<>()).size();
            int needed = targetPerServer - currentCount;

            if (needed > 0) {
                totalToClose += needed;
                log.info("Recovered server {} has {} connections, needs {} more (target: {})",
                        recovered.getAddress(), currentCount, needed, targetPerServer);
            } else {
                log.info("Recovered server {} already has {} connections (target: {}), no redistribution needed",
                        recovered.getAddress(), currentCount, targetPerServer);
            }
        }

        if (totalToClose == 0) {
            log.info("No connections need to be closed for redistribution");
            return;
        }

        // Find overloaded servers and mark connections for closure in a balanced manner
        List<ServerEndpoint> overloadedServers = new ArrayList<>();
        for (Map.Entry<ServerEndpoint, List<Connection>> entry : distribution.entrySet()) {
            ServerEndpoint server = entry.getKey();
            int connectionCount = entry.getValue().size();

            // Skip recovered servers
            if (recoveredServers.contains(server)) {
                continue;
            }

            if (connectionCount > targetPerServer) {
                overloadedServers.add(server);
                log.info("Server {} is overloaded with {} connections (target: {})",
                        server.getAddress(), connectionCount, targetPerServer);
            }
        }

        if (overloadedServers.isEmpty()) {
            log.warn("No overloaded servers found to redistribute from, cannot proceed");
            return;
        }

        // Perform balanced closure: alternate between overloaded servers
        int closedCount = 0;
        int serverIndex = 0;

        while (closedCount < totalToClose && serverIndex < overloadedServers.size() * 100) { // Safety limit
            ServerEndpoint server = overloadedServers.get(serverIndex % overloadedServers.size());
            List<Connection> connections = distribution.get(server);

            if (connections != null && !connections.isEmpty()) {
                // Find first connection that hasn't been marked yet
                for (Connection conn : connections) {
                    if (conn instanceof org.openjproxy.jdbc.Connection) {
                        org.openjproxy.jdbc.Connection ojpConn = (org.openjproxy.jdbc.Connection) conn;

                        // Check if already marked (avoid double-marking)
                        try {
                            if (!ojpConn.isClosed() && !ojpConn.isForceInvalid()) {
                                ojpConn.markForceInvalid();
                                closedCount++;
                                log.debug("Marked connection from {} for closure ({}/{})",
                                        server.getAddress(), closedCount, totalToClose);

                                // Remove from list so we don't mark it again
                                connections.remove(conn);
                                break;
                            }
                        } catch (Exception e) {
                            log.warn("Failed to mark connection from {}: {}",
                                    server.getAddress(), e.getMessage());
                        }
                    }
                }
            }

            serverIndex++;

            // If we've looped through all servers and made no progress, break
            if (serverIndex % overloadedServers.size() == 0 && closedCount == 0) {
                break;
            }
        }

        if (closedCount < totalToClose) {
            log.warn("Redistribution incomplete: marked {}/{} connections. " +
                    "Remaining {} connections may be in use. Will continue on subsequent health checks.",
                    closedCount, totalToClose, (totalToClose - closedCount));
        } else {
            log.info("Redistribution complete: marked {} connections for closure. " +
                    "New connections will be distributed to recovered servers.",
                    closedCount);
        }
    }
}
