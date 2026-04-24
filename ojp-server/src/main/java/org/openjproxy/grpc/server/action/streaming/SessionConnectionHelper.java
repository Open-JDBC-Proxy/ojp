package org.openjproxy.grpc.server.action.streaming;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.server.ConnectionAcquisitionManager;
import org.openjproxy.grpc.server.ConnectionRole;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.PoolNotFoundException;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.UnpooledConnectionDetails;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry;
import org.openjproxy.grpc.server.readwrite.ReadWriteRouter;
import org.openjproxy.grpc.server.readwrite.RegexSqlClassifier;
import org.openjproxy.grpc.server.readwrite.RoundRobinReplicaSelector;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Utility class for managing database connections within gRPC streaming
 * sessions.
 * <p>
 * This helper implements lazy connection allocation, allowing connections to be
 * created
 * on-demand rather than eagerly during session initialization. It supports
 * multiple
 * connection modes:
 * </p>
 * <ul>
 * <li><b>Pooled connections:</b> Acquires connections from connection pools
 * (e.g., HikariCP)</li>
 * <li><b>Unpooled connections:</b> Creates direct connections via JDBC
 * DriverManager</li>
 * <li><b>XA connections:</b> Supports distributed transactions with both pooled
 * and unpooled modes</li>
 * </ul>
 * <p>
 * The class follows a reuse-first strategy: if a session already has an active
 * connection,
 * it will be reused. Otherwise, a new connection is allocated based on the
 * session's
 * configuration (XA vs regular, pooled vs unpooled).
 * </p>
 * <p>
 * <b>Connection Lifecycle:</b>
 * </p>
 * <ul>
 * <li>Existing sessions with valid UUIDs will reuse their stored
 * connections</li>
 * <li>New sessions trigger lazy allocation based on connection hash and XA
 * flag</li>
 * <li>Connections are validated (checked for closed state) before reuse</li>
 * <li>XA connections store the underlying XAConnection object for transaction
 * management</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> This class provides static utility methods that operate
 * on
 * thread-local session managers and context objects. The thread safety depends
 * on the
 * underlying session manager and connection pool implementations.
 * </p>
 *
 * @author OpenJProxy
 * @since 1.0
 */
@Slf4j
public class SessionConnectionHelper {

    /** Shared SQL classifier – stateless and thread-safe. */
    private static final RegexSqlClassifier SQL_CLASSIFIER = new RegexSqlClassifier();

    /**
     * Shared round-robin replica selector – thread-safe ({@link java.util.concurrent.atomic.AtomicInteger}
     * counter) and intentionally shared so that the global round-robin counter distributes
     * load evenly across replicas for the whole server process.
     */
    private static final RoundRobinReplicaSelector REPLICA_SELECTOR = new RoundRobinReplicaSelector();

    /** Shared router built from the above singletons. */
    private static final ReadWriteRouter READ_WRITE_ROUTER =
            new ReadWriteRouter(SQL_CLASSIFIER, REPLICA_SELECTOR);

    /**
     * Private constructor
     */
    private SessionConnectionHelper() {
    }

    /**
     * Finds a suitable connection for the current sessionInfo.
     * If there is a connection already in the sessionInfo reuse it, if not get a
     * fresh one from the data source.
     * This method implements lazy connection allocation for both Hikari and
     * Atomikos XA datasources.
     *
     * @param context          the action context containing the session manager
     * @param sessionInfo        - current sessionInfo object.
     * @param startSessionIfNone - if true will start a new sessionInfo if none
     *                           exists.
     * @return ConnectionSessionDTO
     * @throws SQLException if connection not found or closed (by timeout or other
     *                      reason)
     */
    public static ConnectionSessionDTO sessionConnection(ActionContext context, SessionInfo sessionInfo,
                                                         boolean startSessionIfNone)
            throws SQLException {
        ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder = ConnectionSessionDTO.builder();
        dtoBuilder.session(sessionInfo);
        Connection conn;
        var sessionManager = context.getSessionManager();

        if (StringUtils.isNotEmpty(sessionInfo.getSessionUUID())) {
            // Session already exists, reuse its connection
            conn = sessionManager.getConnection(sessionInfo);
            if (conn == null) {
                throw new SQLException("Connection not found for this sessionInfo");
            }
            dtoBuilder.dbName(DatabaseUtils.resolveDbName(conn.getMetaData().getURL()));
            if (conn.isClosed()) {
                throw new SQLException("Connection is closed");
            }
        } else {
            // Lazy allocation: check if this is an XA or regular connection
            String connHash = sessionInfo.getConnHash();
            boolean isXA = sessionInfo.getIsXA();

            if (isXA) {
                // XA connection - check if unpooled or pooled mode
                XADataSource xaDataSource = context.getXaDataSourceMap().get(connHash);

                if (xaDataSource != null) {
                    // Unpooled XA mode: create XAConnection on demand
                    try {
                        log.debug("Creating unpooled XAConnection for hash: {}", connHash);
                        XAConnection xaConnection = xaDataSource.getXAConnection();
                        conn = xaConnection.getConnection();

                        // Store the XAConnection in session for XA operations
                        if (startSessionIfNone) {
                            SessionInfo updatedSession = sessionManager.createSession(sessionInfo.getClientUUID(),
                                    conn);
                            // Store XAConnection as an attribute for XA operations
                            sessionManager.registerAttr(updatedSession, "xaConnection", xaConnection);
                            dtoBuilder.session(updatedSession);
                        }
                        log.debug("Successfully created unpooled XAConnection for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to create unpooled XAConnection for hash: {}. Error: {}",
                                connHash, e.getMessage());
                        throw e;
                    }
                } else {
                    // Pooled XA mode - should already have a session created in connect()
                    // This shouldn't happen as XA sessions are created eagerly
                    throw new SQLException("XA session should already exist. Session UUID is missing.");
                }
            } else {
                // Regular connection - check if pooled or unpooled mode
                UnpooledConnectionDetails unpooledDetails = context.getUnpooledConnectionDetailsMap().get(connHash);

                if (unpooledDetails != null) {
                    // Unpooled mode: create direct connection without pooling
                    try {
                        log.debug("Creating unpooled (passthrough) connection for hash: {}", connHash);
                        conn = java.sql.DriverManager.getConnection(
                                unpooledDetails.getUrl(),
                                unpooledDetails.getUsername(),
                                unpooledDetails.getPassword());
                        log.debug("Successfully created unpooled connection for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to create unpooled connection for hash: {}. Error: {}",
                                connHash, e.getMessage());
                        throw e;
                    }
                } else {
                    // Pooled mode: acquire from datasource (HikariCP by default)
                    DataSource dataSource = context.getDatasourceMap().get(connHash);
                    if (dataSource == null) {
                        // Signal the client to reconnect. NOT_FOUND is caught by
                        // CommandExecutionHelper and translated to Status.NOT_FOUND so that the
                        // driver can transparently reconnect and retry the SQL call.
                        throw new PoolNotFoundException(connHash);
                    }

                    try {
                        // Use enhanced connection acquisition with timeout protection
                        conn = ConnectionAcquisitionManager.acquireConnection(dataSource, connHash);
                        log.debug("Successfully acquired connection from pool for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to acquire connection from pool for hash: {}. Error: {}",
                                connHash, e.getMessage());

                        // Re-throw the enhanced exception from ConnectionAcquisitionManager
                        throw e;
                    }
                }

                if (startSessionIfNone) {
                    // Keep connectionHashMap consistent with the connHash the client sent.
                    // Non-XA connect() calls may be served from a local cache in the driver
                    // (MultinodeConnectionManager.connHashByConnectionKey) without issuing a
                    // real gRPC RPC, so registerClientUUID is never called on the server and
                    // connectionHashMap may hold a stale connHash from a prior connection that
                    // used the same clientUUID (e.g. the replica connect in setupDatabases()).
                    // Syncing here ensures createSession always uses the correct connHash.
                    if (StringUtils.isNotEmpty(sessionInfo.getConnHash())) {
                        sessionManager.registerClientUUID(sessionInfo.getConnHash(), sessionInfo.getClientUUID());
                    }
                    SessionInfo updatedSession = sessionManager.createSession(sessionInfo.getClientUUID(), conn);
                    dtoBuilder.session(updatedSession);
                }
            }
        }
        dtoBuilder.connection(conn);

        return dtoBuilder.build();
    }

    /**
     * Determines the connection to use for executing a query, applying
     * read/write splitting if configured and conditions allow.
     *
     * <p>This method manages persistent connections in the session (not temporary connections).
     * It allocates and switches between primary and replica connections based on:
     * <ul>
     *   <li>Read/write splitting configuration (replicas registered for this connection hash)</li>
     *   <li>Transaction state (inside transaction → primary)</li>
     *   <li>SQL operation type (READ vs WRITE)</li>
     *   <li>Sticky session status (recent write → primary for consistency)</li>
     * </ul>
     *
     * <p><b>Connection Lifecycle:</b>
     * <ul>
     *   <li>First SELECT (outside transaction) → allocates replica connection, session uses it</li>
     *   <li>First WRITE operation → allocates primary connection, session switches to it</li>
     *   <li>Transaction begins → ensures primary allocated, switches to it</li>
     *   <li>Connections persist for session lifetime (no temporary connections)</li>
     * </ul>
     *
     * <p><b>DB2 / SQL Server exclusion:</b> These databases use row-by-row LOB streaming
     * ({@code RESULT_SET_ROW_BY_ROW_MODE}), which requires persistent connections.
     * Routing is supported for these databases as connections are now persistent.
     *
     * @param context the action context providing the registry and datasource map
     * @param dto     the session connection DTO obtained from {@link #sessionConnection}
     * @param sql     the SQL statement to execute
     * @param isWrite true if this is a write operation (INSERT/UPDATE/DELETE), false for reads
     * @return the connection to use (from session's primary or replica slot), never null
     * @throws SQLException if unable to allocate required connection
     */
    public static Connection routeQueryWithPersistentConnection(ActionContext context, ConnectionSessionDTO dto, 
                                                                 String sql, boolean isWrite) throws SQLException {
        Session session = context.getSessionManager().getSession(dto.getSession());
        
        // XA connections are always transactional – always use primary
        if (session.isXA()) {
            ensurePrimaryConnectionAllocated(context, session, dto);
            return session.getConnection();
        }

        // If in transaction, always use primary
        if (session.isInTransaction()) {
            ensurePrimaryConnectionAllocated(context, session, dto);
            session.switchToPrimary();
            return session.getConnection();
        }

        // If this is a write operation, allocate/switch to primary and record write
        if (isWrite) {
            ensurePrimaryConnectionAllocated(context, session, dto);
            session.switchToPrimary();
            session.recordWriteOperation();
            // Close replica connection as we're now committed to primary
            session.closeReplicaConnection();
            return session.getConnection();
        }

        // For read operations (SELECT), check if we can route to replica
        ReadWriteDataSourceRegistry registry = context.getReadWriteDataSourceRegistry();
        if (registry == null) {
            // No read/write splitting configured, use primary
            ensurePrimaryConnectionAllocated(context, session, dto);
            return session.getConnection();
        }

        String connHash = session.getConnectionHash();
        String primaryName = registry.getPrimaryName(connHash);
        if (primaryName == null) {
            // Read/write splitting not configured for this connection
            ensurePrimaryConnectionAllocated(context, session, dto);
            return session.getConnection();
        }

        List<DataSource> replicas = registry.getReplicas(primaryName);
        if (replicas.isEmpty()) {
            // No replicas registered
            ensurePrimaryConnectionAllocated(context, session, dto);
            return session.getConnection();
        }

        DataSource primaryDs = context.getDatasourceMap().get(connHash);
        if (primaryDs == null) {
            // Primary DataSource not found (e.g. unpooled mode)
            ensurePrimaryConnectionAllocated(context, session, dto);
            return session.getConnection();
        }

        // Sticky session check: if a write was executed recently, use primary
        int stickySeconds = registry.getStickySessionSeconds(primaryName);
        if (stickySeconds > 0 && session.isInStickyMode(stickySeconds * 1000L)) {
            log.debug("Sticky session active ({}s), using primary for connHash={}", stickySeconds, connHash);
            ensurePrimaryConnectionAllocated(context, session, dto);
            session.switchToPrimary();
            return session.getConnection();
        }

        // Use ReadWriteRouter to decide primary vs replica based on SQL classification
        DataSource selectedDs = READ_WRITE_ROUTER.selectDataSource(session, sql, primaryDs, replicas);

        if (selectedDs == primaryDs) {
            // Router chose primary (e.g., non-SELECT statement)
            ensurePrimaryConnectionAllocated(context, session, dto);
            session.switchToPrimary();
            return session.getConnection();
        }

        // Router chose a replica – allocate replica connection if needed
        try {
            ensureReplicaConnectionAllocated(context, session, selectedDs);
            log.debug("Read/write routing: using replica for SELECT, connHash={}", connHash);
            return session.getConnection();
        } catch (SQLException e) {
            log.warn("Failed to allocate replica connection, falling back to primary: {}", e.getMessage());
            ensurePrimaryConnectionAllocated(context, session, dto);
            session.switchToPrimary();
            return session.getConnection();
        }
    }

    /**
     * Ensures the session has a primary connection allocated.
     * If not already allocated, acquires one from the primary DataSource.
     *
     * @param context the action context
     * @param session the session
     * @param dto     the connection DTO (may contain existing connection)
     * @throws SQLException if unable to allocate connection
     */
    public static void ensurePrimaryConnectionAllocated(ActionContext context, Session session, 
                                                         ConnectionSessionDTO dto) throws SQLException {
        Connection currentConn = session.getConnection();
        if (currentConn != null && !currentConn.isClosed() && session.getActiveRole() == ConnectionRole.PRIMARY) {
            return;  // Already have a valid primary connection
        }

        // Need to allocate primary connection
        String connHash = session.getConnectionHash();
        DataSource primaryDs = context.getDatasourceMap().get(connHash);
        if (primaryDs == null) {
            throw new SQLException("Primary DataSource not found for connHash=" + connHash);
        }

        Connection primaryConn = primaryDs.getConnection();
        session.allocatePrimaryConnection(primaryConn);
        log.debug("Allocated primary connection for session {}", session.getSessionUUID());
    }

    /**
     * Ensures the session has a replica connection allocated (if primary not already present).
     * If primary exists, does nothing (session stays on primary).
     *
     * @param context    the action context
     * @param session    the session
     * @param replicaDs  the replica DataSource to use
     * @throws SQLException if unable to allocate connection
     */
    private static void ensureReplicaConnectionAllocated(ActionContext context, Session session, 
                                                          DataSource replicaDs) throws SQLException {
        Connection currentConn = session.getConnection();
        if (currentConn != null && !currentConn.isClosed()) {
            // Session already has a connection
            if (session.getActiveRole() == ConnectionRole.REPLICA) {
                return;  // Already using replica
            } else if (session.getActiveRole() == ConnectionRole.PRIMARY) {
                // Primary exists – don't allocate replica, stay on primary
                return;
            }
        }

        // Allocate replica connection
        Connection replicaConn = replicaDs.getConnection();
        session.allocateReplicaConnection(replicaConn);
        log.debug("Allocated replica connection for session {}", session.getSessionUUID());
    }
    
    /**
     * Legacy method for backward compatibility.
     * Returns null to indicate caller should use session connection.
     * 
     * @deprecated Use {@link #routeQueryWithPersistentConnection} instead
     */
    @Deprecated
    public static Connection routeQueryToReplica(ActionContext context, ConnectionSessionDTO dto, String sql) {
        // Always return null – forces caller to use session connection
        // This maintains backward compatibility while we migrate to the new model
        return null;
    }
}
