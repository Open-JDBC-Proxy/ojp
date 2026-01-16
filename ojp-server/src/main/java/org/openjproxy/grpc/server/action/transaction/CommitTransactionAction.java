package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;
import org.openjproxy.grpc.server.utils.SessionInfoUtils;

import java.sql.Connection;
import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * Action to commit a transaction.
 * Extracts logic from StatementServiceImpl.commitTransaction.
 */
@Slf4j
public class CommitTransactionAction implements Action<SessionInfo, SessionInfo> {

    private final ActionContext context;

    /**
     * Realize a commit transaction.
    */
    public CommitTransactionAction(ActionContext context) {
        this.context = context;
    }

    /**
     * Execute the action.
     * @param sessionInfo Session info.
     * @param responseObserver Response observer.
     */
    @Override
    public void execute(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Commiting transaction");        
        
        new ProcessClusterHealthAction(context).execute(sessionInfo);
        
        try {
            Connection conn = context.getSessionManager().getConnection(sessionInfo);
            conn.commit();

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_COMMITED)
                    .setTransactionUUID(sessionInfo.getTransactionInfo().getTransactionUUID())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(sessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);
            
            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to commit transaction: " + e.getMessage()), responseObserver);
        }
    }
}
