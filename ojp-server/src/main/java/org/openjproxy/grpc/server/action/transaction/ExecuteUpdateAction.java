package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.*;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.*;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.streaming.SessionConnectionHelper;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.sql.SqlSessionAffinityDetector;
import org.openjproxy.grpc.server.statement.ParameterHandler;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.grpc.server.utils.StatementRequestValidator;

import java.sql.PreparedStatement;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

@Slf4j
public class ExecuteUpdateAction implements Action<StatementRequest, OpResult> {


    private static final ExecuteUpdateAction INSTANCE = new ExecuteUpdateAction();

    private ExecuteUpdateAction() {
    }

    public static ExecuteUpdateAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing update {}", request.getSql());

        // Update session activity
        updateSessionActivity(context, request.getSession());

        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        var circuitBreaker = context.getCircuitBreaker();

        try {
            circuitBreaker.preCheck(stmtHash);

            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(context, connHash);

            // Execute with slow query segregation
            OpResult result = manager.executeWithSegregation(stmtHash, () -> executeUpdateInternal(context, request));

            responseObserver.onNext(result);
            responseObserver.onCompleted();
            circuitBreaker.onSuccess(stmtHash);

        } catch (SQLDataException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL data failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver, SqlErrorType.SQL_DATA_EXCEPTION);
        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("Failure during update execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during update execution: " + e.getMessage(), e);
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
     * Updates the last activity time for the session to prevent premature cleanup.
     * This should be called at the beginning of any method that operates on a session.
     *
     * @param sessionInfo the session information
     */
    private void updateSessionActivity(ActionContext context, SessionInfo sessionInfo) {
        if (sessionInfo != null && !sessionInfo.getSessionUUID().isEmpty()) {
            context.getSessionManager().updateSessionActivity(sessionInfo);
        }
    }

    /**
     * Gets the slow query segregation manager for a specific connection hash.
     * If no manager exists, creates a disabled one as a fallback.
     */
    private SlowQuerySegregationManager getSlowQuerySegregationManagerForConnection(ActionContext context, String connHash) {
        var slowQuerySegregationManagers = context.getSlowQuerySegregationManagers();
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

    /**
     * Internal method for executing updates without segregation logic.
     */
    private OpResult executeUpdateInternal(ActionContext context, StatementRequest request) throws SQLException {
        int updated = 0;
        SessionInfo returnSessionInfo;
        ConnectionSessionDTO dto = ConnectionSessionDTO.builder().build();

        Statement stmt = null;
        String psUUID = "";
        OpResult.Builder opResultBuilder = OpResult.newBuilder();

        var sessionManager = context.getSessionManager();

        try {
            // Check if SQL requires session affinity (temporary tables, session variables, etc.)
            boolean requiresSessionAffinity = SqlSessionAffinityDetector.requiresSessionAffinity(request.getSql());

            dto = SessionConnectionHelper.sessionConnection(context, request.getSession(), StatementRequestValidator.isAddBatchOperation(request)
                    || StatementRequestValidator.hasAutoGeneratedKeysFlag(request)
                    || requiresSessionAffinity);
            returnSessionInfo = dto.getSession();

            List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
            PreparedStatement ps = dto.getSession() != null && StringUtils.isNotBlank(dto.getSession().getSessionUUID())
                    && StringUtils.isNoneBlank(request.getStatementUUID())
                    ? sessionManager.getPreparedStatement(dto.getSession(), request.getStatementUUID())
                    : null;
            if (CollectionUtils.isNotEmpty(params) || ps != null) {
                if (StringUtils.isNotEmpty(request.getStatementUUID())) {
                    Collection<Object> lobs = sessionManager.getLobs(dto.getSession());
                    for (Object o : lobs) {
                        LobDataBlocksInputStream lobIS = (LobDataBlocksInputStream) o;

                        Map<String, Object> metadata = (Map<String, Object>) sessionManager.getAttr(dto.getSession(),
                                lobIS.getUuid());
                        Integer parameterIndex = (Integer) metadata
                                .get(CommonConstants.PREPARED_STATEMENT_BINARY_STREAM_INDEX + "");
                        if (ps != null) {
                            ps.setBinaryStream(parameterIndex, lobIS);
                        }
                    }
                    if (DbName.POSTGRES.equals(dto.getDbName())) {// Postgres requires check if the lob streams are
                        // fully consumed.
                        sessionManager.waitLobStreamsConsumption(dto.getSession());
                    }
                    if (ps != null) {
                        ParameterHandler.addParametersPreparedStatement(sessionManager, dto.getSession(), ps, params);
                    }
                } else {
                    ps = StatementFactory.createPreparedStatement(sessionManager, dto, request.getSql(), params,
                            request);
                    if (StatementRequestValidator.hasAutoGeneratedKeysFlag(request)) {
                        String psNewUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                        opResultBuilder.setUuid(psNewUUID);
                    }
                }
                if (ps != null) {
                    if (StatementRequestValidator.isAddBatchOperation(request)) {
                        ps.addBatch();
                        if (request.getStatementUUID().isBlank()) {
                            psUUID = sessionManager.registerPreparedStatement(dto.getSession(), ps);
                        } else {
                            psUUID = request.getStatementUUID();
                        }
                    } else {
                        updated = ps.executeUpdate();
                    }
                }
                stmt = ps;
            } else {
                stmt = StatementFactory.createStatement(sessionManager, dto.getConnection(), request);
                updated = stmt.executeUpdate(request.getSql());
            }

            if (StatementRequestValidator.isAddBatchOperation(request)) {
                return opResultBuilder
                        .setType(ResultType.UUID_STRING)
                        .setSession(returnSessionInfo)
                        .setUuidValue(psUUID).build();
            } else {
                return opResultBuilder
                        .setType(ResultType.INTEGER)
                        .setSession(returnSessionInfo)
                        .setIntValue(updated).build();
            }
        } finally {
            // If there is no session, close statement and connection
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
}
