package org.openjproxy.grpc.client;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.GrpcChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Manages multinode connections to OJP servers with round-robin routing,
 * session stickiness, and failover support.
 * 
 * Addressing PR #39 review comments:
 * - Comment #3: Throws exception when session server is unavailable (enforces session stickiness)
 * - Configurable retry attempts and delays (via CommonConstants properties)
 */
public class MultinodeConnectionManager {
    
    private static final Logger log = LoggerFactory.getLogger(MultinodeConnectionManager.class);
    private static final String DNS_PREFIX = "dns:///";
    
    private final List<ServerEndpoint> serverEndpoints;
    private final Map<ServerEndpoint, ChannelAndStub> channelMap;
    private final Map<String, ServerEndpoint> sessionToServerMap; // sessionUUID -> server
    private final AtomicInteger roundRobinCounter;
    private final int retryAttempts;
    private final long retryDelayMs;
    
    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints) {
        this(serverEndpoints, CommonConstants.DEFAULT_MULTINODE_RETRY_ATTEMPTS, 
             CommonConstants.DEFAULT_MULTINODE_RETRY_DELAY_MS);
    }
    
    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints, 
                                    int retryAttempts, long retryDelayMs) {
        if (serverEndpoints == null || serverEndpoints.isEmpty()) {
            throw new IllegalArgumentException("Server endpoints list cannot be null or empty");
        }
        
        this.serverEndpoints = List.copyOf(serverEndpoints);
        this.channelMap = new ConcurrentHashMap<>();
        this.sessionToServerMap = new ConcurrentHashMap<>();
        this.roundRobinCounter = new AtomicInteger(0);
        this.retryAttempts = retryAttempts;
        this.retryDelayMs = retryDelayMs;
        
        // Initialize channels and stubs for all servers
        initializeConnections();
        
        log.info("MultinodeConnectionManager initialized with {} servers: {}", 
                serverEndpoints.size(), serverEndpoints);
    }
    
    private void initializeConnections() {
        for (ServerEndpoint endpoint : serverEndpoints) {
            try {
                createChannelAndStub(endpoint);
                log.debug("Successfully initialized connection to {}", endpoint.getAddress());
            } catch (Exception e) {
                log.warn("Failed to initialize connection to {}: {}", endpoint.getAddress(), e.getMessage());
                endpoint.setHealthy(false);
                endpoint.setLastFailureTime(System.currentTimeMillis());
            }
        }
    }
    
    private ChannelAndStub createChannelAndStub(ServerEndpoint endpoint) {
        String target = DNS_PREFIX + endpoint.getHost() + ":" + endpoint.getPort();
        ManagedChannel channel = GrpcChannelFactory.createChannel(target);
        
        StatementServiceGrpc.StatementServiceBlockingStub blockingStub = 
                StatementServiceGrpc.newBlockingStub(channel);
        StatementServiceGrpc.StatementServiceStub asyncStub = 
                StatementServiceGrpc.newStub(channel);
        
        ChannelAndStub channelAndStub = new ChannelAndStub(channel, blockingStub, asyncStub);
        channelMap.put(endpoint, channelAndStub);
        
        return channelAndStub;
    }
    
    /**
     * Establishes a connection by calling connect() on ALL servers.
     * This ensures all servers have the datasource information so that subsequent operations
     * can be routed to any server via round-robin when no session is bound.
     * 
     * Returns the SessionInfo from the first successful connection.
     */
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        SessionInfo primarySessionInfo = null;
        SQLException lastException = null;
        int successfulConnections = 0;
        
        // Try to connect to all servers
        for (ServerEndpoint server : serverEndpoints) {
            if (!server.isHealthy()) {
                log.debug("Skipping unhealthy server: {}", server.getAddress());
                continue;
            }
            
            try {
                ChannelAndStub channelAndStub = channelMap.get(server);
                if (channelAndStub == null) {
                    channelAndStub = createChannelAndStub(server);
                }
                
                log.info("Connecting to server {}", server.getAddress());
                SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);
                
                // Mark server as healthy
                server.setHealthy(true);
                server.setLastFailureTime(0);
                
                // Associate session with server for session stickiness
                // Only bind if sessionUUID is present
                if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                    String sessionKey = sessionInfo.getSessionUUID();
                    sessionToServerMap.put(sessionKey, server);
                    log.info("Session {} bound to server {}", sessionKey, server.getAddress());
                } else {
                    log.info("No sessionUUID from server {}, session not bound", server.getAddress());
                }
                
                log.info("Successfully connected to server {}", server.getAddress());
                successfulConnections++;
                
                // Use the first successful connection as the primary
                if (primarySessionInfo == null) {
                    primarySessionInfo = sessionInfo;
                }
                
            } catch (StatusRuntimeException e) {
                try {
                    GrpcExceptionHandler.handle(e);
                    lastException = new SQLException("gRPC call failed: " + e.getMessage(), e);
                } catch (SQLException sqlEx) {
                    lastException = sqlEx;
                }
                handleServerFailure(server, e);
                
                log.warn("Connection failed to server {}: {}", 
                        server.getAddress(), lastException.getMessage());
            }
        }
        
        if (primarySessionInfo == null) {
            throw new SQLException("Failed to connect to any server. " +
                    "Last error: " + (lastException != null ? lastException.getMessage() : "No healthy servers available"));
        }
        
        log.info("Connected to {} out of {} servers", successfulConnections, serverEndpoints.size());
        return primarySessionInfo;
    }
    
    /**
     * Selects a server for a new request using round-robin.
     * For non-session requests or new sessions.
     */
    public ServerEndpoint selectServer() throws SQLException {
        ServerEndpoint server = selectHealthyServer();
        if (server == null) {
            throw new SQLException("No healthy servers available");
        }
        return server;
    }
    
    /**
     * Gets the appropriate server based on session affinity.
     * 
     * If sessionKey is null, returns a round-robin selected server.
     * If sessionKey is not null, returns the server bound to that session.
     * 
     * @param sessionKey the session identifier (sessionUUID), or null for round-robin
     * @return the server endpoint to use
     * @throws SQLException if session exists but server is unavailable
     */
    public ServerEndpoint affinityServer(String sessionKey) throws SQLException {
        if (sessionKey == null) {
            // No session identifier, use round-robin
            log.info("No session key, using round-robin selection");
            return selectHealthyServer();
        }
        
        log.info("Looking up server for session: {}", sessionKey);
        ServerEndpoint sessionServer = sessionToServerMap.get(sessionKey);
        
        // Throw exception if session server is unhealthy or not found
        if (sessionServer == null) {
            log.error("Session {} has no associated server. Available sessions: {}", 
                    sessionKey, sessionToServerMap.keySet());
            throw new SQLException("Session " + sessionKey + 
                    " has no associated server. Session may have expired or server may be unavailable.");
        }
        
        log.info("Session {} is bound to server {}", sessionKey, sessionServer.getAddress());
        
        if (!sessionServer.isHealthy()) {
            // Remove from map and throw exception - do NOT fall back to round-robin
            sessionToServerMap.remove(sessionKey);
            throw new SQLException("Session " + sessionKey + 
                    " is bound to server " + sessionServer.getAddress() + 
                    " which is currently unavailable. Cannot continue with this session.");
        }
        
        return sessionServer;
    }
    
    /**
     * Gets the appropriate server for a session-bound request.
     * 
     * @deprecated Use affinityServer(String sessionKey) instead
     */
    @Deprecated
    public ServerEndpoint getServerForSession(SessionInfo sessionInfo) throws SQLException {
        String sessionKey = null;
        if (sessionInfo != null && sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
            sessionKey = sessionInfo.getSessionUUID();
        }
        return affinityServer(sessionKey);
    }
    
    /**
     * Gets the channel and stub for a specific server.
     */
    public ChannelAndStub getChannelAndStub(ServerEndpoint endpoint) {
        ChannelAndStub channelAndStub = channelMap.get(endpoint);
        if (channelAndStub == null) {
            try {
                channelAndStub = createChannelAndStub(endpoint);
            } catch (Exception e) {
                log.error("Failed to create channel for server {}: {}", endpoint.getAddress(), e.getMessage());
                return null;
            }
        }
        return channelAndStub;
    }
    
    private ServerEndpoint selectHealthyServer() {
        List<ServerEndpoint> healthyServers = serverEndpoints.stream()
                .filter(ServerEndpoint::isHealthy)
                .collect(Collectors.toList());
        
        if (healthyServers.isEmpty()) {
            // No healthy servers, try to recover some servers
            attemptServerRecovery();
            healthyServers = serverEndpoints.stream()
                    .filter(ServerEndpoint::isHealthy)
                    .collect(Collectors.toList());
        }
        
        if (healthyServers.isEmpty()) {
            log.error("No healthy servers available");
            return null;
        }
        
        // Round-robin selection among healthy servers
        int index = Math.abs(roundRobinCounter.getAndIncrement()) % healthyServers.size();
        ServerEndpoint selected = healthyServers.get(index);
        
        log.debug("Selected server {} for request (round-robin)", selected.getAddress());
        return selected;
    }
    
    private void handleServerFailure(ServerEndpoint endpoint, Exception exception) {
        // Only mark server unhealthy for connection-level failures
        // Database-level errors (e.g., table not found, syntax errors) should not affect server health
        boolean shouldMarkUnhealthy = isConnectionLevelError(exception);
        
        if (!shouldMarkUnhealthy) {
            log.debug("Server {} encountered database-level error, not marking unhealthy: {}", 
                    endpoint.getAddress(), exception.getMessage());
            return;
        }
        
        endpoint.setHealthy(false);
        endpoint.setLastFailureTime(System.currentTimeMillis());
        
        log.warn("Marked server {} as unhealthy due to connection-level error: {}", 
                endpoint.getAddress(), exception.getMessage());
        
        // Remove the failed channel
        ChannelAndStub channelAndStub = channelMap.remove(endpoint);
        if (channelAndStub != null) {
            try {
                channelAndStub.channel.shutdown();
            } catch (Exception e) {
                log.debug("Error shutting down channel for {}: {}", endpoint.getAddress(), e.getMessage());
            }
        }
    }
    
    /**
     * Determines if an exception represents a connection-level error that should mark a server unhealthy.
     * Connection-level errors include:
     * - UNAVAILABLE: Server not reachable
     * - DEADLINE_EXCEEDED: Request timeout
     * - CANCELLED: Connection cancelled
     * - UNKNOWN: Connection-related unknown errors
     * 
     * Database-level errors (e.g., table not found, syntax errors) do not mark servers unhealthy.
     */
    public boolean isConnectionLevelError(Exception exception) {
        if (exception instanceof io.grpc.StatusRuntimeException) {
            io.grpc.StatusRuntimeException statusException = (io.grpc.StatusRuntimeException) exception;
            io.grpc.Status.Code code = statusException.getStatus().getCode();
            
            // Only these status codes indicate connection-level failures
            return code == io.grpc.Status.Code.UNAVAILABLE ||
                   code == io.grpc.Status.Code.DEADLINE_EXCEEDED ||
                   code == io.grpc.Status.Code.CANCELLED ||
                   (code == io.grpc.Status.Code.UNKNOWN && 
                    statusException.getMessage() != null && 
                    (statusException.getMessage().contains("connection") || 
                     statusException.getMessage().contains("Connection")));
        }
        
        // For non-gRPC exceptions, check for connection-related keywords
        String message = exception.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            return lowerMessage.contains("connection") || 
                   lowerMessage.contains("timeout") ||
                   lowerMessage.contains("unavailable");
        }
        
        return false; // Default to not marking unhealthy for unknown errors
    }
    
    private void attemptServerRecovery() {
        long currentTime = System.currentTimeMillis();
        
        for (ServerEndpoint endpoint : serverEndpoints) {
            if (!endpoint.isHealthy() && 
                (currentTime - endpoint.getLastFailureTime()) > retryDelayMs) {
                
                try {
                    log.debug("Attempting to recover server {}", endpoint.getAddress());
                    createChannelAndStub(endpoint);
                    endpoint.setHealthy(true);
                    endpoint.setLastFailureTime(0);
                    log.info("Successfully recovered server {}", endpoint.getAddress());
                } catch (Exception e) {
                    endpoint.setLastFailureTime(currentTime);
                    log.debug("Server {} recovery attempt failed: {}", 
                            endpoint.getAddress(), e.getMessage());
                }
            }
        }
    }
    
    /**
     * Terminates a session and removes its server association.
     */
    public void terminateSession(SessionInfo sessionInfo) {
        if (sessionInfo != null && sessionInfo.getSessionUUID() != null) {
            sessionToServerMap.remove(sessionInfo.getSessionUUID());
            log.debug("Removed session {} from server association map", sessionInfo.getSessionUUID());
        }
    }
    
    /**
     * Gets the list of configured server endpoints.
     */
    public List<ServerEndpoint> getServerEndpoints() {
        return List.copyOf(serverEndpoints);
    }
    
    /**
     * Gets the configured number of retry attempts.
     */
    public int getRetryAttempts() {
        return retryAttempts;
    }
    
    /**
     * Gets the configured retry delay in milliseconds.
     */
    public long getRetryDelayMs() {
        return retryDelayMs;
    }
    
    /**
     * Shuts down all connections.
     */
    public void shutdown() {
        log.info("Shutting down MultinodeConnectionManager");
        
        for (ChannelAndStub channelAndStub : channelMap.values()) {
            try {
                channelAndStub.channel.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down channel: {}", e.getMessage());
            }
        }
        
        channelMap.clear();
        sessionToServerMap.clear();
    }
    
    /**
     * Inner class to hold channel and stubs together.
     */
    public static class ChannelAndStub {
        public final ManagedChannel channel;
        public final StatementServiceGrpc.StatementServiceBlockingStub blockingStub;
        public final StatementServiceGrpc.StatementServiceStub asyncStub;
        
        public ChannelAndStub(ManagedChannel channel,
                             StatementServiceGrpc.StatementServiceBlockingStub blockingStub,
                             StatementServiceGrpc.StatementServiceStub asyncStub) {
            this.channel = channel;
            this.blockingStub = blockingStub;
            this.asyncStub = asyncStub;
        }
    }
}
