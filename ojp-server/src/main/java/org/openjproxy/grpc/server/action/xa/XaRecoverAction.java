package org.openjproxy.grpc.server.action.xa;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.transaction.XidHelper.convertXidToProto;

import java.sql.SQLException;
import java.util.List;

import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.XidKey;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

/**
 * Action to handle XA transaction recovery logic.
 */
@Slf4j
public class XaRecoverAction
        implements Action<com.openjproxy.grpc.XaRecoverRequest, com.openjproxy.grpc.XaRecoverResponse> {
    private static final XaRecoverAction INSTANCE = new XaRecoverAction();

    private XaRecoverAction() {
        // Private constructor prevents external instantiation
    }

    public static XaRecoverAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, com.openjproxy.grpc.XaRecoverRequest request,
            StreamObserver<com.openjproxy.grpc.XaRecoverResponse> responseObserver) {
        log.debug("xaRecover: session={}, flag={}",
                request.getSession().getSessionUUID(), request.getFlag());

        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            Session session = context.getSessionManager().getSession(request.getSession());
            if (session == null || !session.isXA()) {
                throw new SQLException("Session is not an XA session");
            }

            com.openjproxy.grpc.XaRecoverResponse.Builder responseBuilder = com.openjproxy.grpc.XaRecoverResponse
                    .newBuilder()
                    .setSession(session.getSessionInfo());

            if (context.getXaPoolProvider() != null) {
                String connHash = session.getSessionInfo().getConnHash();
                XATransactionRegistry registry = context.getXaRegistries().get(connHash);
                if (registry == null) {
                    throw new SQLException("No XA registry found for connection hash: " + connHash);
                }

                List<XidKey> xids = registry.xaRecover(request.getFlag());
                for (XidKey xid : xids) {
                    responseBuilder.addXids(convertXidToProto(xid.toXid()));
                }
            } else {
                if (session.getXaResource() == null) {
                    throw new SQLException("Session does not have XAResource");
                }
                javax.transaction.xa.Xid[] xids = session.getXaResource().recover(request.getFlag());
                if (xids != null) {
                    for (javax.transaction.xa.Xid xid : xids) {
                        responseBuilder.addXids(convertXidToProto(xid));
                    }
                }
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaRecover", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }

}
