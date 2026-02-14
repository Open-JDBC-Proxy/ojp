package org.openjproxy.grpc.server;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ReadLobRequest;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.ResultSetFetchRequest;
import com.openjproxy.grpc.ResultType;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import com.openjproxy.grpc.StatementServiceGrpc;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.stub.StreamObserver;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.action.statement.ExecuteUpdateAction;
import org.openjproxy.grpc.server.action.statement.ExecuteQueryAction;
import org.openjproxy.grpc.server.action.statement.FetchNextRowsAction;
import org.openjproxy.grpc.server.action.transaction.StartTransactionAction;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.statement.ParameterHandler;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.grpc.server.utils.MethodNameGenerator;
import org.openjproxy.grpc.server.utils.MethodReflectionUtils;
import org.openjproxy.grpc.server.utils.SessionInfoUtils;
import org.openjproxy.grpc.server.utils.StatementRequestValidator;
import org.openjproxy.grpc.server.sql.SqlSessionAffinityDetector;
import org.openjproxy.grpc.server.action.xa.XaStartAction;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.spi.XAConnectionPoolProvider;
import org.openjproxy.grpc.server.action.transaction.RollbackTransactionAction;
import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.openjproxy.grpc.server.Constants.EMPTY_LIST;
import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

import org.openjproxy.grpc.server.action.xa.XaEndAction;
import org.openjproxy.grpc.server.action.transaction.CommitTransactionAction;
import org.openjproxy.grpc.server.action.session.TerminateSessionAction;
import org.openjproxy.grpc.server.action.resource.CallResourceAction;
import org.openjproxy.grpc.server.action.xa.XaPrepareAction;
import org.openjproxy.grpc.server.action.xa.XaCommitAction;
import org.openjproxy.grpc.server.action.xa.XaRollbackAction;
import org.openjproxy.grpc.server.action.xa.XaRecoverAction;

@Slf4j
public class StatementServiceImpl extends StatementServiceGrpc.StatementServiceImplBase {

    private final Map<String, DataSource> datasourceMap = new ConcurrentHashMap<>();
    // Map for storing XADataSources (native database XADataSource, not Atomikos)
    private final Map<String, XADataSource> xaDataSourceMap = new ConcurrentHashMap<>();
    // XA Pool Provider for pooling XAConnections (loaded via SPI)
    private XAConnectionPoolProvider xaPoolProvider;
    // XA Transaction Registries (one per connection hash for isolated transaction
    // management)
    private final Map<String, XATransactionRegistry> xaRegistries = new ConcurrentHashMap<>();
    private final SessionManager sessionManager;
    private final CircuitBreaker circuitBreaker;

    // Per-datasource slow query segregation managers
    private final Map<String, SlowQuerySegregationManager> slowQuerySegregationManagers = new ConcurrentHashMap<>();

    // SQL Enhancer Engine for query optimization
    private final org.openjproxy.grpc.server.sql.SqlEnhancerEngine sqlEnhancerEngine;

    // Multinode XA coordinator for distributing transaction limits
    private static final MultinodeXaCoordinator xaCoordinator = new MultinodeXaCoordinator();

    // Cluster health tracker for monitoring health changes
    private final ClusterHealthTracker clusterHealthTracker = new ClusterHealthTracker();

    // Unpooled connection details map (for passthrough mode when pooling is
    // disabled)
    private final Map<String, UnpooledConnectionDetails> unpooledConnectionDetailsMap = new ConcurrentHashMap<>();

    private final Map<String, DbName> dbNameMap = new ConcurrentHashMap<>();

    private static final String RESULT_SET_METADATA_ATTR_PREFIX = "rsMetadata|";
    // ActionContext for refactored actions
    private final org.openjproxy.grpc.server.action.ActionContext actionContext;

    public StatementServiceImpl(SessionManager sessionManager, CircuitBreaker circuitBreaker,
            ServerConfiguration serverConfiguration) {
        this.sessionManager = sessionManager;
        this.circuitBreaker = circuitBreaker;
        // Server configuration for creating segregation managers
        this.sqlEnhancerEngine = new org.openjproxy.grpc.server.sql.SqlEnhancerEngine(
                serverConfiguration.isSqlEnhancerEnabled());
        initializeXAPoolProvider();

        // Initialize ActionContext with all shared state
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
                circuitBreaker,
                serverConfiguration,
                sqlEnhancerEngine);
    }

    /**
     * Updates the last activity time for the session to prevent premature cleanup.
     * This should be called at the beginning of any method that operates on a
     * session.
     *
     * @param sessionInfo the session information
     */
    private void updateSessionActivity(SessionInfo sessionInfo) {
        if (sessionInfo != null && sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
            sessionManager.updateSessionActivity(sessionInfo);
        }
    }

    /**
     * Creates and configures the SQL enhancer engine based on server configuration.
     * Parses mode to determine conversion and optimization settings.
     * Initializes schema cache and loader for query validation.
     *
     * @param config Server configuration
     * @return Configured SqlEnhancerEngine instance
     */
    private org.openjproxy.grpc.server.sql.SqlEnhancerEngine createSqlEnhancerEngine(ServerConfiguration config) {
        // Parse mode to determine conversion and optimization settings
        org.openjproxy.grpc.server.sql.SqlEnhancerMode mode = org.openjproxy.grpc.server.sql.SqlEnhancerMode
                .fromString(config.getSqlEnhancerMode());

        // Parse rules if specified, otherwise use defaults
        java.util.List<String> enabledRules = null;
        if (config.getSqlEnhancerRules() != null && !config.getSqlEnhancerRules().trim().isEmpty()) {
            enabledRules = java.util.Arrays.asList(config.getSqlEnhancerRules().split(","))
                    .stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
        }

        // Initialize schema cache and loader if SQL enhancer is enabled with conversion
        org.openjproxy.grpc.server.sql.SchemaCache schemaCache = null;
        org.openjproxy.grpc.server.sql.SchemaLoader schemaLoader = null;

        if (config.isSqlEnhancerEnabled() && mode.isConversionEnabled()) {
            log.info("Initializing schema cache and loader for SQL enhancer");
            schemaCache = new org.openjproxy.grpc.server.sql.SchemaCache();
            schemaLoader = new org.openjproxy.grpc.server.sql.SchemaLoader();
            // Note: Schema will be loaded on first query execution when datasource is
            // available
        }

        // Create engine with full configuration including schema support
        return new org.openjproxy.grpc.server.sql.SqlEnhancerEngine(
                config.isSqlEnhancerEnabled(),
                config.getSqlEnhancerDialect(),
                config.getSqlEnhancerTargetDialect(),
                mode.isConversionEnabled(),
                mode.isOptimizationEnabled(),
                enabledRules,
                schemaCache,
                schemaLoader,
                null, // DataSource will be provided during query execution
                null, // Catalog name will be determined from connection
                null, // Schema name will be determined from connection
                0 // No automatic refresh (will refresh on-demand)
        );
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

    /**
     * Gets the target server identifier from the incoming request.
     * Simply echoes back what the client sent without any override.
     */
    private String getTargetServer(SessionInfo incomingSessionInfo) {
        // Echo back the targetServer from incoming request, or return empty string if
        // not present
        if (incomingSessionInfo != null &&
                incomingSessionInfo.getTargetServer() != null &&
                !incomingSessionInfo.getTargetServer().isEmpty()) {
            return incomingSessionInfo.getTargetServer();
        }

        // Return empty string if client didn't send targetServer
        return "";
    }

    /**
     * Processes cluster health from the client request and triggers pool
     * rebalancing if needed.
     * This should be called for every request that includes SessionInfo with
     * cluster health.
     */
    private void processClusterHealth(SessionInfo sessionInfo) {
        if (sessionInfo == null) {
            log.debug("[XA-REBALANCE-DEBUG] processClusterHealth: sessionInfo is null");
            return;
        }

        String clusterHealth = sessionInfo.getClusterHealth();
        String connHash = sessionInfo.getConnHash();

        log.debug(
                "[XA-REBALANCE] processClusterHealth called: connHash={}, clusterHealth='{}', isXA={}, hasXARegistry={}",
                connHash, clusterHealth, sessionInfo.getIsXA(), xaRegistries.containsKey(connHash));

        if (clusterHealth != null && !clusterHealth.isEmpty() &&
                connHash != null && !connHash.isEmpty()) {

            // Check if cluster health has changed
            boolean healthChanged = clusterHealthTracker.hasHealthChanged(connHash, clusterHealth);

            log.debug("[XA-REBALANCE] Cluster health check for {}: changed={}, current health='{}', isXA={}",
                    connHash, healthChanged, clusterHealth, sessionInfo.getIsXA());

            if (healthChanged) {
                int healthyServerCount = clusterHealthTracker.countHealthyServers(clusterHealth);
                log.info(
                        "[XA-REBALANCE] Cluster health changed for {}, healthy servers: {}, triggering pool rebalancing, isXA={}",
                        connHash, healthyServerCount, sessionInfo.getIsXA());

                // Update the pool coordinator with new healthy server count
                ConnectionPoolConfigurer.getPoolCoordinator().updateHealthyServers(connHash, healthyServerCount);

                // Apply pool size changes to non-XA HikariDataSource if present
                DataSource ds = datasourceMap.get(connHash);
                if (ds instanceof HikariDataSource) {
                    log.info("[XA-REBALANCE-DEBUG] Applying size changes to HikariDataSource for {}", connHash);
                    ConnectionPoolConfigurer.applyPoolSizeChanges(connHash, (HikariDataSource) ds);
                } else {
                    log.info("[XA-REBALANCE-DEBUG] No HikariDataSource found for {}", connHash);
                }

                // Apply pool size changes to XA registry if present
                XATransactionRegistry xaRegistry = xaRegistries.get(connHash);
                if (xaRegistry != null) {
                    log.info("[XA-REBALANCE-DEBUG] Found XA registry for {}, resizing", connHash);
                    MultinodePoolCoordinator.PoolAllocation allocation = ConnectionPoolConfigurer.getPoolCoordinator()
                            .getPoolAllocation(connHash);

                    if (allocation != null) {
                        int newMaxPoolSize = allocation.getCurrentMaxPoolSize();
                        int newMinIdle = allocation.getCurrentMinIdle();

                        log.info("[XA-REBALANCE-DEBUG] Resizing XA backend pool for {}: maxPoolSize={}, minIdle={}",
                                connHash, newMaxPoolSize, newMinIdle);

                        xaRegistry.resizeBackendPool(newMaxPoolSize, newMinIdle);
                    } else {
                        log.warn("[XA-REBALANCE-DEBUG] No pool allocation found for {}", connHash);
                    }
                } else if (sessionInfo.getIsXA()) {
                    // Only log missing XA registry for actual XA connections
                    log.info("[XA-REBALANCE-DEBUG] No XA registry found for XA connection {}", connHash);
                }
            } else {
                log.debug("[XA-REBALANCE-DEBUG] Cluster health unchanged for {}", connHash);
            }
        } else {
            log.info("[XA-REBALANCE-DEBUG] Skipping cluster health processing: clusterHealth={}, connHash={}",
                    clusterHealth != null && !clusterHealth.isEmpty() ? "present" : "empty",
                    connHash != null && !connHash.isEmpty() ? "present" : "empty");
        }
    }

    @Override
    public void connect(ConnectionDetails connectionDetails, StreamObserver<SessionInfo> responseObserver) {
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
            // Create a disabled manager as fallback
            manager = new SlowQuerySegregationManager(1, 0, 0, 0, 0, 0, false);
            slowQuerySegregationManagers.put(connHash, manager);
        }
        return manager;
    }

    @SneakyThrows
    @Override
    public void executeUpdate(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        ExecuteUpdateAction.getInstance().execute(actionContext, request, responseObserver);
    }

    @Override
    public void executeQuery(StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());

        // Update session activity
        updateSessionActivity(request.getSession());

        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());

        // Process cluster health from the request
        processClusterHealth(request.getSession());

        try {
            circuitBreaker.preCheck(stmtHash);

            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(connHash);

            // Execute with slow query segregation
            manager.executeWithSegregation(stmtHash, () -> {
                executeQueryInternal(request, responseObserver);
                return null; // Void return for query execution
            });

            circuitBreaker.onSuccess(stmtHash);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during query execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during query execution: " + e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlException) {
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        }
    }

    /**
     * Internal method for executing queries without segregation logic.
     */
    private void executeQueryInternal(StatementRequest request, StreamObserver<OpResult> responseObserver)
            throws SQLException {
        // Check if SQL requires session affinity (temporary tables, session variables,
        // etc.)
        // Note: All queries already create sessions (for result set handling), but this
        // ensures session affinity is properly enforced even for queries that don't
        // return results
        boolean requiresSessionAffinity = SqlSessionAffinityDetector.requiresSessionAffinity(request.getSql());

        ConnectionSessionDTO dto = this.sessionConnection(request.getSession(), true || requiresSessionAffinity);

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
        if (CollectionUtils.isNotEmpty(params)) {
            PreparedStatement ps = StatementFactory.createPreparedStatement(sessionManager, dto, sql, params, request);
            String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(), ps.executeQuery());
            this.handleResultSet(dto.getSession(), resultSetUUID, responseObserver);
        } else {
            Statement stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
            String resultSetUUID = this.sessionManager.registerResultSet(dto.getSession(),
                    stmt.executeQuery(sql));
            this.handleResultSet(dto.getSession(), resultSetUUID, responseObserver);
        }
    }

    @Override
    public void fetchNextRows(ResultSetFetchRequest request, StreamObserver<OpResult> responseObserver) {
        FetchNextRowsAction.getInstance().execute(actionContext, request, responseObserver);
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
     * As DB2 eagerly closes result sets in multiple situations the result set
     * metadata is saved a priori in a session
     * attribute and has to be read in a special manner treated in this method.
     *
     * @param request
     * @param responseObserver
     * @return boolean
     * @throws SQLException
     */
    @SneakyThrows
    private boolean db2SpecialResultSetMetadata(CallResourceRequest request,
            StreamObserver<CallResourceResponse> responseObserver) throws SQLException {
        if (DbName.DB2.equals(this.dbNameMap.get(request.getSession().getConnHash())) &&
                ResourceType.RES_RESULT_SET.equals(request.getResourceType()) &&
                CallType.CALL_GET.equals(request.getTarget().getCallType()) &&
                "Metadata".equalsIgnoreCase(request.getTarget().getResourceName())) {
            ResultSetMetaData resultSetMetaData = (ResultSetMetaData) this.sessionManager.getAttr(request.getSession(),
                    RESULT_SET_METADATA_ATTR_PREFIX + request.getResourceUUID());
            List<Object> paramsReceived = (request.getTarget().getNextCall().getParamsCount() > 0)
                    ? ProtoConverter.parameterValuesToObjectList(request.getTarget().getNextCall().getParamsList())
                    : EMPTY_LIST;
            Method methodNext = MethodReflectionUtils.findMethodByName(ResultSetMetaData.class,
                    MethodNameGenerator.methodName(request.getTarget().getNextCall()),
                    paramsReceived);
            Object metadataResult = methodNext.invoke(resultSetMetaData, paramsReceived.toArray());
            responseObserver.onNext(CallResourceResponse.newBuilder()
                    .setSession(request.getSession())
                    .addValues(ProtoConverter.toParameterValue(metadataResult))
                    .build());
            responseObserver.onCompleted();
            return true;
        }
        return false;
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
}
