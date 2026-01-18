package org.openjproxy.grpc.server.action.xa;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.transaction.XidHelper.convertXid;
import static org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer.processClusterHealth;

import java.sql.SQLException;

import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.XidKey;

import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class XaPrepareAction
        implements Action<com.openjproxy.grpc.XaPrepareRequest, com.openjproxy.grpc.XaPrepareResponse> {

    private static final XaPrepareAction INSTANCE = new XaPrepareAction();

    private XaPrepareAction() {
    }

    public static XaPrepareAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context,
            com.openjproxy.grpc.XaPrepareRequest request,
            StreamObserver<com.openjproxy.grpc.XaPrepareResponse> responseObserver) {
        log.debug("xaPrepare: session={}, xid={}",
                request.getSession().getSessionUUID(), request.getXid());

        // Process cluster health changes before XA operation
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            Session session = context.getSessionManager().getSession(request.getSession());
            if (session == null || !session.isXA()) {
                throw new SQLException("Session is not an XA session");
            }

            int result;

            // Branch based on XA pooling configuration
            if (context.getXaPoolProvider() != null) {
                // **NEW PATH: Use XATransactionRegistry**
                String connHash = session.getSessionInfo().getConnHash();
                XATransactionRegistry registry = context.getXaRegistries().get(connHash);
                if (registry == null) {
                    throw new SQLException("No XA registry found for connection hash: " + connHash);
                }

                XidKey xidKey = XidKey.from(convertXid(request.getXid()));
                result = registry.xaPrepare(xidKey);
            } else {
                // **OLD PATH: Pass-through (legacy)**
                if (session.getXaResource() == null) {
                    throw new SQLException("Session does not have XAResource");
                }
                javax.transaction.xa.Xid xid = convertXid(request.getXid());
                result = session.getXaResource().prepare(xid);
            }

            com.openjproxy.grpc.XaPrepareResponse response = com.openjproxy.grpc.XaPrepareResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setResult(result)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaPrepare", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }
}
