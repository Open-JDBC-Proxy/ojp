package org.openjproxy.grpc.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Centralized component for redistributing XA connections when a server recovers.
 * 
 * This component implements a safe, configurable rebalancing process that:
 * - Queries the connection tracker for idle XA connections
 * - Selects a portion of idle connections to evict (oldest first, configurable fraction)
 * - Closes selected connections so the pool creates replacements
 * - Never closes connections with active XA transactions
 * - Limits evictions per recovery event to avoid connection storms
 * 
 * The redistributor is called by MultinodeConnectionManager when a server recovers.
 */
public class XAConnectionRedistributor {
    
    private static final Logger log = LoggerFactory.getLogger(XAConnectionRedistributor.class);
    
    private final ConnectionTracker connectionTracker;
    private final HealthCheckConfig config;
    
    public XAConnectionRedistributor(ConnectionTracker connectionTracker, HealthCheckConfig config) {
        this.connectionTracker = connectionTracker;
        this.config = config;
    }
    
    /**
     * Redistributes XA connections when a server endpoint recovers.
     * 
     * This method:
     * 1. Checks if redistribution is enabled
     * 2. Lists idle XA connections (excluding those already on recovered endpoint)
     * 3. Selects a fraction of idle connections based on configuration
     * 4. Closes selected connections (oldest idle first, up to max cap)
     * 5. Logs summary of redistribution actions
     * 
     * @param recoveredEndpoint The server endpoint that has recovered
     */
    public void redistribute(ServerEndpoint recoveredEndpoint) {
        if (recoveredEndpoint == null) {
            log.warn("Cannot redistribute for null endpoint");
            return;
        }
        
        if (!config.isRedistributionEnabled()) {
            log.info("XA connection redistribution is disabled, skipping for {}", 
                    recoveredEndpoint.getAddress());
            return;
        }
        
        log.info("Starting XA connection redistribution for recovered server: {}", 
                recoveredEndpoint.getAddress());
        
        try {
            // Get all idle XA connections
            List<XAConnectionInfo> allIdleConnections = connectionTracker.listIdleXaConnections();
            
            if (allIdleConnections.isEmpty()) {
                log.info("No idle XA connections to redistribute");
                return;
            }
            
            log.debug("Found {} idle XA connections", allIdleConnections.size());
            
            // Filter out connections already bound to the recovered endpoint
            List<XAConnectionInfo> candidateConnections = allIdleConnections.stream()
                    .filter(info -> !recoveredEndpoint.equals(info.getBoundServer()))
                    .collect(Collectors.toList());
            
            if (candidateConnections.isEmpty()) {
                log.info("All {} idle XA connections are already bound to recovered endpoint {}", 
                        allIdleConnections.size(), recoveredEndpoint.getAddress());
                return;
            }
            
            log.info("Found {} candidate idle XA connections for redistribution (excluding {} already on recovered server)",
                    candidateConnections.size(), 
                    allIdleConnections.size() - candidateConnections.size());
            
            // Calculate how many to close based on idleRebalanceFraction
            int targetCloseCount = (int) Math.ceil(candidateConnections.size() * config.getIdleRebalanceFraction());
            
            // Apply max cap
            int actualCloseCount = Math.min(targetCloseCount, config.getMaxClosePerRecovery());
            
            if (actualCloseCount == 0) {
                log.info("Calculated 0 connections to close (fraction={}, candidates={})", 
                        config.getIdleRebalanceFraction(), candidateConnections.size());
                return;
            }
            
            log.info("Will close {} XA connections (fraction={}, target={}, cap={}, candidates={})",
                    actualCloseCount,
                    config.getIdleRebalanceFraction(),
                    targetCloseCount,
                    config.getMaxClosePerRecovery(),
                    candidateConnections.size());
            
            // Close the selected connections (oldest first, already sorted by listIdleXaConnections)
            int closedCount = 0;
            int failedCount = 0;
            
            for (int i = 0; i < actualCloseCount && i < candidateConnections.size(); i++) {
                XAConnectionInfo info = candidateConnections.get(i);
                
                try {
                    log.debug("Closing XA connection {}: {}", i + 1, info);
                    boolean closed = connectionTracker.closeIdleConnection(info.getConnectionUuid());
                    
                    if (closed) {
                        closedCount++;
                    } else {
                        log.warn("Failed to close XA connection {} - may have become active", 
                                info.getConnectionUuid());
                        failedCount++;
                    }
                } catch (SQLException e) {
                    log.error("Error closing XA connection {}: {}", 
                            info.getConnectionUuid(), e.getMessage());
                    failedCount++;
                }
            }
            
            log.info("XA connection redistribution complete for {}: closed={}, failed={}, total_candidates={}",
                    recoveredEndpoint.getAddress(),
                    closedCount,
                    failedCount,
                    candidateConnections.size());
            
        } catch (Exception e) {
            log.error("Unexpected error during XA connection redistribution for {}: {}", 
                    recoveredEndpoint.getAddress(), e.getMessage(), e);
            // Don't rethrow - redistribution is best-effort
        }
    }
    
    /**
     * Gets the configuration used by this redistributor.
     * 
     * @return The health check configuration
     */
    public HealthCheckConfig getConfig() {
        return config;
    }
}
