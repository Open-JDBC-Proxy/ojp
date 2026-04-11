package org.openjproxy.grpc.server.readwrite;

import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.Session;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Helper class for routing database queries to primary or replica datasources
 * based on SQL operation type and transaction state.
 * <p>
 * This class integrates with {@link ReadWriteRouter} to provide intelligent
 * routing decisions while maintaining transaction safety and sticky session
 * behavior.
 * </p>
 *
 * <h2>Routing Logic</h2>
 * <ul>
 *   <li><b>Transactions:</b> All queries within a transaction route to primary</li>
 *   <li><b>Sticky Sessions:</b> After writes, reads route to primary for configured duration</li>
 *   <li><b>READ queries:</b> Route to healthy replica (with failover to primary)</li>
 *   <li><b>WRITE queries:</b> Always route to primary</li>
 *   <li><b>UNKNOWN queries:</b> Route to primary (safe default)</li>
 * </ul>
 *
 * @author OpenJProxy
 * @since 0.5.0
 */
@Slf4j
public class ReadWriteRoutingHelper {

    private final ReadWriteDataSourceRegistry registry;
    private final SqlClassifier sqlClassifier;

    /**
     * Creates a new routing helper with the specified registry and classifier.
     *
     * @param registry the datasource registry containing primary and replica mappings
     * @param sqlClassifier the SQL classifier for determining operation types
     */
    public ReadWriteRoutingHelper(ReadWriteDataSourceRegistry registry, SqlClassifier sqlClassifier) {
        this.registry = registry;
        this.sqlClassifier = sqlClassifier;
    }

    /**
     * Routes a SQL query to the appropriate datasource (primary or replica).
     * <p>
     * This method determines the correct datasource based on:
     * </p>
     * <ul>
     *   <li>Current transaction state</li>
     *   <li>Sticky session state (recent writes)</li>
     *   <li>SQL operation type (READ/WRITE/UNKNOWN)</li>
     *   <li>Replica health status</li>
     * </ul>
     *
     * @param primaryDatasource the primary datasource
     * @param session the current session context
     * @param sql the SQL statement to execute
     * @return a connection from the selected datasource (primary or replica)
     * @throws SQLException if connection acquisition fails
     */
    public Connection routeQuery(DataSource primaryDatasource, Session session, String sql) throws SQLException {
        // If read/write splitting is not configured for this datasource, use primary
        String primaryDatasourceName = registry.getPrimaryDatasourceName(session.getConnHash());
        if (primaryDatasourceName == null) {
            log.debug("Read/write splitting not configured for session {}, using primary", 
                    session.getSessionUUID());
            return primaryDatasource.getConnection();
        }

        // Get read/write configuration
        ReadWriteConfiguration config = registry.getConfiguration(primaryDatasourceName);
        if (config == null || !config.isEnabled()) {
            log.debug("Read/write splitting disabled for datasource {}, using primary", 
                    primaryDatasourceName);
            return primaryDatasource.getConnection();
        }

        // Get replica list
        java.util.List<DataSource> replicas = registry.getReplicas(primaryDatasourceName);
        if (replicas == null || replicas.isEmpty()) {
            log.debug("No replicas configured for datasource {}, using primary", 
                    primaryDatasourceName);
            return primaryDatasource.getConnection();
        }

        // Create router with primary + replicas
        ReadWriteRouter router = new ReadWriteRouter(
                primaryDatasource,
                replicas,
                createReplicaSelector(config),
                sqlClassifier
        );

        // Route the query
        try {
            DataSource selectedDatasource = router.selectDataSource(sql, session);
            Connection conn = selectedDatasource.getConnection();
            
            // Log routing decision for debugging
            boolean isPrimary = (selectedDatasource == primaryDatasource);
            if (log.isDebugEnabled()) {
                SqlClassifier.SqlOperationType operationType = sqlClassifier.classify(sql);
                log.debug("Routed {} query to {} for session {}: {}", 
                        operationType,
                        isPrimary ? "PRIMARY" : "REPLICA",
                        session.getSessionUUID(),
                        sql.length() > 50 ? sql.substring(0, 50) + "..." : sql);
            }
            
            return conn;
        } catch (SQLException e) {
            log.error("Failed to route query, falling back to primary: {}", e.getMessage());
            return primaryDatasource.getConnection();
        }
    }

    /**
     * Creates a replica selector based on the configuration strategy.
     *
     * @param config the read/write configuration
     * @return a replica selector instance
     */
    private ReplicaSelector createReplicaSelector(ReadWriteConfiguration config) {
        // For now, we only support ROUND_ROBIN strategy
        // Future enhancement: support RANDOM, LEAST_CONNECTIONS, etc.
        return new RoundRobinReplicaSelector();
    }

    /**
     * Checks if read/write splitting is enabled for a given session.
     *
     * @param session the session to check
     * @return true if read/write splitting is enabled, false otherwise
     */
    public boolean isReadWriteSplittingEnabled(Session session) {
        String primaryDatasourceName = registry.getPrimaryDatasourceName(session.getConnHash());
        if (primaryDatasourceName == null) {
            return false;
        }

        ReadWriteConfiguration config = registry.getConfiguration(primaryDatasourceName);
        return config != null && config.isEnabled();
    }

    /**
     * Records a write operation in the session for sticky session tracking.
     * <p>
     * After a write, subsequent reads will route to primary for the configured
     * sticky session duration to avoid reading stale data from replicas.
     * </p>
     *
     * @param session the session that executed a write
     */
    public void recordWriteOperation(Session session) {
        session.recordWrite();
        log.debug("Recorded write operation for session {}, sticky mode activated", 
                session.getSessionUUID());
    }

    /**
     * Updates session transaction state when transaction boundaries are detected.
     *
     * @param session the session to update
     * @param inTransaction true if entering transaction, false if exiting
     */
    public void updateTransactionState(Session session, boolean inTransaction) {
        session.setInTransaction(inTransaction);
        log.debug("Updated transaction state for session {}: inTransaction={}", 
                session.getSessionUUID(), inTransaction);
    }
}
