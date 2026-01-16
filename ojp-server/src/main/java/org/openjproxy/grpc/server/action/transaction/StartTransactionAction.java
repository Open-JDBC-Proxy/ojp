package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.utils.SessionInfoUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import com.zaxxer.hikari.HikariDataSource;

@Slf4j
public class StartTransactionAction implements Action<SessionInfo, SessionInfo> {

    private static final StartTransactionAction INSTANCE = new StartTransactionAction();    

    private StartTransactionAction() {        
    }

    public static StartTransactionAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(SessionInfo sessionInfo, StreamObserver<SessionInfo> responseObserver) {
        log.info("Starting transaction");

        // Process cluster health from the request
        processClusterHealth(sessionInfo);

        try {
            SessionInfo activeSessionInfo = sessionInfo;

            //Start a session if none started yet.
            if (StringUtils.isEmpty(sessionInfo.getSessionUUID())) {
                Connection conn = this.context.getDatasourceMap().get(sessionInfo.getConnHash()).getConnection();
                activeSessionInfo = context.getSessionManager().createSession(sessionInfo.getClientUUID(), conn);
                // Preserve targetServer from incoming request
                activeSessionInfo = SessionInfoUtils.withTargetServer(activeSessionInfo, getTargetServer(sessionInfo));
            }
            Connection sessionConnection = context.getSessionManager().getConnection(activeSessionInfo);
            //Start a transaction
            sessionConnection.setAutoCommit(Boolean.FALSE);

            TransactionInfo transactionInfo = TransactionInfo.newBuilder()
                    .setTransactionStatus(TransactionStatus.TRX_ACTIVE)
                    .setTransactionUUID(UUID.randomUUID().toString())
                    .build();

            SessionInfo.Builder sessionInfoBuilder = SessionInfoUtils.newBuilderFrom(activeSessionInfo);
            sessionInfoBuilder.setTransactionInfo(transactionInfo);
            // Server echoes back targetServer from incoming request (preserved by newBuilderFrom)

            responseObserver.onNext(sessionInfoBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException se) {
            sendSQLExceptionMetadata(se, responseObserver);
        } catch (Exception e) {
            sendSQLExceptionMetadata(new SQLException("Unable to start transaction: " + e.getMessage()), responseObserver);
        }
    }

    private String getTargetServer(SessionInfo incomingSessionInfo) {
        // Echo back the targetServer from incoming request, or return empty string if not present
        if (incomingSessionInfo != null && 
            incomingSessionInfo.getTargetServer() != null && 
            !incomingSessionInfo.getTargetServer().isEmpty()) {
            return incomingSessionInfo.getTargetServer();
        }
        
        // Return empty string if client didn't send targetServer
        return "";
    }

    private void processClusterHealth(SessionInfo sessionInfo) {
        if (sessionInfo == null) return;

        String connHash = sessionInfo.getConnHash();
        String clusterHealth = sessionInfo.getClusterHealth();

        if (StringUtils.isEmpty(connHash) || StringUtils.isEmpty(clusterHealth)) {
            return;
        }

        ConnectionPoolConfigurer.processClusterHealth(
                connHash,
                clusterHealth,
                context.getClusterHealthTracker(),
                (HikariDataSource) context.getDatasourceMap().get(connHash)
        );
    }
}
