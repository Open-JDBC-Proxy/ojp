package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SqlErrorType;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.grpc.server.CircuitBreaker;
import org.openjproxy.grpc.server.PoolNotFoundException;
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

        // Guard the pre-execution setup so that unexpected failures here still
        // produce a well-formed SQL error on the client instead of a bare
        // gRPC UNKNOWN/INTERNAL status without trailers (which Hibernate/Spring Boot
        // may silently swallow rather than surfacing as a recognizable SQL exception).
        String stmtHash;
        CircuitBreaker circuitBreaker;
        SlowQuerySegregationManager manager;
        try {
            // Update session activity
            updateSessionActivity(context, request.getSession());

            stmtHash = SqlStatementXXHash.hashSqlQuery(request.getSql());
            // Process cluster health from the request
            ProcessClusterHealthAction.getInstance().execute(context, request.getSession());

            String connHash = request.getSession().getConnHash();
            circuitBreaker = context.getCircuitBreakerRegistry().get(connHash);

            // Get the appropriate slow query segregation manager for this datasource
            manager = getSlowQuerySegregationManagerForConnection(context, connHash);
        } catch (Exception setupEx) {
            log.error("Unexpected failure during {} pre-execution setup: {}", operationName, setupEx.getMessage(), setupEx);
            sendSQLExceptionMetadata(new SQLException("Unexpected setup error: " + setupEx.getMessage(), setupEx), responseObserver);
            return;
        }

        long sqlStartNs = System.nanoTime();
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
        } catch (PoolNotFoundException e) {
            // Pool was not found for this connection hash. The server may have restarted
            // and lost its in-memory pool state. Signal the client to reconnect via
            // Status.NOT_FOUND so that the driver can transparently redo connect() and
            // retry the SQL call without surfacing an error to the application.
            log.warn("Pool not found during {} execution, signalling client to reconnect: {}",
                    operationName, e.getMessage());
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .asRuntimeException());
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
            // Wrapped in try-catch so a metrics failure never suppresses the SQL error
            // already sent to the client via responseObserver.onError().
            try {
                String sql = request.getSql();
                if (!sql.isEmpty()) {
                    long executionTimeMs = (System.nanoTime() - sqlStartNs) / 1_000_000L;
                    context.getSqlStatementMetrics().recordSqlExecution(
                            sql, executionTimeMs, manager.isSlowOperation(stmtHash));
                }
            } catch (Exception metricsEx) {
                log.warn("Failed to record SQL execution metrics for {} operation: {}", operationName, metricsEx.getMessage(), metricsEx);
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
