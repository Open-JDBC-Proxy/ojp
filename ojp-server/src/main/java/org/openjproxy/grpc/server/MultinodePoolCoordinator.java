package org.openjproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates connection pool sizes across multiple OJP server instances in a multinode setup.
 * 
 * When multiple servers are configured:
 * - Pool sizes are divided among servers (e.g., maxPoolSize=20 with 2 servers = 10 each)
 * - When a server becomes unhealthy, remaining servers increase their pools to maintain capacity
 * - When an unhealthy server recovers, all servers rebalance back to divided pool sizes
 * 
 * This ensures global pool limits are respected while maintaining high availability.
 */
@Slf4j
public class MultinodePoolCoordinator {
    
    private final Map<String, PoolAllocation> poolAllocations = new ConcurrentHashMap<>();
    
    /**
     * Represents the pool allocation for a connection hash (dataSource).
     */
    public static class PoolAllocation {
        private final int originalMaxPoolSize;
        private final int originalMinIdle;
        private final int totalServers;
        private int healthyServers;
        
        public PoolAllocation(int originalMaxPoolSize, int originalMinIdle, int totalServers) {
            this.originalMaxPoolSize = originalMaxPoolSize;
            this.originalMinIdle = originalMinIdle;
            this.totalServers = totalServers;
            this.healthyServers = totalServers;
        }
        
        public int getCurrentMaxPoolSize() {
            if (healthyServers <= 0) {
                return originalMaxPoolSize; // Fallback to original if no healthy servers
            }
            // Divide the total pool among healthy servers, rounding up
            return (int) Math.ceil((double) originalMaxPoolSize / healthyServers);
        }
        
        public int getCurrentMinIdle() {
            if (healthyServers <= 0) {
                return originalMinIdle; // Fallback to original if no healthy servers
            }
            // Divide the total min idle among healthy servers, rounding up
            return (int) Math.ceil((double) originalMinIdle / healthyServers);
        }
        
        public void updateHealthyServerCount(int count) {
            this.healthyServers = Math.max(1, Math.min(count, totalServers));
        }
        
        public int getOriginalMaxPoolSize() {
            return originalMaxPoolSize;
        }
        
        public int getOriginalMinIdle() {
            return originalMinIdle;
        }
        
        public int getTotalServers() {
            return totalServers;
        }
        
        public int getHealthyServers() {
            return healthyServers;
        }
    }
    
    /**
     * Calculates pool sizes for a multinode configuration.
     * 
     * @param connHash Connection hash identifying the datasource
     * @param requestedMaxPoolSize Maximum pool size from configuration
     * @param requestedMinIdle Minimum idle connections from configuration
     * @param serverEndpoints List of server endpoints in the cluster
     * @return PoolAllocation with calculated sizes
     */
    public PoolAllocation calculatePoolSizes(String connHash, int requestedMaxPoolSize, 
                                            int requestedMinIdle, List<String> serverEndpoints) {
        
        if (serverEndpoints == null || serverEndpoints.isEmpty()) {
            // Single node - no coordination needed, return original values
            log.debug("Single node configuration for {}, using original pool sizes: max={}, min={}", 
                    connHash, requestedMaxPoolSize, requestedMinIdle);
            return new PoolAllocation(requestedMaxPoolSize, requestedMinIdle, 1);
        }
        
        int serverCount = serverEndpoints.size();
        
        // Create allocation with divided pool sizes
        PoolAllocation allocation = new PoolAllocation(requestedMaxPoolSize, requestedMinIdle, serverCount);
        
        poolAllocations.put(connHash, allocation);
        
        log.info("Multinode configuration for {}: {} servers, original max={}, min={}, divided max={}, min={}", 
                connHash, serverCount, requestedMaxPoolSize, requestedMinIdle, 
                allocation.getCurrentMaxPoolSize(), allocation.getCurrentMinIdle());
        
        return allocation;
    }
    
    /**
     * Updates the number of healthy servers for a connection hash.
     * This triggers pool size recalculation.
     * 
     * @param connHash Connection hash identifying the datasource
     * @param healthyServerCount Number of currently healthy servers
     */
    public void updateHealthyServers(String connHash, int healthyServerCount) {
        PoolAllocation allocation = poolAllocations.get(connHash);
        if (allocation != null) {
            int oldCount = allocation.getHealthyServers();
            allocation.updateHealthyServerCount(healthyServerCount);
            
            log.info("Updated healthy server count for {}: {} -> {}, pool sizes: max={}, min={}", 
                    connHash, oldCount, healthyServerCount, 
                    allocation.getCurrentMaxPoolSize(), allocation.getCurrentMinIdle());
        }
    }
    
    /**
     * Gets the pool allocation for a connection hash.
     * 
     * @param connHash Connection hash
     * @return PoolAllocation or null if not found
     */
    public PoolAllocation getPoolAllocation(String connHash) {
        return poolAllocations.get(connHash);
    }
    
    /**
     * Removes pool allocation tracking for a connection hash.
     * 
     * @param connHash Connection hash
     */
    public void removeAllocation(String connHash) {
        poolAllocations.remove(connHash);
        log.debug("Removed pool allocation for {}", connHash);
    }
    
    /**
     * Gets the default pool sizes with fallback to CommonConstants.
     */
    public static class DefaultPoolSizes {
        public final int maxPoolSize;
        public final int minIdle;
        
        public DefaultPoolSizes(int maxPoolSize, int minIdle) {
            this.maxPoolSize = maxPoolSize <= 0 ? CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE : maxPoolSize;
            this.minIdle = minIdle < 0 ? CommonConstants.DEFAULT_MINIMUM_IDLE : minIdle;
        }
    }
}
