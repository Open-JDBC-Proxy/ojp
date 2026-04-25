package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.cache.CacheConfiguration;
import org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry;

import javax.sql.DataSource;
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
import java.util.concurrent.locks.ReentrantLock;

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
    
    // Dual-connection model for read/write splitting
    private volatile Connection primaryConnection;    // Connection to primary database (writes + transactional reads)
    private volatile Connection replicaConnection;    // Connection to replica database (non-transactional reads)
    @Getter
    private volatile ConnectionRole activeRole = ConnectionRole.NONE;  // Which connection is currently active
    
    // Lazy loading support for connections
    private final ReentrantLock primaryLock = new ReentrantLock();  // Lock for lazy primary connection allocation
    private final ReentrantLock replicaLock = new ReentrantLock();  // Lock for lazy replica connection allocation
    private final Map<String, DataSource> datasourceMap;  // Reference to datasource map for lazy allocation
    private final ReadWriteDataSourceRegistry readWriteRegistry;  // Reference to read/write registry for replica selection
    
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
    
    // Read/Write Splitting Support
    @Getter
    private volatile boolean inTransaction = false;  // Tracks if session is in an active transaction
    @Getter
    private volatile long lastWriteTimestamp = 0;  // Timestamp of last write operation (for sticky sessions)
    private static final long DEFAULT_STICKY_SESSION_MILLIS = 5000;  // 5 seconds default

    public Session(Connection connection, String connectionHash, String clientUUID) {
        this(connection, connectionHash, clientUUID, false, null, null, null, null);
    }

    public Session(Connection connection, String connectionHash, String clientUUID, boolean isXA, XAConnection xaConnection) {
        this(connection, connectionHash, clientUUID, isXA, xaConnection, null, null, null);
    }

    public Session(Connection connection, String connectionHash, String clientUUID, boolean isXA, XAConnection xaConnection, CacheConfiguration cacheConfiguration) {
        this(connection, connectionHash, clientUUID, isXA, xaConnection, cacheConfiguration, null, null);
    }
    
    /**
     * Constructor with lazy loading support.
     * 
     * @param connection Initial connection (can be null for full lazy loading)
     * @param connectionHash The connection hash identifying the datasource
     * @param clientUUID The client UUID
     * @param isXA Whether this is an XA session
     * @param xaConnection XA connection (for XA sessions)
     * @param cacheConfiguration Cache configuration (can be null)
     * @param datasourceMap Map of datasources for lazy allocation (can be null for eager mode)
     * @param readWriteRegistry Registry for read/write splitting (can be null if not using read/write splitting)
     */
    public Session(Connection connection, String connectionHash, String clientUUID, boolean isXA, XAConnection xaConnection, 
                   CacheConfiguration cacheConfiguration, Map<String, DataSource> datasourceMap, ReadWriteDataSourceRegistry readWriteRegistry) {
        this.primaryConnection = connection;  // Initial connection goes to primary slot (can be null for lazy loading)
        this.activeRole = (connection != null) ? ConnectionRole.PRIMARY : ConnectionRole.NONE;
        this.connectionHash = connectionHash;
        this.clientUUID = clientUUID;
        this.isXA = isXA;
        this.xaConnection = xaConnection;
        this.cacheConfiguration = cacheConfiguration;  // Can be null
        this.datasourceMap = datasourceMap;  // Can be null for eager mode
        this.readWriteRegistry = readWriteRegistry;  // Can be null
        this.sessionUUID = UUID.randomUUID().toString();
        this.closed = false;
        this.creationTime = System.nanoTime();
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
            this.primaryConnection = null;
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
            this.primaryConnection = xaConn.getConnection();
            this.xaResource = xaConn.getXAResource();
            this.activeRole = ConnectionRole.PRIMARY;  // XA connections always use primary
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
            this.primaryConnection = xaBackendSession.getConnection();
            log.debug("Refreshed connection reference in session {}", sessionUUID);
        }
    }
    
    /**
     * Gets the JDBC connection for this session based on the active connection role.
     * For XA sessions with pooled backend sessions, this returns the current
     * connection from the backend session (which may change after sanitization).
     * For read/write splitting, this returns either the primary or replica connection
     * based on the activeRole.
     * 
     * @return the JDBC connection, or null if no connection is available yet
     */
    public Connection getConnection() {
        // For XA sessions with backend session, always get fresh connection reference
        // This ensures we get the updated connection after sanitization
        if (isXA && backendSession != null && backendSession instanceof org.openjproxy.xa.pool.XABackendSession) {
            org.openjproxy.xa.pool.XABackendSession xaBackendSession = 
                (org.openjproxy.xa.pool.XABackendSession) backendSession;
            return xaBackendSession.getConnection();
        }
        
        // For read/write splitting, return the connection based on active role
        // Return null if no connection is available (matches original behavior for deferred XA sessions)
        switch (activeRole) {
            case PRIMARY:
                return this.primaryConnection;
            case REPLICA:
                return this.replicaConnection;
            case NONE:
            default:
                return this.primaryConnection;  // Fallback to primary if no role set (may be null)
        }
    }
    
    /**
     * Gets the JDBC connection for this session with lazy allocation support.
     * This method supports read/write splitting by allocating connections on-demand
     * based on the requested connection type.
     * 
     * Uses double-checked locking for thread-safe lazy initialization:
     * - First check (unlocked) for performance when connection already exists
     * - Lock acquisition only if connection needs to be allocated
     * - Second check (locked) to prevent race conditions
     * - Connection allocation happens only once per type
     * 
     * @param connectionType The type of connection to get (PRIMARY or READ_REPLICA)
     * @return the JDBC connection, or null if allocation is not possible
     * @throws SQLException if connection allocation fails
     */
    public Connection getConnection(ConnectionType connectionType) throws SQLException {
        // For XA sessions, always use primary connection (XA doesn't support read/write splitting)
        if (isXA) {
            if (backendSession != null && backendSession instanceof org.openjproxy.xa.pool.XABackendSession) {
                org.openjproxy.xa.pool.XABackendSession xaBackendSession = 
                    (org.openjproxy.xa.pool.XABackendSession) backendSession;
                return xaBackendSession.getConnection();
            }
            return getConnection();  // Fall back to standard getConnection() for XA
        }
        
        if (connectionType == ConnectionType.READ_REPLICA) {
            // Double-checked locking for replica connection
            if (replicaConnection != null && !isClosed(replicaConnection)) {
                return replicaConnection;  // Fast path - connection already exists
            }
            
            replicaLock.lock();
            try {
                // Second check after acquiring lock
                if (replicaConnection != null && !isClosed(replicaConnection)) {
                    return replicaConnection;
                }
                
                // Lazy allocation: allocate replica connection now
                replicaConnection = allocateReplicaConnectionLazy();
                if (replicaConnection != null) {
                    activeRole = ConnectionRole.REPLICA;
                    log.debug("Lazy-allocated replica connection for session {}", sessionUUID);
                    return replicaConnection;
                }
                
                // Fallback to primary if replica allocation fails
                log.debug("Replica allocation failed, falling back to primary for session {}", sessionUUID);
                return getConnection(ConnectionType.PRIMARY);
            } finally {
                replicaLock.unlock();
            }
        } else {
            // PRIMARY connection type (default)
            // Double-checked locking for primary connection
            if (primaryConnection != null && !isClosed(primaryConnection)) {
                return primaryConnection;  // Fast path - connection already exists
            }
            
            primaryLock.lock();
            try {
                // Second check after acquiring lock
                if (primaryConnection != null && !isClosed(primaryConnection)) {
                    return primaryConnection;
                }
                
                // Lazy allocation: allocate primary connection now
                primaryConnection = allocatePrimaryConnectionLazy();
                if (primaryConnection != null) {
                    activeRole = ConnectionRole.PRIMARY;
                    log.debug("Lazy-allocated primary connection for session {}", sessionUUID);
                }
                return primaryConnection;
            } finally {
                primaryLock.unlock();
            }
        }
    }
    
    /**
     * Lazy-allocates a primary connection from the datasource.
     * This method is called from within a lock, so it doesn't need additional synchronization.
     * 
     * @return the allocated connection, or null if allocation fails
     * @throws SQLException if connection allocation fails
     */
    private Connection allocatePrimaryConnectionLazy() throws SQLException {
        if (datasourceMap == null || connectionHash == null) {
            log.warn("Cannot lazy-allocate primary connection: datasourceMap or connectionHash is null");
            return null;
        }
        
        DataSource dataSource = datasourceMap.get(connectionHash);
        if (dataSource == null) {
            log.error("DataSource not found for connectionHash: {}", connectionHash);
            throw new SQLException("DataSource not found for connectionHash: " + connectionHash);
        }
        
        Connection conn = dataSource.getConnection();
        log.debug("Allocated primary connection from datasource {} for session {}", connectionHash, sessionUUID);
        return conn;
    }
    
    /**
     * Lazy-allocates a replica connection from a read replica datasource.
     * This method is called from within a lock, so it doesn't need additional synchronization.
     * 
     * @return the allocated connection, or null if no replicas are available
     * @throws SQLException if connection allocation fails
     */
    private Connection allocateReplicaConnectionLazy() throws SQLException {
        if (readWriteRegistry == null || connectionHash == null) {
            log.debug("Cannot allocate replica: readWriteRegistry or connectionHash is null");
            return null;
        }
        
        // Check if read/write splitting is enabled for this datasource
        if (!readWriteRegistry.isReadWriteSplittingEnabled(connectionHash)) {
            log.debug("Read/write splitting not enabled for connectionHash: {}", connectionHash);
            return null;
        }
        
        // Get list of replica datasource names
        java.util.List<String> replicaNames = readWriteRegistry.getReplicaNames(connectionHash);
        if (replicaNames == null || replicaNames.isEmpty()) {
            log.debug("No replicas available for connectionHash: {}", connectionHash);
            return null;
        }
        
        // Select a replica using round-robin
        String selectedReplica = replicaNames.get(0);  // Simplified - just use first replica
        if (replicaNames.size() > 1) {
            // Use round-robin selection based on session UUID hashcode
            int index = Math.abs(sessionUUID.hashCode() % replicaNames.size());
            selectedReplica = replicaNames.get(index);
        }
        
        DataSource replicaDataSource = datasourceMap.get(selectedReplica);
        if (replicaDataSource == null) {
            log.error("Replica DataSource not found: {}", selectedReplica);
            return null;
        }
        
        Connection conn = replicaDataSource.getConnection();
        log.debug("Allocated replica connection from {} for session {}", selectedReplica, sessionUUID);
        return conn;
    }
    
    /**
     * Allocates a primary connection for this session.
     * This method should be called when a write operation is executed or a transaction begins.
     * 
     * @param conn the primary connection to allocate
     * @throws IllegalStateException if a primary connection is already allocated
     */
    public synchronized void allocatePrimaryConnection(Connection conn) {
        if (this.primaryConnection != null && !isClosed(this.primaryConnection)) {
            log.debug("Primary connection already allocated for session {}", sessionUUID);
            return;  // Already have a primary connection
        }
        this.primaryConnection = conn;
        this.activeRole = ConnectionRole.PRIMARY;
        log.debug("Allocated primary connection for session {}", sessionUUID);
    }
    
    /**
     * Allocates a replica connection for this session.
     * This method should be called when a SELECT query is executed outside of a transaction.
     * If a primary connection already exists, this method does nothing (session stays on primary).
     * 
     * @param conn the replica connection to allocate
     */
    public synchronized void allocateReplicaConnection(Connection conn) {
        if (this.primaryConnection != null && !isClosed(this.primaryConnection)) {
            log.debug("Primary connection already exists, not allocating replica for session {}", sessionUUID);
            return;  // Once we have a primary, stay with primary
        }
        if (this.replicaConnection != null && !isClosed(this.replicaConnection)) {
            log.debug("Replica connection already allocated for session {}", sessionUUID);
            return;  // Already have a replica connection
        }
        this.replicaConnection = conn;
        this.activeRole = ConnectionRole.REPLICA;
        log.debug("Allocated replica connection for session {}", sessionUUID);
    }
    
    /**
     * Clears the primary connection reference without closing it.
     * This is used when discarding an unused primary connection that will be replaced
     * by a replica connection.
     */
    public synchronized void clearPrimaryConnection() {
        this.primaryConnection = null;
        this.activeRole = ConnectionRole.NONE;
        log.debug("Cleared primary connection for session {}", sessionUUID);
    }
    
    /**
     * Switches the active connection to primary.
     * This should be called when entering a transaction or executing a write operation.
     * The replica connection (if any) remains open but becomes inactive.
     */
    public synchronized void switchToPrimary() {
        if (this.activeRole != ConnectionRole.PRIMARY) {
            this.activeRole = ConnectionRole.PRIMARY;
            log.debug("Switched to primary connection for session {}", sessionUUID);
        }
    }
    
    /**
     * Switches the active connection to replica (if one exists and conditions allow).
     * This should be called after a transaction commits and sticky session expires.
     * 
     * @return true if switched to replica, false if staying on primary
     */
    public synchronized boolean switchToReplicaIfAvailable() {
        if (this.replicaConnection != null && !isClosed(this.replicaConnection)) {
            this.activeRole = ConnectionRole.REPLICA;
            log.debug("Switched to replica connection for session {}", sessionUUID);
            return true;
        }
        return false;
    }
    
    /**
     * Closes the replica connection if it exists.
     * This should be called when the replica is no longer needed (e.g., session switching to primary permanently).
     */
    public synchronized void closeReplicaConnection() {
        if (this.replicaConnection != null) {
            try {
                if (!this.replicaConnection.isClosed()) {
                    this.replicaConnection.close();
                    log.debug("Closed replica connection for session {}", sessionUUID);
                }
            } catch (SQLException e) {
                log.warn("Failed to close replica connection for session {}: {}", sessionUUID, e.getMessage());
            } finally {
                this.replicaConnection = null;
                if (this.activeRole == ConnectionRole.REPLICA) {
                    this.activeRole = ConnectionRole.PRIMARY;
                }
            }
        }
    }
    
    /**
     * Checks if a connection is closed.
     * 
     * @param conn the connection to check
     * @return true if closed or null, false otherwise
     */
    private boolean isClosed(Connection conn) {
        if (conn == null) {
            return true;
        }
        try {
            return conn.isClosed();
        } catch (SQLException e) {
            log.warn("Error checking if connection is closed: {}", e.getMessage());
            return true;  // Assume closed if we can't check
        }
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
    
    // ========== Read/Write Splitting Methods ==========
    
    /**
     * Marks the session as being in a transaction.
     * This is called when autoCommit is set to false or when an explicit transaction begins.
     */
    public void setInTransaction(boolean inTransaction) {
        this.inTransaction = inTransaction;
        log.debug("Session {} transaction state set to: {}", sessionUUID, inTransaction);
    }
    
    /**
     * Records that a write operation occurred in this session.
     * This updates the lastWriteTimestamp to enable sticky session behavior
     * (routing subsequent reads to primary for a period after writes).
     */
    public void recordWriteOperation() {
        this.lastWriteTimestamp = System.currentTimeMillis();
        log.debug("Session {} recorded write operation at {}", sessionUUID, lastWriteTimestamp);
    }
    
    /**
     * Checks if the session is in sticky mode (recent write occurred).
     * Sticky mode means reads should be routed to primary to avoid replication lag issues.
     * 
     * @param stickySessionMillis the duration in milliseconds to stick to primary after a write
     * @return true if within sticky period after last write, false otherwise
     */
    public boolean isInStickyMode(long stickySessionMillis) {
        if (lastWriteTimestamp == 0) {
            return false;  // No write has occurred
        }
        long timeSinceWrite = System.currentTimeMillis() - lastWriteTimestamp;
        return timeSinceWrite < stickySessionMillis;
    }
    
    /**
     * Checks if the session is in sticky mode using the default sticky session duration (5 seconds).
     * 
     * @return true if within default sticky period after last write, false otherwise
     */
    public boolean isInStickyMode() {
        return isInStickyMode(DEFAULT_STICKY_SESSION_MILLIS);
    }
    
    /**
     * Clears the sticky session state by resetting the last write timestamp.
     * This can be called to manually exit sticky mode.
     */
    public void clearStickySession() {
        this.lastWriteTimestamp = 0;
        log.debug("Session {} cleared sticky session state", sessionUUID);
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
        } else {
            // For regular connections, close both primary and replica
            if (primaryConnection != null) {
                try {
                    primaryConnection.close();
                } catch (SQLException e) {
                    log.error("Error closing primary connection", e);
                }
            }
            if (replicaConnection != null) {
                try {
                    replicaConnection.close();
                } catch (SQLException e) {
                    log.error("Error closing replica connection", e);
                }
            }
        }

        //Clear session internal objects to free memory
        this.closed = true;
        this.lobMap = null;
        this.resultSetMap = null;
        this.statementMap = null;
        this.preparedStatementMap = null;
        this.primaryConnection = null;
        this.replicaConnection = null;
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
        this.lastActivityTime = System.nanoTime();
    }

    /**
     * Checks if the session has been inactive for longer than the specified timeout.
     * 
     * @param timeoutMillis the inactivity timeout in milliseconds
     * @return true if the session has been inactive for longer than the timeout, false otherwise
     */
    public boolean isInactive(long timeoutMillis) {
        long inactiveDuration = (System.nanoTime() - this.lastActivityTime) / 1_000_000L;
        return inactiveDuration > timeoutMillis;
    }

    /**
     * Gets the duration in milliseconds since the last activity.
     * 
     * @return milliseconds since last activity
     */
    public long getInactiveDuration() {
        return (System.nanoTime() - this.lastActivityTime) / 1_000_000L;
    }
}
