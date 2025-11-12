package org.openjproxy.grpc.server.xa;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import javax.sql.XAConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.io.PrintWriter;
import java.util.logging.Logger;

/**
 * DecoratingDataSource wraps either a plain DataSource or an XADataSource.
 *
 * - For non-XA usage it delegates getConnection(...) to the wrapped DataSource.
 * - For XA usage it will use the supplied XADataSource to obtain an XAConnection,
 *   then construct an XAResourceConnection (proxy) that implements Connection, XAConnection and XAResource.
 */
public class DecoratingDataSource implements DataSource {

    private final DataSource delegate;
    private final XADataSource xaDataSource; // optional, if this is non-null we produce XAResourceConnection

    public DecoratingDataSource(DataSource delegate) {
        this(delegate, null);
    }

    public DecoratingDataSource(DataSource delegate, XADataSource xaDataSource) {
        this.delegate = delegate;
        this.xaDataSource = xaDataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (xaDataSource != null) {
            XAConnection xaConn = xaDataSource.getXAConnection();
            javax.transaction.xa.XAResource xaRes = xaConn.getXAResource();
            Connection conn = xaConn.getConnection();
            return XAResourceConnection.wrap(xaConn, xaRes, conn);
        } else {
            return delegate.getConnection();
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        if (xaDataSource != null) {
            XAConnection xaConn = xaDataSource.getXAConnection(username, password);
            javax.transaction.xa.XAResource xaRes = xaConn.getXAResource();
            Connection conn = xaConn.getConnection();
            return XAResourceConnection.wrap(xaConn, xaRes, conn);
        } else {
            return delegate.getConnection(username, password);
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
        // Return the delegate's parent logger if available
        try {
            return delegate.getParentLogger();
        } catch (Exception e) {
            // Return a default logger if delegate doesn't support this
            return Logger.getLogger(DecoratingDataSource.class.getName());
        }
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
