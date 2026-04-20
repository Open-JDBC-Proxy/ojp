package org.openjproxy.grpc.client;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper class for multinode integration tests.
 * Contains common logic for exception tracking and categorization.
 * Each test should instantiate its own helper to avoid counter conflicts.
 */
public class MultinodeTestHelper {
    
    private final AtomicInteger totalFailedQueries;
    private final AtomicInteger nonConnectivityFailedQueries;
    
    /**
     * Creates a new helper instance with the provided counters.
     * 
     * @param totalFailedQueries Counter for all failures
     * @param nonConnectivityFailedQueries Counter for non-connectivity failures only
     */
    public MultinodeTestHelper(AtomicInteger totalFailedQueries, 
                               AtomicInteger nonConnectivityFailedQueries) {
        this.totalFailedQueries = totalFailedQueries;
        this.nonConnectivityFailedQueries = nonConnectivityFailedQueries;
    }
    
    /**
     * Categorizes and increments failure counters based on exception type.
     * 
     * @param e The exception that caused the failure
     */
    public void incrementFailures(Exception e) {
        totalFailedQueries.incrementAndGet();
        
        // Check if this is a connectivity-related failure using the shared handler logic
        // 1. Connection-level errors (UNAVAILABLE, DEADLINE_EXCEEDED, etc.)
        // 2. Session invalidation errors - indirect result of server unavailability
        boolean isConnectivityRelated = GrpcExceptionHandler.isConnectionLevelError(e) || 
                                        GrpcExceptionHandler.isSessionInvalidationError(e);
        
        if (!isConnectivityRelated) {
            // Errors non related to the fact that a node is down
            nonConnectivityFailedQueries.incrementAndGet();
        }
    }
    
    /**
     * Determines if an exception is connectivity-related (expected during server restarts).
     * 
     * @param e The exception to check
     * @return true if the exception is connectivity-related, false otherwise
     */
    public boolean isConnectivityRelated(Exception e) {
        return GrpcExceptionHandler.isConnectionLevelError(e) || 
               GrpcExceptionHandler.isSessionInvalidationError(e);
    }
}
