package org.openjproxy.interceptor;

/**
 * Defines the lifecycle phases where interceptors can hook into request processing.
 * 
 * <p>Each phase represents a distinct point in the request execution lifecycle.
 * Interceptors can selectively support specific phases to provide targeted functionality.</p>
 */
public enum LifecyclePhase {
    /**
     * Before request processing begins.
     * Used for: session validation, cluster health processing, request enrichment.
     */
    PRE_REQUEST,
    
    /**
     * Before execution (after SQL hash, before circuit breaker).
     * Used for: circuit breaker checks, SQL transformation, query validation.
     */
    PRE_EXECUTION,
    
    /**
     * During resource acquisition (connections, slots).
     * Used for: slow query slot acquisition, connection pooling.
     */
    RESOURCE_ACQUISITION,
    
    /**
     * During actual database execution.
     * Used for: query execution monitoring, tracing.
     */
    EXECUTION,
    
    /**
     * After execution completes successfully.
     * Used for: result processing, metadata extraction, performance recording.
     */
    POST_EXECUTION,
    
    /**
     * During resource cleanup and release.
     * Used for: connection release, slot release, cleanup.
     */
    RESOURCE_RELEASE,
    
    /**
     * After request completes (success or failure).
     * Used for: success/failure recording, metrics publishing, logging.
     */
    POST_REQUEST,
    
    /**
     * When an exception occurs at any phase.
     * Used for: exception transformation, failure recording, recovery attempts.
     */
    EXCEPTION_HANDLING
}
