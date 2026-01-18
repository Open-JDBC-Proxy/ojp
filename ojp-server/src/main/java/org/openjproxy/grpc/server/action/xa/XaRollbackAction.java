package org.openjproxy.grpc.server.action.xa;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.transaction.XidHelper.convertXid;

import java.sql.SQLException;

import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.xa.pool.XATransactionRegistry;
import org.openjproxy.xa.pool.XidKey;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

/**
 * Action to handle XA transaction rollback logic.
 */
@Slf4j
public class XaRollbackAction implements Action<com.openjproxy.grpc.XaRollbackRequest, com.openjproxy.grpc.XaResponse> {
    private static final XaRollbackAction INSTANCE = new XaRollbackAction();

    private XaRollbackAction() {
        // Private constructor prevents external instantiation
    }

    public static XaRollbackAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, com.openjproxy.grpc.XaRollbackRequest request,
            StreamObserver<com.openjproxy.grpc.XaResponse> responseObserver) {

        log.debug("xaRollback: session={}, xid={}",
                request.getSession().getSessionUUID(), request.getXid());

        // Process cluster health changes before XA operation
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            Session session = context.getSessionManager().getSession(request.getSession());
            if (session == null || !session.isXA()) {
                throw new SQLException("Session is not an XA session");
            }

            // Branch based on XA pooling configuration
            if (context.getXaPoolProvider() != null) {
                // **NEW PATH: Use XATransactionRegistry**
                String connHash = session.getSessionInfo().getConnHash();
                XATransactionRegistry registry = context.getXaRegistries().get(connHash);
                if (registry == null) {
                    throw new SQLException("No XA registry found for connection hash: " + connHash);
                }

                XidKey xidKey = XidKey.from(convertXid(request.getXid()));
                registry.xaRollback(xidKey);

                // NOTE: Do NOT unbind XAConnection here - it stays bound for session lifetime
                // XABackendSession will be returned to pool when OJP Session terminates
            } else {
                // **OLD PATH: Pass-through (legacy)**
                if (session.getXaResource() == null) {
                    throw new SQLException("Session does not have XAResource");
                }
                javax.transaction.xa.Xid xid = convertXid(request.getXid());
                session.getXaResource().rollback(xid);
            }

            com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(true)
                    .setMessage("XA rollback successful")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaRollback", e);
            SQLException sqlException = (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }

    }

}
