package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.XaSetTransactionTimeoutRequest;
import com.openjproxy.grpc.XaSetTransactionTimeoutResponse;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;

import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * gRPC action that sets the XA transaction timeout for the current session.
 *
 * <p>
 * This action resolves the {@link Session} from the {@link SessionManager},
 * validates that it is an XA session
 * (i.e. {@link Session#isXA()} and {@link Session#getXaResource()} are
 * present), then delegates to the underlying
 * XA resource via {@code XAResource#setTransactionTimeout(int)} using
 * {@link XaSetTransactionTimeoutRequest#getSeconds()}.
 *
 * <p>
 * If the XA resource reports success, the timeout value is also stored on the
 * server-side session via
 * {@link Session#setTransactionTimeout(int)} so that the session state remains
 * consistent with the XA resource.
 *
 * <p>
 * If the session is not an XA session or any error occurs while applying the
 * timeout, the error is reported to
 * the client as SQL exception metadata.
 */
@Slf4j
public class XaSetTransactionTimeoutAction
        implements Action<XaSetTransactionTimeoutRequest, XaSetTransactionTimeoutResponse> {

    private static final XaSetTransactionTimeoutAction INSTANCE = new XaSetTransactionTimeoutAction();

    /**
     * Private constructor prevents external instantiation.
     */
    private XaSetTransactionTimeoutAction() {
        // Private constructor for singleton pattern
    }

    /**
     * Returns the singleton instance of XaSetTransactionTimeoutAction.
     *
     * @return the singleton instance
     */
    public static XaSetTransactionTimeoutAction getInstance() {
        return INSTANCE;
    }


    /**
     * Applies the XA transaction timeout for the provided session.
     *
     * <p>
     * The session is resolved via
     * {@link SessionManager#getSession(com.openjproxy.grpc.SessionInfo)} and must
     * represent
     * an XA session with a non-null XA resource. The timeout is applied using the
     * value in
     * {@link XaSetTransactionTimeoutRequest#getSeconds()}.
     *
     * <p>
     * The response includes the (possibly updated) session info and a
     * {@code success} flag indicating whether the XA
     * resource accepted the timeout. On success, the timeout is also persisted on
     * the server-side session.
     *
     * <p>
     * If the session is not XA or an error occurs, the call is completed with SQL
     * exception metadata.
     *
     * @param context          the action context containing the session manager
     * @param request          request containing the session identifier and timeout
     *                         value in seconds
     * @param responseObserver observer used to stream the
     *                         {@link XaSetTransactionTimeoutResponse} or an error
     */
    @Override
    public void execute(ActionContext context, XaSetTransactionTimeoutRequest request,
                        StreamObserver<XaSetTransactionTimeoutResponse> responseObserver) {
        log.debug("xaSetTransactionTimeout: session={}, seconds={}",
                request.getSession().getSessionUUID(), request.getSeconds());

        try {
            Session session = context.getSessionManager().getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }

            boolean success = session.getXaResource().setTransactionTimeout(request.getSeconds());
            if (success) {
                session.setTransactionTimeout(request.getSeconds());
            }

            com.openjproxy.grpc.XaSetTransactionTimeoutResponse response = com.openjproxy.grpc.XaSetTransactionTimeoutResponse
                    .newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSuccess(success)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaSetTransactionTimeout", e);
            SQLException sqlException = (e instanceof SQLException ex) ? ex : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }
}
