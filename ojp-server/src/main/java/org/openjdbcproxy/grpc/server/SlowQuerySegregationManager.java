package org.openjdbcproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;

/**
 * Manages slow query segregation by combining performance monitoring with slot management.
 * 
 * This class coordinates between the QueryPerformanceMonitor (which tracks execution times)
 * and the SlotManager (which enforces execution limits) to implement the slow query segregation feature.
 */
@Slf4j
public class SlowQuerySegregationManager {
    
    private final QueryPerformanceMonitor performanceMonitor;
    private final SlotManager slotManager;
    private final boolean enabled;
    private final long slowSlotTimeoutMs;
    private final long fastSlotTimeoutMs;
    
    /**
     * Creates a new SlowQuerySegregationManager.
     * 
     * @param totalSlots The maximum total number of concurrent operations (from HikariCP max pool size)
     * @param slowSlotPercentage The percentage of slots allocated to slow operations (0-100)
     * @param idleTimeoutMs The time in milliseconds before a slot is considered idle and eligible for borrowing
     * @param slowSlotTimeoutMs The timeout in milliseconds for acquiring slow operation slots
     * @param fastSlotTimeoutMs The timeout in milliseconds for acquiring fast operation slots
     * @param enabled Whether the slow query segregation feature is enabled
     */
    public SlowQuerySegregationManager(int totalSlots, int slowSlotPercentage, long idleTimeoutMs, 
                                     long slowSlotTimeoutMs, long fastSlotTimeoutMs, boolean enabled) {
        this.enabled = enabled;
        this.slowSlotTimeoutMs = slowSlotTimeoutMs;
        this.fastSlotTimeoutMs = fastSlotTimeoutMs;
        this.performanceMonitor = new QueryPerformanceMonitor();
        
        if (enabled) {
            this.slotManager = new SlotManager(totalSlots, slowSlotPercentage, idleTimeoutMs);
            log.info("SlowQuerySegregationManager initialized: enabled={}, totalSlots={}, slowSlotPercentage={}%, idleTimeout={}ms, slowSlotTimeout={}ms, fastSlotTimeout={}ms", 
                    enabled, totalSlots, slowSlotPercentage, idleTimeoutMs, slowSlotTimeoutMs, fastSlotTimeoutMs);
        } else {
            this.slotManager = null;
            log.info("SlowQuerySegregationManager initialized: enabled={}", enabled);
        }
    }
    
    /**
     * Executes an operation with slow query segregation.
     * This method handles slot acquisition, performance monitoring, and slot release.
     * 
     * @param operationHash The hash of the SQL operation
     * @param operation The operation to execute
     * @param <T> The return type of the operation
     * @return The result of the operation
     * @throws Exception if the operation fails or slot acquisition times out
     */
    public <T> T executeWithSegregation(String operationHash, SegregatedOperation<T> operation) throws Exception {
        if (!enabled) {
            // If segregation is disabled, just execute and monitor performance
            return executeAndMonitor(operationHash, operation);
        }
        
        // Determine if this is a slow or fast operation
        boolean isSlowOperation = performanceMonitor.isSlowOperation(operationHash);
        
        // Acquire appropriate slot
        boolean slotAcquired = false;
        long startTime = System.currentTimeMillis();
        
        try {
            if (isSlowOperation) {
                slotAcquired = slotManager.acquireSlowSlot(slowSlotTimeoutMs);
                if (!slotAcquired) {
                    throw new RuntimeException("Timeout waiting for slow operation slot for operation: " + operationHash);
                }
                log.debug("Acquired slow slot for operation: {}", operationHash);
            } else {
                slotAcquired = slotManager.acquireFastSlot(fastSlotTimeoutMs);
                if (!slotAcquired) {
                    throw new RuntimeException("Timeout waiting for fast operation slot for operation: " + operationHash);
                }
                log.debug("Acquired fast slot for operation: {}", operationHash);
            }
            
            // Execute the operation and monitor its performance
            return executeAndMonitor(operationHash, operation);
            
        } finally {
            // Always release the slot
            if (slotAcquired) {
                if (isSlowOperation) {
                    slotManager.releaseSlowSlot();
                    log.debug("Released slow slot for operation: {}", operationHash);
                } else {
                    slotManager.releaseFastSlot();
                    log.debug("Released fast slot for operation: {}", operationHash);
                }
            }
        }
    }
    
    /**
     * Executes an operation and monitors its performance without slot management.
     */
    private <T> T executeAndMonitor(String operationHash, SegregatedOperation<T> operation) throws Exception {
        long startTime = System.currentTimeMillis();
        
        try {
            T result = operation.execute();
            
            // Record successful execution time
            long executionTime = System.currentTimeMillis() - startTime;
            performanceMonitor.recordExecutionTime(operationHash, executionTime);
            
            return result;
        } catch (Exception e) {
            // Still record execution time even for failed operations for monitoring purposes
            long executionTime = System.currentTimeMillis() - startTime;
            performanceMonitor.recordExecutionTime(operationHash, executionTime);
            throw e;
        }
    }
    
    /**
     * Gets the current status of both the performance monitor and slot manager.
     */
    public String getStatus() {
        if (!enabled) {
            return "SlowQuerySegregationManager[enabled=false]";
        }
        
        return String.format(
            "SlowQuerySegregationManager[enabled=true, trackedOps=%d, totalExecs=%d, overallAvg=%.2fms, %s]",
            performanceMonitor.getTrackedOperationCount(),
            performanceMonitor.getTotalExecutionCount(),
            performanceMonitor.getOverallAverageExecutionTime(),
            slotManager.getStatus()
        );
    }
    
    /**
     * Checks if an operation is currently classified as slow.
     */
    public boolean isSlowOperation(String operationHash) {
        return performanceMonitor.isSlowOperation(operationHash);
    }
    
    /**
     * Gets the average execution time for a specific operation.
     */
    public double getOperationAverageTime(String operationHash) {
        return performanceMonitor.getOperationAverageTime(operationHash);
    }
    
    /**
     * Gets the overall average execution time across all operations.
     */
    public double getOverallAverageExecutionTime() {
        return performanceMonitor.getOverallAverageExecutionTime();
    }
    
    /**
     * Checks if the slow query segregation feature is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Gets the performance monitor (for testing purposes).
     */
    public QueryPerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }
    
    /**
     * Gets the slot manager (for testing purposes).
     */
    public SlotManager getSlotManager() {
        return slotManager;
    }
    
    /**
     * Functional interface for operations that can be executed with segregation.
     */
    @FunctionalInterface
    public interface SegregatedOperation<T> {
        T execute() throws Exception;
    }
}