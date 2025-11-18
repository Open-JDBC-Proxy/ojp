package org.openjproxy.grpc.client;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.PropertyEntry;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.GrpcChannelFactory;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.jdbc.DatasourcePropertiesLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private final Map<String, List<ServerEndpoint>> connHashToServersMap; // connHash -> list of servers that received connect()
    private final AtomicInteger roundRobinCounter;
    private final int retryAttempts;
    private final long retryDelayMs;
    private final List<ServerHealthListener> healthListeners; // Phase 2: Listeners for server health changes
    
    // Health check and redistribution support
    private final HealthCheckConfig healthCheckConfig;
    private final AtomicLong lastHealthCheckTimestamp;
    private final HealthCheckValidator healthCheckValidator;
    private final ConnectionTracker connectionTracker;
    private final ConnectionRedistributor connectionRedistributor;
    private final XAConnectionRedistributor xaConnectionRedistributor;
    
    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints) {
        this(serverEndpoints, CommonConstants.DEFAULT_MULTINODE_RETRY_ATTEMPTS, 
             CommonConstants.DEFAULT_MULTINODE_RETRY_DELAY_MS, null);
    }
    
    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints, 
                                    int retryAttempts, long retryDelayMs) {
        this(serverEndpoints, retryAttempts, retryDelayMs, null);
    }
    
    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints, 
                                    int retryAttempts, long retryDelayMs,
                                    HealthCheckConfig healthCheckConfig) {
        this(serverEndpoints, retryAttempts, retryDelayMs, healthCheckConfig, null);
    }
    
    public MultinodeConnectionManager(List<ServerEndpoint> serverEndpoints, 
                                    int retryAttempts, long retryDelayMs,
                                    HealthCheckConfig healthCheckConfig,
                                    ConnectionTracker connectionTracker) {
        if (serverEndpoints == null || serverEndpoints.isEmpty()) {
            throw new IllegalArgumentException("Server endpoints list cannot be null or empty");
        }
        
        this.serverEndpoints = List.copyOf(serverEndpoints);
        this.channelMap = new ConcurrentHashMap<>();
        this.sessionToServerMap = new ConcurrentHashMap<>();
        this.connHashToServersMap = new ConcurrentHashMap<>();
        this.roundRobinCounter = new AtomicInteger(0);
        this.retryAttempts = retryAttempts;
        this.retryDelayMs = retryDelayMs;
        this.healthListeners = new ArrayList<>(); // Phase 2: Initialize listener list
        this.healthCheckConfig = healthCheckConfig != null ? healthCheckConfig : HealthCheckConfig.createDefault();
        this.lastHealthCheckTimestamp = new AtomicLong(0);
        this.connectionTracker = connectionTracker != null ? connectionTracker : new ConnectionTracker();
        this.healthCheckValidator = new HealthCheckValidator(this.healthCheckConfig, this);
        this.connectionRedistributor = new ConnectionRedistributor(this.connectionTracker, this.healthCheckConfig);
        this.xaConnectionRedistributor = new XAConnectionRedistributor(this.connectionTracker, this.healthCheckConfig);
        
        // Initialize channels and stubs for all servers
        initializeConnections();
        
        log.info("MultinodeConnectionManager initialized with {} servers: {}, health check config: {}", 
                serverEndpoints.size(), serverEndpoints, this.healthCheckConfig);
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
     * Establishes a connection by calling connect() on servers.
     * 
     * For XA connections (isXA=true): Uses round-robin to select ONE server to ensure
     * proper load distribution and avoid creating orphaned sessions.
     * 
     * For non-XA connections: Connects to ALL servers to ensure all servers have the 
     * datasource information so that subsequent operations can be routed to any server.
     * 
     * Returns the SessionInfo from the successful connection.
     */
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        boolean isXA = connectionDetails.getIsXA();
        
        log.info("=== connect() called: isXA={} ===", isXA);
        
        // Try to trigger health check (time-based, non-blocking)
        if (healthCheckConfig.isRedistributionEnabled()) {
            tryTriggerHealthCheck();
        }
        
        if (isXA) {
            // For XA connections, use round-robin to select a single server
            return connectToSingleServer(connectionDetails);
        } else {
            // For non-XA connections, connect to all servers (existing behavior)
            return connectToAllServers(connectionDetails);
        }
    }
    
    /**
     * Attempts to trigger a health check if enough time has elapsed since the last check.
     * Uses compareAndSet to ensure only one thread executes the health check.
     * Non-blocking - if another thread is already doing a health check, this returns immediately.
     */
    private void tryTriggerHealthCheck() {
        long now = System.currentTimeMillis();
        long lastCheck = lastHealthCheckTimestamp.get();
        long elapsed = now - lastCheck;
        
        // Only check if interval has passed
        if (elapsed >= healthCheckConfig.getHealthCheckIntervalMs()) {
            // Atomic update - only one thread succeeds
            if (lastHealthCheckTimestamp.compareAndSet(lastCheck, now)) {
                try {
                    performHealthCheck();
                } catch (Exception e) {
                    log.warn("Health check failed: {}", e.getMessage());
                    // Don't fail the connection attempt - health check is best effort
                }
            }
        }
    }
    
    /**
     * Performs health check on unhealthy servers.
     * If any servers have recovered, notifies listeners and triggers redistribution.
     */
    private void performHealthCheck() {
        log.debug("Performing health check on unhealthy servers");
        
        List<ServerEndpoint> unhealthyServers = serverEndpoints.stream()
                .filter(endpoint -> !endpoint.isHealthy())
                .collect(Collectors.toList());
        
        if (unhealthyServers.isEmpty()) {
            log.debug("No unhealthy servers to check");
            return;
        }
        
        log.info("Checking {} unhealthy server(s)", unhealthyServers.size());
        
        List<ServerEndpoint> recoveredServers = new ArrayList<>();
        
        // Check each unhealthy server
        for (ServerEndpoint endpoint : unhealthyServers) {
            long timeSinceFailure = System.currentTimeMillis() - endpoint.getLastFailureTime();
            
            // Only check if enough time has passed since last failure
            if (timeSinceFailure >= healthCheckConfig.getHealthCheckThresholdMs()) {
                if (validateServer(endpoint)) {
                    log.info("Server {} has recovered", endpoint.getAddress());
                    endpoint.markHealthy();
                    recoveredServers.add(endpoint);
                    notifyServerRecovered(endpoint);
                } else {
                    // Still unhealthy, update timestamp
                    endpoint.setLastFailureTime(System.currentTimeMillis());
                    log.debug("Server {} still unhealthy", endpoint.getAddress());
                }
            }
        }
        
        // If any servers recovered, trigger connection redistribution
        if (!recoveredServers.isEmpty() && healthCheckConfig.isRedistributionEnabled()) {
            log.info("Triggering connection redistribution for {} recovered server(s)", 
                    recoveredServers.size());
            
            List<ServerEndpoint> allHealthyServers = serverEndpoints.stream()
                    .filter(ServerEndpoint::isHealthy)
                    .collect(Collectors.toList());
            
            try {
                connectionRedistributor.rebalance(recoveredServers, allHealthyServers);
            } catch (Exception e) {
                log.error("Failed to redistribute connections after server recovery: {}", 
                        e.getMessage(), e);
            }
        }
    }
    
    /**
     * Validates if a server is healthy by attempting a simple connection.
     * Returns true if server is responsive, false otherwise.
     */
    private boolean validateServer(ServerEndpoint endpoint) {
        return healthCheckValidator.validateServer(endpoint);
    }
    
    /**
     * Provides public access to create a channel and stub for an endpoint.
     * Used by HealthCheckValidator for server validation.
     */
    public ChannelAndStub createChannelAndStubForEndpoint(ServerEndpoint endpoint) {
        return createChannelAndStub(endpoint);
    }
    
    /**
     * Closes a session by its UUID.
     * Used by HealthCheckValidator to clean up test connections.
     */
    public void closeSession(String sessionUUID) throws SQLException {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            return;
        }
        
        // Remove from session map
        ServerEndpoint server = sessionToServerMap.remove(sessionUUID);
        
        if (server != null) {
            log.debug("Closed session {}, was bound to {}", sessionUUID, server.getAddress());
        }
        
        // TODO: Actually call close on the server - for now just remove from tracking
    }
    
    /**
     * Gets the connection tracker for integration with XA data sources.
     */
    public ConnectionTracker getConnectionTracker() {
        return connectionTracker;
    }
    
    /**
     * Connects to a single server using round-robin selection.
     * Used for XA connections to ensure proper load distribution.
     * 
     * Quick Fix for XA Mode: After selecting a server, checks if the selected server's
     * datasource differs from the properties in ConnectionDetails. If different, reloads
     * properties for the selected server's datasource and rebuilds ConnectionDetails.
     * This ensures each server receives properties for ITS OWN datasource.
     */
    private SessionInfo connectToSingleServer(ConnectionDetails connectionDetails) throws SQLException {
        ServerEndpoint selectedServer = selectHealthyServer();
        
        if (selectedServer == null) {
            throw new SQLException("No healthy servers available for XA connection");
        }
        
        log.info("===XA connection: selected server {} via round-robin (counter={}) ===", 
                selectedServer.getAddress(), roundRobinCounter.get() - 1);
        
        // Quick Fix: Check if selected server's datasource differs from ConnectionDetails datasource
        String selectedServerDataSource = selectedServer.getDataSourceName();
        String connectionDetailsDataSource = extractDataSourceNameFromConnectionDetails(connectionDetails);
        
        log.info("Selected server datasource: '{}', ConnectionDetails datasource: '{}'", 
                selectedServerDataSource, connectionDetailsDataSource);
        
        // If datasources differ, reload properties for the selected server's datasource
        if (!selectedServerDataSource.equals(connectionDetailsDataSource)) {
            log.info("Datasource mismatch detected! Reloading properties for selected server's datasource: '{}'", 
                    selectedServerDataSource);
            connectionDetails = rebuildConnectionDetailsWithDataSource(connectionDetails, selectedServerDataSource);
        } else {
            log.debug("Datasource match - using existing properties for datasource: '{}'", selectedServerDataSource);
        }
        
        try {
            ChannelAndStub channelAndStub = channelMap.get(selectedServer);
            if (channelAndStub == null) {
                channelAndStub = createChannelAndStub(selectedServer);
            }
            
            log.info("Connecting to server {} (XA) with datasource '{}'", 
                    selectedServer.getAddress(), selectedServerDataSource);
            SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);
            
            // Mark server as healthy
            selectedServer.setHealthy(true);
            selectedServer.setLastFailureTime(0);
            
            // Bind session to this server
            if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                String targetServer = sessionInfo.getTargetServer();
                if (targetServer != null && !targetServer.isEmpty()) {
                    bindSession(sessionInfo.getSessionUUID(), targetServer);
                    log.info("=== XA session {} bound to target server {} (from response) ===", 
                            sessionInfo.getSessionUUID(), targetServer);
                } else {
                    String serverAddress = selectedServer.getHost() + ":" + selectedServer.getPort();
                    sessionToServerMap.put(sessionInfo.getSessionUUID(), selectedServer);
                    log.info("=== XA session {} bound to server {} (fallback) - Map size now: {} ===", 
                            sessionInfo.getSessionUUID(), serverAddress, sessionToServerMap.size());
                }
            }
            
            // Track the server for this connection hash
            if (sessionInfo.getConnHash() != null && !sessionInfo.getConnHash().isEmpty()) {
                List<ServerEndpoint> connectedServers = new ArrayList<>();
                connectedServers.add(selectedServer);
                connHashToServersMap.put(sessionInfo.getConnHash(), connectedServers);
                log.info("Tracked 1 server for XA connection hash {}", sessionInfo.getConnHash());
            }
            
            log.info("Successfully connected to server {} (XA) with datasource '{}'", 
                    selectedServer.getAddress(), selectedServerDataSource);
            return sessionInfo;
            
        } catch (StatusRuntimeException e) {
            SQLException sqlEx;
            try {
                throw GrpcExceptionHandler.handle(e);
            } catch (SQLException ex) {
                sqlEx = ex;
            }
            handleServerFailure(selectedServer, e);
            
            log.error("XA connection failed to server {}: {}", 
                    selectedServer.getAddress(), sqlEx.getMessage());
            throw sqlEx;
        }
    }
    
    /**
     * Connects to all servers to ensure datasource information is available on all nodes.
     * Used for non-XA connections.
     */
    private SessionInfo connectToAllServers(ConnectionDetails connectionDetails) throws SQLException {
        SessionInfo primarySessionInfo = null;
        SQLException lastException = null;
        int successfulConnections = 0;
        List<ServerEndpoint> connectedServers = new ArrayList<>();
        
        // Try to connect to all servers
        for (ServerEndpoint server : serverEndpoints) {
            if (!server.isHealthy()) {
                // Attempt to recover unhealthy servers if enough time has passed
                long currentTime = System.currentTimeMillis();
                if ((currentTime - server.getLastFailureTime()) > retryDelayMs) {
                    log.info("Attempting to recover unhealthy server {} during connect()", server.getAddress());
                    try {
                        createChannelAndStub(server);
                        server.setHealthy(true);
                        server.setLastFailureTime(0);
                        log.info("Successfully recovered server {} during connect()", server.getAddress());
                        // Continue to attempt connection below
                    } catch (Exception e) {
                        server.setLastFailureTime(currentTime);
                        log.debug("Server {} recovery attempt failed during connect(): {}", 
                                server.getAddress(), e.getMessage());
                        continue;  // Skip this server
                    }
                } else {
                    log.debug("Skipping unhealthy server: {} (waiting for retry delay)", server.getAddress());
                    continue;
                }
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
                
                // NEW: Use targetServer-based binding if available
                // Bind session using targetServer from response if both sessionUUID and targetServer are present
                if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                    String targetServer = sessionInfo.getTargetServer();
                    if (targetServer != null && !targetServer.isEmpty()) {
                        // Use the server-returned targetServer as authoritative for binding
                        bindSession(sessionInfo.getSessionUUID(), targetServer);
                        log.info("Session {} bound to target server {} (from response)", 
                                sessionInfo.getSessionUUID(), targetServer);
                    } else {
                        // Fallback: bind using current server endpoint if targetServer not provided
                        String serverAddress = server.getHost() + ":" + server.getPort();
                        sessionToServerMap.put(sessionInfo.getSessionUUID(), server);
                        log.info("Session {} bound to server {} (fallback, no targetServer in response)", 
                                sessionInfo.getSessionUUID(), serverAddress);
                    }
                } else {
                    log.info("No sessionUUID from server {}, session not bound", server.getAddress());
                }
                
                log.info("Successfully connected to server {}", server.getAddress());
                successfulConnections++;
                
                // Track that this server received a connect() call
                connectedServers.add(server);
                
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
        
        // Track which servers received connect() for this connection hash
        // This is used during terminateSession() to ensure all servers are cleaned up
        if (primarySessionInfo.getConnHash() != null && !primarySessionInfo.getConnHash().isEmpty()) {
            connHashToServersMap.put(primarySessionInfo.getConnHash(), new ArrayList<>(connectedServers));
            log.info("Tracked {} servers for connection hash {}", connectedServers.size(), primarySessionInfo.getConnHash());
        }
        
        log.info("Connected to {} out of {} servers", successfulConnections, serverEndpoints.size());
        return primarySessionInfo;
    }
    
    
    /**
     * Gets the appropriate server based on session affinity.
     * 
     * If sessionKey is null, returns a round-robin selected server.
     * If sessionKey is not null, returns the server bound to that session.
     * 
     * @param sessionKey the session identifier (sessionUUID), or null for round-robin
     * @return the server endpoint to use
     * @throws SQLException if session exists but server is unavailable or not bound
     */
    public ServerEndpoint affinityServer(String sessionKey) throws SQLException {
        if (sessionKey == null || sessionKey.isEmpty()) {
            // No session identifier, use round-robin
            log.info("No session key, using round-robin selection");
            return selectHealthyServer();
        }
        
        log.info("Looking up server for session: {}", sessionKey);
        ServerEndpoint sessionServer = sessionToServerMap.get(sessionKey);
        
        log.info("=== affinityServer lookup: sessionKey={}, found server={}, total sessions in map={} ===", 
                sessionKey, 
                sessionServer != null ? sessionServer.getAddress() : "NOT_FOUND",
                sessionToServerMap.size());
        
        // Log session distribution for debugging
        if (sessionServer != null && log.isDebugEnabled()) {
            Map<String, Long> serverDistribution = sessionToServerMap.values().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            ServerEndpoint::getAddress,
                            java.util.stream.Collectors.counting()));
            log.debug("Session distribution across servers: {}", serverDistribution);
        }
        
        // Session must be bound - throw exception if not found
        if (sessionServer == null) {
            log.error("Session {} has no associated server. Available sessions: {}. This indicates the session binding was lost.", 
                    sessionKey, sessionToServerMap.keySet());
            throw new SQLException("Session " + sessionKey + 
                    " has no associated server. Session may have expired or server may be unavailable. " +
                    "Available bound sessions: " + sessionToServerMap.keySet());
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
        
        // Only attempt recovery if NO servers are healthy (last resort)
        // Time-based health checks via tryTriggerHealthCheck() handle recovery for normal cases
        if (healthyServers.isEmpty()) {
            log.warn("No healthy servers available, attempting recovery as last resort");
            attemptServerRecovery();
            
            // Re-check healthy servers after recovery attempt
            healthyServers = serverEndpoints.stream()
                    .filter(ServerEndpoint::isHealthy)
                    .collect(Collectors.toList());
        }
        
        if (healthyServers.isEmpty()) {
            log.error("No healthy servers available after recovery attempt");
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
        
        // Phase 2: Notify listeners that server became unhealthy
        notifyServerUnhealthy(endpoint, exception);
        
        // Remove the failed channel from the map, but don't shut it down immediately
        // The channel will be replaced during recovery, and the old one will be garbage collected
        // This prevents "Channel shutdown invoked" errors for in-flight operations
        ChannelAndStub channelAndStub = channelMap.remove(endpoint);
        if (channelAndStub != null) {
            log.debug("Removed channel for {} from map (will be replaced during recovery)", 
                    endpoint.getAddress());
            // Note: We intentionally don't call channel.shutdown() here to avoid disrupting
            // in-flight operations. The channel will be garbage collected when no longer referenced.
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
                    
                    // Phase 2: Notify listeners that server recovered
                    notifyServerRecovered(endpoint);
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
     * Also cleans up the connection hash mapping used to track which servers received connect() calls.
     */
    public void terminateSession(SessionInfo sessionInfo) {
        if (sessionInfo != null) {
            // Remove session binding if sessionUUID is present
            if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                unbindSession(sessionInfo.getSessionUUID());
                log.debug("Removed session {} from server association map", sessionInfo.getSessionUUID());
            }
            
            // Remove connection hash mapping if present
            if (sessionInfo.getConnHash() != null && !sessionInfo.getConnHash().isEmpty()) {
                connHashToServersMap.remove(sessionInfo.getConnHash());
                log.debug("Removed connection hash {} from server tracking map", sessionInfo.getConnHash());
            }
        }
    }
    
    /**
     * Binds a session UUID to a target server endpoint (host:port format).
     * This is used for session stickiness - subsequent operations with this sessionUUID
     * will be routed to the bound server.
     * 
     * @param sessionUUID The session identifier
     * @param targetServer The target server in host:port format
     */
    public void bindSession(String sessionUUID, String targetServer) {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            log.warn("Attempted to bind session with null or empty sessionUUID");
            return;
        }
        
        if (targetServer == null || targetServer.isEmpty()) {
            log.warn("Attempted to bind session {} with null or empty targetServer", sessionUUID);
            return;
        }
        
        // Find the matching ServerEndpoint for this targetServer string
        ServerEndpoint matchingEndpoint = null;
        for (ServerEndpoint endpoint : serverEndpoints) {
            String endpointAddress = endpoint.getHost() + ":" + endpoint.getPort();
            if (endpointAddress.equals(targetServer)) {
                matchingEndpoint = endpoint;
                break;
            }
        }
        
        if (matchingEndpoint != null) {
            ServerEndpoint previous = sessionToServerMap.put(sessionUUID, matchingEndpoint);
            if (previous == null) {
                log.info("Bound session {} to target server {}", sessionUUID, targetServer);
            } else {
                log.info("Rebound session {} from {} to target server {}", 
                        sessionUUID, previous.getAddress(), targetServer);
            }
        } else {
            log.warn("Could not find matching endpoint for targetServer: {}. Available endpoints: {}", 
                    targetServer, 
                    serverEndpoints.stream()
                            .map(e -> e.getHost() + ":" + e.getPort())
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
    }
    
    /**
     * Gets the bound server for a given session UUID.
     * 
     * @param sessionUUID The session identifier
     * @return The server endpoint string (host:port) if bound, null otherwise
     */
    public String getBoundTargetServer(String sessionUUID) {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            return null;
        }
        
        ServerEndpoint endpoint = sessionToServerMap.get(sessionUUID);
        if (endpoint != null) {
            return endpoint.getHost() + ":" + endpoint.getPort();
        }
        
        return null;
    }
    
    /**
     * Removes the session binding for a given session UUID.
     * 
     * @param sessionUUID The session identifier
     */
    public void unbindSession(String sessionUUID) {
        if (sessionUUID != null && !sessionUUID.isEmpty()) {
            ServerEndpoint removed = sessionToServerMap.remove(sessionUUID);
            if (removed != null) {
                log.debug("Unbound session {} from server {}", sessionUUID, removed.getAddress());
            }
        }
    }
    
    /**
     * Gets the list of servers that received connect() for a given connection hash.
     * This is used during terminateSession() to ensure all servers that received connect()
     * also receive terminateSession() so they can clean up their resources properly.
     * 
     * @param connHash The connection hash
     * @return List of servers that received connect(), or null if not tracked
     */
    public List<ServerEndpoint> getServersForConnHash(String connHash) {
        if (connHash == null || connHash.isEmpty()) {
            return null;
        }
        List<ServerEndpoint> servers = connHashToServersMap.get(connHash);
        return servers != null ? new ArrayList<>(servers) : null;
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
     * Generates the cluster health status string.
     * Format: "host1:port1(UP);host2:port2(DOWN);host3:port3(UP)"
     * 
     * @return Cluster health status string
     */
    public String generateClusterHealth() {
        return serverEndpoints.stream()
                .map(endpoint -> endpoint.getAddress() + "(" + (endpoint.isHealthy() ? "UP" : "DOWN") + ")")
                .collect(Collectors.joining(";"));
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
     * Phase 2: Adds a server health listener.
     * 
     * @param listener The listener to add
     */
    public void addHealthListener(ServerHealthListener listener) {
        if (listener != null && !healthListeners.contains(listener)) {
            healthListeners.add(listener);
            log.debug("Added server health listener: {}", listener.getClass().getSimpleName());
        }
    }
    
    /**
     * Phase 2: Removes a server health listener.
     * 
     * @param listener The listener to remove
     */
    public void removeHealthListener(ServerHealthListener listener) {
        if (listener != null) {
            healthListeners.remove(listener);
            log.debug("Removed server health listener: {}", listener.getClass().getSimpleName());
        }
    }
    
    /**
     * Phase 2: Notifies all listeners that a server became unhealthy.
     * 
     * @param endpoint The server endpoint that became unhealthy
     * @param exception The exception that caused the failure
     */
    private void notifyServerUnhealthy(ServerEndpoint endpoint, Exception exception) {
        for (ServerHealthListener listener : healthListeners) {
            try {
                listener.onServerUnhealthy(endpoint, exception);
            } catch (Exception e) {
                log.error("Error notifying listener of unhealthy server {}: {}", 
                        endpoint.getAddress(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * Phase 2: Notifies all listeners that a server recovered.
     * After notifying listeners, triggers XA connection redistribution if enabled.
     * 
     * @param endpoint The server endpoint that recovered
     */
    private void notifyServerRecovered(ServerEndpoint endpoint) {
        // First notify all registered health listeners
        for (ServerHealthListener listener : healthListeners) {
            try {
                listener.onServerRecovered(endpoint);
            } catch (Exception e) {
                log.error("Error notifying listener of recovered server {}: {}", 
                        endpoint.getAddress(), e.getMessage(), e);
            }
        }
        
        // Then trigger XA connection redistribution if enabled
        if (healthCheckConfig.isRedistributionEnabled()) {
            try {
                log.debug("Triggering XA connection redistribution for recovered server: {}", 
                        endpoint.getAddress());
                xaConnectionRedistributor.redistribute(endpoint);
            } catch (Exception e) {
                log.error("Error during XA connection redistribution for {}: {}", 
                        endpoint.getAddress(), e.getMessage(), e);
                // Don't fail server recovery if redistribution fails
            }
        }
    }
    
    /**
     * Extracts the datasource name from ConnectionDetails properties.
     * Returns the value of "ojp.datasource.name" property, or "default" if not found.
     * 
     * @param connectionDetails The ConnectionDetails to extract from
     * @return The datasource name, or "default" if not specified
     */
    private String extractDataSourceNameFromConnectionDetails(ConnectionDetails connectionDetails) {
        if (connectionDetails == null || connectionDetails.getPropertiesList().isEmpty()) {
            return "default";
        }
        
        for (PropertyEntry prop : connectionDetails.getPropertiesList()) {
            if (CommonConstants.DATASOURCE_NAME_PROPERTY.equals(prop.getKey())) {
                return prop.getStringValue();
            }
        }
        
        return "default";
    }
    
    /**
     * Rebuilds ConnectionDetails with properties for a specific datasource.
     * Used in XA mode to ensure the selected server receives properties for its datasource.
     * 
     * @param originalDetails The original ConnectionDetails
     * @param dataSourceName The datasource name to load properties for
     * @return New ConnectionDetails with updated properties
     */
    private ConnectionDetails rebuildConnectionDetailsWithDataSource(
            ConnectionDetails originalDetails, String dataSourceName) {
        
        log.info("Reloading properties for datasource: {}", dataSourceName);
        
        // Load properties for the specified datasource
        Properties dsProperties = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource(dataSourceName);
        
        // Rebuild ConnectionDetails with new properties
        ConnectionDetails.Builder builder = ConnectionDetails.newBuilder(originalDetails)
                .clearProperties();
        
        if (dsProperties != null && !dsProperties.isEmpty()) {
            // Convert Properties to Map<String, Object>
            Map<String, Object> propertiesMap = new HashMap<>();
            for (String key : dsProperties.stringPropertyNames()) {
                propertiesMap.put(key, dsProperties.getProperty(key));
            }
            builder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
            log.info("Rebuilt ConnectionDetails with {} properties for datasource: {}", 
                    propertiesMap.size(), dataSourceName);
        } else {
            log.info("No properties found for datasource: {}, using defaults", dataSourceName);
        }
        
        return builder.build();
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
