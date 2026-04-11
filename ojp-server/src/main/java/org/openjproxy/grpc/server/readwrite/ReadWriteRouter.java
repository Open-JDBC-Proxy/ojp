package org.openjproxy.grpc.server.readwrite;

import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.readwrite.SqlClassifier.SqlOperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;

/**
 * Routes SQL queries to either primary or replica datasources based on:
 * <ul>
 *   <li>Transaction state: In-transaction queries always go to primary</li>
 *   <li>SQL operation type: READ queries go to replicas, WRITE/UNKNOWN go to primary</li>
 *   <li>Replica health: Falls back to primary if all replicas are unavailable</li>
 * </ul>
 * 
 * <p>Thread-safe for concurrent routing decisions.
 */
public class ReadWriteRouter {
    
    private static final Logger log = LoggerFactory.getLogger(ReadWriteRouter.class);
    
    private final SqlClassifier sqlClassifier;
    private final ReplicaSelector replicaSelector;
    
    /**
     * Create a router with specified classifier and replica selector.
     * 
     * @param sqlClassifier Classifier for determining SQL operation type
     * @param replicaSelector Strategy for selecting replicas from the pool
     */
    public ReadWriteRouter(SqlClassifier sqlClassifier, ReplicaSelector replicaSelector) {
        if (sqlClassifier == null) {
            throw new IllegalArgumentException("sqlClassifier cannot be null");
        }
        if (replicaSelector == null) {
            throw new IllegalArgumentException("replicaSelector cannot be null");
        }
        this.sqlClassifier = sqlClassifier;
        this.replicaSelector = replicaSelector;
    }
    
    /**
     * Select appropriate datasource for the given SQL statement.
     * 
     * <p>Routing logic:
     * <ol>
     *   <li>If in transaction → use primary (transaction consistency)</li>
     *   <li>Classify SQL statement (READ, WRITE, or UNKNOWN)</li>
     *   <li>For READ: try healthy replica, fallback to primary if all unavailable</li>
     *   <li>For WRITE/UNKNOWN: use primary</li>
     * </ol>
     * 
     * @param session Current session (may be null for connection establishment)
     * @param sql SQL statement to execute
     * @param primaryDataSource Primary datasource
     * @param replicaDataSources Available replica datasources (may be empty)
     * @return Selected datasource (primary or replica)
     */
    public DataSource selectDataSource(
            Session session,
            String sql,
            DataSource primaryDataSource,
            List<DataSource> replicaDataSources
    ) {
        if (primaryDataSource == null) {
            throw new IllegalArgumentException("primaryDataSource cannot be null");
        }
        
        // 1. Check if in transaction → always use primary for consistency
        if (session != null && isInTransaction(session)) {
            log.debug("In transaction, routing to primary");
            return primaryDataSource;
        }
        
        // 2. If no replicas configured, always use primary
        if (replicaDataSources == null || replicaDataSources.isEmpty()) {
            log.debug("No replicas configured, routing to primary");
            return primaryDataSource;
        }
        
        // 3. Classify SQL statement
        SqlOperationType operationType = sqlClassifier.classify(sql);
        log.debug("SQL classified as: {}", operationType);
        
        // 4. Route based on operation type
        if (operationType == SqlOperationType.READ) {
            // Try to get a healthy replica
            DataSource replica = replicaSelector.selectHealthyReplica(replicaDataSources);
            if (replica != null) {
                log.debug("READ operation, routing to replica");
                return replica;
            }
            
            // All replicas unavailable, fallback to primary
            log.warn("All replicas unavailable for READ operation, falling back to primary");
            return primaryDataSource;
        }
        
        // 5. For WRITE or UNKNOWN, use primary
        log.debug("{} operation, routing to primary", operationType);
        return primaryDataSource;
    }
    
    /**
     * Check if session is currently in a transaction.
     * 
     * @param session Session to check
     * @return true if in transaction, false otherwise
     */
    private boolean isInTransaction(Session session) {
        // When auto-commit is false, we're in a transaction
        try {
            java.sql.Connection conn = session.getConnection();
            if (conn != null) {
                return !conn.getAutoCommit();
            }
            return false;
        } catch (Exception e) {
            // If we can't determine transaction state, assume in transaction (safe default)
            log.warn("Unable to determine transaction state, assuming in transaction: {}", e.getMessage());
            return true;
        }
    }
}
