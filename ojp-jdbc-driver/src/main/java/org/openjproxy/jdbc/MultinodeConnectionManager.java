package org.openjproxy.jdbc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.jdbc.MultinodeUrlParser.Endpoint;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages server selection, session binding, and failover for multinode deployments.
 * Thread-safe for concurrent access.
 */
@Slf4j
public class MultinodeConnectionManager {
    
    private final List<Endpoint> endpoints;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final Map<String, Endpoint> sessionToServer = new ConcurrentHashMap<>();
    private final Map<Endpoint, ServerHealth> serverHealth = new ConcurrentHashMap<>();
    
    // Configuration
    private final int maxRetries;
    private final long unhealthyTimeout;
    
    /**
     * Tracks the health status of a server.
     */
    private static class ServerHealth {
        private final AtomicLong lastFailureTime = new AtomicLong(0);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        
        boolean isHealthy(long unhealthyTimeout) {
            long lastFailure = lastFailureTime.get();
            if (lastFailure == 0) {
                return true; // Never failed
            }
            
            long timeSinceFailure = System.currentTimeMillis() - lastFailure;
            return timeSinceFailure >= unhealthyTimeout;
        }
        
        void recordFailure() {
            lastFailureTime.set(System.currentTimeMillis());
            consecutiveFailures.incrementAndGet();
        }
        
        void recordSuccess() {
            lastFailureTime.set(0);
            consecutiveFailures.set(0);
        }
    }
    
    /**
     * Create a new MultinodeConnectionManager.
     * 
     * @param endpoints list of server endpoints
     * @param maxRetries maximum number of retry attempts (default: 2)
     * @param unhealthyTimeoutMs time to wait before retrying an unhealthy server (default: 30000ms)
     */
    public MultinodeConnectionManager(List<Endpoint> endpoints, int maxRetries, long unhealthyTimeoutMs) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("Endpoints list cannot be null or empty");
        }
        
        this.endpoints = endpoints;
        this.maxRetries = maxRetries;
        this.unhealthyTimeout = unhealthyTimeoutMs;
        
        // Initialize health tracking for all endpoints
        for (Endpoint endpoint : endpoints) {
            serverHealth.put(endpoint, new ServerHealth());
        }
        
        log.info("Initialized MultinodeConnectionManager with {} endpoints, maxRetries={}, unhealthyTimeout={}ms",
                endpoints.size(), maxRetries, unhealthyTimeoutMs);
    }
    
    /**
     * Create with default retry configuration.
     */
    public MultinodeConnectionManager(List<Endpoint> endpoints) {
        this(endpoints, 2, 30000L);
    }
    
    /**
     * Select a server using round-robin load balancing.
     * Only selects healthy servers if available.
     * 
     * @return the selected endpoint
     */
    public Endpoint selectServer() {
        int attempts = 0;
        int maxAttempts = endpoints.size();
        
        while (attempts < maxAttempts) {
            int index = Math.abs(roundRobinCounter.getAndIncrement() % endpoints.size());
            Endpoint endpoint = endpoints.get(index);
            ServerHealth health = serverHealth.get(endpoint);
            
            if (health.isHealthy(unhealthyTimeout)) {
                log.debug("Selected healthy server: {}", endpoint);
                return endpoint;
            }
            
            attempts++;
        }
        
        // All servers are unhealthy, return the next one anyway (circuit breaker pattern)
        int index = Math.abs(roundRobinCounter.getAndIncrement() % endpoints.size());
        Endpoint endpoint = endpoints.get(index);
        log.warn("All servers unhealthy, selecting {} anyway", endpoint);
        return endpoint;
    }
    
    /**
     * Bind a session to a specific server.
     * 
     * @param sessionId the session ID
     * @param endpoint the server endpoint
     */
    public void bindSession(String sessionId, Endpoint endpoint) {
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("Attempted to bind null or empty session ID");
            return;
        }
        
        sessionToServer.put(sessionId, endpoint);
        log.debug("Bound session {} to server {}", sessionId, endpoint);
    }
    
    /**
     * Get the server bound to a session.
     * 
     * @param sessionId the session ID
     * @return the bound endpoint, or null if not bound
     */
    public Endpoint getServerForSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        
        Endpoint endpoint = sessionToServer.get(sessionId);
        log.debug("Retrieved server {} for session {}", endpoint, sessionId);
        return endpoint;
    }
    
    /**
     * Unbind a session from its server (e.g., when connection closes).
     * 
     * @param sessionId the session ID
     */
    public void unbindSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        
        Endpoint removed = sessionToServer.remove(sessionId);
        log.debug("Unbound session {} from server {}", sessionId, removed);
    }
    
    /**
     * Mark a server as unhealthy after a connection failure.
     * 
     * @param endpoint the failed server endpoint
     */
    public void markServerUnhealthy(Endpoint endpoint) {
        ServerHealth health = serverHealth.get(endpoint);
        if (health != null) {
            health.recordFailure();
            log.warn("Marked server {} as unhealthy (consecutive failures: {})",
                    endpoint, health.consecutiveFailures.get());
        }
    }
    
    /**
     * Mark a server as healthy after a successful operation.
     * 
     * @param endpoint the server endpoint
     */
    public void markServerHealthy(Endpoint endpoint) {
        ServerHealth health = serverHealth.get(endpoint);
        if (health != null) {
            health.recordSuccess();
            log.debug("Marked server {} as healthy", endpoint);
        }
    }
    
    /**
     * Check if an exception is a connection-level error that should trigger failover.
     * Database errors (like constraint violations) should not trigger failover.
     * 
     * @param throwable the exception to check
     * @return true if it's a connection-level error
     */
    public boolean isConnectionLevelError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        // Check for gRPC connection errors
        if (throwable instanceof StatusRuntimeException) {
            StatusRuntimeException sre = (StatusRuntimeException) throwable;
            Status.Code code = sre.getStatus().getCode();
            
            // Connection-level errors
            boolean isConnectionError = code == Status.Code.UNAVAILABLE
                    || code == Status.Code.DEADLINE_EXCEEDED
                    || code == Status.Code.CANCELLED
                    || code == Status.Code.UNKNOWN;
            
            if (isConnectionError) {
                log.debug("Detected connection-level error: {}", code);
            }
            
            return isConnectionError;
        }
        
        // Check for common connection exceptions
        String message = throwable.getMessage();
        if (message != null) {
            message = message.toLowerCase();
            boolean isConnectionError = message.contains("connection refused")
                    || message.contains("connection reset")
                    || message.contains("connection timeout")
                    || message.contains("network error")
                    || message.contains("unable to connect")
                    || message.contains("broken pipe");
            
            if (isConnectionError) {
                log.debug("Detected connection-level error from message: {}", message);
            }
            
            return isConnectionError;
        }
        
        return false;
    }
    
    /**
     * Get the maximum number of retries.
     */
    public int getMaxRetries() {
        return maxRetries;
    }
    
    /**
     * Get all endpoints.
     */
    public List<Endpoint> getEndpoints() {
        return endpoints;
    }
}
