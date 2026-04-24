package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.*;
import io.grpc.stub.StreamObserver;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.*;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.sql.SqlSessionAffinityDetector;
import org.openjproxy.grpc.server.statement.ParameterHandler;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.grpc.server.utils.StatementRequestValidator;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper.sessionConnection;
import static org.openjproxy.grpc.server.action.transaction.CommandExecutionHelper.executeWithResilience;

/**
 * Action to execute SQL update statements (INSERT, UPDATE, DELETE).
 * Handles both regular updates and batch operations, with support for prepared
 * statements, LOB parameters, session affinity, and slow query segregation.
 *
 * @see Action
 */
@SuppressWarnings("java:S6548")
@Slf4j
public class ExecuteUpdateAction implements Action<StatementRequest, OpResult> {

    private static final String UPDATE = "update";

    private static final ExecuteUpdateAction INSTANCE = new ExecuteUpdateAction();

    /**
     * Private constructor for singleton.
     */
    private ExecuteUpdateAction() {
    }

    /**
     * Returns the singleton instance of this action.
     *
     * @return the singleton instance
     */
    public static ExecuteUpdateAction getInstance() {
        return INSTANCE;
    }

    /**
     * Executes an SQL update statement with session validation, circuit breaker
     * checks, and slow query segregation.
     *
     * @param context          the action context containing shared state and
     *                         services
     * @param request          the statement request with SQL and parameters
     * @param responseObserver the gRPC response observer for sending the result
     */
    @SneakyThrows
    @Override
    public void execute(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing update {}", request.getSql());

        executeWithResilience(context, request, responseObserver,
                () -> {
                    OpResult result = executeUpdateInternal(context, request);
                    responseObserver.onNext(result);
                    responseObserver.onCompleted();
                },
                SqlErrorType.SQL_EXCEPTION, UPDATE);
    }

    /**
     * Internal method for executing updates without segregation logic.
     *
     * @param actionContext the action context with session manager and connection
     *                      info
     * @param request       the statement request with SQL and parameters
     * @return the operation result (row count or batch statement UUID)
     * @throws SQLException if the update fails
     */
    @SuppressWarnings("java:S2095")
    private OpResult executeUpdateInternal(ActionContext actionContext, StatementRequest request) throws SQLException {
        int updated = 0;
        SessionInfo returnSessionInfo;
        ConnectionSessionDTO dto = ConnectionSessionDTO.builder().build();

        Statement stmt = null;
        String psUUID = "";
        OpResult.Builder opResultBuilder = OpResult.newBuilder();

        var sessionManager = actionContext.getSessionManager();

        try {
            // Check if SQL requires session affinity (temporary tables, session variables,
            // etc.)
            boolean requiresSessionAffinity = SqlSessionAffinityDetector.requiresSessionAffinity(request.getSql());
            boolean requiresGeneratedKeys = StatementRequestValidator.requiresGeneratedKeysTracking(request);

            dto = sessionConnection(actionContext, request.getSession(),
                    StatementRequestValidator.isAddBatchOperation(request)
                            || requiresGeneratedKeys
                            || requiresSessionAffinity);
            returnSessionInfo = dto.getSession();

            List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
            PreparedStatement ps = dto.getSession() != null && StringUtils.isNotBlank(dto.getSession().getSessionUUID())
                    && StringUtils.isNoneBlank(request.getStatementUUID())
                    ? sessionManager.getPreparedStatement(dto.getSession(), request.getStatementUUID())
                    : null;

            if (CollectionUtils.isNotEmpty(params) || ps != null || requiresGeneratedKeys) {
                if (StringUtils.isNotEmpty(request.getStatementUUID()) && ps != null) {
                    bindLobsAndParameters(sessionManager, dto, ps, params);
                } else {
                    ps = createAndRegisterPreparedStatement(sessionManager, dto, request, params, opResultBuilder);
                }
                if (StatementRequestValidator.isAddBatchOperation(request)) {
                    psUUID = addBatchAndGetStatementUUID(sessionManager, dto, ps, request);
                } else {
                    updated = ps.executeUpdate();
                }
                stmt = ps;
            } else {
                stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
                updated = stmt.executeUpdate(request.getSql());
            }

            OpResult result = buildOpResult(request, opResultBuilder, returnSessionInfo, psUUID, updated);
            
            // Phase 9: Cache Invalidation (after successful update)
            org.openjproxy.grpc.server.cache.QueryCacheHelper.invalidateCacheIfEnabled(actionContext, dto.getSession(), request.getSql());
            
            return result;
        } finally {
            closeStatementAndConnectionIfNoSession(dto, stmt);
        }
    }

    /**
     * Binds LOB streams to the prepared statement and adds parameters.
     * For Postgres, waits for LOB streams to be fully consumed before continuing.
     *
     * @param sessionManager the session manager holding LOB data
     * @param dto            the connection and session DTO
     * @param ps             the prepared statement to bind parameters to
     * @param params         the parameters to bind
     * @throws SQLException if binding fails
     */
    @SuppressWarnings("unchecked")
    private void bindLobsAndParameters(SessionManager sessionManager, ConnectionSessionDTO dto,
                                       PreparedStatement ps, List<Parameter> params) throws SQLException {
        Collection<Object> lobs = sessionManager.getLobs(dto.getSession());
        for (Object o : lobs) {
            LobDataBlocksInputStream lobIS = (LobDataBlocksInputStream) o;
            Map<String, Object> metadata = (Map<String, Object>) sessionManager.getAttr(dto.getSession(),
                    lobIS.getUuid());
            Integer parameterIndex = (Integer) metadata
                    .get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_INDEX + "");
            ps.setBinaryStream(parameterIndex, lobIS);
        }
        if (DbName.POSTGRES.equals(dto.getDbName())) {
            sessionManager.waitLobStreamsConsumption(dto.getSession());
        }
        ParameterHandler.addParametersPreparedStatement(sessionManager, dto.getSession(), ps, params);
    }

    /**
     * Creates a prepared statement and registers it when generated-key tracking is
     * requested (via RETURN_GENERATED_KEYS, column indexes, or column names),
     * populating the result builder with the statement UUID.
     *
     * @param sessionManager  the session manager for statement registration
     * @param dto             the connection and session DTO
     * @param request         the statement request
     * @param params          the parameters to bind
     * @param opResultBuilder the builder to set the statement UUID on when
     *                        generated-key tracking is requested
     * @return the created prepared statement
     * @throws SQLException if creation or registration fails
     */
    private PreparedStatement createAndRegisterPreparedStatement(SessionManager sessionManager,
                                                                 ConnectionSessionDTO dto, StatementRequest request, List<Parameter> params,
                                                                 OpResult.Builder opResultBuilder) throws SQLException {
        PreparedStatement ps = StatementFactory.createPreparedStatement(sessionManager, dto, request.getSql(), params,
                request);
        if (StatementRequestValidator.requiresGeneratedKeysTracking(request)
                && !StatementRequestValidator.isAddBatchOperation(request)) {
            String psNewUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
            opResultBuilder.setUuid(psNewUUID);
        }
        return ps;
    }

    /**
     * Adds the prepared statement to the batch and returns the statement UUID,
     * either by registering a new prepared statement or reusing the existing one.
     *
     * @param sessionManager the session manager for statement registration
     * @param dto            the connection and session DTO
     * @param ps             the prepared statement to add to the batch
     * @param request        the statement request
     * @return the statement UUID (newly registered or from the request)
     * @throws SQLException if adding to batch or registering fails
     */
    private String addBatchAndGetStatementUUID(SessionManager sessionManager, ConnectionSessionDTO dto,
                                               PreparedStatement ps, StatementRequest request) throws SQLException {
        ps.addBatch();
        if (request.getStatementUUID().isBlank()) {
            return sessionManager.registerPreparedStatement(dto.getSession(), ps);
        } else {
            return request.getStatementUUID();
        }
    }

    /**
     * Builds the appropriate {@link OpResult} based on whether it was an add-batch
     * operation (returns UUID) or a regular update (returns row count).
     *
     * @param request           the statement request
     * @param opResultBuilder   the builder for the result
     * @param returnSessionInfo the session info to include in the result
     * @param psUUID            the prepared statement UUID (for batch operations)
     * @param updated           the row count (for regular updates)
     * @return the built {@link OpResult}
     */
    private OpResult buildOpResult(StatementRequest request, OpResult.Builder opResultBuilder,
                                   SessionInfo returnSessionInfo, String psUUID, int updated) {
        if (StatementRequestValidator.isAddBatchOperation(request)) {
            return opResultBuilder
                    .setType(ResultType.UUID_STRING)
                    .setSession(returnSessionInfo)
                    .setUuidValue(psUUID).build();
        }
        return opResultBuilder
                .setType(ResultType.INTEGER)
                .setSession(returnSessionInfo)
                .setIntValue(updated).build();
    }

    /**
     * Closes the statement and its connection when there is no session (stateless
     * execution). This must be done when the connection was obtained without a
     * session, as it would otherwise be left open.
     *
     * @param dto  the connection and session DTO
     * @param stmt the statement to close (may be null)
     */
    private void closeStatementAndConnectionIfNoSession(ConnectionSessionDTO dto, Statement stmt) {
        if ((dto.getSession() == null || StringUtils.isEmpty(dto.getSession().getSessionUUID())) && stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                log.error("Failure closing statement: {}", e.getMessage(), e);
            }
            try {
                stmt.getConnection().close();
            } catch (SQLException e) {
                log.error("Failure closing connection: {}", e.getMessage(), e);
            }
        }
    }
}
