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
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.cache.CacheConfiguration;
import org.openjproxy.grpc.server.cache.QueryCacheHelper;
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
        // Get or create session first
        ConnectionSessionDTO queryDto = sessionConnection(actionContext, dto.getSession(), true);
        
        // Use new persistent connection routing API
        // Route the query (repurposes connection to replica if first operation is SELECT)
        Connection conn = routeQueryWithPersistentConnection(actionContext, queryDto, sql, false);  // false = read operation
        
        // Update DTO with the routed connection
        queryDto = ConnectionSessionDTO.builder()
                .session(queryDto.getSession())
                .connection(conn)
                .dbName(queryDto.getDbName())
                .build();

        executeAndCleanup(actionContext, queryDto, sql, params, request, finalObserver);
    }
    
    private void executeAndCleanup(ActionContext actionContext, ConnectionSessionDTO queryDto, String sql,
                                   List<Parameter> params, StatementRequest request, 
                                   StreamObserver<OpResult> finalObserver) throws SQLException {
        PreparedStatement ps = null;
        Statement stmt = null;
        try {
            if (CollectionUtils.isNotEmpty(params)) {
                ps = StatementFactory.createPreparedStatement(actionContext.getSessionManager(), queryDto, sql, params, request);
                String resultSetUUID = actionContext.getSessionManager().registerResultSet(queryDto.getSession(), ps.executeQuery());
                handleResultSet(actionContext, queryDto.getSession(), resultSetUUID, finalObserver);
            } else {
                stmt = StatementFactory.createStatement(actionContext.getSessionManager(), queryDto.getConnection(), request);
                String resultSetUUID = actionContext.getSessionManager().registerResultSet(queryDto.getSession(), stmt.executeQuery(sql));
                handleResultSet(actionContext, queryDto.getSession(), resultSetUUID, finalObserver);
            }
        } finally {
            closeStatementResources(ps, stmt);
        }
    }
    
    private void closeStatementResources(PreparedStatement ps, Statement stmt) {
        if (ps != null) {
            try { ps.close(); } catch (SQLException e) { 
                log.warn("Failed to close PreparedStatement: {}", e.getMessage()); 
            }
        }
        if (stmt != null) {
            try { stmt.close(); } catch (SQLException e) { 
                log.warn("Failed to close Statement: {}", e.getMessage()); 
            }
        }
        // Note: Connection is now persistent in the session and should not be closed here
    }
}
