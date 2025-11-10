package org.openjproxy.grpc.server.xa;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;

import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages dynamic pool sizing for Atomikos XA connection pools based on cluster health.
 * 
 * <p>This class mirrors the behavior of HikariCP's multinode pool coordination but adapted
 * for Atomikos XADataSource pooling. Key responsibilities:</p>
 * 
 * <ul>
 *   <li>Calculate initial pool sizes based on healthy nodes at startup</li>
 *   <li>Resize pools when cluster health changes (nodes UP/DOWN)</li>
 *   <li>Prevent thrashing via cooldown mechanism</li>
 *   <li>Ensure thread-safe pool resizing operations</li>
 *   <li>Respect global pool size limits</li>
 * </ul>
 * 
 * <h3>Differences from HikariCP Implementation:</h3>
 * <ul>
 *   <li><b>Runtime Setters:</b> Atomikos supports setMinPoolSize/setMaxPoolSize at runtime,
 *       making resizing simpler than with some connection pools</li>
 *   <li><b>No Connection Draining:</b> Unlike pool recreation, runtime setters don't require
 *       waiting for active connections to return</li>
 *   <li><b>Gradual Pool Reduction:</b> When reducing pool size, Atomikos will gradually close
 *       idle connections as they return to the pool</li>
 *   <li><b>Thread Safety:</b> All resize operations are serialized using a ReentrantLock to
 *       prevent concurrent modifications</li>
 * </ul>
 * 
 * <h3>Configuration Properties:</h3>
 * <ul>
 *   <li>{@code ojp.atomikos.perNodeMinPoolSize} - Minimum connections per healthy node (default: 2)</li>
 *   <li>{@code ojp.atomikos.perNodeMaxPoolSize} - Maximum connections per healthy node (default: 10)</li>
 *   <li>{@code ojp.atomikos.globalMaxPoolSize} - Global upper bound (default: 100)</li>
 *   <li>{@code ojp.atomikos.sizingCooldownMs} - Cooldown between resizes (default: 5000ms)</li>
 * </ul>
 * 
 * @see org.openjproxy.grpc.server.MultinodePoolCoordinator
 * @see com.atomikos.jdbc.AtomikosDataSourceBean
 */
@Slf4j
public class AtomikosDynamicPoolSizer {
    
    private final AtomikosDataSourceBean atomikosDataSource;
    private final String resourceName;
    private final int perNodeMinPoolSize;
    private final int perNodeMaxPoolSize;
    private final int globalMaxPoolSize;
    private final long sizingCooldownMs;
    
    // Thread safety and cooldown management
    private final ReentrantLock resizeLock = new ReentrantLock();
    private final ScheduledExecutorService cooldownExecutor = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "atomikos-pool-sizer");
            t.setDaemon(true);
            return t;
        }
    );
    
    private volatile long lastResizeTimestamp = 0;
    private volatile int currentHealthyNodes = 0;
    private volatile int currentMinPoolSize = 0;
    private volatile int currentMaxPoolSize = 0;
    
    /**
     * Creates a dynamic pool sizer for an Atomikos datasource.
     * 
     * @param atomikosDataSource The Atomikos datasource to manage
     * @param resourceName Unique resource name for logging
     * @param poolConfig Configuration properties
     */
    public AtomikosDynamicPoolSizer(AtomikosDataSourceBean atomikosDataSource, 
                                   String resourceName, 
                                   Properties poolConfig) {
        this.atomikosDataSource = atomikosDataSource;
        this.resourceName = resourceName;
        
        // Read configuration properties
        this.perNodeMinPoolSize = getIntProperty(poolConfig, 
            CommonConstants.ATOMIKOS_PER_NODE_MIN_POOL_SIZE_PROPERTY, 
            CommonConstants.DEFAULT_ATOMIKOS_PER_NODE_MIN_POOL_SIZE);
        
        this.perNodeMaxPoolSize = getIntProperty(poolConfig, 
            CommonConstants.ATOMIKOS_PER_NODE_MAX_POOL_SIZE_PROPERTY, 
            CommonConstants.DEFAULT_ATOMIKOS_PER_NODE_MAX_POOL_SIZE);
        
        this.globalMaxPoolSize = getIntProperty(poolConfig, 
            CommonConstants.ATOMIKOS_GLOBAL_MAX_POOL_SIZE_PROPERTY, 
            CommonConstants.DEFAULT_ATOMIKOS_GLOBAL_MAX_POOL_SIZE);
        
        this.sizingCooldownMs = getLongProperty(poolConfig, 
            CommonConstants.ATOMIKOS_SIZING_COOLDOWN_MS_PROPERTY, 
            CommonConstants.DEFAULT_ATOMIKOS_SIZING_COOLDOWN_MS);
        
        log.info("AtomikosDynamicPoolSizer initialized for '{}': perNodeMin={}, perNodeMax={}, globalMax={}, cooldownMs={}", 
            resourceName, perNodeMinPoolSize, perNodeMaxPoolSize, globalMaxPoolSize, sizingCooldownMs);
    }
    
    /**
     * Performs initial pool sizing based on the number of healthy nodes at startup.
     * This method should be called once during pool initialization.
     * 
     * @param healthyNodes Number of healthy cluster nodes
     */
    public void performStartupSizing(int healthyNodes) {
        resizeLock.lock();
        try {
            currentHealthyNodes = Math.max(1, healthyNodes);
            
            int newMinPoolSize = calculateMinPoolSize(currentHealthyNodes);
            int newMaxPoolSize = calculateMaxPoolSize(currentHealthyNodes);
            
            atomikosDataSource.setMinPoolSize(newMinPoolSize);
            atomikosDataSource.setMaxPoolSize(newMaxPoolSize);
            
            currentMinPoolSize = newMinPoolSize;
            currentMaxPoolSize = newMaxPoolSize;
            lastResizeTimestamp = System.currentTimeMillis();
            
            log.info("Startup sizing for '{}': healthyNodes={}, minPoolSize={}, maxPoolSize={}", 
                resourceName, currentHealthyNodes, newMinPoolSize, newMaxPoolSize);
        } finally {
            resizeLock.unlock();
        }
    }
    
    /**
     * Resizes the pool based on cluster health change.
     * This method is idempotent and respects the cooldown period.
     * 
     * @param healthyNodes New number of healthy cluster nodes
     */
    public void resizePoolForHealthChange(int healthyNodes) {
        // Validate input
        if (healthyNodes < 1) {
            log.warn("Invalid healthyNodes count: {}, ignoring resize request", healthyNodes);
            return;
        }
        
        // Check cooldown period (non-blocking check)
        long timeSinceLastResize = System.currentTimeMillis() - lastResizeTimestamp;
        if (timeSinceLastResize < sizingCooldownMs) {
            long remainingCooldown = sizingCooldownMs - timeSinceLastResize;
            log.debug("Resize request for '{}' ignored - cooldown active ({}ms remaining)", 
                resourceName, remainingCooldown);
            return;
        }
        
        // Schedule resize task asynchronously to avoid blocking health change notifications
        cooldownExecutor.submit(() -> performResize(healthyNodes));
    }
    
    /**
     * Internal method that performs the actual resize operation.
     * Protected by lock to ensure thread safety.
     */
    private void performResize(int healthyNodes) {
        resizeLock.lock();
        try {
            // Double-check cooldown after acquiring lock (another thread may have resized)
            long timeSinceLastResize = System.currentTimeMillis() - lastResizeTimestamp;
            if (timeSinceLastResize < sizingCooldownMs) {
                log.debug("Resize for '{}' skipped - another thread completed resize recently", resourceName);
                return;
            }
            
            // Check if health count has actually changed
            if (healthyNodes == currentHealthyNodes) {
                log.debug("Healthy nodes count unchanged for '{}' ({}), skipping resize", 
                    resourceName, healthyNodes);
                return;
            }
            
            int newMinPoolSize = calculateMinPoolSize(healthyNodes);
            int newMaxPoolSize = calculateMaxPoolSize(healthyNodes);
            
            // Check if sizes have actually changed (idempotent check)
            if (newMinPoolSize == currentMinPoolSize && newMaxPoolSize == currentMaxPoolSize) {
                log.debug("Pool sizes unchanged for '{}', skipping actual resize", resourceName);
                currentHealthyNodes = healthyNodes; // Update health count even if sizes didn't change
                return;
            }
            
            log.info("Resizing Atomikos pool '{}': healthyNodes {} -> {}, minPoolSize {} -> {}, maxPoolSize {} -> {}", 
                resourceName, currentHealthyNodes, healthyNodes, 
                currentMinPoolSize, newMinPoolSize, currentMaxPoolSize, newMaxPoolSize);
            
            // Determine resize direction
            boolean isDecreasing = (newMaxPoolSize < currentMaxPoolSize) || (newMinPoolSize < currentMinPoolSize);
            
            if (isDecreasing) {
                // When reducing pool size: set minPoolSize first, then maxPoolSize
                // This avoids validation errors (minPoolSize <= maxPoolSize must always hold)
                atomikosDataSource.setMinPoolSize(newMinPoolSize);
                atomikosDataSource.setMaxPoolSize(newMaxPoolSize);
                
                log.info("Pool '{}' decreased successfully. Idle connections above {} will be closed gradually.", 
                    resourceName, newMinPoolSize);
            } else {
                // When increasing pool size: set maxPoolSize first, then minPoolSize
                // This allows the pool to grow before setting the new minimum
                atomikosDataSource.setMaxPoolSize(newMaxPoolSize);
                atomikosDataSource.setMinPoolSize(newMinPoolSize);
                
                log.info("Pool '{}' increased successfully. Pool can now grow up to {} connections.", 
                    resourceName, newMaxPoolSize);
            }
            
            // Update state
            currentHealthyNodes = healthyNodes;
            currentMinPoolSize = newMinPoolSize;
            currentMaxPoolSize = newMaxPoolSize;
            lastResizeTimestamp = System.currentTimeMillis();
            
        } catch (Exception e) {
            log.error("Error resizing Atomikos pool '{}': {}", resourceName, e.getMessage(), e);
        } finally {
            resizeLock.unlock();
        }
    }
    
    /**
     * Calculates minimum pool size based on healthy nodes.
     * Formula: perNodeMinPoolSize * healthyNodes
     */
    private int calculateMinPoolSize(int healthyNodes) {
        return perNodeMinPoolSize * healthyNodes;
    }
    
    /**
     * Calculates maximum pool size based on healthy nodes.
     * Formula: min(perNodeMaxPoolSize * healthyNodes, globalMaxPoolSize)
     */
    private int calculateMaxPoolSize(int healthyNodes) {
        int calculated = perNodeMaxPoolSize * healthyNodes;
        return Math.min(calculated, globalMaxPoolSize);
    }
    
    /**
     * Gets current pool sizing information for debugging/monitoring.
     */
    public String getPoolSizingInfo() {
        return String.format("AtomikosPoolSizing[%s]: healthyNodes=%d, minPoolSize=%d, maxPoolSize=%d, " +
                "perNodeMin=%d, perNodeMax=%d, globalMax=%d",
            resourceName, currentHealthyNodes, currentMinPoolSize, currentMaxPoolSize,
            perNodeMinPoolSize, perNodeMaxPoolSize, globalMaxPoolSize);
    }
    
    /**
     * Shuts down the cooldown executor.
     * Should be called when the pool is being closed.
     */
    public void shutdown() {
        log.info("Shutting down AtomikosDynamicPoolSizer for '{}'", resourceName);
        cooldownExecutor.shutdown();
        try {
            if (!cooldownExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cooldownExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cooldownExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // Helper methods for property reading
    
    private int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value for {}: '{}', using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    private long getLongProperty(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid long value for {}: '{}', using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
}
