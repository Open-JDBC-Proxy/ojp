package org.openjproxy.grpc.client;

import com.openjproxy.grpc.SqlErrorResponse;
import com.openjproxy.grpc.SqlErrorType;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;

import java.sql.SQLDataException;
import java.sql.SQLException;

public class GrpcExceptionHandler {
    /**
     * Handler for StatusRuntimeException, converting it to a SQLException when SQL metadata returned.
     *
     * @param sre StatusRuntimeException
     * @return StatusRuntimeException if SQL metadata not found just return the exception received.
     * @throws SQLException If conversion possible.
     */
    public static StatusRuntimeException handle(StatusRuntimeException sre) throws SQLException {
        Metadata metadata = Status.trailersFromThrowable(sre);
        SqlErrorResponse errorResponse = metadata.get(ProtoUtils.keyForProto(SqlErrorResponse.getDefaultInstance()));
        if (errorResponse == null) {
            return sre;
        }
        if (SqlErrorType.SQL_DATA_EXCEPTION.equals(errorResponse.getSqlErrorType())) {
            throw new SQLDataException(errorResponse.getReason(), errorResponse.getSqlState(),
                    errorResponse.getVendorCode());
        } else {
            throw new SQLException(errorResponse.getReason(), errorResponse.getSqlState(),
                    errorResponse.getVendorCode());
        }
    }
    
    /**
     * Determines if an exception signals that the server has no pool for the current
     * connection hash and the client must reconnect.
     *
     * <p>The server emits {@code Status.NOT_FOUND} when {@code SessionConnectionHelper}
     * throws {@link org.openjproxy.grpc.server.PoolNotFoundException}. This happens when
     * the server restarts and loses its in-memory datasource map, or when a client
     * sends SQL without having first called {@code connect()}.
     *
     * @param exception the exception to inspect
     * @return {@code true} if the driver should invalidate its cached connHash,
     *         issue a fresh {@code connect()} RPC, and retry the SQL call
     */
    public static boolean isPoolNotFoundException(Exception exception) {
        if (exception instanceof StatusRuntimeException) {
            StatusRuntimeException sre = (StatusRuntimeException) exception;
            return sre.getStatus().getCode() == Status.Code.NOT_FOUND;
        }
        return false;
    }

    /**
     * Determines if an exception represents a session invalidation error.
     * Session invalidation occurs when the health checker removes session bindings
     * after detecting server failure. These sessions are permanently lost.
     * 
     * @param exception The exception to check
     * @return true if this is a session invalidation error
     */
    public static boolean isSessionInvalidationError(Exception exception) {
        if (exception instanceof SQLException) {
            String message = exception.getMessage();
            if (message != null) {
                String lowerMessage = message.toLowerCase();
                return lowerMessage.contains("session") &&
                       (lowerMessage.contains("has no associated server") || 
                        lowerMessage.contains("binding was lost") ||
                        lowerMessage.contains("may have expired") ||
                        lowerMessage.contains("were invalidated"));
            }
        }
        return false;
    }
    
    /**
     * Determines if an exception represents a connection-level error that indicates server unavailability.
     * Connection-level errors include:
     * - UNAVAILABLE: Server not reachable
     * - DEADLINE_EXCEEDED: Request timeout
     * - UNKNOWN: Connection-related unknown errors
     *
     * Database-level errors (e.g., table not found, syntax errors) do not indicate server unavailability.
     * Pool exhaustion errors do NOT indicate server unavailability - they indicate resource limits, not connectivity issues.
     * Session invalidation errors do NOT indicate server unavailability - they indicate the session was lost/expired.
     * SQL exceptions from the server are sent with {@code Status.INTERNAL} and carry SQL metadata in the trailers;
     * they must NOT be treated as connectivity failures.
     *
     * @param exception The exception to check
     * @return true if this is a connection-level error indicating server unavailability
     */
    public static boolean isConnectionLevelError(Exception exception) {
        if (exception instanceof StatusRuntimeException) {
            StatusRuntimeException statusException = (StatusRuntimeException) exception;
            Status.Code code = statusException.getStatus().getCode();
            
            // Only these status codes indicate connection-level failures
            return code == Status.Code.UNAVAILABLE ||
                   code == Status.Code.DEADLINE_EXCEEDED ||
                   (code == Status.Code.UNKNOWN && 
                    statusException.getMessage() != null && 
                    (statusException.getMessage().contains("connection") || 
                     statusException.getMessage().contains("Connection")));
        }
        
        // For non-gRPC exceptions, check for connection-related keywords
        String message = exception.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            
            // CRITICAL: Pool exhaustion is NOT a server connectivity issue
            // Don't mark server unhealthy when pool is exhausted - it's a resource limit, not a connection failure
            if (lowerMessage.contains("pool exhausted") || lowerMessage.contains("pool is exhausted")) {
                return false;
            }
            
            // CRITICAL: Session invalidation/loss is NOT a connection-level error
            // Sessions are explicitly invalidated when servers fail. The session is permanently lost.
            // Retrying with the same session will always fail. This needs proper XA transaction handling, not retry.
            if (isSessionInvalidationError(exception)) {
                return false;
            }
            
            // Check for connectivity-related keywords
            // Using substring matching intentionally - these patterns appear in various error messages
            // from our multinode connection management and should all be treated as connectivity issues
            return lowerMessage.contains("connection") || 
                   lowerMessage.contains("timeout") ||
                   lowerMessage.contains("unavailable") ||
                   lowerMessage.contains("failed to connect") ||
                   lowerMessage.contains("no healthy servers");
        }
        
        return false; // Default to not marking unhealthy for unknown errors
    }
}
