package org.openjproxy.grpc.server;

import lombok.Builder;
import lombok.Getter;

/**
 * Connection details for unpooled (passthrough) JDBC connections.
 * Used when connection pooling is disabled (ojp.connection.pool.enabled=false).
 *
 * <p>In unpooled mode, connections are created directly on demand rather than
 * being managed by a connection pool. These details are stored and used to
 * create new connections as needed.
 */
@Builder
@Getter
public class UnpooledConnectionDetails {
    private final String url;
    private final String username;
    private final String password;
    private final long connectionTimeout;
}
