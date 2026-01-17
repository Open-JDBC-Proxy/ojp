package org.openjproxy.grpc.server.action.resource;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.UnpooledConnectionDetails;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.utils.MethodNameGenerator;
import org.openjproxy.grpc.server.utils.MethodReflectionUtils;
import org.openjproxy.grpc.server.JavaSqlInterfacesConverter;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.openjproxy.grpc.server.Constants.EMPTY_LIST;
import static org.openjproxy.grpc.server.Constants.EMPTY_MAP;
import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action to call a resource operation.
 * Extracts the logic from StatementServiceImpl.callResource().
 */
@Slf4j
public class CallResourceAction implements Action<CallResourceRequest, CallResourceResponse> {

    private static final CallResourceAction INSTANCE = new CallResourceAction();
    private static final String RESULT_SET_METADATA_ATTR_PREFIX = "rsMetadata|";

    private CallResourceAction() {
        // Private constructor prevents external instantiation
    }

    public static CallResourceAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) {
        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            if (!request.hasSession()) {
                throw new SQLException("No active session.");
            }

            CallResourceResponse.Builder responseBuilder = CallResourceResponse.newBuilder();

            if (this.db2SpecialResultSetMetadata(context, request, responseObserver)) {
                return;
            }

            Object resource;
            switch (request.getResourceType()) {
                case RES_RESULT_SET:
                    resource = context.getSessionManager().getResultSet(request.getSession(), request.getResourceUUID());
                    break;
                case RES_LOB:
                    resource = context.getSessionManager().getLob(request.getSession(), request.getResourceUUID());
                    break;
                case RES_STATEMENT: {
                    ConnectionSessionDTO csDto = sessionConnection(context, request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    java.sql.Statement statement = null;
                    if (!request.getResourceUUID().isBlank()) {
                        statement = context.getSessionManager().getStatement(csDto.getSession(), request.getResourceUUID());
                    } else {
                        statement = csDto.getConnection().createStatement();
                        String uuid = context.getSessionManager().registerStatement(csDto.getSession(), statement);
                        responseBuilder.setResourceUUID(uuid);
                    }
                    resource = statement;
                    break;
                }
                case RES_PREPARED_STATEMENT: {
                    ConnectionSessionDTO csDto = sessionConnection(context, request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    PreparedStatement ps = null;
                    if (!request.getResourceUUID().isBlank()) {
                        ps = context.getSessionManager().getPreparedStatement(request.getSession(), request.getResourceUUID());
                    } else {
                        Map<String, Object> mapProperties = EMPTY_MAP;
                        if (!request.getPropertiesList().isEmpty()) {
                            mapProperties = ProtoConverter.propertiesFromProto(request.getPropertiesList());
                        }
                        ps = csDto.getConnection().prepareStatement((String) mapProperties.get(CommonConstants.PREPARED_STATEMENT_SQL_KEY));
                        String uuid = context.getSessionManager().registerPreparedStatement(csDto.getSession(), ps);
                        responseBuilder.setResourceUUID(uuid);
                    }
                    resource = ps;
                    break;
                }
                case RES_CALLABLE_STATEMENT:
                    resource = context.getSessionManager().getCallableStatement(request.getSession(), request.getResourceUUID());
                    break;
                case RES_CONNECTION: {
                    ConnectionSessionDTO csDto = sessionConnection(context, request.getSession(), true);
                    responseBuilder.setSession(csDto.getSession());
                    resource = csDto.getConnection();
                    break;
                }
                case RES_SAVEPOINT:
                    resource = context.getSessionManager().getAttr(request.getSession(), request.getResourceUUID());
                    break;
                default:
                    throw new RuntimeException("Resource type invalid");
            }

            if (responseBuilder.getSession() == null || StringUtils.isBlank(responseBuilder.getSession().getSessionUUID())) {
                responseBuilder.setSession(request.getSession());
            }

            List<Object> paramsReceived = (request.getTarget().getParamsCount() > 0) ?
                    ProtoConverter.parameterValuesToObjectList(request.getTarget().getParamsList()) : EMPTY_LIST;
            Class<?> clazz = resource.getClass();
            if ((paramsReceived != null && !paramsReceived.isEmpty()) &&
                    ((CallType.CALL_RELEASE.equals(request.getTarget().getCallType()) &&
                            "Savepoint".equalsIgnoreCase(request.getTarget().getResourceName())) ||
                            (CallType.CALL_ROLLBACK.equals(request.getTarget().getCallType()))
                    )
            ) {
                Savepoint savepoint = (Savepoint) context.getSessionManager().getAttr(request.getSession(),
                        (String) paramsReceived.get(0));
                paramsReceived.set(0, savepoint);
            }
            Method method = MethodReflectionUtils.findMethodByName(JavaSqlInterfacesConverter.interfaceClass(clazz),
                    MethodNameGenerator.methodName(request.getTarget()), paramsReceived);
            java.lang.reflect.Parameter[] params = method.getParameters();
            Object resultFirstLevel = null;
            if (params != null && params.length > 0) {
                resultFirstLevel = method.invoke(resource, paramsReceived.toArray());
                if (resultFirstLevel instanceof CallableStatement) {
                    CallableStatement cs = (CallableStatement) resultFirstLevel;
                    resultFirstLevel = context.getSessionManager().registerCallableStatement(responseBuilder.getSession(), cs);
                }
            } else {
                resultFirstLevel = method.invoke(resource);
                if (resultFirstLevel instanceof ResultSet) {
                    ResultSet rs = (ResultSet) resultFirstLevel;
                    resultFirstLevel = context.getSessionManager().registerResultSet(responseBuilder.getSession(), rs);
                } else if (resultFirstLevel instanceof Array) {
                    Array array = (Array) resultFirstLevel;
                    String arrayUUID = UUID.randomUUID().toString();
                    context.getSessionManager().registerAttr(responseBuilder.getSession(), arrayUUID, array);
                    resultFirstLevel = arrayUUID;
                }
            }
            if (resultFirstLevel instanceof Savepoint) {
                Savepoint sp = (Savepoint) resultFirstLevel;
                String uuid = UUID.randomUUID().toString();
                resultFirstLevel = uuid;
                context.getSessionManager().registerAttr(responseBuilder.getSession(), uuid, sp);
            }
            if (request.getTarget().hasNextCall()) {
                //Second level calls, for cases like getMetadata().isAutoIncrement(int column)
                Class<?> clazzNext = resultFirstLevel.getClass();
                List<Object> paramsReceived2 = (request.getTarget().getNextCall().getParamsCount() > 0) ?
                        ProtoConverter.parameterValuesToObjectList(request.getTarget().getNextCall().getParamsList()) :
                        EMPTY_LIST;
                Method methodNext = MethodReflectionUtils.findMethodByName(JavaSqlInterfacesConverter.interfaceClass(clazzNext),
                        MethodNameGenerator.methodName(request.getTarget().getNextCall()),
                        paramsReceived2);
                params = methodNext.getParameters();
                Object resultSecondLevel = null;
                if (params != null && params.length > 0) {
                    resultSecondLevel = methodNext.invoke(resultFirstLevel, paramsReceived2.toArray());
                } else {
                    resultSecondLevel = methodNext.invoke(resultFirstLevel);
                }
                if (resultSecondLevel instanceof ResultSet) {
                    ResultSet rs = (ResultSet) resultSecondLevel;
                    resultSecondLevel = context.getSessionManager().registerResultSet(responseBuilder.getSession(), rs);
                }
                responseBuilder.addValues(ProtoConverter.toParameterValue(resultSecondLevel));
            } else {
                responseBuilder.addValues(ProtoConverter.toParameterValue(resultFirstLevel));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof SQLException) {
                SQLException sqlException = (SQLException) e.getTargetException();
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                sendSQLExceptionMetadata(new SQLException("Unable to call resource: " + e.getTargetException().getMessage()),
                        responseObserver);
            }
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to call resource: " + e.getMessage(), e), responseObserver);
        }
    }

    private boolean db2SpecialResultSetMetadata(ActionContext context, CallResourceRequest request, StreamObserver<CallResourceResponse> responseObserver) throws SQLException {
        if (DbName.DB2.equals(context.getDbNameMap().get(request.getSession().getConnHash())) &&
                ResourceType.RES_RESULT_SET.equals(request.getResourceType()) &&
                CallType.CALL_GET.equals(request.getTarget().getCallType()) &&
                "Metadata".equalsIgnoreCase(request.getTarget().getResourceName())) {
            ResultSetMetaData resultSetMetaData = (ResultSetMetaData) context.getSessionManager().getAttr(request.getSession(),
                    RESULT_SET_METADATA_ATTR_PREFIX + request.getResourceUUID());
            List<Object> paramsReceived = (request.getTarget().getNextCall().getParamsCount() > 0) ?
                    ProtoConverter.parameterValuesToObjectList(request.getTarget().getNextCall().getParamsList()) :
                    EMPTY_LIST;
            Method methodNext = MethodReflectionUtils.findMethodByName(ResultSetMetaData.class,
                    MethodNameGenerator.methodName(request.getTarget().getNextCall()),
                    paramsReceived);
            try {
                Object metadataResult = methodNext.invoke(resultSetMetaData, paramsReceived.toArray());
                responseObserver.onNext(CallResourceResponse.newBuilder()
                        .setSession(request.getSession())
                        .addValues(ProtoConverter.toParameterValue(metadataResult))
                        .build());
                responseObserver.onCompleted();
                return true;
            } catch (Exception e) {
                throw new SQLException("Failed to call DB2 special result set metadata", e);
            }
        }
        return false;
    }

    private ConnectionSessionDTO sessionConnection(ActionContext context, SessionInfo sessionInfo, boolean startSessionIfNone) throws SQLException {
        ConnectionSessionDTO.ConnectionSessionDTOBuilder dtoBuilder = ConnectionSessionDTO.builder();
        dtoBuilder.session(sessionInfo);
        Connection conn;

        if (StringUtils.isNotEmpty(sessionInfo.getSessionUUID())) {
            // Session already exists, reuse its connection
            conn = context.getSessionManager().getConnection(sessionInfo);
            if (conn == null) {
                throw new SQLException("Connection not found for this sessionInfo");
            }
            dtoBuilder.dbName(DatabaseUtils.resolveDbName(conn.getMetaData().getURL()));
            if (conn.isClosed()) {
                throw new SQLException("Connection is closed");
            }
        } else {
            // Lazy allocation: check if this is an XA or regular connection
            String connHash = sessionInfo.getConnHash();
            boolean isXA = sessionInfo.getIsXA();

            if (isXA) {
                // XA connection - check if unpooled or pooled mode
                XADataSource xaDataSource = context.getXaDataSourceMap().get(connHash);

                if (xaDataSource != null) {
                    // Unpooled XA mode: create XAConnection on demand
                    try {
                        log.debug("Creating unpooled XAConnection for hash: {}", connHash);
                        XAConnection xaConnection = xaDataSource.getXAConnection();
                        conn = xaConnection.getConnection();

                        // Store the XAConnection in session for XA operations
                        if (startSessionIfNone) {
                            SessionInfo updatedSession = context.getSessionManager().createSession(sessionInfo.getClientUUID(), conn);
                            // Store XAConnection as an attribute for XA operations
                            context.getSessionManager().registerAttr(updatedSession, "xaConnection", xaConnection);
                            dtoBuilder.session(updatedSession);
                        }
                        log.debug("Successfully created unpooled XAConnection for hash: {}", connHash);
                    } catch (SQLException e) {
                        log.error("Failed to create unpooled XAConnection for hash: {}. Error: {}",
                                connHash, e.getMessage());
                        throw e;
                    }
                } else {
                    // Pooled XA mode - should already have a session created in connect()
                    // This shouldn't happen as XA sessions are created eagerly
                    throw new SQLException("XA session should already exist. Session UUID is missing.");
                }
            } else {
                // Regular connection - check if pooled or unpooled mode
                UnpooledConnectionDetails unpooledDetails = context.getUnpooledConnectionDetailsMap().get(connHash);

                if (unpooledDetails != null) {
                    // Unpooled mode: create direct connection without pooling
                    try {
                        log.debug("Creating unpooled (passthrough) connection for hash: {}", connHash);
                        conn = java.sql.DriverManager.getConnection(
                                unpooledDetails.getUrl(),
                                unpooledDetails.getUsername(),
                                unpooledDetails.getPassword());
                        log.debug("Successfully created unpooled connection for hash: {}", connHash);

                        if (startSessionIfNone) {
                            SessionInfo updatedSession = context.getSessionManager().createSession(sessionInfo.getClientUUID(), conn);
                            dtoBuilder.session(updatedSession);
                        }
                    } catch (SQLException e) {
                        log.error("Failed to create unpooled connection for hash: {}. Error: {}",
                                connHash, e.getMessage());
                        throw e;
                    }
                } else {
                    // Pooled mode: get connection from Hikari datasource
                    DataSource ds = context.getDatasourceMap().get(connHash);
                    if (ds == null) {
                        throw new SQLException("DataSource not found for connection hash: " + connHash);
                    }
                    conn = ds.getConnection();
                    log.debug("Successfully got pooled connection for hash: {}", connHash);

                    if (startSessionIfNone) {
                        SessionInfo updatedSession = context.getSessionManager().createSession(sessionInfo.getClientUUID(), conn);
                        dtoBuilder.session(updatedSession);
                    }
                }
                dtoBuilder.dbName(DatabaseUtils.resolveDbName(conn.getMetaData().getURL()));
            }
        }
        dtoBuilder.connection(conn);
        return dtoBuilder.build();
    }
}
