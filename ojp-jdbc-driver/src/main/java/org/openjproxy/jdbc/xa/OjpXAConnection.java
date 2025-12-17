package org.openjproxy.jdbc.xa;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.MultinodeConnectionManager;
import org.openjproxy.grpc.client.MultinodeStatementService;
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
 * <p>With Narayana pooling, XA connections use round-robin load balancing like non-XA
 * connections. Session stickiness ensures all operations within a transaction stay on
 * the same server.
 */
@Slf4j
public class OjpXAConnection implements XAConnection {

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
        // With Narayana pooling, sessions use round-robin load balancing
        // Session stickiness ensures transaction stays on same server
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
            }

            if (properties != null && !properties.isEmpty()) {
                Map<String, Object> propertiesMap = new HashMap<>();
                for (String key : properties.stringPropertyNames()) {
                    propertiesMap.put(key, properties.getProperty(key));
                }
                connBuilder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
            }

            this.sessionInfo = statementService.connect(connBuilder.build());
            
            log.debug("XA session created with UUID: {}", sessionInfo.getSessionUUID());
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
            xaResource = null; // Force recreation of XAResource with new session

            // Create new session (will use round-robin to select a server)
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
        logicalConnection = new OjpXALogicalConnection(this, session, url);
        
        return logicalConnection;
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
}
