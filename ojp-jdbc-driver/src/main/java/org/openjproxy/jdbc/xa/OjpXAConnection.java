package org.openjproxy.jdbc.xa;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.MultinodeConnectionManager;
import org.openjproxy.grpc.client.MultinodeStatementService;
import org.openjproxy.grpc.client.ServerEndpoint;
import org.openjproxy.grpc.client.ServerHealthListener;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.jdbc.ClientUUID;

import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of XAConnection that connects to the OJP server for XA operations.
 * Uses the integrated StatementService for connection management.
 * 
 * <p>The server-side session is created lazily when first needed (either when getting
 * the XAResource or when getting a Connection), to avoid creating unnecessary sessions.
 * 
 * <p>Phase 2: Implements ServerHealthListener to handle server failures proactively.
 */
@Slf4j
public class OjpXAConnection implements XAConnection, ServerHealthListener {

    private final StatementService statementService;
    private SessionInfo sessionInfo; // Lazily initialized
    private final String url;
    private final String user;
    private final String password;
    private final Properties properties;
    private Connection logicalConnection;
    private OjpXAResource xaResource;
    private boolean closed = false;
    private List<String> serverEndpoints;
    private final List<ConnectionEventListener> listeners = new ArrayList<>();
    private String boundServerAddress; // Phase 2: Track which server this connection is bound to
    private final ReentrantLock sessionLock = new ReentrantLock();

    public OjpXAConnection(StatementService statementService, String url, String user, String password, Properties properties, List<String> serverEndpoints) {
        log.debug("Creating OjpXAConnection for URL: {}", url);
        this.statementService = statementService;
        this.url = url;
        this.user = user;
        this.password = password;
        this.properties = properties;
        this.serverEndpoints = serverEndpoints;
        // Session is created lazily when needed
        
        // Register as health listener if using multinode
        if (statementService instanceof MultinodeStatementService) {
            MultinodeStatementService ms = (MultinodeStatementService) statementService;
            MultinodeConnectionManager cm = ms.getConnectionManager();
            if (cm != null) {
                cm.addHealthListener(this);
                log.debug("Registered XA connection as health listener with MultinodeConnectionManager");
            }
        }
    }
    
    /**
     * Lazily create the server-side session when first needed.
     * This avoids creating sessions that may never be used.
     */
    private SessionInfo getOrCreateSession() throws SQLException {
        if (sessionInfo != null) {
            return sessionInfo;
        }
        sessionLock.lock();
        try {
            if (sessionInfo != null) {
                return sessionInfo;
            }
            // Connect to server with XA flag enabled
            ConnectionDetails.Builder connBuilder = ConnectionDetails.newBuilder()
                    .setUrl(url)
                    .setUser(user != null ? user : "")
                    .setPassword(password != null ? password : "")
                    .setClientUUID(ClientUUID.getUUID())
                    .setIsXA(true);  // Mark this as an XA connection

            // Add server endpoints list for multinode coordination
            if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
                connBuilder.addAllServerEndpoints(serverEndpoints);
                log.info("Adding {} server endpoints to ConnectionDetails for multinode coordination", serverEndpoints.size());
                
                // CRITICAL FIX: Add actual cluster health for XA pool rebalancing during connect()
                // The server needs the CURRENT cluster health (not synthetic) to properly size the XA backend pool
                // when server 1 fails before any XA operations execute
                if (statementService instanceof MultinodeStatementService) {
                    MultinodeConnectionManager connectionManager = ((MultinodeStatementService) statementService).getConnectionManager();
                    String clusterHealth = connectionManager.generateClusterHealth();
                    connBuilder.setClusterHealth(clusterHealth);
                    log.info("[XA-CONNECT-HEALTH] Adding cluster health to ConnectionDetails: {}", clusterHealth);
                }
            }

            if (properties != null && !properties.isEmpty()) {
                Map<String, Object> propertiesMap = new HashMap<>();
                for (String key : properties.stringPropertyNames()) {
                    propertiesMap.put(key, properties.getProperty(key));
                }
                connBuilder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
            }

            SessionInfo newSessionInfo = statementService.connect(connBuilder.build());
            
            // Session validation is now handled inside MultinodeConnectionManager.connectToAllServers()
            // which checks if the primary session was invalidated and returns a valid session instead
            this.sessionInfo = newSessionInfo;
            String newBoundServer = newSessionInfo.getTargetServer();
            this.boundServerAddress = newBoundServer;
            
            if (boundServerAddress != null && !boundServerAddress.isEmpty()) {
                log.debug("XA connection bound to server: {}", boundServerAddress);
            }
            
            log.debug("XA connection established with session: {}", sessionInfo.getSessionUUID());
            return sessionInfo;

        } catch (Exception e) {
            log.error("Failed to create XA connection session", e);
            throw new SQLException("Failed to create XA connection session", e);
        }finally {
            sessionLock.unlock();
        }
    }
    
    /**
     * Phase 1: Recreates the session on a different server.
     * Used for retry logic when the bound server fails.
     * 
     * @return The new SessionInfo
     * @throws SQLException if session recreation fails
     */
     SessionInfo recreateSession() throws SQLException {
        sessionLock.lock();
        try {
            log.info("Recreating XA session (previous session: {})",
                    sessionInfo != null ? sessionInfo.getSessionUUID() : "none");

            // Clear existing session
            sessionInfo = null;
            boundServerAddress = null;
            xaResource = null; // Force recreation of XAResource with new session

            // Create new session (will use round-robin to select a different server)
            return getOrCreateSession();
        } finally {
            sessionLock.unlock();
        }
    }

    @Override
    public XAResource getXAResource() throws SQLException {
        log.debug("getXAResource called");
        checkClosed();
        if (xaResource == null) {
            SessionInfo session = getOrCreateSession();
            xaResource = new OjpXAResource(statementService, session, this); // Phase 1: Pass this connection to XAResource
        }
        return xaResource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        log.debug("getConnection called");
        checkClosed();
        
        // Close any existing logical connection
        if (logicalConnection != null && !logicalConnection.isClosed()) {
            logicalConnection.close();
        }
        
        SessionInfo session = getOrCreateSession();
        
        // Verify session was created successfully
        if (session == null) {
            log.error("Failed to create valid session - sessionInfo: {}", session);
            throw new SQLException("Failed to create XA connection session");
        }
        
        log.debug("Creating logical connection for session: {}", session.getSessionUUID());
        
        // Create a new logical connection that uses the same XA session on the server
        // The logical connection will register itself with ConnectionTracker in its constructor
        logicalConnection = new OjpXALogicalConnection(this, session, url, boundServerAddress);
        
        return logicalConnection;
    }
    
    /**
     * Find the ServerEndpoint matching the bound server address.
     */
    private ServerEndpoint findServerEndpoint(MultinodeConnectionManager connectionManager, String serverAddress) {
        try {
            log.debug("Finding server endpoint for address: {}", serverAddress);
            ServerEndpoint serverEndpoint = connectionManager.getServerEndpoints().stream().filter(se ->
                se.getAddress().equalsIgnoreCase(serverAddress)
            ).findFirst().orElse(null);
            log.debug("Server endpoint for address {} found {}", serverAddress, serverEndpoint != null ? "successfully" : "not found");
            return serverEndpoint;
        } catch (Exception e) {
            log.warn("Failed to find server endpoint for {}: {}", serverAddress, e.getMessage());
            return null;
        }
    }
    
    /**
     * Get the statement service for this XA connection.
     */
    StatementService getStatementService() {
        return statementService;
    }

    @Override
    public void close() throws SQLException {
        try {
            log.info("[XA-CONN-CLOSE] OjpXAConnection.close() called, closed={}, sessionInfo={}", 
                    closed, sessionInfo != null ? sessionInfo.getSessionUUID() : "null");
            if (closed) {
                log.debug("[XA-CONN-CLOSE] Already closed, returning");
                return;
            }
            
            closed = true;
            
            // Deregister from health listener
            if (statementService instanceof MultinodeStatementService) {
                MultinodeStatementService ms = (MultinodeStatementService) statementService;
                MultinodeConnectionManager cm = ms.getConnectionManager();
                if (cm != null) {
                    cm.removeHealthListener(this);
                    log.debug("Deregistered XA connection from health listener");
                }
            }
            
            // Unregister from ConnectionTracker if registered
            if (logicalConnection != null && statementService instanceof MultinodeStatementService) {
                MultinodeStatementService multinodeService = (MultinodeStatementService) statementService;
                MultinodeConnectionManager connectionManager = multinodeService.getConnectionManager();
                if (connectionManager != null) {
                    connectionManager.getConnectionTracker().unregister(logicalConnection);
                    log.debug("Unregistered connection from tracker");
                }
            }
            
            // Close logical connection if open
            if (logicalConnection != null && !logicalConnection.isClosed()) {
                logicalConnection.close();
            }
            
            // Notify listeners
            ConnectionEvent event = new ConnectionEvent(this);
            for (ConnectionEventListener listener : listeners) {
                listener.connectionClosed(event);
            }
            
            // Close XA session on server (only if it was created)
            if (sessionInfo != null) {
                try {
                    log.info("[XA-CONN-CLOSE] Calling terminateSession for sessionUUID={}", sessionInfo.getSessionUUID());
                    statementService.terminateSession(sessionInfo);
                    log.info("[XA-CONN-CLOSE] terminateSession completed successfully for sessionUUID={}", sessionInfo.getSessionUUID());
                } catch (Exception e) {
                    log.error("[XA-CONN-CLOSE] Error calling terminateSession for sessionUUID={}", sessionInfo.getSessionUUID(), e);
                    throw new SQLException("Error closing XA session", e);
                }
            } else {
                log.warn("[XA-CONN-CLOSE] sessionInfo is null, terminateSession NOT called");
            }
        } catch (Throwable t) {
            log.error("[XA-CONN-CLOSE] CRITICAL EXCEPTION in close() - this causes session leak!", t);
            throw new SQLException("XAConnection close failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void addConnectionEventListener(ConnectionEventListener listener) {
        log.debug("addConnectionEventListener called");
        listeners.add(listener);
    }

    @Override
    public void removeConnectionEventListener(ConnectionEventListener listener) {
        log.debug("removeConnectionEventListener called");
        listeners.remove(listener);
    }

    @Override
    public void addStatementEventListener(javax.sql.StatementEventListener listener) {
        log.debug("addStatementEventListener called - not supported");
        // Not supported for XA connections
    }

    @Override
    public void removeStatementEventListener(javax.sql.StatementEventListener listener) {
        log.debug("removeStatementEventListener called - not supported");
        // Not supported for XA connections
    }

    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("XA Connection is closed");
        }
    }
    
    /**
     * Phase 2: Called when a server becomes unhealthy.
     * If this connection is bound to that server, invalidate the session and close the connection.
     * 
     * CRITICAL FIX: Also invalidate sessionInfo if the health checker removed the session binding
     * during a race condition (mid-connect). This prevents using stale session UUIDs.
     */
    @Override
    public void onServerUnhealthy(ServerEndpoint endpoint, Exception exception) {
        String serverAddr = endpoint.getHost() + ":" + endpoint.getPort();
        
        sessionLock.lock();
        try {
            // Check if this connection is bound to the failed server
            if (boundServerAddress != null && boundServerAddress.equals(serverAddr)) {
                log.warn("XA connection bound to unhealthy server {}, invalidating session and closing connection", serverAddr);
                
                // CRITICAL FIX: Invalidate the session info immediately
                // This handles the race condition where the health checker invalidates the session
                // during connect(), but before the sessionInfo field is fully initialized in the caller
                if (sessionInfo != null) {
                    String invalidatedSessionUUID = sessionInfo.getSessionUUID();
                    log.info("[RACE-FIX] Invalidating session {} due to server {} failure", invalidatedSessionUUID, serverAddr);
                    sessionInfo = null;
                    boundServerAddress = null;
                    xaResource = null; // Force recreation of XAResource with new session
                }
                
                // Close this connection - Atomikos will remove it from pool and create a new one
                if (!closed) {
                    try {
                        close();
                    } catch (SQLException e) {
                        log.error("Error closing XA connection after server failure: {}", e.getMessage(), e);
                    }
                }
            }
        } finally {
            sessionLock.unlock();
        }
    }
    
    /**
     * Phase 2: Called when a server recovers.
     * Intentionally conservative: do not recreate or close sessions here to avoid 
     * disrupting in-flight XA transactions. Redistribution policy is handled centrally 
     * by XAConnectionRedistributor which acts only on idle connections.
     */
    @Override
    public void onServerRecovered(ServerEndpoint endpoint) {
        log.debug("Server {} recovered (XA connection listener)", endpoint.getAddress());
        // Intentionally conservative: do not recreate or close sessions here to avoid disrupting in-flight XA transactions.
        // Redistribution policy is handled centrally by XAConnectionRedistributor which acts only on idle connections.
    }
}
