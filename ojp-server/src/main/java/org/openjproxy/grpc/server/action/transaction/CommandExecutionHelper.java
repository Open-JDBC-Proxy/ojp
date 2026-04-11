package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.grpc.server.CircuitBreaker;
import org.openjproxy.grpc.server.SlowQuerySegregationManager;
import org.openjproxy.grpc.server.SqlStatementXXHash;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.action.util.ProcessClusterHealthAction;

import java.sql.SQLDataException;
import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;
import static org.openjproxy.grpc.server.action.session.ResultSetHelper.updateSessionActivity;

@Slf4j
public class CommandExecutionHelper {

    /**
     * Helper method to centralize session validation, activity updates, cluster
     * health processing, circuit breaker checks, and slow query segregation for
     * statement execution. This resolves SonarQube duplication issues.
     *
     * @param context          the action context
     * @param request          the statement request
     * @param responseObserver the response observer for error reporting
     * @param executionLogic   the logic to execute (e.g., update or query)
     */
    public static void executeWithResilience(ActionContext context, StatementRequest request, StreamObserver<OpResult> responseObserver,
                                       StatementExecution executionLogic, SqlErrorType sqlDataExceptionType, String operationName) {

        // Ensure session isn't null
        if (StringUtils.isBlank(request.getSession().getConnHash())) {
            sendSQLExceptionMetadata(new SQLException("Invalid request: Session or ConnHash is missing"), responseObserver);
            log.error("Invalid {} request: Session or ConnHash is missing", operationName);
            return;
        }

        // Update session activity
        updateSessionActivity(context, request.getSession());

        String stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
        // Process cluster health from the request
        ProcessClusterHealthAction.getInstance().execute(context, request.getSession());


        String connHash = request.getSession().getConnHash();
        CircuitBreaker circuitBreaker = context.getCircuitBreakerRegistry().get(connHash);

        // Get the appropriate slow query segregation manager for this datasource
        SlowQuerySegregationManager manager = getSlowQuerySegregationManagerForConnection(context, connHash);
        long sqlStartMs = System.currentTimeMillis();
        try {
            circuitBreaker.preCheck(stmtHash);

            // Execute with slow query segregation, passing actual SQL for metric labelling
            manager.executeWithSegregation(stmtHash, request.getSql(), () -> {
                executionLogic.execute();
                return null;
            });

            circuitBreaker.onSuccess(stmtHash);

        } catch(SQLDataException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL data failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            SqlErrorType type = sqlDataExceptionType != null
                    ? sqlDataExceptionType
                    : SqlErrorType.SQL_EXCEPTION;

            sendSQLExceptionMetadata(e, responseObserver, type);

        } catch (SQLException e) {
            circuitBreaker.onFailure(stmtHash, e);
            log.error("SQL failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            sendSQLExceptionMetadata(e, responseObserver);
        } catch (Exception e) {
            log.error("Unexpected failure during {} execution: {}",
                    operationName, e.getMessage(), e);
            if (e.getCause() instanceof SQLException sqlException) {
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            } else {
                SQLException sqlException = new SQLException("Unexpected error: " + e.getMessage(), e);
                circuitBreaker.onFailure(stmtHash, sqlException);
                sendSQLExceptionMetadata(sqlException, responseObserver);
            }
        } finally {
            // Record SQL execution time for all connections (XA and non-XA) regardless of
            // manager state. This is the single authoritative place for SQL metrics.
            String sql = request.getSql();
            if (!sql.isEmpty()) {
                long executionTimeMs = System.currentTimeMillis() - sqlStartMs;
                context.getSqlStatementMetrics().recordSqlExecution(
                        sql, executionTimeMs, manager.isSlowOperation(stmtHash));
            }
        }
    }


    /**
     * Gets the slow query segregation manager for a specific connection hash.
     * If no manager exists, creates a disabled one as a fallback.
     *
     * @param context  the action context with segregation managers
     * @param connHash the connection hash to look up
     * @return the slow query segregation manager for the connection
     */
    private static SlowQuerySegregationManager getSlowQuerySegregationManagerForConnection(ActionContext context,
                                                                                           String connHash) {
        var slowQuerySegregationManagers = context.getSlowQuerySegregationManagers();

        SlowQuerySegregationManager manager = slowQuerySegregationManagers.get(connHash);
        if (manager == null) {
            log.warn("No SlowQuerySegregationManager found for connection hash {}, creating disabled fallback",
                    connHash);
            manager = new SlowQuerySegregationManager(1, 0, 0, 0, 0, 0, false);
            slowQuerySegregationManagers.put(connHash, manager);
        }
        return manager;
    }

    /**
     * Functional interface for statement execution logic, used by
     * {@link #executeWithResilience} to wrap the actual update/query execution.
     */
    @SuppressWarnings("java:S112")
    @FunctionalInterface
    public interface StatementExecution {
        /**
         * Executes the statement logic.
         */
        void execute() throws Exception;
    }
}
