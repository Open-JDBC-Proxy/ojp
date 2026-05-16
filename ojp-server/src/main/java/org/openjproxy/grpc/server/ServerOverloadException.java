package org.openjproxy.grpc.server;

/**
 * Indicates request rejection due to server-side overload and admission-control limits.
 */
public class ServerOverloadException extends RuntimeException {

    public ServerOverloadException(String message) {
        super(message);
    }

    public ServerOverloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
