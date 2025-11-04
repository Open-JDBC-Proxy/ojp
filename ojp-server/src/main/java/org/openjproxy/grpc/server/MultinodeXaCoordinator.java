package org.openjproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates XA transaction limits across multiple OJP server instances in a multinode setup.
 * 
 * Similar to pool coordination, but for XA transactions:
 * - Max transactions are divided among servers
 * - When a server becomes unhealthy, remaining servers increase their limits
 * - When a server recovers, all servers rebalance back to divided limits
 * 
 * Example:
 * - Initial: maxXaTransactions=30, servers=3 → each server: max=10
 * - Server failure: servers=2 (1 down) → each remaining: max=15
 * - Server recovery: servers=3 (recovered) → each server: max=10 (rebalanced)
 */
@Slf4j
public class MultinodeXaCoordinator {
    
    private final Map<String, XaAllocation> xaAllocations = new ConcurrentHashMap<>();
    
    /**
     * Represents the XA transaction allocation for a connection hash.
     */
    public static class XaAllocation {
        private final int originalMaxTransactions;
        private final int totalServers;
        private int healthyServers;
        
        public XaAllocation(int originalMaxTransactions, int totalServers) {
            this.originalMaxTransactions = originalMaxTransactions;
            this.totalServers = totalServers;
            this.healthyServers = totalServers;
        }
        
        public int getCurrentMaxTransactions() {
            if (healthyServers <= 0) {
                return originalMaxTransactions; // Fallback if no healthy servers
            }
            // Divide the total max transactions among healthy servers, rounding up
            return (int) Math.ceil((double) originalMaxTransactions / healthyServers);
        }
        
        public void updateHealthyServerCount(int count) {
            this.healthyServers = Math.max(1, Math.min(count, totalServers));
        }
        
        public int getOriginalMaxTransactions() {
            return originalMaxTransactions;
        }
        
        public int getTotalServers() {
            return totalServers;
        }
        
        public int getHealthyServers() {
            return healthyServers;
        }
    }
    
    /**
     * Calculates XA transaction limits for a multinode configuration.
     * 
     * @param connHash Connection hash identifying the XA datasource
     * @param requestedMaxTransactions Maximum XA transactions from configuration
     * @param serverEndpoints List of server endpoints in the cluster
     * @return XaAllocation with calculated limits
     */
    public XaAllocation calculateXaLimits(String connHash, int requestedMaxTransactions, 
                                         List<String> serverEndpoints) {
        
        if (serverEndpoints == null || serverEndpoints.isEmpty()) {
            // Single node - no coordination needed, return original value
            log.debug("Single node XA configuration for {}, using original max transactions: {}", 
                    connHash, requestedMaxTransactions);
            return new XaAllocation(requestedMaxTransactions, 1);
        }
        
        int serverCount = serverEndpoints.size();
        
        // Create allocation with divided transaction limits
        XaAllocation allocation = new XaAllocation(requestedMaxTransactions, serverCount);
        
        xaAllocations.put(connHash, allocation);
        
        log.info("Multinode XA configuration for {}: {} servers, original max={}, divided max={}", 
                connHash, serverCount, requestedMaxTransactions, allocation.getCurrentMaxTransactions());
        
        return allocation;
    }
    
    /**
     * Updates the number of healthy servers for a connection hash.
     * This triggers XA transaction limit recalculation.
     * 
     * @param connHash Connection hash identifying the XA datasource
     * @param healthyServerCount Number of currently healthy servers
     */
    public void updateHealthyServers(String connHash, int healthyServerCount) {
        XaAllocation allocation = xaAllocations.get(connHash);
        if (allocation != null) {
            int oldCount = allocation.getHealthyServers();
            allocation.updateHealthyServerCount(healthyServerCount);
            
            log.info("Updated healthy server count for XA {}: {} -> {}, max transactions: {}", 
                    connHash, oldCount, healthyServerCount, allocation.getCurrentMaxTransactions());
        }
    }
    
    /**
     * Gets the XA allocation for a connection hash.
     * 
     * @param connHash Connection hash
     * @return XaAllocation or null if not found
     */
    public XaAllocation getXaAllocation(String connHash) {
        return xaAllocations.get(connHash);
    }
    
    /**
     * Removes XA allocation tracking for a connection hash.
     * 
     * @param connHash Connection hash
     */
    public void removeAllocation(String connHash) {
        xaAllocations.remove(connHash);
        log.debug("Removed XA allocation for {}", connHash);
    }
}
