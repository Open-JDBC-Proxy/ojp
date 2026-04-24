package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.XaForgetRequest;
import com.openjproxy.grpc.XaResponse;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;

import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.transaction.XidHelper.convertXid;

/**
 * Action to forget a heuristically completed XA transaction branch.
 * <p>
 * The forget operation tells the resource manager to forget about a
 * heuristically
 * completed transaction branch. This is typically used when a transaction
 * branch
 * has been heuristically committed or rolled back, and the transaction manager
 * wants to inform the resource manager that it no longer needs to track this
 * branch.
 * <p>
 * This action validates that the session is an XA session with an available
 * XA resource before performing the forget operation.
 * <p>
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 * It is stateless and receives all necessary context via parameters.
 */
@Slf4j
public class XaForgetAction implements Action<XaForgetRequest, XaResponse> {

    private static final XaForgetAction INSTANCE = new XaForgetAction();

    /**
     * Private constructor prevents external instantiation.
     */
    private XaForgetAction() {
        // Private constructor for singleton pattern
    }

    /**
     * Returns the singleton instance of XaForgetAction.
     *
     * @return the singleton instance
     */
    public static XaForgetAction getInstance() {
        return INSTANCE;
    }

    /**
     * Executes the XA forget operation for the specified transaction branch.
     * <p>
     * This method retrieves the XA session from the session manager, validates
     * that it is an XA session with an available XA resource, converts the
     * protobuf XID to a javax.transaction.xa.Xid, and calls forget on the
     * XA resource.
     *
     * @param context          the action context containing the session manager
     * @param request          the XA forget request containing the session and XID
     * @param responseObserver the response observer for sending the result
     */
    @Override
    public void execute(ActionContext context, XaForgetRequest request, StreamObserver<XaResponse> responseObserver) {
        log.debug("xaForget: session={}, xid={}",
                request.getSession().getSessionUUID(), request.getXid());

        try {
            SessionManager sessionManager = context.getSessionManager();
            Session session = sessionManager.getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }

            javax.transaction.xa.Xid xid = convertXid(request.getXid());
            session.getXaResource().forget(xid);

            com.openjproxy.grpc.XaResponse response = com.openjproxy.grpc.XaResponse.newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(true)
                    .setMessage("XA forget successful")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaForget", e);
            SQLException sqlException = (e instanceof SQLException ex) ? ex : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }
}
