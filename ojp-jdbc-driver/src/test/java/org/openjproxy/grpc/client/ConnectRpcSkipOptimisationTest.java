package org.openjproxy.grpc.client;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the connect() RPC-skip optimisation and pool-not-found recovery.
 *
 * <p>The optimisation caches the {@code connHash} returned by the first successful
 * non-XA {@code connect()} call and builds subsequent {@code SessionInfo} objects
 * locally, avoiding a gRPC round-trip for every JDBC {@code Connection} creation.</p>
 *
 * <p>If the server later returns {@code NOT_FOUND} (pool lost after restart),
 * {@link GrpcExceptionHandler#isPoolNotFoundException} must detect it and the driver
 * must invalidate its cache and reconnect.</p>
 */
class ConnectRpcSkipOptimisationTest {

    // -----------------------------------------------------------------------
    // GrpcExceptionHandler.isPoolNotFoundException
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnTrueForNotFoundStatus() {
        StatusRuntimeException ex =
                new StatusRuntimeException(Status.NOT_FOUND.withDescription("No pool found for connection hash: abc"));
        assertTrue(GrpcExceptionHandler.isPoolNotFoundException(ex),
                "NOT_FOUND status should be detected as pool-not-found");
    }

    @Test
    void shouldReturnFalseForUnavailableStatus() {
        StatusRuntimeException ex =
                new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Connection refused"));
        assertFalse(GrpcExceptionHandler.isPoolNotFoundException(ex),
                "UNAVAILABLE should not be treated as pool-not-found");
    }

    @Test
    void shouldReturnFalseForCancelledStatus() {
        StatusRuntimeException ex =
                new StatusRuntimeException(Status.CANCELLED.withDescription("Cancelled"));
        assertFalse(GrpcExceptionHandler.isPoolNotFoundException(ex));
    }

    @Test
    void shouldReturnFalseForNonGrpcException() {
        Exception ex = new RuntimeException("some random error");
        assertFalse(GrpcExceptionHandler.isPoolNotFoundException(ex));
    }

    // -----------------------------------------------------------------------
    // MultinodeConnectionManager.invalidateConnHash
    // -----------------------------------------------------------------------

    @Test
    void shouldHandleInvalidateForUnknownConnHashGracefully() {
        // No exception expected when invalidating a connHash that was never cached.
        MultinodeConnectionManager manager =
                new MultinodeConnectionManager(List.of(new ServerEndpoint("localhost", 10591)));

        assertDoesNotThrow(() -> manager.invalidateConnHash("unknown-hash"),
                "Invalidating an unknown connHash should not throw");
    }

    @Test
    void shouldHandleInvalidateForNullConnHashGracefully() {
        MultinodeConnectionManager manager =
                new MultinodeConnectionManager(List.of(new ServerEndpoint("localhost", 10591)));

        assertDoesNotThrow(() -> manager.invalidateConnHash(null),
                "Invalidating null connHash should not throw");
    }

    @Test
    void shouldHandleInvalidateForEmptyConnHashGracefully() {
        MultinodeConnectionManager manager =
                new MultinodeConnectionManager(List.of(new ServerEndpoint("localhost", 10591)));

        assertDoesNotThrow(() -> manager.invalidateConnHash(""),
                "Invalidating empty connHash should not throw");
    }

    // -----------------------------------------------------------------------
    // MultinodeConnectionManager.reconnectForConnHash
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnNullWhenNoStoredConnectionDetailsForConnHash() throws Exception {
        MultinodeConnectionManager manager =
                new MultinodeConnectionManager(List.of(new ServerEndpoint("localhost", 10591)));

        SessionInfo result = manager.reconnectForConnHash("non-existent-hash");
        assertNull(result,
                "reconnectForConnHash should return null when no ConnectionDetails are stored");
    }

    // -----------------------------------------------------------------------
    // GrpcExceptionHandler.isConnectionLevelError must NOT include NOT_FOUND
    // -----------------------------------------------------------------------

    @Test
    void notFoundShouldNotBeConsideredConnectionLevelError() {
        StatusRuntimeException ex =
                new StatusRuntimeException(Status.NOT_FOUND.withDescription("No pool found"));
        assertFalse(GrpcExceptionHandler.isConnectionLevelError(ex),
                "NOT_FOUND should not be treated as a connection-level error (server remains healthy)");
    }
}
