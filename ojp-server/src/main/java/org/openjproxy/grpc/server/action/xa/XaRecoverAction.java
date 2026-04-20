package org.openjproxy.grpc.server.action.xa;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.transaction.XidHelper.convertXidToProto;

import java.sql.SQLException;

import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;

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

            // Always use the session's own XAResource for recovery.
            //
            // The previous pooled path called registry.xaRecover() which borrowed a *new*
            // backend connection from the pool via pool.borrowObject() -> makeObject().
            // When the pool is empty (e.g. first use with a fresh serverEndpoints hash after
            // switching from 1-node to 3-node), makeObject() opens a physical JDBC connection
            // to the backend database.  If the database is slow or at its connection limit after
            // several prior test suites, that makeObject() call can block indefinitely — there is
            // no connect-timeout configured on the vendor XADataSource by default.  Because the
            // gRPC server-side handler thread is blocked, and the client has no gRPC deadline on
            // xaRecover(), the entire Narayana PeriodicRecovery thread hangs forever.
            //
            // The session was created *specifically* for this recovery scan (Narayana calls
            // getXAConnection() before calling recover()).  Its backend XAResource is already
            // bound to a live physical connection that was established when the session was
            // created.  Using it directly is both cheaper and safe: XAResource.recover() is a
            // read-only query (e.g. SELECT gid FROM pg_prepared_xacts on PostgreSQL) that does
            // not interfere with the connection's current XA state.
            if (session.getXaResource() == null) {
                throw new SQLException("Session does not have XAResource");
            }
            javax.transaction.xa.Xid[] xids = session.getXaResource().recover(request.getFlag());
            if (xids != null) {
                for (javax.transaction.xa.Xid xid : xids) {
                    responseBuilder.addXids(convertXidToProto(xid));
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
