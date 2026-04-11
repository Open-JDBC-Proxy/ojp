package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.cache.CacheConfiguration;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds information about a session of a given client.
 */
@Slf4j
public class Session {
    @Getter
    private final String sessionUUID;
    @Getter
    private final String connectionHash;
    @Getter
    private final String clientUUID;
    private Connection connection;  // Removed @Getter - custom getter below
    @Getter
    private final boolean isXA;
    @Getter
    private XAConnection xaConnection;
    @Getter
    private XAResource xaResource;
    @Getter
    private Object backendSession; // Holds XABackendSession for XA pooling (avoids hard dependency)
    @Getter
    private final CacheConfiguration cacheConfiguration;  // Can be null if caching not configured
    private Map<String, ResultSet> resultSetMap;
    private Map<String, Statement> statementMap;
    private Map<String, PreparedStatement> preparedStatementMap;
    private Map<String, CallableStatement> callableStatementMap;
    private Map<String, Object> lobMap;
    private Map<String, Object> attrMap;
    private boolean closed;
    private int transactionTimeout = 0;
    @Getter
    private volatile long lastActivityTime;
    @Getter
    private final long creationTime;

    public Session(Connection connection, String connectionHash, String clientUUID) {
        this(connection, connectionHash, clientUUID, false, null, null);
    }

    public Session(Connection connection, String connectionHash, String clientUUID, boolean isXA, XAConnection xaConnection) {
        this(connection, connectionHash, clientUUID, isXA, xaConnection, null);
    }

    public Session(Connection connection, String connectionHash, String clientUUID, boolean isXA, XAConnection xaConnection, CacheConfiguration cacheConfiguration) {
        this.connection = connection;
        this.connectionHash = connectionHash;
        this.clientUUID = clientUUID;
        this.isXA = isXA;
        this.xaConnection = xaConnection;
        this.cacheConfiguration = cacheConfiguration;  // Can be null
        this.sessionUUID = UUID.randomUUID().toString();
        this.closed = false;
        this.creationTime = System.currentTimeMillis();
        this.lastActivityTime = this.creationTime;
        this.resultSetMap = new ConcurrentHashMap<>();
        this.statementMap = new ConcurrentHashMap<>();
        this.preparedStatementMap = new ConcurrentHashMap<>();
        this.callableStatementMap = new ConcurrentHashMap<>();
        this.lobMap = new ConcurrentHashMap<>();
        this.attrMap = new ConcurrentHashMap<>();
        
        if (isXA && xaConnection != null) {
            try {
                this.xaResource = xaConnection.getXAResource();
            } catch (SQLException e) {
                log.error("Failed to get XAResource from XAConnection", e);
                throw new RuntimeException("Failed to initialize XA session", e);
            }
        }
    }
    
    /**
     * Binds an XAConnection to this session (for lazy XA allocation with pooling).
     * This method is thread-safe and can only be called once.
     * 
     * @param xaConn The XAConnection to bind
     * @param backendSession The XABackendSession wrapper (from XA pool)
     * @throws IllegalStateException if XAConnection is already bound (unless both parameters are null for unbinding)
     */
    public synchronized void bindXAConnection(XAConnection xaConn, Object backendSession) {
        // Allow unbinding by passing null for both parameters
        if (xaConn == null && backendSession == null) {
            this.xaConnection = null;
            this.backendSession = null;
            this.connection = null;
            this.xaResource = null;
            log.debug("Unbound XAConnection from session {}", sessionUUID);
            return;
        }
        
        if (this.xaConnection != null) {
            throw new IllegalStateException("XAConnection already bound to session");
        }
        if (!this.isXA) {
            throw new IllegalStateException("Cannot bind XAConnection to non-XA session");
        }
        
        try {
            this.xaConnection = xaConn;
            this.backendSession = backendSession;
            this.connection = xaConn.getConnection();
            this.xaResource = xaConn.getXAResource();
            log.debug("Bound XAConnection to session {}", sessionUUID);
        } catch (SQLException e) {
            log.error("Failed to bind XAConnection", e);
            throw new RuntimeException("Failed to bind XAConnection", e);
        }
    }
    
    /**
     * Sets the backend session reference for XA pooling.
     * 
     * @param backendSession The XABackendSession from the XA pool
     */
    public void setBackendSession(Object backendSession) {
        this.backendSession = backendSession;
    }
    
    /**
     * Refreshes the connection reference from the backend session.
     * This is called after XA transaction sanitization to update the connection
     * reference to the new logical connection obtained from the XAConnection.
     * 
     * @throws SQLException if unable to get connection from backend session
     */
    public void refreshConnection() throws SQLException {
        if (backendSession != null && backendSession instanceof org.openjproxy.xa.pool.XABackendSession) {
            org.openjproxy.xa.pool.XABackendSession xaBackendSession = 
                (org.openjproxy.xa.pool.XABackendSession) backendSession;
            this.connection = xaBackendSession.getConnection();
            log.debug("Refreshed connection reference in session {}", sessionUUID);
        }
    }
    
    /**
     * Gets the JDBC connection for this session.
     * For XA sessions with pooled backend sessions, this returns the current
     * connection from the backend session (which may change after sanitization).
     * 
     * @return the JDBC connection
     */
    public Connection getConnection() {
        // For XA sessions with backend session, always get fresh connection reference
        // This ensures we get the updated connection after sanitization
        if (isXA && backendSession != null && backendSession instanceof org.openjproxy.xa.pool.XABackendSession) {
            org.openjproxy.xa.pool.XABackendSession xaBackendSession = 
                (org.openjproxy.xa.pool.XABackendSession) backendSession;
            return xaBackendSession.getConnection();
        }
        // For non-XA sessions or pass-through XA sessions, return stored connection
        return this.connection;
    }

    public SessionInfo getSessionInfo() {
        log.debug("get session info -> " + this.connectionHash);
        return SessionInfo.newBuilder()
                .setConnHash(this.connectionHash)
                .setClientUUID(this.clientUUID)
                .setSessionUUID(this.sessionUUID)
                .setIsXA(this.isXA)
                .build();
    }

    public void addAttr(String key, Object value) {
        this.notClosed();
        this.attrMap.put(key, value);
    }

    public Object getAttr(String key) {
        this.notClosed();
        return this.attrMap.get(key);
    }

    public void addResultSet(String uuid, ResultSet rs) {
        this.notClosed();
        this.resultSetMap.put(uuid, rs);
    }

    public ResultSet getResultSet(String uuid) {
        this.notClosed();
        return this.resultSetMap.get(uuid);
    }

    public void addStatement(String uuid, Statement stmt) {
        this.notClosed();
        this.statementMap.put(uuid, stmt);
    }

    public Statement getStatement(String uuid) {
        this.notClosed();
        return this.statementMap.get(uuid);
    }

    public void addPreparedStatement(String uuid, PreparedStatement ps) {
        this.notClosed();
        this.preparedStatementMap.put(uuid, ps);
    }

    public PreparedStatement getPreparedStatement(String uuid) {
        this.notClosed();
        return this.preparedStatementMap.get(uuid);
    }

    public void addCallableStatement(String uuid, CallableStatement cs) {
        this.notClosed();
        this.callableStatementMap.put(uuid, cs);
    }

    public CallableStatement getCallableStatement(String uuid) {
        this.notClosed();
        return this.callableStatementMap.get(uuid);
    }

    public void addLob(String uuid, Object o) {
        this.notClosed();
        if (o != null) {
            this.lobMap.put(uuid, o);
        }
    }

    public <T> T getLob(String uuid) {
        this.notClosed();
        return (T) this.lobMap.get(uuid);
    }

    private void notClosed() {
        if (this.closed) {
            throw new RuntimeException("Session is closed.");
        }
    }

    public void terminate() throws SQLException {

        if (this.closed) {
            return;
        }

        // For XA connections with pooled XABackendSession, DO NOT close anything here
        // The XATransactionRegistry handles returning sessions to the pool via returnCompletedSessions()
        // which is called when the OJP XAConnection is closed (dual-condition lifecycle)
        if (isXA && backendSession != null) {
            // Pooled XA backend session - managed by XATransactionRegistry
            // Do nothing here - registry will return session to pool when appropriate
            log.debug("Skipping close for pooled XABackendSession {} - managed by XATransactionRegistry", sessionUUID);
        } else if (isXA && xaConnection != null) {
            // For XA connections WITHOUT pooling (pass-through mode), close the XA connection
            // Do NOT close the regular connection as it would trigger auto-commit changes
            try {
                xaConnection.close();
            } catch (SQLException e) {
                log.error("Error closing XA connection", e);
            }
        } else if (connection != null) {
            // For regular connections, close normally
            this.connection.close();
        }

        //Clear session internal objects to free memory
        this.closed = true;
        this.lobMap = null;
        this.resultSetMap = null;
        this.statementMap = null;
        this.preparedStatementMap = null;
        this.connection = null;
        this.xaConnection = null;
        this.xaResource = null;
        this.backendSession = null;
        this.attrMap = null;
    }

    public void setTransactionTimeout(int seconds) {
        this.transactionTimeout = seconds;
    }

    public int getTransactionTimeout() {
        return this.transactionTimeout;
    }

    public Collection<Object> getAllLobs() {
        return this.lobMap.values();
    }

    /**
     * Updates the last activity time for this session to the current time.
     * This method should be called on every operation that uses this session
     * to prevent premature cleanup of active sessions.
     */
    public void updateActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * Checks if the session has been inactive for longer than the specified timeout.
     * 
     * @param timeoutMillis the inactivity timeout in milliseconds
     * @return true if the session has been inactive for longer than the timeout, false otherwise
     */
    public boolean isInactive(long timeoutMillis) {
        long inactiveDuration = System.currentTimeMillis() - this.lastActivityTime;
        return inactiveDuration > timeoutMillis;
    }

    /**
     * Gets the duration in milliseconds since the last activity.
     * 
     * @return milliseconds since last activity
     */
    public long getInactiveDuration() {
        return System.currentTimeMillis() - this.lastActivityTime;
    }
}
