package org.openjproxy.grpc.client;

import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Redistributes XA connections across recovered servers to rebalance load.
 * Uses ConnectionTracker to identify idle connections and marks them as invalid
 * so connection pools can replace them naturally, ensuring proper session cleanup.
 */
@Slf4j
public class XAConnectionRedistributor {

    private final MultinodeConnectionManager connectionManager;
    private final HealthCheckConfig healthConfig;

    public XAConnectionRedistributor(MultinodeConnectionManager connectionManager,
                                    HealthCheckConfig healthConfig) {
        this.connectionManager = connectionManager;
        this.healthConfig = healthConfig;
    }

    /**
     * Rebalances connections when servers recover.
     * Marks a subset of idle connections on overloaded servers as invalid,
     * allowing connection pools to detect them via isValid() and replace them
     * naturally. This ensures proper session termination on the server side.
     *
     * @param recoveredServers List of servers that have just recovered
     * @param allHealthyServers List of all currently healthy servers (including recovered ones)
     */
    public void rebalance(List<ServerEndpoint> recoveredServers, List<ServerEndpoint> allHealthyServers) {
        if (recoveredServers == null || recoveredServers.isEmpty()) {
            log.debug("No recovered servers to rebalance");
            return;
        }

        if (allHealthyServers == null || allHealthyServers.size() < 2) {
            log.debug("Not enough healthy servers for redistribution");
            return;
        }

        log.info("Starting XA connection redistribution for {} recovered server(s) among {} healthy servers",
                recoveredServers.size(), allHealthyServers.size());

        try {
            ConnectionTracker tracker = connectionManager.getConnectionTracker();

            // Get current connection distribution
            List<ConnectionTracker.ConnectionInfo> allConnections = tracker.getAllXAConnections();

            if (allConnections.isEmpty()) {
                log.info("No XA connections to redistribute");
                return;
            }

            // Calculate target connections per server
            int totalConnections = allConnections.size();
            int targetPerServer = totalConnections / allHealthyServers.size();

            log.info("Total XA connections: {}, Target per server: {}", totalConnections, targetPerServer);

            // Group connections by server
            Map<String, List<ConnectionTracker.ConnectionInfo>> connectionsByServer = allConnections.stream()
                    .collect(Collectors.groupingBy(ConnectionTracker.ConnectionInfo::getBoundServerAddress));

            // Identify overloaded servers (servers with more than their fair share)
            double idleRebalanceFraction = healthConfig.getIdleRebalanceFraction();
            int maxMarkPerRecovery = healthConfig.getMaxClosePerRecovery();
            int totalMarked = 0;

            // Sort servers by connection count (descending) to mark from most loaded first
            List<String> overloadedServers = connectionsByServer.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > targetPerServer)
                    .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            log.info("Found {} overloaded server(s)", overloadedServers.size());

            for (String serverAddress : overloadedServers) {
                if (totalMarked >= maxMarkPerRecovery) {
                    log.info("Reached max mark limit ({}), stopping redistribution", maxMarkPerRecovery);
                    break;
                }

                List<ConnectionTracker.ConnectionInfo> serverConnections = connectionsByServer.get(serverAddress);
                int excessConnections = serverConnections.size() - targetPerServer;
                int toMark = (int) Math.ceil(excessConnections * idleRebalanceFraction);
                toMark = Math.min(toMark, maxMarkPerRecovery - totalMarked);

                if (toMark <= 0) {
                    continue;
                }

                log.info("Server {} has {} connections ({} excess), marking {} idle connections as invalid",
                        serverAddress, serverConnections.size(), excessConnections, toMark);

                // Sort by last used time (oldest first) and mark idle connections as invalid
                List<ConnectionTracker.ConnectionInfo> idleConnections = serverConnections.stream()
                        .sorted(Comparator.comparingLong(ConnectionTracker.ConnectionInfo::getLastUsedTime))
                        .limit(toMark)
                        .collect(Collectors.toList());

                for (ConnectionTracker.ConnectionInfo connInfo : idleConnections) {
                    tracker.markConnectionInvalid(connInfo.getConnectionUUID());
                    totalMarked++;
                    log.debug("Marked idle connection {} from server {} as invalid",
                            connInfo.getConnectionUUID(), serverAddress);
                }
            }

            log.info("XA connection redistribution complete: marked {} connections as invalid for pool replacement", totalMarked);

        } catch (Exception e) {
            log.error("Error during XA connection redistribution: {}", e.getMessage(), e);
        }
    }
}
