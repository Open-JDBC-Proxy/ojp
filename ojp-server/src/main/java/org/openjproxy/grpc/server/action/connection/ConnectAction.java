package org.openjproxy.grpc.server.action.connection;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.datasource.ConnectionPoolProviderRegistry;
import org.openjproxy.datasource.PoolConfig;
import org.openjproxy.grpc.server.MultinodePoolCoordinator;
import org.openjproxy.grpc.server.MultinodeXaCoordinator;
import org.openjproxy.grpc.server.UnpooledConnectionDetails;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.pool.DataSourceConfigurationManager;
import org.openjproxy.grpc.server.utils.ConnectionHashGenerator;
import org.openjproxy.grpc.server.utils.UrlParser;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action to establish a database connection (regular or XA).
 * Handles connection pooling, multinode coordination, and both pooled/unpooled modes.
 * 
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 * It is stateless and receives all necessary context via parameters.
 */
@Slf4j
public class ConnectAction implements Action<ConnectionDetails, SessionInfo> {
    
    private static final ConnectAction INSTANCE = new ConnectAction();
    
    private ConnectAction() {
        // Private constructor prevents external instantiation
    }
    
    public static ConnectAction getInstance() {
        return INSTANCE;
    }
    
    @Override
    public void execute(ActionContext context, ConnectionDetails connectionDetails, StreamObserver<SessionInfo> responseObserver) {
        // Handle empty connection details (health check)
        if (StringUtils.isBlank(connectionDetails.getUrl()) &&
            StringUtils.isBlank(connectionDetails.getUser()) &&
            StringUtils.isBlank(connectionDetails.getPassword())) {
            // Empty connection details - return empty session info - used for initial health checks only
            responseObserver.onNext(SessionInfo.newBuilder().build());
            responseObserver.onCompleted();
            return;
        }

        String connHash = ConnectionHashGenerator.hashConnectionDetails(connectionDetails);

        // Use default XA configuration values (deprecated pass-through properties no longer supported)
        int maxXaTransactions = org.openjproxy.constants.CommonConstants.DEFAULT_MAX_XA_TRANSACTIONS;
        long xaStartTimeoutMillis = org.openjproxy.constants.CommonConstants.DEFAULT_XA_START_TIMEOUT_MILLIS;
        
        log.info("connect connHash = {}, isXA = {}, maxXaTransactions = {}, xaStartTimeout = {}ms", 
                connHash, connectionDetails.getIsXA(), maxXaTransactions, xaStartTimeoutMillis);

        // Check if this is an XA connection request
        if (connectionDetails.getIsXA()) {
            handleXAConnection(context, connectionDetails, connHash, maxXaTransactions, xaStartTimeoutMillis, responseObserver);
            return;
        }
        
        // Handle non-XA connection
        handleRegularConnection(context, connectionDetails, connHash, responseObserver);
    }
    
    /**
     * Handle XA connection establishment.
     */
    private void handleXAConnection(ActionContext context, ConnectionDetails connectionDetails, String connHash,
                                    int maxXaTransactions, long xaStartTimeoutMillis,
                                    StreamObserver<SessionInfo> responseObserver) {
        // Check if multinode configuration is present for XA coordination
        List<String> serverEndpoints = connectionDetails.getServerEndpointsList();
        int actualMaxXaTransactions = maxXaTransactions;
        
        if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
            // Multinode: calculate divided XA transaction limits
            MultinodeXaCoordinator.XaAllocation xaAllocation = 
                    context.getXaCoordinator().calculateXaLimits(connHash, maxXaTransactions, serverEndpoints);
            
            actualMaxXaTransactions = xaAllocation.getCurrentMaxTransactions();
            
            log.info("Multinode XA coordination enabled for {}: {} servers, divided max transactions: {}", 
                    connHash, serverEndpoints.size(), actualMaxXaTransactions);
        }
        
        // Branch based on XA pooling configuration
        // XA Pool Provider SPI (always enabled)
        if (context.getXaPoolProvider() != null) {
            HandleXAConnectionWithPoolingAction.getInstance().execute(
                context, connectionDetails, connHash, actualMaxXaTransactions, 
                xaStartTimeoutMillis, responseObserver);
        } else {
            log.error("XA Pool Provider not initialized");
            responseObserver.onError(Status.INTERNAL
                    .withDescription("XA Pool Provider not available")
                    .asRuntimeException());
        }
    }
    
    /**
     * Handle regular (non-XA) connection establishment.
     */
    private void handleRegularConnection(ActionContext context, ConnectionDetails connectionDetails, String connHash,
                                        StreamObserver<SessionInfo> responseObserver) {
        // Handle non-XA connection - check if pooling is enabled
        DataSource ds = context.getDatasourceMap().get(connHash);
        UnpooledConnectionDetails unpooledDetails = 
                context.getUnpooledConnectionDetailsMap().get(connHash);
        
        if (ds == null && unpooledDetails == null) {
            try {
                // Get datasource-specific configuration from client properties
                Properties clientProperties = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
                DataSourceConfigurationManager.DataSourceConfiguration dsConfig = 
                        DataSourceConfigurationManager.getConfiguration(clientProperties);
                
                // Check if pooling is enabled
                if (!dsConfig.isPoolEnabled()) {
                    // Unpooled mode: store connection details for direct connection creation
                    unpooledDetails = UnpooledConnectionDetails.builder()
                            .url(UrlParser.parseUrl(connectionDetails.getUrl()))
                            .username(connectionDetails.getUser())
                            .password(connectionDetails.getPassword())
                            .connectionTimeout(dsConfig.getConnectionTimeout())
                            .build();
                    context.getUnpooledConnectionDetailsMap().put(connHash, unpooledDetails);
                    
                    log.info("Unpooled (passthrough) mode enabled for dataSource '{}' with connHash: {}", 
                            dsConfig.getDataSourceName(), connHash);
                } else {
                    // Pooled mode: create datasource with Connection Pool SPI (HikariCP by default)
                    // Get pool sizes - apply multinode coordination if needed
                    int maxPoolSize = dsConfig.getMaximumPoolSize();
                    int minIdle = dsConfig.getMinimumIdle();
                    
                    List<String> serverEndpoints = connectionDetails.getServerEndpointsList();
                    if (serverEndpoints != null && !serverEndpoints.isEmpty()) {
                        // Multinode: calculate divided pool sizes
                        MultinodePoolCoordinator.PoolAllocation allocation = 
                                ConnectionPoolConfigurer.getPoolCoordinator().calculatePoolSizes(
                                        connHash, maxPoolSize, minIdle, serverEndpoints);
                        
                        maxPoolSize = allocation.getCurrentMaxPoolSize();
                        minIdle = allocation.getCurrentMinIdle();
                        
                        log.info("Multinode pool coordination enabled for {}: {} servers, divided pool sizes: max={}, min={}", 
                                connHash, serverEndpoints.size(), maxPoolSize, minIdle);
                    }
                    
                    // Get transaction isolation from configuration, default to READ_COMMITTED
                    Integer configuredTransactionIsolation = dsConfig.getDefaultTransactionIsolation();
                    Integer defaultTransactionIsolation = configuredTransactionIsolation != null 
                            ? configuredTransactionIsolation 
                            : java.sql.Connection.TRANSACTION_READ_COMMITTED;
                    
                    if (configuredTransactionIsolation == null) {
                        log.info("No transaction isolation configured for {}, using default READ_COMMITTED", connHash);
                    } else {
                        log.info("Using configured transaction isolation level for {}: {}", 
                                connHash, configuredTransactionIsolation);
                    }
                    
                    // Build PoolConfig with transaction isolation (configured or default)
                    PoolConfig poolConfig = PoolConfig.builder()
                            .url(UrlParser.parseUrl(connectionDetails.getUrl()))
                            .username(connectionDetails.getUser())
                            .password(connectionDetails.getPassword())
                            .maxPoolSize(maxPoolSize)
                            .minIdle(minIdle)
                            .connectionTimeoutMs(dsConfig.getConnectionTimeout())
                            .idleTimeoutMs(dsConfig.getIdleTimeout())
                            .maxLifetimeMs(dsConfig.getMaxLifetime())
                            .defaultTransactionIsolation(defaultTransactionIsolation)
                            .metricsPrefix("OJP-Pool-" + dsConfig.getDataSourceName())
                            .build();
                    
                    // Create DataSource with properly configured transaction isolation
                    ds = ConnectionPoolProviderRegistry.createDataSource(poolConfig);
                    log.info("Created DataSource with transaction isolation level: {}", defaultTransactionIsolation);
                    
                    context.getDatasourceMap().put(connHash, ds);
                    
                    // Create a slow query segregation manager for this datasource
                    CreateSlowQuerySegregationManagerAction.getInstance().execute(context, connHash, maxPoolSize);
                    
                    log.info("Created new DataSource for dataSource '{}' with connHash: {} using provider: {}, maxPoolSize={}, minIdle={}", 
                            dsConfig.getDataSourceName(), connHash, 
                            ConnectionPoolProviderRegistry.getDefaultProvider().map(p -> p.id()).orElse("unknown"),
                            maxPoolSize, minIdle);
                }
                
            } catch (Exception e) {
                log.error("Failed to create datasource for connection hash {}: {}", connHash, e.getMessage(), e);
                SQLException sqlException = new SQLException("Failed to create datasource: " + e.getMessage(), e);
                sendSQLExceptionMetadata(sqlException, responseObserver);
                return;
            }
        }

        context.getSessionManager().registerClientUUID(connHash, connectionDetails.getClientUUID());

        // For regular connections, just return session info without creating a session yet (lazy allocation)
        // Server does not populate targetServer - client will set it on future requests
        SessionInfo sessionInfo = SessionInfo.newBuilder()
                .setConnHash(connHash)
                .setClientUUID(connectionDetails.getClientUUID())
                .setIsXA(false)
                .build();

        responseObserver.onNext(sessionInfo);

        context.getDbNameMap().put(connHash, DatabaseUtils.resolveDbName(connectionDetails.getUrl()));

        responseObserver.onCompleted();
    }
}
