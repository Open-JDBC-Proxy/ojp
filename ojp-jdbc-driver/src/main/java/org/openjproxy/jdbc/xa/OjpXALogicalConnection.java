package org.openjproxy.jdbc.xa;

import com.openjproxy.grpc.*;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.jdbc.Connection;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Logical connection that wraps the XA session on the server.
 * This is a thin remote proxy that delegates to the server-side XA logical connection.
 */
@Slf4j
public class OjpXALogicalConnection extends Connection {

    private final String resourceUUID;
    private final StatementService statementService;
    private final Connection parentConnection;

    public OjpXALogicalConnection(String resourceUUID, StatementService statementService, Connection parentConnection) {
        super(parentConnection.getSession(), statementService, parentConnection.getDbName());
        this.resourceUUID = resourceUUID;
        this.statementService = statementService;
        this.parentConnection = parentConnection;
        log.debug("Created OjpXALogicalConnection with UUID: {}", resourceUUID);
    }

    @Override
    public void close() throws SQLException {
        log.debug("Logical connection close called");
        this.callProxy(CallType.CALL_CLOSE, "", Void.class);
    }

    @Override
    public boolean isClosed() throws SQLException {
        return this.callProxy(CallType.CALL_IS, "Closed", Boolean.class);
    }

    @Override
    public void commit() throws SQLException {
        log.debug("commit called on logical connection - should be controlled by XA");
        throw new SQLException("Commit not allowed on XA connection. Use XAResource.commit() instead.");
    }

    @Override
    public void rollback() throws SQLException {
        log.debug("rollback called on logical connection - should be controlled by XA");
        throw new SQLException("Rollback not allowed on XA connection. Use XAResource.rollback() instead.");
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        // XA connections ignore auto-commit settings as they are controlled by XA protocol
        log.debug("setAutoCommit({}) called on XA connection - ignored (XA protocol controls transaction)", autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        // XA connections are always non-auto-commit
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OjpXALogicalConnection)) return false;
        OjpXALogicalConnection that = (OjpXALogicalConnection) o;
        return Objects.equals(resourceUUID, that.resourceUUID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resourceUUID);
    }

    @Override
    public String toString() {
        return "OjpXALogicalConnection{" +
                "resourceUUID='" + resourceUUID + '\'' +
                '}';
    }

    private CallResourceRequest.Builder newCallBuilder() throws SQLException {
        log.debug("newCallBuilder called");
        return CallResourceRequest.newBuilder()
                .setSession(this.parentConnection.getSession())
                .setResourceType(ResourceType.RES_XA_LOGICAL_CONNECTION)
                .setResourceUUID(this.resourceUUID != null ? this.resourceUUID : "");
    }

    private <T> T callProxy(CallType callType, String target, Class<T> returnType) throws SQLException {
        log.debug("callProxy: {}, {}, {}", callType, target, returnType);
        return this.callProxy(callType, target, returnType, Arrays.asList());
    }

    /**
     * Calls a method or attribute in the remote OJP proxy server.
     *
     * @param callType   - Call type prefix, for example GET, SET, CLOSE...
     * @param target     - Target name of the method or attribute being called.
     * @param returnType - Type returned if a return is present, if not Void.class
     * @param params     - List of parameters required to execute the method.
     * @return - Returns the type passed as returnType parameter.
     * @throws SQLException - In case of failure of call or interface not supported.
     */
    private <T> T callProxy(CallType callType, String target, Class<T> returnType, List<Object> params) throws SQLException {
        log.debug("callProxy: {}, {}, {}, <params>", callType, target, returnType);
        CallResourceRequest.Builder reqBuilder = this.newCallBuilder();
        reqBuilder.setTarget(
                TargetCall.newBuilder()
                        .setCallType(callType)
                        .setResourceName(target)
                        .addAllParams(ProtoConverter.objectListToParameterValues(params))
                        .build()
        );
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.parentConnection.setSession(response.getSession());
        if (Void.class.equals(returnType)) {
            return null;
        }

        List<ParameterValue> values = response.getValuesList();
        if (values.isEmpty()) {
            return null;
        }

        Object result = ProtoConverter.fromParameterValue(values.get(0));
        return (T) result;
    }
}
