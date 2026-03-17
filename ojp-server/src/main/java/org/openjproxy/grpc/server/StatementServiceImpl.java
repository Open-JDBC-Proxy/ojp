package org.openjproxy.grpc.server;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ReadLobRequest;
import com.openjproxy.grpc.ResultSetFetchRequest;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import com.openjproxy.grpc.StatementServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.action.resource.CallResourceAction;
import org.openjproxy.grpc.server.action.session.ResultSetHelper;
import org.openjproxy.grpc.server.action.session.TerminateSessionAction;
import org.openjproxy.grpc.server.action.transaction.CommitTransactionAction;
import org.openjproxy.grpc.server.action.transaction.RollbackTransactionAction;
import org.openjproxy.grpc.server.action.transaction.StartTransactionAction;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.action.xa.XaCommitAction;
import org.openjproxy.grpc.server.action.xa.XaEndAction;
import org.openjproxy.grpc.server.action.xa.XaPrepareAction;
import org.openjproxy.grpc.server.action.xa.XaRecoverAction;
import org.openjproxy.grpc.server.action.xa.XaRollbackAction;
import org.openjproxy.grpc.server.action.xa.XaStartAction;
import org.openjproxy.grpc.server.paging.CachedPage;
import org.openjproxy.grpc.server.paging.NextPagePrefetchCache;
import org.openjproxy.grpc.server.paging.PageInfo;
import org.openjproxy.grpc.server.paging.PaginationDetector;
import org.openjproxy.grpc.server.resultset.ResultSetWrapper;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.session.ResultSetHelper.handleResultSet;
import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;

@Slf4j
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {

    private final Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
    // XA Pool Provider for pooling XAConnections (loaded via SPI)
    private XAConnectionPoolProvider xaPoolProvider;
    private final SessionManager sessionManager;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    // Per-datasource slow query segregation managers
    private final Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers = new ConcurrentHashMap<>();

    // SQL Enhancer Engine for query optimization
    private final org.openjproxy.grpc.server.sql.SqlEnhancerEngine sqlEnhancerEngine;

    // Next-page prefetch cache for paginated queries (disabled by default)
    private final NextPagePrefetchCache nextPagePrefetchCache;
    private final ServerConfiguration serverConfiguration;

    // Multinode XA coordinator for distributing transaction limits
    private static final MultinodeXaCoordinator xaCoordinator = new MultinodeXaCoordinator();

    private final Map<String, DbName> dbNameMap = new ConcurrentHashMap<>();

    // ActionContext for refactored actions
    private final org.openjproxy.grpc.server.action.ActionContext actionContext;

    // SQL statement metrics (only used by executeQuery/executeUpdate)
    private final org.openjproxy.grpc.server.metrics.SqlStatementMetrics sqlStatementMetrics;

    public StatementServiceImpl(SessionManager sessionManager, CircuitBreakerRegistry circuitBreakerRegistry,
            ServerConfiguration serverConfiguration) {
        this.sessionManager = sessionManager;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.serverConfiguration = serverConfiguration;
        // Server configuration for creating segregation managers
        this.sqlEnhancerEngine = new org.openjproxy.grpc.server.sql.SqlEnhancerEngine(
                serverConfiguration.isSqlEnhancerEnabled());
        // Next-page prefetch cache (disabled by default)
        this.nextPagePrefetchCache = new NextPagePrefetchCache(
                serverConfiguration.isNextPageCacheEnabled(),
                serverConfiguration.getNextPageCacheMaxEntries(),
                serverConfiguration.getNextPageCacheTtlSeconds(),
                serverConfiguration.getNextPageCachePrefetchWaitTimeoutMs(),
                serverConfiguration.getNextPageCacheCleanupIntervalSeconds());
        initializeXAPoolProvider();

        // Create SQL statement metrics from the registered OpenTelemetry instance (if available)
        this.sqlStatementMetrics = createSqlStatementMetrics();

        // Initialize ActionContext with all shared state
        // Map for storing XADataSources (native database XADataSource, not Atomikos)
        Map<String, XADataSource> xaDataSourceMap = new ConcurrentHashMap<>();
        // XA Transaction Registries (one per connection hash for isolated transaction
        // management)
        Map<String, XATransactionRegistry> xaRegistries = new ConcurrentHashMap<>();
        // Unpooled connection details map (for passthrough mode when pooling is
        // disabled)
        Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap = new ConcurrentHashMap<>();
        // Cluster health tracker for monitoring health changes
        ClusterHealthTracker clusterHealthTracker = new ClusterHealthTracker();
        this.actionContext = new org.openjproxy.grpc.server.action.ActionContext(
                datasourceMap,
                xaDataSourceMap,
                xaRegistries,
                unpooledConnectionDetailsMap,
                dbNameMap,
                slowQuerySegregationManagers,
                xaPoolProvider,
                xaCoordinator,
                clusterHealthTracker,
                sessionManager,
                circuitBreakerRegistry,
                serverConfiguration,
                sqlStatementMetrics);
    }

    /**
     * Creates SQL statement metrics using the registered OpenTelemetry instance.
     * Falls back to no-op metrics when OpenTelemetry is unavailable.
     */
    private org.openjproxy.grpc.server.metrics.SqlStatementMetrics createSqlStatementMetrics() {
        io.opentelemetry.api.OpenTelemetry openTelemetry =
                org.openjproxy.xa.pool.commons.metrics.OpenTelemetryHolder.getInstance();
        if (openTelemetry != null) {
            log.info("OpenTelemetry instance available – enabling SQL statement metrics");
            return new org.openjproxy.grpc.server.metrics.OpenTelemetrySqlStatementMetrics(openTelemetry);
        }
        log.info("OpenTelemetry not available – SQL statement metrics disabled (no-op)");
        return org.openjproxy.grpc.server.metrics.NoOpSqlStatementMetrics.INSTANCE;
    }

    /**
     * Updates the last activity time for the session to prevent premature cleanup.
     * This should be called at the beginning of any method that operates on a session.
     *
     * @param sessionInfo the session information
     */
    private void updateSessionActivity(SessionInfo sessionInfo) {
        if (sessionInfo != null && !sessionInfo.getSessionUUID().isEmpty()) {
            sessionManager.updateSessionActivity(sessionInfo);
        }
    }

    /**
     * Initialize XA Pool Provider if XA pooling is enabled in configuration.
     * Loads the provider via ServiceLoader (Commons Pool 2 by default).
     */
    private void initializeXAPoolProvider() {
        // XA pooling is always enabled
        // Select the provider with the HIGHEST priority (100 = highest, 0 = lowest)

        try {
            ServiceLoader<XAConnectionPoolProvider> loader = ServiceLoader.load(XAConnectionPoolProvider.class);
            XAConnectionPoolProvider selectedProvider = null;
            int highestPriority = Integer.MIN_VALUE;

            for (XAConnectionPoolProvider provider : loader) {
                if (provider.isAvailable()) {
                    log.debug("Found available XA Pool Provider: {} (priority: {})",
                            provider.getClass().getName(), provider.getPriority());

                    if (provider.getPriority() > highestPriority) {
                        selectedProvider = provider;
                        highestPriority = provider.getPriority();
                    }
                }
            }

            if (selectedProvider != null) {
                this.xaPoolProvider = selectedProvider;
                log.info("Selected XA Pool Provider: {} (priority: {})",
                        selectedProvider.getClass().getName(), selectedProvider.getPriority());

                // Update ActionContext with initialized provider (if actionContext is already
                // created)
                if (this.actionContext != null) {
                    this.actionContext.setXaPoolProvider(selectedProvider);
                }
            } else {
                log.warn("No available XA Pool Provider found via ServiceLoader, XA pooling will be unavailable");
            }
        } catch (Exception e) {
            log.error("Failed to load XA Pool Provider: {}", e.getMessage(), e);
        }
    }

    @Override
    public void connect(ConnectionDetails connectionDetails, StreamObserver<SessionInfo> responseObserver) {
        // Register per-datasource prefetch wait timeout so that getIfReady() uses the
        // correct timeout for this datasource rather than the global default.
        if (nextPagePrefetchCache.isEnabled()) {
            String connHash = org.openjproxy.grpc.server.utils.ConnectionHashGenerator
                    .hashConnectionDetails(connectionDetails);
            String datasourceName = org.openjproxy.grpc.server.utils.ConnectionHashGenerator
                    .extractDataSourceName(connectionDetails);
            long perDatasourceTimeout = serverConfiguration
                    .getNextPageCachePrefetchWaitTimeoutMs(datasourceName);
            nextPagePrefetchCache.registerDatasourcePrefetchWaitTimeout(connHash, perDatasourceTimeout);
        }
        org.openjproxy.grpc.server.action.connection.ConnectAction.getInstance()
                .execute(actionContext, connectionDetails, responseObserver);
    }

    /**
     * Gets the slow query segregation manager for a specific connection hash.
     * If no manager exists, creates a disabled one as a fallback.
     */
    private SlowQuerySegregationManager getSlowQuerySegregationManagerForConnection(String connHash) {
        SlowQuerySegregationManager manager = slowQuerySegregationManagers.get(connHash);
        if (manager == null) {
            log.warn("No SlowQuerySegregationManager found for connection hash {}, creating disabled fallback",
                    connHash);
            manager = new SlowQuerySegregationManager(1, 0, 0, 0, 0, 0, false);
            slowQuerySegregationManagers.put(connHash, manager);
        }
        return manager;
    }

    @SneakyThrows
    @Override
    public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.ExecuteUpdateAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void executeQuery(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());

        executeWithResilience(request, responseObserver, () ->
            executeQueryInternal(request, responseObserver),
                null, "query");
    }

    /**
     * Internal method for executing queries without segregation logic.
     */
    private void executeQueryInternal(StatementRequest request, StreamObserver<OpResult> responseObserver)
            throws SQLException {
        
        ConnectionSessionDTO dto = sessionConnection(actionContext, request.getSession(), true);

        // Phase 2: SQL Enhancement with timing
        String sql = request.getSql();
        long enhancementStartTime = System.currentTimeMillis();

        if (sqlEnhancerEngine.isEnabled()) {
            // Ensure schema is loaded before enhancement (on-demand, only once)
            try {
                // Get the DataSource for this connection
                String dsKey = dto.getSession().getConnHash();
                DataSource dataSource = datasourceMap.get(dsKey);

                if (dataSource != null) {
                    // Get catalog and schema from the connection
                    Connection connection = dto.getConnection();
                    String catalogName = connection.getCatalog();
                    String schemaName = connection.getSchema();

                    // PostgreSQL: Use "public" schema if schema name is null or empty
                    // This ensures tables created in the default schema are visible to Calcite
                    if ((schemaName == null || schemaName.isEmpty()) &&
                        connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")) {
                        schemaName = "public";
                        log.debug("Using default PostgreSQL 'public' schema for schema loading");
                    }

                    // Ensure schema is loaded (thread-safe, idempotent)
                    sqlEnhancerEngine.ensureSchemaLoaded(dataSource, catalogName, schemaName);
                } else {
                    log.debug("No DataSource found for connection hash: {}", dsKey);
                }
            } catch (Exception e) {
                // Log but don't fail - enhancement can proceed without schema
                log.warn("Failed to ensure schema loaded: {}", e.getMessage());
            }

            org.openjproxy.grpc.server.sql.SqlEnhancementResult result = sqlEnhancerEngine.enhance(sql);
            sql = result.getEnhancedSql();

            long enhancementDuration = System.currentTimeMillis() - enhancementStartTime;

            if (result.isModified()) {
                log.debug("SQL was enhanced in {}ms: {} -> {}", enhancementDuration,
                        request.getSql().substring(0, Math.min(request.getSql().length(), 50)),
                        sql.substring(0, Math.min(sql.length(), 50)));
            } else if (enhancementDuration > 10) {
                log.debug("SQL enhancement took {}ms (no modifications)", enhancementDuration);
            }
        }

        List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());

        // ---- Next-page prefetch cache ----
        if (nextPagePrefetchCache.isEnabled()) {
            String connHash = dto.getSession().getConnHash();
            Optional<CachedPage> cached = nextPagePrefetchCache.getIfReady(connHash, sql);
            if (cached.isPresent()) {
                CachedPage page = cached.get();
                // Start prefetch for the page after this one before returning the cached result
                startNextPagePrefetch(sql, params, connHash);
                streamCachedPage(page, dto.getSession(), responseObserver);
                return;
            }
        }
        // ---- End next-page prefetch cache check ----

        String resultSetUUID;
        if (CollectionUtils.isNotEmpty(params)) {
            PreparedStatement ps = StatementFactory.createPreparedStatement(sessionManager, dto, sql, params, request);
            resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(), ps.executeQuery());
        } else {
            Statement stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
            resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(), stmt.executeQuery(sql));
        }
        // Start prefetch for the next page while the current page is being streamed
        startNextPagePrefetch(sql, params, dto.getSession().getConnHash());
        handleResultSet(actionContext, dto.getSession(), resultSetUUID, responseObserver);
    }

    /**
     * Starts an asynchronous prefetch of the next page for the given SQL, if the feature
     * is enabled and the SQL contains a recognised pagination clause.
     *
     * @param sql      the current paginated SQL
     * @param params   the query parameters (used as-is for the next-page query)
     * @param connHash the connection hash used to look up the DataSource
     */
    private void startNextPagePrefetch(String sql, List<Parameter> params, String connHash) {
        if (!nextPagePrefetchCache.isEnabled()) {
            return;
        }
        Optional<PageInfo> pageInfo = PaginationDetector.detect(sql);
        if (pageInfo.isEmpty()) {
            return;
        }
        String nextPageSql = PaginationDetector.buildNextPageSql(sql, pageInfo.get());
        if (nextPageSql == null) {
            return;
        }
        DataSource dataSource = datasourceMap.get(connHash);
        if (dataSource == null) {
            log.debug("No DataSource found for prefetch, connHash={}", connHash);
            return;
        }
        nextPagePrefetchCache.prefetchAsync(dataSource, connHash, nextPageSql, params);
    }

    /**
     * Streams the rows held in a {@link CachedPage} directly to the gRPC response observer,
     * using the same chunking strategy as {@link ResultSetHelper#handleResultSet}.
     *
     * @param page             the cached page to stream
     * @param session          the current session info (embedded in each response message)
     * @param responseObserver the gRPC observer to stream results into
     */
    private static void streamCachedPage(CachedPage page, SessionInfo session,
                                         StreamObserver<OpResult> responseObserver) {
        OpQueryResult.OpQueryResultBuilder queryResultBuilder = OpQueryResult.builder();
        queryResultBuilder.labels(page.getColumnLabels());

        List<Object[]> batch = new ArrayList<>();
        int row = 0;
        boolean justSent = false;

        for (Object[] rowValues : page.getRows()) {
            justSent = false;
            row++;
            batch.add(rowValues);
            if (row % CommonConstants.ROWS_PER_RESULT_SET_DATA_BLOCK == 0) {
                justSent = true;
                responseObserver.onNext(ResultSetWrapper.wrapResults(session, batch,
                        queryResultBuilder, null, ""));
                queryResultBuilder = OpQueryResult.builder();
                batch = new ArrayList<>();
            }
        }

        if (!justSent) {
            responseObserver.onNext(ResultSetWrapper.wrapResults(session, batch,
                    queryResultBuilder, null, ""));
        }
        responseObserver.onCompleted();
    }

    @Override
    public void fetchNextRows(ResultSetFetchRequest request, StreamObserver<OpResult> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.FetchNextRowsAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public StreamObserver<LobDataBlock> createLob(StreamObserver<LobReference> responseObserver) {
        return org.openjproxy.grpc.server.action.streaming.CreateLobAction.getInstance()
                .execute(actionContext, responseObserver);
    }

    @Override
    public void readLob(ReadLobRequest request, StreamObserver<LobDataBlock> responseObserver) {
        org.openjproxy.grpc.server.action.streaming.ReadLobAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Builder
    public static class ReadLobContext {
        @Getter
        private InputStream inputStream;
        @Getter
        private Optional<Long> lobLength;
        @Getter
        private Optional<Integer> availableLength;
    }

    @Override
    public void terminateSession(SessionInfo sessionInfo, StreamObserver<SessionTerminationStatus> responseObserver) {
        TerminateSessionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void startTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        StartTransactionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void commitTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        CommitTransactionAction.getInstance().execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void rollbackTransaction(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        RollbackTransactionAction.getInstance()
                .execute(actionContext, sessionInfo, responseObserver);
    }

    @Override
    public void callResource(CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) {
        CallResourceAction.getInstance().execute(actionContext, request, responseObserver);
    }

    /**
     * Helper method to centralize session validation, activity updates, cluster health processing,
     * circuit breaker checks, and slow query segregation for statement execution.
     * This resolves SonarQube duplication issues.
     */
    private void executeWithResilience(StatementRequest request, StreamObserver<OpResult> responseObserver,
                                       StatementExecution executionLogic, SqlErrorType sqlDataExceptionType, String operationName) {

        // Ensure session isn't null
        if (request.getSession() == null || StringUtils.isBlank(request.getSession().getConnHash())) {
            sendSQLExceptionMetadata(new SQLException("Invalid request: Session or ConnHash is missing"), responseObserver);
            log.error("Invalid {} request: Session or ConnHash is missing", operationName);
            return;
        }

        // Update session activity
        updateSessionActivity(request.getSession());

        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(actionContext, request.getSession());


        String connHash = request.getSession().getConnHash();
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.get(connHash);

        // Get the appropriate slow query segregation manager for this datasource
        SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);
        long sqlStartMs = System.currentTimeMillis();
        try {
            circuitBreaker.preCheck(stmtHash);

            // Execute with slow query segregation, passing actual SQL for metric labelling
            manager.executeWithSegregation(stmtHash, request.getSql(), () -> {
                executionLogic.execute();
                return null;
            });

            circuitBreaker.onSuccess(stmtHash);

        } catch(SQLDataException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL data failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            SqlErrorType type = sqlDataExceptionType != null
                    ? sqlDataExceptionType
                    : SqlErrorType.SQL_EXCEPTION;

            sendSQLExceptionMetadata(e, responseObserver, type);

        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlException) {
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        } finally {
            // Record SQL execution time for all connections (XA and non-XA) regardless of
            // manager state. This is the single authoritative place for SQL metrics.
            String sql = request.getSql();
            if (sql != null && !sql.isEmpty()) {
                long executionTimeMs = System.currentTimeMillis() - sqlStartMs;
                sqlStatementMetrics.recordSqlExecution(
                        sql, executionTimeMs, manager.isSlowOperation(stmtHash));
            }
        }
    }

    // ===== XA Transaction Operations =====

    @Override
    public void xaStart(com.openjproxy.grpc.XaStartRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaStartAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaEnd(com.openjproxy.grpc.XaEndRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaEndAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaPrepare(com.openjproxy.grpc.XaPrepareRequest request,
            StreamObserver<com.openjproxy.grpc.XaPrepareResponse> responseObserver) {
        XaPrepareAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaCommit(com.openjproxy.grpc.XaCommitRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaCommitAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaRollback(com.openjproxy.grpc.XaRollbackRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        XaRollbackAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaRecover(com.openjproxy.grpc.XaRecoverRequest request,
            StreamObserver<com.openjproxy.grpc.XaRecoverResponse> responseObserver) {
        XaRecoverAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaForget(com.openjproxy.grpc.XaForgetRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaForgetAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaSetTransactionTimeout(com.openjproxy.grpc.XaSetTransactionTimeoutRequest request,
            StreamObserver<com.openjproxy.grpc.XaSetTransactionTimeoutResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaSetTransactionTimeoutAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    @Override
    public void xaGetTransactionTimeout(com.openjproxy.grpc.XaGetTransactionTimeoutRequest request,
            StreamObserver<com.openjproxy.grpc.XaGetTransactionTimeoutResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaGetTransactionTimeoutAction.getInstance()
                .execute(actionContext, request, responseObserver);

    }

    @Override
    public void xaIsSameRM(com.openjproxy.grpc.XaIsSameRMRequest request,
            StreamObserver<com.openjproxy.grpc.XaIsSameRMResponse> responseObserver) {
        org.openjproxy.grpc.server.action.transaction.XaIsSameRMAction.getInstance()
                .execute(actionContext, request, responseObserver);
    }

    /**
     * Shuts down the SQL enhancer engine and releases associated resources.
     * This method should be called during server shutdown to ensure proper cleanup.
     */
    public void shutdown() {
        if (sqlEnhancerEngine != null) {
            log.info("Shutting down SQL enhancer engine");
            sqlEnhancerEngine.shutdown();
        }
    }

    @FunctionalInterface
    private interface StatementExecution {
        void execute() throws Exception;
    }
}
