package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SetAutoCommitRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;

import java.sql.Connection;
import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action that forwards a {@code setAutoCommit} call from the driver to the
 * server-side physical connection.
 * <p>
 * The JDBC specification guarantees that {@link Connection#commit()} does
 * <em>not</em> reset {@code autoCommit} — the connection stays in manual-commit
 * mode until {@code setAutoCommit(true)} is explicitly invoked. OJP mirrors
 * this contract: the driver sends this RPC whenever it needs to change the
 * {@code autoCommit} state of the physical connection held in the server-side
 * {@link org.openjproxy.grpc.server.Session}.
 * <p>
 * This keeps the server-side {@code autoCommit} flag in sync with the client,
 * which is essential for correct read/write routing:
 * {@code Session.hasActiveTransaction()} reads {@code connection.getAutoCommit()}
 * to decide whether to route a SELECT to a replica or to the primary.
 */
@Slf4j
public class SetAutoCommitAction {

    private static final SetAutoCommitAction INSTANCE = new SetAutoCommitAction();

    private SetAutoCommitAction() {
    }

    /**
     * Returns the singleton instance of this action.
     *
     * @return the singleton instance
     */
    public static SetAutoCommitAction getInstance() {
        return INSTANCE;
    }

    /**
     * Sets {@code autoCommit} on the physical connection associated with the session.
     *
     * @param context          the action context with session manager and datasource map
     * @param request          the request carrying the session info and desired autoCommit value
     * @param responseObserver the observer to send the updated session info or error metadata
     */
    public void execute(ActionContext context, SetAutoCommitRequest request,
            StreamObserver<SessionInfo> responseObserver) {
        boolean autoCommit = request.getAutoCommit();
        log.debug("setAutoCommit: {}", autoCommit);

        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            Connection conn = context.getSessionManager().getConnection(request.getSession());
            if (conn == null) {
                throw new SQLException("Connection not found for session: "
                        + request.getSession().getSessionUUID());
            }
            conn.setAutoCommit(autoCommit);

            responseObserver.onNext(request.getSession());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            log.error("Error in setAutoCommit action", e);
            sendSQLExceptionMetadata(
                    new SQLException("Unable to set autoCommit: " + e.getMessage()), responseObserver);
        }
    }
}
