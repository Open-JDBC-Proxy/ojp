package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.TargetCall;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;

import java.sql.SQLException;
import java.util.List;

@Slf4j
public class Savepoint implements java.sql.Savepoint {

    @Getter
    private final String savepointUUID;
    private final StatementService statementService;
    private final Connection connection;

    public Savepoint(String savepointUUID, StatementService statementService, Connection connection) {
        log.debug("Savepoint constructor called: savepointUUID={}", savepointUUID);
        this.savepointUUID = savepointUUID;
        this.statementService = statementService;
        this.connection = connection;
    }

    @Override
    public int getSavepointId() throws SQLException {
        log.debug("getSavepointId called");
        return this.retrieveAttribute(CallType.CALL_GET, "SavepointId", Integer.class);
    }

    @Override
    public String getSavepointName() throws SQLException {
        log.debug("getSavepointName called");
        return this.retrieveAttribute(CallType.CALL_GET, "SavepointName", String.class);
    }

    private <T> T retrieveAttribute(CallType callType, String attrName, Class returnType) throws SQLException {
        log.debug("retrieveAttribute: {}, {}", callType, attrName);
        CallResourceRequest.Builder reqBuilder = CallResourceRequest.newBuilder()
                .setSession(this.connection.getSession())
                .setResourceType(ResourceType.RES_SAVEPOINT)
                .setResourceUUID(this.savepointUUID)
                .setTarget(
                        TargetCall.newBuilder()
                                .setCallType(callType)
                                .setResourceName(attrName)
                                .build()
                );
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.connection.setSession(response.getSession());

        List<ParameterValue> values = response.getValuesList();
        if (values.isEmpty()) {
            return null;
        }

        Object result = ProtoConverter.fromParameterValue(values.get(0));
        return (T) result;
    }
}
