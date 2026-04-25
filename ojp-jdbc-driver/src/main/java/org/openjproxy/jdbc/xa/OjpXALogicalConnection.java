package org.openjproxy.jdbc.xa;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.client.MultinodeConnectionManager;
import org.openjproxy.grpc.client.MultinodeStatementService;
import org.openjproxy.grpc.client.ServerEndpoint;
import org.openjproxy.jdbc.Connection;

import java.sql.SQLException;

/**
 * Logical connection that wraps the XA session on the server.
 * This connection delegates to the server-side XA connection for all operations,
 * but ensures that commits and rollbacks are controlled by the XA resource.
 */
@Slf4j
class OjpXALogicalConnection extends Connection {

    private final OjpXAConnection xaConnection;
    private boolean closed = false;

    OjpXALogicalConnection(OjpXAConnection xaConnection, SessionInfo sessionInfo, String url, String boundServerAddress) throws SQLException {
        // Pass the statementService and dbName to the parent Connection class
        super(sessionInfo, xaConnection.getStatementService(), DatabaseUtils.resolveDbName(url));
        this.xaConnection = xaConnection;

        // Register with ConnectionTracker if using multinode - this ensures XAConnectionRedistributor
        // can find and invalidate this connection when the bound server fails
        if (xaConnection.getStatementService() instanceof MultinodeStatementService) {
            MultinodeStatementService multinodeService = (MultinodeStatementService) xaConnection.getStatementService();
            MultinodeConnectionManager connectionManager = multinodeService.getConnectionManager();
            if (connectionManager != null && boundServerAddress != null) {
                // Find the ServerEndpoint for the bound server
                ServerEndpoint boundEndpoint = findServerEndpoint(connectionManager, boundServerAddress);
                if (boundEndpoint != null) {
                    connectionManager.getConnectionTracker().register(this, boundEndpoint);
                    log.debug("XALogicalConnection registered with ConnectionTracker for server: {}", boundServerAddress);
                } else {
                    log.warn("Could not find ServerEndpoint for bound server: {}", boundServerAddress);
                }
            }
        }

        log.debug("Created logical connection using XA session: {}", sessionInfo.getSessionUUID());
    }

    /**
     * Find the ServerEndpoint matching the bound server address.
     */
    private ServerEndpoint findServerEndpoint(MultinodeConnectionManager connectionManager, String serverAddress) {
        try {
            log.debug("Finding server endpoint for address: {}", serverAddress);
            ServerEndpoint serverEndpoint = connectionManager.getServerEndpoints().stream().filter(se ->
                se.getAddress().equalsIgnoreCase(serverAddress)
            ).findFirst().orElse(null);
            log.debug("Server endpoint for address {} found {}", serverAddress, serverEndpoint != null ? "successfully" : "not found");
            return serverEndpoint;
        } catch (Exception e) {
            log.warn("Failed to find server endpoint for {}: {}", serverAddress, e.getMessage());
            return null;
        }
    }

    @Override
    public void close() throws SQLException {
        log.debug("Logical connection close called");
        if (!closed) {
            closed = true;
            // Don't close the underlying XA connection - just mark this logical connection as closed
            // The actual XA connection will be closed when XAConnection.close() is called
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public void commit() throws SQLException {
        log.debug("commit called on logical connection - should be controlled by XA");
        throw new SQLException("Commit not allowed on XA connection. Use XAResource.commit() instead.");
    }

    @Override
    public void rollback() throws SQLException {
        log.debug("rollback called on logical connection - should be controlled by XA");
        throw new SQLException("Rollback not allowed on XA connection. Use XAResource.rollback() instead.");
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        // XA connections ignore auto-commit settings as they are controlled by XA protocol
        // This is required for compatibility with transaction managers like Atomikos
        // that may call setAutoCommit(true) during connection lifecycle management
        log.debug("setAutoCommit({}) called on XA connection - ignored (XA protocol controls transaction)", autoCommit);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        // XA connections are always non-auto-commit
        return false;
    }
}
