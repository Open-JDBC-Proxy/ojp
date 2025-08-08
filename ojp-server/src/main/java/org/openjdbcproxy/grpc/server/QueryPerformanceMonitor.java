package org.openjdbcproxy.grpc.server;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors the performance of SQL operations and tracks their average execution times.
 * 
 * This class tracks execution times for unique operations (identified by their SQL hash)
 * and maintains a rolling average using the formula:
 * new_average = ((stored_average * 4) + new_measurement) / 5
 * 
 * This gives 20% weight to the newest measurement, smoothing out outliers.
 */
@Slf4j
public class QueryPerformanceMonitor {
    
    /**
     * Record for tracking operation performance metrics.
     */
    private static class PerformanceRecord {
        private volatile double averageExecutionTime;
        private final AtomicLong executionCount;
        
        public PerformanceRecord(double initialTime) {
            this.averageExecutionTime = initialTime;
            this.executionCount = new AtomicLong(1);
        }
        
        /**
         * Updates the average execution time using the weighted formula.
         * new_average = ((stored_average * 4) + new_measurement) / 5
         */
        public void updateAverage(double newMeasurement) {
            synchronized (this) {
                this.averageExecutionTime = ((this.averageExecutionTime * 4) + newMeasurement) / 5;
                this.executionCount.incrementAndGet();
            }
        }
        
        public double getAverageExecutionTime() {
            return averageExecutionTime;
        }
        
        public long getExecutionCount() {
            return executionCount.get();
        }
    }
    
    private final ConcurrentHashMap<String, PerformanceRecord> operationRecords = new ConcurrentHashMap<>();
    private volatile double overallAverageExecutionTime = 0.0;
    private final AtomicLong totalOperations = new AtomicLong(0);
    
    /**
     * Records the execution time for an operation.
     * 
     * @param operationHash The hash of the SQL operation (from SqlStatementXXHash)
     * @param executionTimeMs The execution time in milliseconds
     */
    public void recordExecutionTime(String operationHash, double executionTimeMs) {
        if (operationHash == null || executionTimeMs < 0) {
            log.warn("Invalid operation hash or execution time: hash={}, time={}", operationHash, executionTimeMs);
            return;
        }
        
        PerformanceRecord record = operationRecords.compute(operationHash, (key, existing) -> {
            if (existing == null) {
                return new PerformanceRecord(executionTimeMs);
            } else {
                existing.updateAverage(executionTimeMs);
                return existing;
            }
        });
        
        totalOperations.incrementAndGet();
        updateOverallAverage();
        
        log.debug("Updated operation {} with execution time {}ms, average now {}ms", 
                 operationHash, executionTimeMs, record.getAverageExecutionTime());
    }
    
    /**
     * Gets the average execution time for a specific operation.
     * 
     * @param operationHash The hash of the SQL operation
     * @return The average execution time in milliseconds, or 0.0 if not found
     */
    public double getOperationAverageTime(String operationHash) {
        PerformanceRecord record = operationRecords.get(operationHash);
        return record != null ? record.getAverageExecutionTime() : 0.0;
    }
    
    /**
     * Gets the overall average execution time across all tracked operations.
     * This is the average of all individual operation averages.
     * 
     * @return The overall average execution time in milliseconds
     */
    public double getOverallAverageExecutionTime() {
        return overallAverageExecutionTime;
    }
    
    /**
     * Determines if an operation is classified as "slow".
     * An operation is slow if its average execution time is 2x or greater than the overall average.
     * 
     * @param operationHash The hash of the SQL operation
     * @return true if the operation is classified as slow, false otherwise
     */
    public boolean isSlowOperation(String operationHash) {
        double operationAverage = getOperationAverageTime(operationHash);
        double overallAverage = getOverallAverageExecutionTime();
        
        // If overall average is 0 or very small, consider all operations as fast initially
        if (overallAverage <= 1.0) {
            return false;
        }
        
        boolean isSlow = operationAverage >= (overallAverage * 2.0);
        log.debug("Operation {} classification: average={}ms, overall={}ms, slow={}", 
                 operationHash, operationAverage, overallAverage, isSlow);
        
        return isSlow;
    }
    
    /**
     * Updates the overall average execution time.
     * This is calculated as the average of all current operation averages.
     */
    private void updateOverallAverage() {
        if (operationRecords.isEmpty()) {
            overallAverageExecutionTime = 0.0;
            return;
        }
        
        double sum = operationRecords.values().stream()
                .mapToDouble(PerformanceRecord::getAverageExecutionTime)
                .sum();
        
        overallAverageExecutionTime = sum / operationRecords.size();
        
        log.trace("Updated overall average execution time to {}ms across {} operations", 
                 overallAverageExecutionTime, operationRecords.size());
    }
    
    /**
     * Gets the number of unique operations being tracked.
     * 
     * @return The number of unique operations
     */
    public int getTrackedOperationCount() {
        return operationRecords.size();
    }
    
    /**
     * Gets the total number of operation executions recorded.
     * 
     * @return The total execution count across all operations
     */
    public long getTotalExecutionCount() {
        return totalOperations.get();
    }
    
    /**
     * Clears all performance records. Used primarily for testing.
     */
    public void clear() {
        operationRecords.clear();
        overallAverageExecutionTime = 0.0;
        totalOperations.set(0);
        log.info("Performance monitor cleared");
    }
}