package org.openjproxy.grpc.client;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.jdbc.Connection;
import org.openjproxy.jdbc.MultinodeConnectionManager;
import org.openjproxy.jdbc.MultinodeUrlParser.Endpoint;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multinode implementation of StatementService with load balancing, session stickiness, and failover.
 * Thread-safe for concurrent requests.
 */
@Slf4j
public class MultinodeStatementService implements StatementService {
    
    private final MultinodeConnectionManager connectionManager;
    private final Map<Endpoint, StatementServiceGrpcClient> clientPool;
    private final String originalUrl;
    
    /**
     * Create a new MultinodeStatementService.
     * 
     * @param connectionManager the connection manager for server selection and failover
     * @param originalUrl the original JDBC URL (used for creating client connections)
     */
    public MultinodeStatementService(MultinodeConnectionManager connectionManager, String originalUrl) {
        if (connectionManager == null) {
            throw new IllegalArgumentException("ConnectionManager cannot be null");
        }
        if (originalUrl == null || originalUrl.isEmpty()) {
            throw new IllegalArgumentException("Original URL cannot be null or empty");
        }
        
        this.connectionManager = connectionManager;
        this.originalUrl = originalUrl;
        this.clientPool = new ConcurrentHashMap<>();
        
        log.info("Initialized MultinodeStatementService with {} endpoints",
                connectionManager.getEndpoints().size());
    }
    
    /**
     * Get or create a client for the specified endpoint.
     */
    private StatementServiceGrpcClient getClient(Endpoint endpoint) {
        return clientPool.computeIfAbsent(endpoint, ep -> {
            log.debug("Creating new StatementServiceGrpcClient for endpoint: {}", ep);
            return new StatementServiceGrpcClient();
        });
    }
    
    /**
     * Get the client for a session, or select a new server using round-robin.
     */
    private StatementServiceGrpcClient getClientForSession(SessionInfo sessionInfo) {
        if (sessionInfo != null && sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
            Endpoint boundServer = connectionManager.getServerForSession(sessionInfo.getSessionUUID());
            if (boundServer != null) {
                log.debug("Using bound server {} for session {}", boundServer, sessionInfo.getSessionUUID());
                return getClient(boundServer);
            }
        }
        
        // No session binding, select server using round-robin
        Endpoint server = connectionManager.selectServer();
        log.debug("Selected server {} using round-robin", server);
        return getClient(server);
    }
    
    /**
     * Execute an operation with automatic failover on connection errors.
     */
    private <T> T executeWithFailover(SessionInfo sessionInfo, Operation<T> operation) throws SQLException {
        Endpoint boundServer = null;
        if (sessionInfo != null && sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
            boundServer = connectionManager.getServerForSession(sessionInfo.getSessionUUID());
        }
        
        // If session is bound, only try that server (no failover for session-bound operations)
        if (boundServer != null) {
            StatementServiceGrpcClient client = getClient(boundServer);
            try {
                T result = operation.execute(client);
                connectionManager.markServerHealthy(boundServer);
                return result;
            } catch (Exception e) {
                if (connectionManager.isConnectionLevelError(e)) {
                    connectionManager.markServerUnhealthy(boundServer);
                    log.error("Connection error on bound server {} for session {}: {}",
                            boundServer, sessionInfo.getSessionUUID(), e.getMessage());
                }
                throw e;
            }
        }
        
        // No session binding - try servers with failover
        int retries = 0;
        SQLException lastException = null;
        
        while (retries <= connectionManager.getMaxRetries()) {
            Endpoint server = connectionManager.selectServer();
            StatementServiceGrpcClient client = getClient(server);
            
            try {
                T result = operation.execute(client);
                connectionManager.markServerHealthy(server);
                return result;
            } catch (SQLException e) {
                lastException = e;
                
                if (connectionManager.isConnectionLevelError(e)) {
                    connectionManager.markServerUnhealthy(server);
                    log.warn("Connection error on server {} (attempt {}/{}): {}",
                            server, retries + 1, connectionManager.getMaxRetries() + 1, e.getMessage());
                    retries++;
                } else {
                    // Database error, don't retry
                    log.debug("Database error (not retrying): {}", e.getMessage());
                    throw e;
                }
            }
        }
        
        log.error("All retry attempts exhausted after {} tries", retries);
        throw lastException != null ? lastException : new SQLException("Operation failed after all retries");
    }
    
    /**
     * Functional interface for operations that can be retried.
     */
    @FunctionalInterface
    private interface Operation<T> {
        T execute(StatementServiceGrpcClient client) throws SQLException;
    }
    
    /**
     * Convert URL with multinode endpoints to single endpoint URL.
     */
    private String buildUrlForEndpoint(Endpoint endpoint) {
        // Replace the multinode part with single endpoint
        // jdbc:ojp[host1:1059,host2:1059]_... -> jdbc:ojp[host:1059]_...
        return originalUrl.replaceFirst("ojp\\[[^\\]]+\\]", "ojp[" + endpoint.toString() + "]");
    }
    
    @Override
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        // Select server using round-robin
        Endpoint server = connectionManager.selectServer();
        
        // Build URL for the selected endpoint
        String endpointUrl = buildUrlForEndpoint(server);
        ConnectionDetails endpointDetails = ConnectionDetails.newBuilder(connectionDetails)
                .setUrl(endpointUrl)
                .build();
        
        log.debug("Connecting to server {} with URL: {}", server, endpointUrl);
        
        StatementServiceGrpcClient client = getClient(server);
        
        try {
            SessionInfo sessionInfo = client.connect(endpointDetails);
            
            // Bind the new session to this server
            if (sessionInfo != null && sessionInfo.getSessionUUID() != null && !sessionInfo.getSessionUUID().isEmpty()) {
                connectionManager.bindSession(sessionInfo.getSessionUUID(), server);
                log.info("Connected to server {} with session {}", server, sessionInfo.getSessionUUID());
            }
            
            connectionManager.markServerHealthy(server);
            return sessionInfo;
            
        } catch (SQLException e) {
            if (connectionManager.isConnectionLevelError(e)) {
                connectionManager.markServerUnhealthy(server);
                log.error("Connection error on server {}: {}", server, e.getMessage());
            }
            throw e;
        }
    }
    
    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                  Map<String, Object> properties) throws SQLException {
        return executeWithFailover(sessionInfo, client -> 
                client.executeUpdate(sessionInfo, sql, params, properties));
    }
    
    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                  String statementUUID, Map<String, Object> properties) throws SQLException {
        return executeWithFailover(sessionInfo, client ->
                client.executeUpdate(sessionInfo, sql, params, statementUUID, properties));
    }
    
    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                           String statementUUID, Map<String, Object> properties) throws SQLException {
        return executeWithFailover(sessionInfo, client ->
                client.executeQuery(sessionInfo, sql, params, statementUUID, properties));
    }
    
    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params,
                                           Map<String, Object> properties) throws SQLException {
        return executeWithFailover(sessionInfo, client ->
                client.executeQuery(sessionInfo, sql, params, properties));
    }
    
    @Override
    public OpResult fetchNextRows(SessionInfo sessionInfo, String resultSetUUID, int size) throws SQLException {
        return executeWithFailover(sessionInfo, client ->
                client.fetchNextRows(sessionInfo, resultSetUUID, size));
    }
    
    @Override
    public LobReference createLob(Connection connection, Iterator<LobDataBlock> lobDataBlock) throws SQLException {
        SessionInfo sessionInfo = connection.getSession();
        return executeWithFailover(sessionInfo, client ->
                client.createLob(connection, lobDataBlock));
    }
    
    @Override
    public Iterator<LobDataBlock> readLob(LobReference lobReference, long pos, int length) throws SQLException {
        SessionInfo sessionInfo = lobReference.getSession();
        return executeWithFailover(sessionInfo, client ->
                client.readLob(lobReference, pos, length));
    }
    
    @Override
    public void terminateSession(SessionInfo session) {
        if (session == null || session.getSessionUUID() == null || session.getSessionUUID().isEmpty()) {
            return;
        }
        
        Endpoint boundServer = connectionManager.getServerForSession(session.getSessionUUID());
        if (boundServer != null) {
            StatementServiceGrpcClient client = getClient(boundServer);
            client.terminateSession(session);
            connectionManager.unbindSession(session.getSessionUUID());
            log.debug("Terminated session {} on server {}", session.getSessionUUID(), boundServer);
        }
    }
    
    @Override
    public SessionInfo startTransaction(SessionInfo session) throws SQLException {
        return executeWithFailover(session, client -> client.startTransaction(session));
    }
    
    @Override
    public SessionInfo commitTransaction(SessionInfo session) throws SQLException {
        return executeWithFailover(session, client -> client.commitTransaction(session));
    }
    
    @Override
    public SessionInfo rollbackTransaction(SessionInfo session) throws SQLException {
        return executeWithFailover(session, client -> client.rollbackTransaction(session));
    }
    
    @Override
    public CallResourceResponse callResource(CallResourceRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithFailover(sessionInfo, client -> client.callResource(request));
    }
    
    @Override
    public com.openjproxy.grpc.XaResponse xaStart(com.openjproxy.grpc.XaStartRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithFailover(sessionInfo, client -> client.xaStart(request));
    }
    
    @Override
    public com.openjproxy.grpc.XaResponse xaEnd(com.openjproxy.grpc.XaEndRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithFailover(sessionInfo, client -> client.xaEnd(request));
    }
    
    @Override
    public com.openjproxy.grpc.XaPrepareResponse xaPrepare(com.openjproxy.grpc.XaPrepareRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithFailover(sessionInfo, client -> client.xaPrepare(request));
    }
    
    @Override
    public com.openjproxy.grpc.XaResponse xaCommit(com.openjproxy.grpc.XaCommitRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithFailover(sessionInfo, client -> client.xaCommit(request));
    }
    
    @Override
    public com.openjproxy.grpc.XaResponse xaRollback(com.openjproxy.grpc.XaRollbackRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithFailover(sessionInfo, client -> client.xaRollback(request));
    }
    
    @Override
    public com.openjproxy.grpc.XaRecoverResponse xaRecover(com.openjproxy.grpc.XaRecoverRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithFailover(sessionInfo, client -> client.xaRecover(request));
    }
    
    @Override
    public com.openjproxy.grpc.XaResponse xaForget(com.openjproxy.grpc.XaForgetRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithFailover(sessionInfo, client -> client.xaForget(request));
    }
    
    @Override
    public com.openjproxy.grpc.XaSetTransactionTimeoutResponse xaSetTransactionTimeout(
            com.openjproxy.grpc.XaSetTransactionTimeoutRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithFailover(sessionInfo, client -> client.xaSetTransactionTimeout(request));
    }
    
    @Override
    public com.openjproxy.grpc.XaGetTransactionTimeoutResponse xaGetTransactionTimeout(
            com.openjproxy.grpc.XaGetTransactionTimeoutRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithFailover(sessionInfo, client -> client.xaGetTransactionTimeout(request));
    }
    
    @Override
    public com.openjproxy.grpc.XaIsSameRMResponse xaIsSameRM(
            com.openjproxy.grpc.XaIsSameRMRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession1();
        return executeWithFailover(sessionInfo, client -> client.xaIsSameRM(request));
    }
}
