package org.openjproxy.datasource.narayana;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.ConnectionEventListener;
import javax.sql.StatementEventListener;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wrapper for pooled XA connections.
 * 
 * <p>This class wraps a physical XAConnection and returns it to the pool
 * when close() is called, rather than actually closing the physical connection.</p>
 */
class PooledXAConnectionWrapper implements XAConnection {

    private static final Logger log = LoggerFactory.getLogger(PooledXAConnectionWrapper.class);
    
    private final XAConnection physicalConnection;
    private final NarayanaPooledXADataSource pool;
    private final long createdTime;
    private volatile long lastUsedTime;
    private volatile boolean closed = false;

    public PooledXAConnectionWrapper(XAConnection physicalConnection, NarayanaPooledXADataSource pool) {
        this.physicalConnection = physicalConnection;
        this.pool = pool;
        this.createdTime = System.currentTimeMillis();
        this.lastUsedTime = createdTime;
    }

    @Override
    public XAResource getXAResource() throws SQLException {
        checkClosed();
        return physicalConnection.getXAResource();
    }

    @Override
    public Connection getConnection() throws SQLException {
        checkClosed();
        return physicalConnection.getConnection();
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            // Return to pool instead of closing
            pool.returnConnection(this);
        }
    }

    /**
     * Actually closes the physical connection.
     * Called by pool when removing from pool.
     */
    void physicalClose() {
        try {
            physicalConnection.close();
        } catch (SQLException e) {
            log.warn("Error closing physical XA connection", e);
        }
    }

    /**
     * Checks if the connection is still valid.
     */
    boolean isValid() {
        try {
            Connection conn = physicalConnection.getConnection();
            return conn != null && conn.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Marks the connection as active (taken from pool).
     */
    void markActive() {
        closed = false;
        lastUsedTime = System.currentTimeMillis();
    }

    /**
     * Marks the connection as idle (returned to pool).
     */
    void markIdle() {
        lastUsedTime = System.currentTimeMillis();
    }

    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("XA Connection is closed");
        }
    }

    @Override
    public void addConnectionEventListener(ConnectionEventListener listener) {
        try {
            physicalConnection.addConnectionEventListener(listener);
        } catch (Exception e) {
            log.warn("Error adding connection event listener", e);
        }
    }

    @Override
    public void removeConnectionEventListener(ConnectionEventListener listener) {
        try {
            physicalConnection.removeConnectionEventListener(listener);
        } catch (Exception e) {
            log.warn("Error removing connection event listener", e);
        }
    }

    @Override
    public void addStatementEventListener(StatementEventListener listener) {
        try {
            physicalConnection.addStatementEventListener(listener);
        } catch (Exception e) {
            log.warn("Error adding statement event listener", e);
        }
    }

    @Override
    public void removeStatementEventListener(StatementEventListener listener) {
        try {
            physicalConnection.removeStatementEventListener(listener);
        } catch (Exception e) {
            log.warn("Error removing statement event listener", e);
        }
    }
}
