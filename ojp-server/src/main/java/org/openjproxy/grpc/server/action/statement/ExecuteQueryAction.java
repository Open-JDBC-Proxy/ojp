package org.openjproxy.grpc.server.action.statement;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.SlowQuerySegregationManager;
import org.openjproxy.grpc.server.SqlStatementXXHash;
import org.openjproxy.grpc.server.action.Action;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;

import java.sql.SQLDataException;
import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

@Slf4j
public class ExecuteQueryAction implements Action<StatementRequest, OpResult> {

    private static final ExecuteQueryAction INSTANCE = new ExecuteQueryAction();

    private ExecuteQueryAction() {
    }

    public static ExecuteQueryAction getInstance() {
        return INSTANCE;
    }

    @Override
    public void execute(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver) {
        log.info("Executing query for {}", request.getSql());
        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());

        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

        try {
            context.getCircuitBreaker().preCheck(stmtHash);

            // Get the appropriate slow query segregation manager for this datasource
            String connHash = request.getSession().getConnHash();
            SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(context, connHash);

            // Execute with slow query segregation
            manager.executeWithSegregation(stmtHash, () -> {
                ExecuteQueryInternalAction.ExecuteQueryInternalRequest internalRequest = ExecuteQueryInternalAction.ExecuteQueryInternalRequest
                        .builder()
                        .context(context)
                        .statementRequest(request)
                        .responseObserver(responseObserver) // Pass observer
                        .build();
                return ExecuteQueryInternalAction.getInstance().execute(internalRequest);
            });

            context.getCircuitBreaker().onSuccess(stmtHash);
        } catch (SQLException e) {
            context.getCircuitBreaker().onFailure(stmtHash, e);
            log.error("Failure during query execution: " + e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during query execution: " + e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlException) {
                context.getCircuitBreaker().onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                context.getCircuitBreaker().onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        }
    }

    private SlowQuerySegregationManager getSlowQuerySegregationManagerForConnection(ActionContext context,
            String connHash) {
        SlowQuerySegregationManager manager = context.getSlowQuerySegregationManagers().get(connHash);
        if (manager == null) {
            log.warn("No SlowQuerySegregationManager found for connection hash {}, creating disabled fallback",
                    connHash);
            manager = new SlowQuerySegregationManager(1, 0, 0, 0, 0, 0, false);
            context.getSlowQuerySegregationManagers().put(connHash, manager);
        }
        return manager;
    }
}
