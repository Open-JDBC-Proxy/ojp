package org.openjproxy.grpc.client;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.LobDataBlock;
import com.openjproxy.grpc.LobReference;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.StatusRuntimeException;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.jdbc.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multinode implementation of StatementService that provides:
 * - Round-robin load balancing across multiple OJP servers
 * - Session stickiness (once a session is bound to a server, all requests for that session go to the same server)
 * - Automatic failover on connection-level errors
 * - Thread-safe concurrent request handling
 * 
 * This class delegates to StatementServiceGrpcClient instances, one per server endpoint.
 */
public class MultinodeStatementService implements StatementService {
    
    private static final Logger log = LoggerFactory.getLogger(MultinodeStatementService.class);
    
    private final MultinodeConnectionManager connectionManager;
    private final Map<ServerEndpoint, StatementServiceGrpcClient> clientMap;
    private final String originalUrl;
    
    /**
     * Creates a new MultinodeStatementService.
     * 
     * @param connectionManager The connection manager to use for server selection and session tracking
     * @param originalUrl The original multinode URL (for logging and debugging)
     */
    public MultinodeStatementService(MultinodeConnectionManager connectionManager, String originalUrl) {
        this.connectionManager = connectionManager;
        this.clientMap = new ConcurrentHashMap<>();
        this.originalUrl = originalUrl;
        
        log.info("MultinodeStatementService initialized with {} servers from URL: {}", 
                connectionManager.getServerEndpoints().size(), originalUrl);
    }
    
    /**
     * Gets or creates a StatementServiceGrpcClient for a specific server endpoint.
     * The client uses the same gRPC channel and stubs as the MultinodeConnectionManager
     * to ensure session continuity.
     */
    private StatementServiceGrpcClient getClient(ServerEndpoint endpoint) {
        return clientMap.computeIfAbsent(endpoint, ep -> {
            log.info("Creating new StatementServiceGrpcClient for endpoint: {}", ep.getAddress());
            
            // Get the channel and stubs from the connection manager
            // This ensures we use the SAME channel as the connection, maintaining session continuity
            MultinodeConnectionManager.ChannelAndStub channelAndStub = 
                    connectionManager.getChannelAndStub(ep);
            
            if (channelAndStub == null) {
                log.error("Unable to get channel and stub for endpoint: {}", ep.getAddress());
                throw new RuntimeException("Unable to initialize client for endpoint: " + ep.getAddress());
            }
            
            log.info("Got channel and stub for endpoint {}: blockingStub={}, asyncStub={}", 
                ep.getAddress(), 
                System.identityHashCode(channelAndStub.blockingStub),
                System.identityHashCode(channelAndStub.asyncStub));
            
            // Create a client and inject the connection manager's stubs into it
            // This ensures all operations use the same gRPC connection where the session was created
            StatementServiceGrpcClient client = new StatementServiceGrpcClient();
            
            try {
                // Use reflection to set the stubs to the connection manager's stubs
                // This is necessary because StatementServiceGrpcClient doesn't have a constructor
                // that accepts stubs, and we need to use the same channel for session continuity
                java.lang.reflect.Field blockingStubField = StatementServiceGrpcClient.class
                    .getDeclaredField("statemetServiceBlockingStub");
                blockingStubField.setAccessible(true);
                blockingStubField.set(client, channelAndStub.blockingStub);
                
                java.lang.reflect.Field asyncStubField = StatementServiceGrpcClient.class
                    .getDeclaredField("statemetServiceStub");
                asyncStubField.setAccessible(true);
                asyncStubField.set(client, channelAndStub.asyncStub);
                
                log.info("Initialized StatementServiceGrpcClient with connection manager's stubs for endpoint: {}", 
                    ep.getAddress());
            } catch (Exception e) {
                log.error("Failed to initialize client for endpoint {}: {}", ep.getAddress(), e.getMessage(), e);
                throw new RuntimeException("Failed to initialize client for endpoint: " + ep.getAddress(), e);
            }
            
            return client;
        });
    }
    
    /**
     * Checks if a session was created (sessionUUID went from empty to non-empty) and binds it to the server.
     * Also ensures existing sessions remain bound to the correct server.
     * This is called after operations that may create a session (e.g., startTransaction).
     * 
     * @param requestSessionInfo The SessionInfo sent in the request
     * @param responseSessionInfo The SessionInfo received in the response
     * @param server The server that handled the request
     */
    private void checkAndBindSession(SessionInfo requestSessionInfo, SessionInfo responseSessionInfo, ServerEndpoint server) {
        // Check if session was created in this call (request had no UUID, response has UUID)
        boolean requestHadNoSession = requestSessionInfo == null || 
                                      requestSessionInfo.getSessionUUID() == null || 
                                      requestSessionInfo.getSessionUUID().isEmpty();
        boolean responseHasSession = responseSessionInfo != null && 
                                      responseSessionInfo.getSessionUUID() != null && 
                                      !responseSessionInfo.getSessionUUID().isEmpty();
        
        if (responseHasSession) {
            String sessionUUID = responseSessionInfo.getSessionUUID();
            String targetServer = responseSessionInfo.getTargetServer();
            
            // Check if session is already bound
            String currentBinding = connectionManager.getBoundTargetServer(sessionUUID);
            
            if (currentBinding == null) {
                // Session not bound yet - bind it now
                if (targetServer != null && !targetServer.isEmpty()) {
                    connectionManager.bindSession(sessionUUID, targetServer);
                    log.info("Session {} bound to target server {}", sessionUUID, targetServer);
                } else {
                    // Fallback: use server address if targetServer not provided
                    String serverAddress = server.getHost() + ":" + server.getPort();
                    connectionManager.bindSession(sessionUUID, serverAddress);
                    log.info("Session {} bound to server {} (no targetServer in response)", 
                            sessionUUID, serverAddress);
                }
            } else {
                // Session already bound - verify it matches
                String expectedServer = (targetServer != null && !targetServer.isEmpty()) 
                    ? targetServer 
                    : (server.getHost() + ":" + server.getPort());
                
                if (!currentBinding.equals(expectedServer)) {
                    log.warn("Session {} binding mismatch - currently bound to {}, response indicates {}", 
                            sessionUUID, currentBinding, expectedServer);
                }
            }
        }
    }
    
    /**
     * Executes an operation that returns SessionInfo with session stickiness and binding check.
     * This wrapper handles binding newly-created sessions to servers.
     * 
     * @param requestSessionInfo The session info for determining which server to use
     * @param operation The operation to execute
     * @return The SessionInfo result
     * @throws SQLException if the operation fails
     */
    private SessionInfo executeWithSessionStickinessAndBinding(SessionInfo requestSessionInfo, 
                                                                ThrowingFunction<StatementServiceGrpcClient, SessionInfo> operation) 
            throws SQLException {
        // Get the appropriate server based on session binding or round-robin
        ServerEndpoint server = connectionManager.getServerForSession(requestSessionInfo);
        
        log.info("executeWithSessionStickinessAndBinding: session={}, server={}", 
            requestSessionInfo != null ? requestSessionInfo.getSessionUUID() : "null", 
            server != null ? server.getAddress() : "null");
        
        try {
            // Get the channel and stub for the selected server
            MultinodeConnectionManager.ChannelAndStub channelAndStub = 
                    connectionManager.getChannelAndStub(server);
            
            if (channelAndStub == null) {
                throw new SQLException("Unable to get channel for server: " + server.getAddress());
            }
            
            // Get or create the client for this endpoint
            StatementServiceGrpcClient client = getClient(server);
            
            // Execute the operation
            SessionInfo responseSessionInfo = operation.apply(client);
            
            // Check if a session was created and bind it
            checkAndBindSession(requestSessionInfo, responseSessionInfo, server);
            
            return responseSessionInfo;
            
        } catch (StatusRuntimeException e) {
            // Let GrpcExceptionHandler convert the exception
            SQLException sqlEx;
            try {
                throw GrpcExceptionHandler.handle(e);
            } catch (SQLException ex) {
                sqlEx = ex;
            }
            
            // Only mark server unhealthy for connection-level errors
            if (connectionManager.isConnectionLevelError(e)) {
                log.warn("Connection-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
            } else {
                log.debug("Database-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
            }
            
            throw sqlEx;
            
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Unexpected error executing operation on server " + 
                    server.getAddress() + ": " + e.getMessage(), e);
        }
    }
    
    @Override
    public SessionInfo connect(ConnectionDetails connectionDetails) throws SQLException {
        // Use the connection manager to handle connection with retry and failover logic
        return connectionManager.connect(connectionDetails);
    }
    
    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params, 
                                  Map<String, Object> properties) throws SQLException {
        return executeUpdate(sessionInfo, sql, params, "", properties);
    }
    
    @Override
    public OpResult executeUpdate(SessionInfo sessionInfo, String sql, List<Parameter> params, 
                                  String statementUUID, Map<String, Object> properties) throws SQLException {
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.executeUpdate(sessionInfo, sql, params, statementUUID, properties)
        );
    }
    
    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params, 
                                           Map<String, Object> properties) throws SQLException {
        return executeQuery(sessionInfo, sql, params, "", properties);
    }
    
    @Override
    public Iterator<OpResult> executeQuery(SessionInfo sessionInfo, String sql, List<Parameter> params, 
                                           String statementUUID, Map<String, Object> properties) throws SQLException {
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.executeQuery(sessionInfo, sql, params, statementUUID, properties)
        );
    }
    
    @Override
    public OpResult fetchNextRows(SessionInfo sessionInfo, String resultSetUUID, int size) throws SQLException {
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.fetchNextRows(sessionInfo, resultSetUUID, size)
        );
    }
    
    @Override
    public LobReference createLob(Connection connection, Iterator<LobDataBlock> lobDataBlock) throws SQLException {
        SessionInfo sessionInfo = connection.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.createLob(connection, lobDataBlock)
        );
    }
    
    @Override
    public Iterator<LobDataBlock> readLob(LobReference lobReference, long pos, int length) throws SQLException {
        SessionInfo sessionInfo = lobReference.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.readLob(lobReference, pos, length)
        );
    }
    
    @Override
    public void terminateSession(SessionInfo session) {
        try {
            // For sessions with a UUID, terminate only on the bound server
            if (session != null && session.getSessionUUID() != null && !session.getSessionUUID().isEmpty()) {
                String boundServer = connectionManager.getBoundTargetServer(session.getSessionUUID());
                if (boundServer != null) {
                    // Session is bound - terminate on that specific server
                    log.info("Terminating session {} on bound server {}", session.getSessionUUID(), boundServer);
                    try {
                        ServerEndpoint server = connectionManager.getServerForSession(session);
                        StatementServiceGrpcClient client = getClient(server);
                        client.terminateSession(session);
                        log.info("Successfully terminated session {} on server {}", 
                                session.getSessionUUID(), server.getAddress());
                    } catch (SQLException e) {
                        log.warn("Error terminating session {} on bound server: {}", 
                                session.getSessionUUID(), e.getMessage());
                    }
                } else {
                    // Session not bound - try all servers that received connect()
                    log.info("Session {} not bound, attempting termination on all connected servers", 
                            session.getSessionUUID());
                    List<ServerEndpoint> serversToTerminate = connectionManager.getServersForConnHash(
                            session.getConnHash());
                    if (serversToTerminate != null && !serversToTerminate.isEmpty()) {
                        for (ServerEndpoint server : serversToTerminate) {
                            try {
                                StatementServiceGrpcClient client = getClient(server);
                                client.terminateSession(session);
                                log.debug("Terminated session on server {}", server.getAddress());
                            } catch (Exception e) {
                                log.warn("Error terminating session on server {}: {}", 
                                        server.getAddress(), e.getMessage());
                            }
                        }
                    }
                }
            } else {
                // No session UUID - try terminating on all servers that received connect()
                log.info("No sessionUUID, attempting termination on all connected servers");
                List<ServerEndpoint> serversToTerminate = connectionManager.getServersForConnHash(
                        session != null ? session.getConnHash() : null);
                if (serversToTerminate != null && !serversToTerminate.isEmpty()) {
                    for (ServerEndpoint server : serversToTerminate) {
                        try {
                            StatementServiceGrpcClient client = getClient(server);
                            client.terminateSession(session);
                            log.debug("Terminated session on server {}", server.getAddress());
                        } catch (Exception e) {
                            log.warn("Error terminating session on server {}: {}", 
                                    server.getAddress(), e.getMessage());
                        }
                    }
                }
            }
            
            // Clean up connection manager's session tracking
            connectionManager.terminateSession(session);
        } catch (Exception e) {
            log.warn("Error terminating session {}: {}", 
                    session != null ? session.getSessionUUID() : "null", e.getMessage());
            // Best effort - don't throw exception for terminate
        }
    }
    
    @Override
    public SessionInfo startTransaction(SessionInfo session) throws SQLException {
        return executeWithSessionStickinessAndBinding(session, client -> 
            client.startTransaction(session)
        );
    }
    
    @Override
    public SessionInfo commitTransaction(SessionInfo session) throws SQLException {
        return executeWithSessionStickinessAndBinding(session, client -> 
            client.commitTransaction(session)
        );
    }
    
    @Override
    public SessionInfo rollbackTransaction(SessionInfo session) throws SQLException {
        return executeWithSessionStickinessAndBinding(session, client -> 
            client.rollbackTransaction(session)
        );
    }
    
    @Override
    public CallResourceResponse callResource(CallResourceRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.callResource(request)
        );
    }
    
    // XA Transaction Operations
    @Override
    public com.openjproxy.grpc.XaResponse xaStart(com.openjproxy.grpc.XaStartRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.xaStart(request)
        );
    }
    
    @Override
    public com.openjproxy.grpc.XaResponse xaEnd(com.openjproxy.grpc.XaEndRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.xaEnd(request)
        );
    }
    
    @Override
    public com.openjproxy.grpc.XaPrepareResponse xaPrepare(com.openjproxy.grpc.XaPrepareRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.xaPrepare(request)
        );
    }
    
    @Override
    public com.openjproxy.grpc.XaResponse xaCommit(com.openjproxy.grpc.XaCommitRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.xaCommit(request)
        );
    }
    
    @Override
    public com.openjproxy.grpc.XaResponse xaRollback(com.openjproxy.grpc.XaRollbackRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.xaRollback(request)
        );
    }
    
    @Override
    public com.openjproxy.grpc.XaRecoverResponse xaRecover(com.openjproxy.grpc.XaRecoverRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.xaRecover(request)
        );
    }
    
    @Override
    public com.openjproxy.grpc.XaResponse xaForget(com.openjproxy.grpc.XaForgetRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.xaForget(request)
        );
    }
    
    @Override
    public com.openjproxy.grpc.XaSetTransactionTimeoutResponse xaSetTransactionTimeout(
            com.openjproxy.grpc.XaSetTransactionTimeoutRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.xaSetTransactionTimeout(request)
        );
    }
    
    @Override
    public com.openjproxy.grpc.XaGetTransactionTimeoutResponse xaGetTransactionTimeout(
            com.openjproxy.grpc.XaGetTransactionTimeoutRequest request) throws SQLException {
        SessionInfo sessionInfo = request.getSession();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.xaGetTransactionTimeout(request)
        );
    }
    
    @Override
    public com.openjproxy.grpc.XaIsSameRMResponse xaIsSameRM(
            com.openjproxy.grpc.XaIsSameRMRequest request) throws SQLException {
        // For isSameRM, use the first session for server selection
        SessionInfo sessionInfo = request.getSession1();
        return executeWithSessionStickiness(sessionInfo, client -> 
            client.xaIsSameRM(request)
        );
    }
    
    /**
     * Executes an operation with session stickiness and selective error handling.
     * If the session is bound to a server, uses that server.
     * Connection-level errors do not trigger failover for session-bound requests (session stickiness).
     * Database-level errors are propagated without marking servers unhealthy.
     * 
     * @param sessionInfo The session info for determining which server to use
     * @param operation The operation to execute
     * @param <T> The return type of the operation
     * @return The result of the operation
     * @throws SQLException if the operation fails
     */
    private <T> T executeWithSessionStickiness(SessionInfo sessionInfo, 
                                               ThrowingFunction<StatementServiceGrpcClient, T> operation) 
            throws SQLException {
        // Get the appropriate server based on session binding or round-robin
        ServerEndpoint server = connectionManager.getServerForSession(sessionInfo);
        
        log.info("executeWithSessionStickiness: session={}, server={}", 
            sessionInfo != null ? sessionInfo.getSessionUUID() : "null", 
            server != null ? server.getAddress() : "null");
        
        try {
            // Get the channel and stub for the selected server
            MultinodeConnectionManager.ChannelAndStub channelAndStub = 
                    connectionManager.getChannelAndStub(server);
            
            if (channelAndStub == null) {
                throw new SQLException("Unable to get channel for server: " + server.getAddress());
            }
            
            log.info("Got channelAndStub for server {}: blockingStub={}, asyncStub={}", 
                server.getAddress(),
                System.identityHashCode(channelAndStub.blockingStub),
                System.identityHashCode(channelAndStub.asyncStub));
            
            // Get or create the client for this endpoint
            StatementServiceGrpcClient client = getClient(server);
            
            log.info("Using client for server {}, about to execute operation", server.getAddress());
            
            // Execute the operation
            return operation.apply(client);
            
        } catch (StatusRuntimeException e) {
            // Let GrpcExceptionHandler convert the exception
            SQLException sqlEx;
            try {
                throw GrpcExceptionHandler.handle(e);
            } catch (SQLException ex) {
                sqlEx = ex;
            }
            
            // Only mark server unhealthy for connection-level errors
            // Database-level errors (e.g., syntax errors, constraint violations) should not affect server health
            if (connectionManager.isConnectionLevelError(e)) {
                log.warn("Connection-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
                // Note: For session-bound requests, we don't failover - we throw the exception
                // This enforces session stickiness as per the requirements
            } else {
                log.debug("Database-level error on server {}: {}", server.getAddress(), sqlEx.getMessage());
            }
            
            throw sqlEx;
            
        } catch (SQLException e) {
            // Already a SQLException, just throw it
            throw e;
        } catch (Exception e) {
            // Unexpected exception
            throw new SQLException("Unexpected error executing operation on server " + 
                    server.getAddress() + ": " + e.getMessage(), e);
        }
    }
    
    /**
     * Functional interface for operations that throw SQLException.
     */
    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T t) throws SQLException;
    }
    
    /**
     * Shuts down all connections managed by this service.
     */
    public void shutdown() {
        log.info("Shutting down MultinodeStatementService");
        connectionManager.shutdown();
        clientMap.clear();
    }
}
