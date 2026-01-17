package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.XaGetTransactionTimeoutRequest;
import com.openjproxy.grpc.XaGetTransactionTimeoutResponse;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;

import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * gRPC action that returns the current XA transaction timeout (in seconds) for
 * the given session.
 *
 * <p>
 * The request's session must resolve to an XA-capable {@link Session} with a
 * non-null XA resource;
 * otherwise a {@link SQLException} is raised and propagated to the client via
 * SQL exception metadata.
 */
@Slf4j
public class XaGetTransactionTimeoutAction
        implements Action<XaGetTransactionTimeoutRequest, XaGetTransactionTimeoutResponse> {

    private static final XaGetTransactionTimeoutAction INSTANCE = new XaGetTransactionTimeoutAction();

    /**
     * Private constructor prevents external instantiation.
     */
    private XaGetTransactionTimeoutAction() {
        // Private constructor for singleton pattern
    }

    /**
     * Returns the singleton instance of XaGetTransactionTimeoutAction.
     * @return the singleton instance
     */
    public static XaGetTransactionTimeoutAction getInstance() {
        return INSTANCE;
    }

    /**
     * Retrieves the current XA transaction timeout (in seconds) for the given
     * session.
     *
     * <p>
     * The session is resolved via
     * {@link SessionManager#getSession(com.openjproxy.grpc.SessionInfo)} and must
     * represent an XA session with a non-null XA resource. The timeout is retrieved
     * using {@code XAResource#getTransactionTimeout()}.
     *
     * <p>
     * The response includes the session info and the timeout value in seconds.
     *
     * <p>
     * If the session is not XA or an error occurs, the call is completed with SQL
     * exception metadata.
     *
     * @param context          the action context containing the session manager
     * @param request          request containing the session identifier
     * @param responseObserver observer used to stream the
     *                         {@link XaGetTransactionTimeoutResponse} or an error
     */
    @Override
    public void execute(ActionContext context, XaGetTransactionTimeoutRequest request,
                        StreamObserver<XaGetTransactionTimeoutResponse> responseObserver) {
        log.debug("xaGetTransactionTimeout: session={}", request.getSession().getSessionUUID());

        try {
            Session session = context.getSessionManager().getSession(request.getSession());
            if (session == null || !session.isXA() || session.getXaResource() == null) {
                throw new SQLException("Session is not an XA session");
            }

            int timeout = session.getXaResource().getTransactionTimeout();

            com.openjproxy.grpc.XaGetTransactionTimeoutResponse response = com.openjproxy.grpc.XaGetTransactionTimeoutResponse
                    .newBuilder()
                    .setSession(session.getSessionInfo())
                    .setSeconds(timeout)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaGetTransactionTimeout", e);
            SQLException sqlException = (e instanceof SQLException ex) ? ex : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }
}
