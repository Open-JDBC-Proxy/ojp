package org.openjproxy.jdbc.xa;

import com.openjproxy.grpc.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.jdbc.Connection;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * XAResource linked to a remote instance of XAResource in OJP server, it delegates all calls to server instance.
 */
@Slf4j
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class OjpXAResource implements XAResource {

    private String resourceUUID;
    private StatementService statementService;
    private Connection connection;

    @Override
    public void start(Xid xid, int flags) throws XAException {
        log.debug("start: xid={}, flags={}", xid, flags);
        try {
            this.callProxy(CallType.CALL_START, "", Void.class, Arrays.asList(xid, flags));
        } catch (Exception e) {
            throw mapToXAException(e);
        }
    }

    @Override
    public void end(Xid xid, int flags) throws XAException {
        log.debug("end: xid={}, flags={}", xid, flags);
        try {
            this.callProxy(CallType.CALL_END, "", Void.class, Arrays.asList(xid, flags));
        } catch (Exception e) {
            throw mapToXAException(e);
        }
    }

    @Override
    public int prepare(Xid xid) throws XAException {
        log.debug("prepare: xid={}", xid);
        try {
            return this.callProxy(CallType.CALL_PREPARE, "", Integer.class, Arrays.asList(xid));
        } catch (Exception e) {
            throw mapToXAException(e);
        }
    }

    @Override
    public void commit(Xid xid, boolean onePhase) throws XAException {
        log.debug("commit: xid={}, onePhase={}", xid, onePhase);
        try {
            this.callProxy(CallType.CALL_COMMIT, "", Void.class, Arrays.asList(xid, onePhase));
        } catch (Exception e) {
            throw mapToXAException(e);
        }
    }

    @Override
    public void rollback(Xid xid) throws XAException {
        log.debug("rollback: xid={}", xid);
        try {
            this.callProxy(CallType.CALL_ROLLBACK, "", Void.class, Arrays.asList(xid));
        } catch (Exception e) {
            throw mapToXAException(e);
        }
    }

    @Override
    public Xid[] recover(int flag) throws XAException {
        log.debug("recover: flag={}", flag);
        try {
            // Recover returns an array - need special handling
            Object result = this.callProxy(CallType.CALL_RECOVER, "", Object.class, Arrays.asList(flag));
            if (result instanceof Xid[]) {
                return (Xid[]) result;
            }
            return new Xid[0];
        } catch (Exception e) {
            throw mapToXAException(e);
        }
    }

    @Override
    public void forget(Xid xid) throws XAException {
        log.debug("forget: xid={}", xid);
        try {
            this.callProxy(CallType.CALL_FORGET, "", Void.class, Arrays.asList(xid));
        } catch (Exception e) {
            throw mapToXAException(e);
        }
    }

    @Override
    public boolean setTransactionTimeout(int seconds) throws XAException {
        log.debug("setTransactionTimeout: seconds={}", seconds);
        try {
            return this.callProxy(CallType.CALL_SET, "TransactionTimeout", Boolean.class, Arrays.asList(seconds));
        } catch (Exception e) {
            throw mapToXAException(e);
        }
    }

    @Override
    public int getTransactionTimeout() throws XAException {
        log.debug("getTransactionTimeout");
        try {
            return this.callProxy(CallType.CALL_GET, "TransactionTimeout", Integer.class);
        } catch (Exception e) {
            throw mapToXAException(e);
        }
    }

    @Override
    public boolean isSameRM(XAResource xares) throws XAException {
        log.debug("isSameRM: xares={}", xares);
        try {
            return this.callProxy(CallType.CALL_IS, "SameRM", Boolean.class, Arrays.asList(xares));
        } catch (Exception e) {
            throw mapToXAException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OjpXAResource)) return false;
        OjpXAResource that = (OjpXAResource) o;
        return Objects.equals(resourceUUID, that.resourceUUID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resourceUUID);
    }

    @Override
    public String toString() {
        return "OjpXAResource{" +
                "resourceUUID='" + resourceUUID + '\'' +
                '}';
    }

    private CallResourceRequest.Builder newCallBuilder() throws SQLException {
        log.debug("newCallBuilder called");
        return CallResourceRequest.newBuilder()
                .setSession(this.connection.getSession())
                .setResourceType(ResourceType.RES_XA_RESOURCE)
                .setResourceUUID(this.resourceUUID != null ? this.resourceUUID : "");
    }

    private <T> T callProxy(CallType callType, String target, Class<T> returnType) throws SQLException {
        log.debug("callProxy: {}, {}, {}", callType, target, returnType);
        return this.callProxy(callType, target, returnType, Arrays.asList());
    }

    /**
     * Calls a method or attribute in the remote OJP proxy server.
     *
     * @param callType   - Call type prefix, for example GET, SET, START, END...
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
        this.connection.setSession(response.getSession());
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

    private XAException mapToXAException(Exception e) {
        log.error("Error in XA operation", e);
        if (e instanceof XAException) {
            return (XAException) e;
        }
        XAException xae = new XAException(XAException.XAER_RMERR);
        xae.initCause(e);
        return xae;
    }
}
