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
    private final String connectionUuid; // Unique identifier for this XA connection

    public OjpXAConnection(StatementService statementService, String url, String user, String password, Properties properties, List<String> serverEndpoints) {
        log.debug("Creating OjpXAConnection for URL: {}", url);
        this.statementService = statementService;
        this.url = url;
        this.user = user;
        this.password = password;
        this.properties = properties;
        this.serverEndpoints = serverEndpoints;
        this.connectionUuid = java.util.UUID.randomUUID().toString();
        
        // Register as health listener with multinode connection manager if available
        registerHealthListener();
        
        // Session is created lazily when needed
    }
    
    /**
     * Lazily create the server-side session when first needed.
     * This avoids creating sessions that may never be used.
     */
    private synchronized SessionInfo getOrCreateSession() throws SQLException {
        if (sessionInfo != null) {
            return sessionInfo;
        }
        
        try {
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
            }

            if (properties != null && !properties.isEmpty()) {
                Map<String, Object> propertiesMap = new HashMap<>();
                for (String key : properties.stringPropertyNames()) {
                    propertiesMap.put(key, properties.getProperty(key));
                }
                connBuilder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
            }

            this.sessionInfo = statementService.connect(connBuilder.build());
            
            // Phase 2: Track the bound server from session info
            if (sessionInfo.getTargetServer() != null && !sessionInfo.getTargetServer().isEmpty()) {
                this.boundServerAddress = sessionInfo.getTargetServer();
                log.debug("XA connection bound to server: {}", boundServerAddress);
                
                // Register this XA connection with the tracker for redistribution
                registerWithTracker();
            }
            
            log.debug("XA connection established with session: {}", sessionInfo.getSessionUUID());
            return sessionInfo;

        } catch (Exception e) {
            log.error("Failed to create XA connection session", e);
            throw new SQLException("Failed to create XA connection session", e);
        }
    }
    
    /**
     * Phase 1: Recreates the session on a different server.
     * Used for retry logic when the bound server fails.
     * 
     * @return The new SessionInfo
     * @throws SQLException if session recreation fails
     */
    synchronized SessionInfo recreateSession() throws SQLException {
        log.info("Recreating XA session (previous session: {})", 
                sessionInfo != null ? sessionInfo.getSessionUUID() : "none");
        
        // Clear existing session
        sessionInfo = null;
        boundServerAddress = null;
        xaResource = null; // Force recreation of XAResource with new session
        
        // Create new session (will use round-robin to select a different server)
        return getOrCreateSession();
    }

    @Override
    public XAResource getXAResource() throws SQLException {
        log.debug("getXAResource called");
        checkClosed();
        if (xaResource == null) {
            // Lazily create session when XAResource is first requested
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
        
        // Lazily create session when Connection is first requested
        SessionInfo session = getOrCreateSession();
        
        // Verify session was created successfully
        if (session == null) {
            log.error("Failed to create valid session - sessionInfo: {}", session);
            throw new SQLException("Failed to create XA connection session");
        }
        
        log.debug("Creating logical connection for session: {}", session.getSessionUUID());
        
        // Create a new logical connection that uses the same XA session on the server
        logicalConnection = new OjpXALogicalConnection(this, session, url);
        
        // Register with ConnectionTracker if using multinode
        if (statementService instanceof MultinodeStatementService) {
            MultinodeStatementService multinodeService = (MultinodeStatementService) statementService;
            MultinodeConnectionManager connectionManager = multinodeService.getConnectionManager();
            if (connectionManager != null && boundServerAddress != null) {
                // Find the ServerEndpoint for the bound server
                ServerEndpoint boundEndpoint = findServerEndpoint(connectionManager, boundServerAddress);
                if (boundEndpoint != null) {
                    connectionManager.getConnectionTracker().register(logicalConnection, boundEndpoint);
                    log.debug("Registered connection with tracker for server: {}", boundServerAddress);
                }
            }
        }
        
        return logicalConnection;
    }
    
    /**
     * Find the ServerEndpoint matching the bound server address.
     */
    private ServerEndpoint findServerEndpoint(MultinodeConnectionManager connectionManager, String serverAddress) {
        try {
            // The connectionManager has access to all server endpoints
            // We need to find the one matching our boundServerAddress
            // For now, return null as we don't have direct access to the endpoint list
            // This will be enhanced in Phase 4
            log.debug("Finding server endpoint for address: {}", serverAddress);
            return null;
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
        log.debug("close called");
        if (closed) {
            return;
        }
        
        closed = true;
        
        // Unregister from health listeners
        unregisterHealthListener();
        
        // Unregister from XA connection tracker
        unregisterFromTracker();
        
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
                statementService.terminateSession(sessionInfo);
            } catch (Exception e) {
                log.error("Error closing XA session", e);
                throw new SQLException("Error closing XA session", e);
            }
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
     * If this connection is bound to that server, close it proactively
     * so Atomikos will create a new connection.
     */
    @Override
    public void onServerUnhealthy(ServerEndpoint endpoint, Exception exception) {
        String serverAddr = endpoint.getHost() + ":" + endpoint.getPort();
        
        // Check if this connection is bound to the failed server
        if (boundServerAddress != null && boundServerAddress.equals(serverAddr)) {
            log.warn("XA connection bound to unhealthy server {}, closing connection proactively", serverAddr);
            try {
                // Close this connection - Atomikos will remove it from pool and create a new one
                close();
            } catch (SQLException e) {
                log.error("Error closing XA connection after server failure: {}", e.getMessage(), e);
            }
        }
    }
    
    /**
     * Phase 2: Called when a server recovers.
     * No action needed for individual connections - centralized redistribution handles rebalancing.
     */
    @Override
    public void onServerRecovered(ServerEndpoint endpoint) {
        // No action needed - XAConnectionRedistributor handles redistribution
        log.debug("Server {} recovered, XA connection {} will continue using current server {}", 
                endpoint.getAddress(), connectionUuid, boundServerAddress);
    }
    
    /**
     * Registers this XA connection as a health listener with the multinode connection manager.
     */
    private void registerHealthListener() {
        if (statementService instanceof MultinodeStatementService) {
            MultinodeStatementService multinodeService = (MultinodeStatementService) statementService;
            MultinodeConnectionManager connectionManager = multinodeService.getConnectionManager();
            if (connectionManager != null) {
                connectionManager.addHealthListener(this);
                log.debug("XA connection {} registered as health listener", connectionUuid);
            }
        }
    }
    
    /**
     * Unregisters this XA connection from health listener notifications.
     */
    private void unregisterHealthListener() {
        if (statementService instanceof MultinodeStatementService) {
            MultinodeStatementService multinodeService = (MultinodeStatementService) statementService;
            MultinodeConnectionManager connectionManager = multinodeService.getConnectionManager();
            if (connectionManager != null) {
                connectionManager.removeHealthListener(this);
                log.debug("XA connection {} unregistered from health listener", connectionUuid);
            }
        }
    }
    
    /**
     * Registers this XA connection with the connection tracker for redistribution.
     * Called after session is created and bound to a server.
     */
    private void registerWithTracker() {
        if (statementService instanceof MultinodeStatementService) {
            MultinodeStatementService multinodeService = (MultinodeStatementService) statementService;
            MultinodeConnectionManager connectionManager = multinodeService.getConnectionManager();
            if (connectionManager != null && boundServerAddress != null) {
                // Find the ServerEndpoint for the bound server
                ServerEndpoint boundEndpoint = findServerEndpointByAddress(connectionManager, boundServerAddress);
                if (boundEndpoint != null) {
                    connectionManager.getConnectionTracker().registerXAConnection(
                            connectionUuid, this, boundEndpoint);
                    log.debug("XA connection {} registered with tracker for server: {}", 
                            connectionUuid, boundServerAddress);
                } else {
                    log.warn("Could not find ServerEndpoint for address {} to register XA connection", 
                            boundServerAddress);
                }
            }
        }
    }
    
    /**
     * Unregisters this XA connection from the connection tracker.
     */
    private void unregisterFromTracker() {
        if (statementService instanceof MultinodeStatementService) {
            MultinodeStatementService multinodeService = (MultinodeStatementService) statementService;
            MultinodeConnectionManager connectionManager = multinodeService.getConnectionManager();
            if (connectionManager != null) {
                connectionManager.getConnectionTracker().unregisterXAConnection(connectionUuid);
                log.debug("XA connection {} unregistered from tracker", connectionUuid);
            }
        }
    }
    
    /**
     * Finds the ServerEndpoint matching the given address string (host:port format).
     */
    private ServerEndpoint findServerEndpointByAddress(MultinodeConnectionManager connectionManager, String serverAddress) {
        if (serverAddress == null || connectionManager == null) {
            return null;
        }
        
        for (ServerEndpoint endpoint : connectionManager.getServerEndpoints()) {
            String endpointAddress = endpoint.getHost() + ":" + endpoint.getPort();
            if (endpointAddress.equals(serverAddress)) {
                return endpoint;
            }
        }
        
        return null;
    }
    
    /**
     * Gets the unique identifier for this XA connection.
     * Used for tracking and redistribution.
     */
    public String getConnectionUuid() {
        return connectionUuid;
    }
}
