package org.openjproxy.grpc.server.action.session;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.SessionTerminationStatus;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.xa.pool.XATransactionRegistry;

import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action to terminate a database session and clean up resources.
 * <p>
 * This action handles the termination of a session, including returning any
 * completed XA backend sessions to the pool if applicable.
 * <p>
 * This action is implemented as a singleton for thread-safety and memory efficiency.
 * It is stateless and receives all necessary context via parameters.
 */
@Slf4j
public class TerminateSessionAction implements Action<SessionInfo, SessionTerminationStatus> {

    private static final TerminateSessionAction INSTANCE = new TerminateSessionAction();

    /**
     * Private constructor prevents external instantiation.
     */
    private TerminateSessionAction() {
        // Private constructor for singleton pattern
    }

    /**
     * Returns the singleton instance of TerminateSessionAction.
     *
     * @return the singleton instance
     */
    public static TerminateSessionAction getInstance() {
        return INSTANCE;
    }

    /**
     * Executes the session termination operation.
     *
     * @param context          the action context containing shared state and services
     * @param sessionInfo      the session info to be terminated
     * @param responseObserver the response observer for sending the result
     */
    @Override
    public void execute(ActionContext context, SessionInfo sessionInfo, StreamObserver<SessionTerminationStatus> responseObserver) {
        try {
            log.info("Terminating session");

            // Before terminating, return any completed XA backend sessions to pool
            // This implements the dual-condition lifecycle: sessions are returned when
            // both transaction is complete AND XAConnection is closed
            log.info("[XA-TERMINATE] terminateSession called for sessionUUID={}, isXA={}, connHash={}",
                    sessionInfo.getSessionUUID(), sessionInfo.getIsXA(), sessionInfo.getConnHash());
            
            if (sessionInfo.getIsXA()) {
                String connHash = sessionInfo.getConnHash();
                XATransactionRegistry registry = context.getXaRegistries().get(connHash);
                log.info("[XA-TERMINATE] Looking up XA registry for connHash={}, found={}", connHash, registry != null);
                if (registry != null) {
                    log.info("[XA-TERMINATE] Calling returnCompletedSessions for ojpSessionId={}", sessionInfo.getSessionUUID());
                    int returnedCount = registry.returnCompletedSessions(sessionInfo.getSessionUUID());
                    log.info("[XA-TERMINATE] returnCompletedSessions returned count={}", returnedCount);
                    if (returnedCount > 0) {
                        log.info("Returned {} completed XA backend sessions to pool on session termination", returnedCount);
                    }
                } else {
                    log.warn("[XA-TERMINATE] No XA registry found for connHash={}", connHash);
                }
            }

            log.info("[XA-TERMINATE] Calling sessionManager.terminateSession for sessionUUID={}", sessionInfo.getSessionUUID());
            context.getSessionManager().terminateSession(sessionInfo);
            responseObserver.onNext(SessionTerminationStatus.newBuilder().setTerminated(true).build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            log.error("SQLException during terminateSession", se);
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected error during terminateSession", e);
            sendSQLExceptionMetadata(new SQLException("Unable to terminate session: " + e.getMessage()), responseObserver);
        }
    }
}
