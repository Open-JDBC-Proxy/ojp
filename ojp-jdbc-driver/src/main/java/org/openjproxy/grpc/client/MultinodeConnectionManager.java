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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Manages multinode connections to OJP servers with load-aware routing,
 * session stickiness, and failover support.
 * 
 * Server Selection Strategies:
 * - Load-aware (default): Selects the healthy server with the fewest active connections
 * - Round-robin (legacy): Cycles through healthy servers in order
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
    private final ConnectionTracker connectionTracker; // Legacy - kept for backward compatibility
    private final SessionTracker sessionTracker; // New unified tracker
    private final ConnectionRedistributor connectionRedistributor;
    private XAConnectionRedistributor xaConnectionRedistributor;
    private final ScheduledExecutorService healthCheckScheduler;
    
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
        this.healthListeners = new ArrayList<>();
        this.healthCheckConfig = healthCheckConfig != null ? healthCheckConfig : HealthCheckConfig.createDefault();
        this.lastHealthCheckTimestamp = new AtomicLong(0);
        this.connectionTracker = connectionTracker != null ? connectionTracker : new ConnectionTracker(); // Legacy
        this.sessionTracker = new SessionTracker();
        this.healthCheckValidator = new HealthCheckValidator(this.healthCheckConfig, this);
        this.connectionRedistributor = new ConnectionRedistributor(this.connectionTracker, this.healthCheckConfig);
        
        // Initialize channels and stubs for all servers
        initializeConnections();
        
        // Start periodic health checker
        if (this.healthCheckConfig.isRedistributionEnabled()) {
            this.healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ojp-health-checker");
                t.setDaemon(true); // Don't prevent JVM shutdown
                return t;
            });
            
            long intervalMs = this.healthCheckConfig.getHealthCheckIntervalMs();
            this.healthCheckScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        performHealthCheck();
                    } catch (Exception e) {
                        log.warn("Periodic health check failed: {}", e.getMessage());
                    }
                },
                intervalMs, // Initial delay
                intervalMs, // Period
                TimeUnit.MILLISECONDS
            );
            log.info("Started periodic health checker with interval {}ms", intervalMs);
        } else {
            this.healthCheckScheduler = null;
            log.info("Periodic health checker disabled (redistribution not enabled)");
        }
        
        log.info("MultinodeConnectionManager initialized with {} servers: {}, health check config: {}", 
                serverEndpoints.size(), serverEndpoints, this.healthCheckConfig);
    }
    
    private void initializeConnections() {
        for (ServerEndpoint endpoint : serverEndpoints) {
            try {
                createChannelAndStub(endpoint);
                endpoint.markHealthy();
                log.debug("Successfully initialized connection to {}", endpoint.getAddress());
            } catch (Exception e) {
                log.warn("Failed to initialize connection to {}: {}", endpoint.getAddress(), e.getMessage());
                endpoint.markUnhealthy();
                endpoint.setLastFailureTime(System.currentTimeMillis());
            }
        }
    }
    
    private ChannelAndStub createChannelAndStub(ServerEndpoint endpoint) {
        // Synchronize on the endpoint to prevent concurrent channel creation for the same endpoint
        synchronized (endpoint) {
            String target = DNS_PREFIX + endpoint.getHost() + ":" + endpoint.getPort();
            ManagedChannel channel = GrpcChannelFactory.createChannel(target);
            
            StatementServiceGrpc.StatementServiceBlockingStub blockingStub = 
                    StatementServiceGrpc.newBlockingStub(channel);
            StatementServiceGrpc.StatementServiceStub asyncStub = 
                    StatementServiceGrpc.newStub(channel);
            
            ChannelAndStub newChannelAndStub = new ChannelAndStub(channel, blockingStub, asyncStub);
            
            // Atomically replace old channel with new one and shutdown the old one if it exists
            // This prevents race conditions by doing check-and-replace atomically
            ChannelAndStub oldChannelAndStub = channelMap.put(endpoint, newChannelAndStub);
            if (oldChannelAndStub != null) {
                log.debug("Shutting down replaced channel for {}", endpoint.getAddress());
                try {
                    oldChannelAndStub.channel.shutdown();
                } catch (Exception e) {
                    log.warn("Error shutting down replaced channel for {}: {}", endpoint.getAddress(), e.getMessage());
                }
            }
            
            return newChannelAndStub;
        }
    }
    
    /**
     * Establishes a connection by calling connect() on servers.
     * 
     * UNIFIED MODE (always enabled):
     * - Both XA and non-XA connections connect to ALL servers
     * - Ensures all servers have datasource configuration for improved failover
     * - Sessions tracked via SessionTracker for accurate load balancing
     * - Each session bound directly to the ServerEndpoint it was created on
     * 
     * Returns the SessionInfo from the successful connection.
     */
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        boolean isXA = connectionDetails.getIsXA();
        
        log.info("=== connect() called: isXA={} (unified mode always enabled) ===", isXA);
        
        // Try to trigger health check (time-based, non-blocking)
        if (healthCheckConfig.isRedistributionEnabled()) {
            tryTriggerHealthCheck();
        }
        
        // UNIFIED MODE: Both XA and non-XA connect to all servers
        log.debug("Connecting to all servers for {} connection", isXA ? "XA" : "non-XA");
        return connectToAllServers(connectionDetails);
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
     * Performs health check on all servers.
     * - For XA mode: Proactively checks healthy servers and invalidates sessions when they fail
     * - For all modes: Checks unhealthy servers to see if they've recovered
     */
    private void performHealthCheck() {
        log.debug("Performing health check on servers");
        
        // XA Mode: Proactively check healthy servers to detect failures early
        // This ensures sessions are invalidated even if no active operations are hitting the server
        if (xaConnectionRedistributor != null) {
            List<ServerEndpoint> healthyServers = serverEndpoints.stream()
                    .filter(ServerEndpoint::isHealthy)
                    .collect(Collectors.toList());
            
            for (ServerEndpoint endpoint : healthyServers) {
                if (!validateServer(endpoint)) {
                    log.info("XA Health check: Server {} has become unhealthy", endpoint.getAddress());
                    
                    // Mark server unhealthy
                    endpoint.setHealthy(false);
                    endpoint.setLastFailureTime(System.currentTimeMillis());
                    
                    // XA Mode: Immediately invalidate sessions and connections for the failed server
                    invalidateSessionsAndConnectionsForFailedServer(endpoint);
                    
                    // Notify listeners
                    notifyServerUnhealthy(endpoint, new Exception("Health check failed"));
                }
            }
        }
        
        // Check unhealthy servers to see if they've recovered
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
        
        // For non-XA mode: trigger connection redistribution when servers recover
        // XA mode handles redistribution differently (through invalidation), so skip it
        if (!recoveredServers.isEmpty() && xaConnectionRedistributor == null && healthCheckConfig.isRedistributionEnabled()) {
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
     * Invalidates a specified number of connections for a server.
     * 
     * @param server The server whose connections should be invalidated
     * @param connections The list of connections for this server
     * @param count The number of connections to invalidate
     * @return The actual number of connections invalidated
     */
    private int invalidateConnectionsForServer(ServerEndpoint server, List<java.sql.Connection> connections, int count) {
        int invalidated = 0;
        int toInvalidate = Math.min(count, connections.size());
        
        for (int i = 0; i < toInvalidate; i++) {
            java.sql.Connection conn = connections.get(i);
            if (conn instanceof org.openjproxy.jdbc.Connection) {
                org.openjproxy.jdbc.Connection ojpConn = (org.openjproxy.jdbc.Connection) conn;
                ojpConn.markForceInvalid();
                try {
                    conn.close();
                    invalidated++;
                    log.debug("Invalidated and closed connection {} for server {} during rebalancing", 
                            System.identityHashCode(conn), server.getAddress());
                } catch (Exception e) {
                    log.warn("Failed to close connection {} for server {} during rebalancing: {}", 
                            System.identityHashCode(conn), server.getAddress(), e.getMessage());
                }
            }
        }
        
        log.info("Invalidated {} of {} connections for server {} during rebalancing", 
                invalidated, toInvalidate, server.getAddress());
        return invalidated;
    }
    
    /**
     * Invalidates all XA sessions and connections for a server that has become unhealthy.
     * 
     * In XA mode, when a server fails, we immediately:
     * 1. Clear client-side session bindings from sessionToServerMap
     * 2. Unregister sessions from SessionTracker
     * 3. Mark Connection objects as invalid (forceInvalid) so pools discard them
     * 4. Close connections to force pool replacement
     * 
     * This prevents attempts to use stale sessions on the failed server. When the server
     * recovers, new connections will be created with fresh sessions.
     * 
     * @param endpoint The server endpoint that has failed
     */
    private void invalidateSessionsAndConnectionsForFailedServer(ServerEndpoint endpoint) {
        log.info("Invalidating all XA sessions and connections for failed server {}", endpoint.getAddress());
        
        // Step 1: Remove all session bindings for this server
        List<String> sessionsToInvalidate = sessionToServerMap.entrySet().stream()
                .filter(entry -> entry.getValue().equals(endpoint))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        for (String sessionUUID : sessionsToInvalidate) {
            sessionToServerMap.remove(sessionUUID);
            // Unregister from SessionTracker
            sessionTracker.unregisterSession(sessionUUID);
            log.debug("Removed session binding {} for failed server {}", sessionUUID, endpoint.getAddress());
        }
        
        // Step 2: Mark all connections for this server as invalid and close them
        Map<ServerEndpoint, List<java.sql.Connection>> distribution = connectionTracker.getDistribution();
        List<java.sql.Connection> connectionsToInvalidate = distribution.get(endpoint);
        
        if (connectionsToInvalidate != null && !connectionsToInvalidate.isEmpty()) {
            int invalidatedCount = 0;
            for (java.sql.Connection conn : connectionsToInvalidate) {
                if (conn instanceof org.openjproxy.jdbc.Connection) {
                    org.openjproxy.jdbc.Connection ojpConn = (org.openjproxy.jdbc.Connection) conn;
                    ojpConn.markForceInvalid();
                    try {
                        conn.close();
                        invalidatedCount++;
                        log.debug("Invalidated and closed connection {} for failed server {}", 
                                System.identityHashCode(conn), endpoint.getAddress());
                    } catch (Exception e) {
                        log.warn("Failed to close connection {} for failed server {}: {}", 
                                System.identityHashCode(conn), endpoint.getAddress(), e.getMessage());
                    }
                }
            }
            
            log.info("Invalidated {} session(s) and {} connection(s) for failed server {}",
                    sessionsToInvalidate.size(), invalidatedCount, endpoint.getAddress());
        } else {
            log.info("Invalidated {} session(s) for failed server {} (no connections tracked)",
                    sessionsToInvalidate.size(), endpoint.getAddress());
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
     * @deprecated Use getSessionTracker() for unified tracking
     */
    @Deprecated
    public ConnectionTracker getConnectionTracker() {
        return connectionTracker;
    }
    
    /**
     * Gets the session tracker for unified session-based load balancing.
     * Replaces ConnectionTracker with lighter-weight session tracking.
     */
    public SessionTracker getSessionTracker() {
        return sessionTracker;
    }
    
    /**
     * Gets the health check configuration.
     */
    public HealthCheckConfig getHealthCheckConfig() {
        return healthCheckConfig;
    }
    
    /**
     * Connects to all servers to ensure datasource information is available on all nodes.
     * Used for both XA and non-XA connections in unified mode.
     * 
     * Each session is bound directly to the ServerEndpoint object that was just connected to.
     * Only sessions with UUIDs (actual server sessions) are bound.
     */
    private SessionInfo connectToAllServers(ConnectionDetails connectionDetails) throws SQLException {
        SessionInfo primarySessionInfo = null;
        SQLException lastException = null;
        int successfulConnections = 0;
        List<ServerEndpoint> connectedServers = new ArrayList<>();
        List<SessionInfo> allSessionInfos = new ArrayList<>(); // Track all successful sessions
        boolean isXA = connectionDetails.getIsXA();
        
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
                
                log.info("Connecting to server {} ({} connection)", server.getAddress(), isXA ? "XA" : "non-XA");
                SessionInfo sessionInfo = channelAndStub.blockingStub.connect(connectionDetails);
                
                // Mark server as healthy
                server.setHealthy(true);
                server.setLastFailureTime(0);
                
                // Bind session directly to the ServerEndpoint we just connected to
                // Only bind sessions with UUIDs (actual server sessions, not lazy allocation)
                if (sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                    // Direct binding to ServerEndpoint object - no string matching needed
                    sessionToServerMap.put(sessionInfo.getSessionUUID(), server);
                    sessionTracker.registerSession(sessionInfo.getSessionUUID(), server);
                    
                    String connectedServerAddress = server.getHost() + ":" + server.getPort();
                    log.info("Session {} bound to server {} (direct binding to ServerEndpoint)", 
                            sessionInfo.getSessionUUID(), connectedServerAddress);
                } else {
                    // No UUID = lazy allocation = no session on server yet = don't bind
                    log.debug("No sessionUUID from server {} - skipping binding (lazy allocation)", 
                            server.getAddress());
                }
                
                log.info("Successfully connected to server {} ({} connection)", 
                        server.getAddress(), isXA ? "XA" : "non-XA");
                successfulConnections++;
                
                // Track that this server received a connect() call
                connectedServers.add(server);
                
                // Track all successful session infos
                allSessionInfos.add(sessionInfo);
                
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
        
        // CRITICAL FIX: Check if primary session was invalidated by health checker during connect
        // This can happen if health checker runs between binding the session and returning from connect()
        String primarySessionUUID = primarySessionInfo.getSessionUUID();
        if (primarySessionUUID != null && !primarySessionUUID.isEmpty()) {
            if (!sessionToServerMap.containsKey(primarySessionUUID)) {
                log.warn("[RACE-FIX] Primary session {} was invalidated during connect(), searching for valid session", 
                        primarySessionUUID);
                
                // Find a valid session from the other successful connections
                SessionInfo validSession = null;
                for (SessionInfo si : allSessionInfos) {
                    String uuid = si.getSessionUUID();
                    if (uuid != null && !uuid.isEmpty() && sessionToServerMap.containsKey(uuid)) {
                        validSession = si;
                        log.info("[RACE-FIX] Found valid session {} to use instead", uuid);
                        break;
                    }
                }
                
                if (validSession != null) {
                    primarySessionInfo = validSession;
                } else {
                    // All sessions were invalidated - this is a rare case but possible
                    throw new SQLException("All sessions were invalidated during connection establishment (servers failed during connect)");
                }
            }
        }
        
        // Track which servers received connect() for this connection hash
        // This is used during terminateSession() to ensure all servers are cleaned up
        if (primarySessionInfo.getConnHash() != null && !primarySessionInfo.getConnHash().isEmpty()) {
            connHashToServersMap.put(primarySessionInfo.getConnHash(), new ArrayList<>(connectedServers));
            log.info("Tracked {} servers for connection hash {}", connectedServers.size(), primarySessionInfo.getConnHash());
        }
        
        log.info("Connected to {} out of {} servers ({} connection)", 
                successfulConnections, serverEndpoints.size(), isXA ? "XA" : "non-XA");
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
            log.warn("DIAGNOSTIC: affinityServer called with NULL or EMPTY sessionKey - routing via round-robin. " +
                    "This may cause 'Connection not found' errors if queries reach wrong server. " +
                    "SessionKey value: '{}', isEmpty: {}, isNull: {}", 
                    sessionKey, 
                    sessionKey != null && sessionKey.isEmpty(),
                    sessionKey == null);
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
        
        // Choose selection strategy based on configuration
        if (healthCheckConfig.isLoadAwareSelectionEnabled()) {
            return selectByLeastConnections(healthyServers);
        } else {
            return selectByRoundRobin(healthyServers);
        }
    }
    
    /**
     * Selects the healthy server with the fewest active sessions.
     * This provides automatic load balancing by directing new connections
     * to the least-loaded server.
     * 
     * Uses SessionTracker for accurate session counts across both XA and non-XA connections.
     * When all servers have equal session counts, falls back to round-robin selection.
     * 
     * @param healthyServers List of healthy servers to choose from
     * @return The server with the lowest session count
     */
    private ServerEndpoint selectByLeastConnections(List<ServerEndpoint> healthyServers) {
        if (healthyServers.isEmpty()) {
            return null;
        }
        
        // Get current session counts per server from SessionTracker
        Map<ServerEndpoint, Integer> sessionCounts = sessionTracker.getSessionCounts();
        
        // Check if all servers have the same count
        boolean allEqual = true;
        Integer firstCount = null;
        for (ServerEndpoint server : healthyServers) {
            int count = sessionCounts.getOrDefault(server, 0);
            if (firstCount == null) {
                firstCount = count;
            } else if (firstCount != count) {
                allEqual = false;
                break;
            }
        }
        
        // If all counts are equal, use true round-robin instead of load-aware
        if (allEqual) {
            log.debug("All servers have equal session load ({}), using round-robin selection", firstCount);
            return selectByRoundRobin(healthyServers);
        }
        
        // Find server with minimum sessions
        ServerEndpoint selected = healthyServers.stream()
                .min((s1, s2) -> {
                    int count1 = sessionCounts.getOrDefault(s1, 0);
                    int count2 = sessionCounts.getOrDefault(s2, 0);
                    return Integer.compare(count1, count2);
                })
                .orElse(healthyServers.get(0));
        
        int selectedCount = sessionCounts.getOrDefault(selected, 0);
        log.debug("Selected server {} with {} active sessions (load-aware)", 
                selected.getAddress(), selectedCount);
        
        return selected;
    }
    
    /**
     * Selects a healthy server using round-robin strategy.
     * This is the legacy selection method that cycles through servers
     * without considering their current load.
     * 
     * @param healthyServers List of healthy servers to choose from
     * @return The next server in round-robin order
     */
    private ServerEndpoint selectByRoundRobin(List<ServerEndpoint> healthyServers) {
        if (healthyServers.isEmpty()) {
            return null;
        }
        
        int index = Math.abs(roundRobinCounter.getAndIncrement()) % healthyServers.size();
        ServerEndpoint selected = healthyServers.get(index);
        
        log.debug("Selected server {} for request (round-robin)", selected.getAddress());
        return selected;
    }
    
    /**
     * Handles server failure by marking it unhealthy and invalidating sessions/connections in XA mode.
     * This method can be called from both internal connection attempts and external callers (e.g., MultinodeStatementService)
     * when they detect connection-level failures.
     * 
     * @param endpoint The server endpoint that failed
     * @param exception The exception that caused the failure
     */
    public void handleServerFailure(ServerEndpoint endpoint, Exception exception) {
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
        
        // XA Mode: Immediately invalidate all sessions and connections for the failed server
        // This prevents attempts to use stale sessions after server failure
        if (xaConnectionRedistributor != null) {
            invalidateSessionsAndConnectionsForFailedServer(endpoint);
        }
        
        // Phase 2: Notify listeners that server became unhealthy
        notifyServerUnhealthy(endpoint, exception);
        
        // Remove the failed channel from the map and shut it down gracefully
        // Using shutdown() instead of shutdownNow() allows in-flight operations to complete
        ChannelAndStub channelAndStub = channelMap.remove(endpoint);
        if (channelAndStub != null) {
            log.debug("Removed channel for {} from map, initiating graceful shutdown", 
                    endpoint.getAddress());
            try {
                channelAndStub.channel.shutdown();
                log.debug("Initiated graceful shutdown for channel {}", endpoint.getAddress());
            } catch (Exception e) {
                log.warn("Error shutting down channel for {}: {}", endpoint.getAddress(), e.getMessage());
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
     * Pool exhaustion errors do NOT mark servers unhealthy - they indicate resource limits, not connectivity issues.
     */
    public boolean isConnectionLevelError(Exception exception) {
        return GrpcExceptionHandler.isConnectionLevelError(exception);
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
     * Registers the session with both sessionToServerMap (for backward compatibility)
     * and SessionTracker (for unified load balancing).
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
            
            // Register with SessionTracker for unified load balancing
            sessionTracker.registerSession(sessionUUID, matchingEndpoint);
            
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
     * Unregisters from both sessionToServerMap and SessionTracker.
     * 
     * @param sessionUUID The session identifier
     */
    public void unbindSession(String sessionUUID) {
        if (sessionUUID != null && !sessionUUID.isEmpty()) {
            ServerEndpoint removed = sessionToServerMap.remove(sessionUUID);
            
            // Unregister from SessionTracker
            sessionTracker.unregisterSession(sessionUUID);
            
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
     * Checks if a session is currently bound to a server.
     * Used to detect race conditions where a session is created but then
     * immediately invalidated by the health checker before it can be used.
     * 
     * @param sessionUUID The session UUID to check
     * @return true if the session is bound to a server, false otherwise
     */
    public boolean isSessionBound(String sessionUUID) {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            return false;
        }
        return sessionToServerMap.containsKey(sessionUUID);
    }
    
    /**
     * Shuts down all connections.
     */
    public void shutdown() {
        log.info("Shutting down MultinodeConnectionManager");
        
        // Shutdown health checker scheduler
        if (healthCheckScheduler != null) {
            healthCheckScheduler.shutdown();
            try {
                if (!healthCheckScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthCheckScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                healthCheckScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
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
     * Sets the XA connection redistributor for rebalancing connections on server recovery.
     * 
     * @param redistributor The redistributor to use
     */
    public void setXaConnectionRedistributor(XAConnectionRedistributor redistributor) {
        this.xaConnectionRedistributor = redistributor;
        log.info("XA connection redistributor registered");
    }
    
    /**
     * Phase 2: Notifies all listeners that a server became unhealthy.
     * 
     * @param endpoint The server endpoint that became unhealthy
     * @param exception The exception that caused the failure
     */
    private void notifyServerUnhealthy(ServerEndpoint endpoint, Exception exception) {
        for (ServerHealthListener listener : healthListeners) {
            if (listener == null) {
                continue; // Skip null listeners
            }
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
     * Also triggers XA connection redistribution if configured.
     * 
     * @param endpoint The server endpoint that recovered
     */
    private void notifyServerRecovered(ServerEndpoint endpoint) {
        for (ServerHealthListener listener : healthListeners) {
            if (listener == null) {
                continue; // Skip null listeners
            }
            try {
                listener.onServerRecovered(endpoint);
            } catch (Exception e) {
                log.error("Error notifying listener of recovered server {}: {}", 
                        endpoint.getAddress(), e.getMessage(), e);
            }
        }
        
        // Trigger XA connection redistribution if configured
        if (xaConnectionRedistributor != null && healthCheckConfig.isRedistributionEnabled()) {
            List<ServerEndpoint> allHealthyServers = serverEndpoints.stream()
                    .filter(ServerEndpoint::isHealthy)
                    .collect(Collectors.toList());
            
            try {
                xaConnectionRedistributor.rebalance(List.of(endpoint), allHealthyServers);
            } catch (Exception e) {
                log.error("Error during XA connection redistribution: {}", e.getMessage(), e);
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
