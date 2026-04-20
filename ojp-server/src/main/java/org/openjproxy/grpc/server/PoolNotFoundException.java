package org.openjproxy.grpc.server;

/**
 * Thrown when no connection pool (DataSource) is found for a given connection hash.
 *
 * <p>This can happen when the server is restarted (losing all in-memory pool state)
 * or when a client sends SQL without having first established a connection.
 * The driver catches the corresponding {@code NOT_FOUND} gRPC status, invalidates
 * its cached connection info, issues a fresh {@code connect()} RPC, and retries
 * the original SQL call transparently.</p>
 */
public class PoolNotFoundException extends RuntimeException {

    public PoolNotFoundException(String connHash) {
        super("No pool found for connection hash: " + connHash + ". Client must reconnect.");
    }
}
