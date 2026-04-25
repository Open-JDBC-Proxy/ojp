package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.ConnectionType;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.cache.CacheConfiguration;
import org.openjproxy.grpc.server.cache.QueryCacheHelper;
import org.openjproxy.grpc.server.readwrite.ReadWriteDataSourceRegistry;
import org.openjproxy.grpc.server.statement.StatementFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.openjproxy.grpc.server.action.session.ResultSetHelper.handleResultSet;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.routeQueryWithPersistentConnection;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;
import static org.openjproxy.grpc.server.action.transaction.CommandExecutionHelper.executeWithResilience;

@Slf4j
public class ExecuteQueryAction implements Action<StatementRequest, OpResult> {

    private static final ExecuteQueryAction INSTANCE = new ExecuteQueryAction();

    /**
     * Private constructor for singleton.
     */
    private ExecuteQueryAction() {
    }

    /**
     * Returns the singleton instance of this action.
     *
     * @return the singleton instance
     */
    public static ExecuteQueryAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());

        executeWithResilience(context, request, responseObserver, () ->
                        executeQueryInternal(context, request, responseObserver),
                null, "query");
    }

    /**
     * Internal method for executing queries without segregation logic.
     */
    private void executeQueryInternal(ActionContext actionContext, StatementRequest request, StreamObserver<OpResult> responseObserver)
            throws SQLException {

        ConnectionSessionDTO dto = sessionConnection(actionContext, request.getSession(), true);
        String sql = request.getSql();
        CacheConfiguration cacheConfig = QueryCacheHelper.getCacheConfiguration(actionContext, dto.getSession());
        List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
        
        // Try cache lookup first
        if (tryCacheLookup(cacheConfig, sql, params, dto.getSession().getConnHash(), responseObserver)) {
            return;  // Cache hit - early return
        }

        // Enhance SQL if enabled
        sql = enhanceSqlIfEnabled(actionContext, dto, sql, request);
        
        // Wrap observer for cache storage
        StreamObserver<OpResult> finalObserver = QueryCacheHelper.wrapWithCaching(
                responseObserver, cacheConfig, sql, params, dto.getSession().getConnHash());

        // Route to replica if applicable and execute query
        executeWithRouting(actionContext, dto, sql, params, request, finalObserver);
    }
    
    private boolean tryCacheLookup(CacheConfiguration cacheConfig, String sql, List<Parameter> params, 
                                   String datasourceName, StreamObserver<OpResult> responseObserver) {
        if (cacheConfig == null || !cacheConfig.isEnabled()) {
            return false;
        }
        
        try {
            com.openjproxy.grpc.OpQueryResultProto cachedProto = QueryCacheHelper.getCachedResult(
                    cacheConfig, sql, params, datasourceName);
            
            if (cachedProto != null) {
                OpResult result = OpResult.newBuilder().setQueryResult(cachedProto).build();
                responseObserver.onNext(result);
                responseObserver.onCompleted();
                return true;
            }
        } catch (Exception e) {
            log.error("Cache lookup failed, falling back to database: datasource={}, sql={}, error={}", 
                    datasourceName, sql.substring(0, Math.min(sql.length(), 50)), e.getMessage());
        }
        return false;
    }
    
    private String enhanceSqlIfEnabled(ActionContext actionContext, ConnectionSessionDTO dto, 
                                       String sql, StatementRequest request) throws SQLException {
        var sqlEnhancerEngine = actionContext.getSqlEnhancerEngine();
        if (!sqlEnhancerEngine.isEnabled()) {
            return sql;
        }
        
        long startTime = System.nanoTime();
        ensureSchemaLoadedForEnhancement(actionContext, dto);
        
        org.openjproxy.grpc.server.sql.SqlEnhancementResult result = sqlEnhancerEngine.enhance(sql);
        logEnhancementResult(result, request.getSql(), startTime);
        
        return result.getEnhancedSql();
    }
    
    private void ensureSchemaLoadedForEnhancement(ActionContext actionContext, ConnectionSessionDTO dto) {
        try {
            String dsKey = dto.getSession().getConnHash();
            DataSource dataSource = actionContext.getDatasourceMap().get(dsKey);
            
            if (dataSource != null) {
                Connection connection = dto.getConnection();
                String catalogName = connection.getCatalog();
                String schemaName = getSchemaNameForPostgreSQL(connection);
                actionContext.getSqlEnhancerEngine().ensureSchemaLoaded(dataSource, catalogName, schemaName);
            } else {
                log.debug("No DataSource found for connection hash: {}", dsKey);
            }
        } catch (Exception e) {
            log.warn("Failed to ensure schema loaded: {}", e.getMessage());
        }
    }
    
    private String getSchemaNameForPostgreSQL(Connection connection) throws SQLException {
        String schemaName = connection.getSchema();
        if ((schemaName == null || schemaName.isEmpty()) &&
                connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")) {
            log.debug("Using default PostgreSQL 'public' schema for schema loading");
            return "public";
        }
        return schemaName;
    }
    
    private void logEnhancementResult(org.openjproxy.grpc.server.sql.SqlEnhancementResult result, 
                                      String originalSql, long startTimeNanos) {
        long durationMs = (System.nanoTime() - startTimeNanos) / 1_000_000L;
        
        if (result.isModified()) {
            log.debug("SQL was enhanced in {}ms: {} -> {}", durationMs,
                    originalSql.substring(0, Math.min(originalSql.length(), 50)),
                    result.getEnhancedSql().substring(0, Math.min(result.getEnhancedSql().length(), 50)));
        } else if (durationMs > 10) {
            log.debug("SQL enhancement took {}ms (no modifications)", durationMs);
        }
    }
    
    private void executeWithRouting(ActionContext actionContext, ConnectionSessionDTO dto, String sql,
                                    List<Parameter> params, StatementRequest request, 
                                    StreamObserver<OpResult> finalObserver) throws SQLException {
        // Determine connection type BEFORE allocating connection
        ConnectionType connectionType = determineConnectionType(actionContext, dto, sql);
        
        // Get or create session with the appropriate connection type
        ConnectionSessionDTO queryDto = sessionConnection(actionContext, dto.getSession(), true, connectionType);
        
        // Use new persistent connection routing API
        // Route the query (manages connection switching if needed)
        Connection conn = routeQueryWithPersistentConnection(actionContext, queryDto, sql, false);  // false = read operation
        
        // Update DTO with the routed connection
        queryDto = ConnectionSessionDTO.builder()
                .session(queryDto.getSession())
                .connection(conn)
                .dbName(queryDto.getDbName())
                .build();

        executeAndCleanup(actionContext, queryDto, sql, params, request, finalObserver);
    }
    
    /**
     * Determines the appropriate connection type for a query operation.
     * Connection type is READ_REPLICA if:
     * <ul>
     *   <li>This is a SELECT query</li>
     *   <li>Not in a transaction</li>
     *   <li>Not in sticky session window (after recent write)</li>
     *   <li>Read/write splitting is configured and replicas are available</li>
     * </ul>
     * Otherwise, connection type is PRIMARY.
     *
     * @param actionContext the action context
     * @param dto the connection session DTO
     * @param sql the SQL query to execute
     * @return ConnectionType.READ_REPLICA if query can route to replica, PRIMARY otherwise
     */
    private ConnectionType determineConnectionType(ActionContext actionContext, ConnectionSessionDTO dto, String sql) {
        // If session doesn't exist yet, check routing configuration
        if (dto.getSession() == null || dto.getSession().getSessionUUID().isEmpty()) {
            // New session - check if read/write splitting is configured
            ReadWriteDataSourceRegistry registry = actionContext.getReadWriteDataSourceRegistry();
            if (registry == null) {
                return ConnectionType.PRIMARY;
            }
            
            String connHash = dto.getSession().getConnHash();
            String primaryName = registry.getPrimaryName(connHash);
            if (primaryName == null || !registry.hasReplicas(primaryName)) {
                // No read/write splitting configured for this connection
                return ConnectionType.PRIMARY;
            }
            
            // Check if SQL is a SELECT query (simple check - will be refined by router later)
            if (sql != null && sql.trim().toLowerCase().startsWith("select")) {
                return ConnectionType.READ_REPLICA;
            }
            
            return ConnectionType.PRIMARY;
        }
        
        // Session exists - check session state
        Session session = actionContext.getSessionManager().getSession(dto.getSession());
        if (session == null) {
            return ConnectionType.PRIMARY;
        }
        
        // If in transaction, always use primary
        if (session.isInTransaction()) {
            return ConnectionType.PRIMARY;
        }
        
        // Check sticky session
        ReadWriteDataSourceRegistry registry = actionContext.getReadWriteDataSourceRegistry();
        if (registry != null) {
            String primaryName = registry.getPrimaryName(session.getConnectionHash());
            if (primaryName != null) {
                int stickySeconds = registry.getStickySessionSeconds(primaryName);
                if (stickySeconds > 0 && session.isInStickyMode(stickySeconds * 1000L)) {
                    // Sticky session active - use primary
                    return ConnectionType.PRIMARY;
                }
            }
        }
        
        // Check if replicas are available
        if (registry != null && registry.hasReplicas(registry.getPrimaryName(session.getConnectionHash()))) {
            // Check if SQL is a SELECT query
            if (sql != null && sql.trim().toLowerCase().startsWith("select")) {
                return ConnectionType.READ_REPLICA;
            }
        }
        
        return ConnectionType.PRIMARY;
    }
    
    private void executeAndCleanup(ActionContext actionContext, ConnectionSessionDTO queryDto, String sql,
                                   List<Parameter> params, StatementRequest request, 
                                   StreamObserver<OpResult> finalObserver) throws SQLException {
        PreparedStatement ps = null;
        Statement stmt = null;
        // Note: Do NOT close statements - they must remain open because the ResultSet is still in use
        // The session will manage statement lifecycle when the ResultSet is closed or session terminates
        if (CollectionUtils.isNotEmpty(params)) {
            ps = StatementFactory.createPreparedStatement(actionContext.getSessionManager(), queryDto, sql, params, request);
            String resultSetUUID = actionContext.getSessionManager().registerResultSet(queryDto.getSession(), ps.executeQuery());
            handleResultSet(actionContext, queryDto.getSession(), resultSetUUID, finalObserver);
        } else {
            stmt = StatementFactory.createStatement(actionContext.getSessionManager(), queryDto.getConnection(), request);
            String resultSetUUID = actionContext.getSessionManager().registerResultSet(queryDto.getSession(), stmt.executeQuery(sql));
            handleResultSet(actionContext, queryDto.getSession(), resultSetUUID, finalObserver);
        }
    }
}
