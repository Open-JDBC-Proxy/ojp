package org.openjproxy.jdbc.xa;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.jdbc.Connection;

import java.sql.SQLException;

/**
 * Logical connection that wraps the XA session on the server.
 * This connection delegates to the server-side XA connection for all operations,
 * but ensures that commits and rollbacks are controlled by the XA resource.
 * 
 * <p>Tracks connection alterations to prevent unsafe xaStart retries. If any operation
 * that modifies connection state is called before xaStart, retrying on a different
 * server would be unsafe.</p>
 */
@Slf4j
class OjpXALogicalConnection extends Connection {

    private final OjpXAConnection xaConnection;
    private boolean closed = false;
    private volatile boolean connectionAltered = false;

    OjpXALogicalConnection(OjpXAConnection xaConnection, SessionInfo sessionInfo, String url) throws SQLException {
        // Pass the statementService and dbName to the parent Connection class
        super(sessionInfo, xaConnection.getStatementService(), DatabaseUtils.resolveDbName(url));
        this.xaConnection = xaConnection;
        
        log.debug("Created logical connection using XA session: {}", sessionInfo.getSessionUUID());
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
        // Track that connection was altered
        connectionAltered = true;
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
    
    /**
     * Checks if the connection has been altered since creation or last reset.
     * 
     * <p>Operations that modify connection state (setAutoCommit, SQL execution,
     * setTransactionIsolation, setReadOnly, etc.) set this flag to true. This is
     * used to prevent unsafe xaStart retries on different servers.</p>
     * 
     * @return true if connection was altered, false otherwise
     */
    boolean isConnectionAltered() {
        return connectionAltered;
    }
    
    /**
     * Resets the connection alteration flag.
     * Called after successful xaStart to allow future tracking.
     */
    void resetAlterationFlag() {
        connectionAltered = false;
    }
    
    /**
     * Marks connection as altered. Called before executing SQL or changing settings.
     */
    private void markAltered() {
        connectionAltered = true;
    }
    
    // Override methods that alter connection state to track alterations
    
    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        markAltered();
        super.setTransactionIsolation(level);
    }
    
    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        markAltered();
        super.setReadOnly(readOnly);
    }
    
    @Override
    public void setCatalog(String catalog) throws SQLException {
        markAltered();
        super.setCatalog(catalog);
    }
    
    @Override
    public void setSchema(String schema) throws SQLException {
        markAltered();
        super.setSchema(schema);
    }
    
    @Override
    public java.sql.Statement createStatement() throws SQLException {
        // Creating a statement alters connection (will execute SQL)
        markAltered();
        return super.createStatement();
    }
    
    @Override
    public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        markAltered();
        return super.createStatement(resultSetType, resultSetConcurrency);
    }
    
    @Override
    public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        markAltered();
        return super.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
    }
    
    @Override
    public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {
        markAltered();
        return super.prepareStatement(sql);
    }
    
    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        markAltered();
        return super.prepareStatement(sql, resultSetType, resultSetConcurrency);
    }
    
    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        markAltered();
        return super.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }
    
    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        markAltered();
        return super.prepareStatement(sql, autoGeneratedKeys);
    }
    
    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        markAltered();
        return super.prepareStatement(sql, columnIndexes);
    }
    
    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        markAltered();
        return super.prepareStatement(sql, columnNames);
    }
    
    @Override
    public java.sql.CallableStatement prepareCall(String sql) throws SQLException {
        markAltered();
        return super.prepareCall(sql);
    }
    
    @Override
    public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        markAltered();
        return super.prepareCall(sql, resultSetType, resultSetConcurrency);
    }
    
    @Override
    public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        markAltered();
        return super.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }
}
