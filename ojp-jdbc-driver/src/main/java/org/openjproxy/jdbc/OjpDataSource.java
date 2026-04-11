package org.openjproxy.jdbc;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Implementation of {@link javax.sql.DataSource} for OJP.
 *
 * <p>This class allows OJP to be registered as a {@code javax.sql.DataSource} JNDI resource
 * in Java EE / Jakarta EE containers such as GlassFish, WildFly, or WebLogic.
 *
 * <p>Without this class, containers that accept {@code res-type="java.sql.Driver"} register the
 * resource under the {@code java.sql.Driver} type rather than {@code javax.sql.DataSource}, which
 * causes the JTA deployment validator to fail because it expects {@code javax.sql.DataSource}.
 *
 * <p>Usage in GlassFish (asadmin):
 * <pre>
 *   asadmin create-jdbc-connection-pool \
 *     --restype javax.sql.DataSource \
 *     --datasourceclassname org.openjproxy.jdbc.OjpDataSource \
 *     --property url=jdbc:ojp[localhost:1059]_postgresql://localhost/mydb:user=myuser:password=mypass \
 *     MyPool
 *
 *   asadmin create-jdbc-resource --connectionpoolid MyPool jdbc/myDataSource
 * </pre>
 *
 * <p>Connections are obtained through the {@link DriverManager}, which delegates to the
 * registered OJP {@link Driver}.
 */
@Slf4j
public class OjpDataSource implements DataSource {

    @Getter
    @Setter
    private String url;

    @Getter
    @Setter
    private String user;

    @Getter
    @Setter
    private String password;

    private int loginTimeout = 0;

    /**
     * Stored for {@link javax.sql.DataSource} interface compliance.
     * OJP uses SLF4J internally for all logging, so this writer is not actively used.
     */
    private PrintWriter logWriter;

    /** No-arg constructor required by Java EE / Jakarta EE containers for bean-style instantiation. */
    public OjpDataSource() {
        log.debug("Creating OjpDataSource");
    }

    /**
     * Convenience constructor.
     *
     * @param url      OJP JDBC URL, e.g. {@code jdbc:ojp[localhost:1059]_postgresql://localhost/mydb}
     * @param user     database user name
     * @param password database password
     */
    public OjpDataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        log.debug("Creating OjpDataSource with URL: {}", url);
    }

    /**
     * Obtain a connection using the configured {@link #user} and {@link #password}.
     *
     * @return a JDBC {@link Connection}
     * @throws SQLException if the URL is not set or the connection cannot be established
     */
    @Override
    public Connection getConnection() throws SQLException {
        return getConnection(user, password);
    }

    /**
     * Obtain a connection using the supplied credentials.
     *
     * @param username database user name (may be {@code null})
     * @param password database password (may be {@code null})
     * @return a JDBC {@link Connection}
     * @throws SQLException if the URL is not set or the connection cannot be established
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        if (url == null || url.isEmpty()) {
            throw new SQLException("URL is not set");
        }
        Properties props = new Properties();
        if (username != null) {
            props.setProperty(Constants.USER, username);
        }
        if (password != null) {
            props.setProperty(Constants.PASSWORD, password);
        }
        log.debug("getConnection: url={}, user={}", url, username);
        return DriverManager.getConnection(url, props);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        this.logWriter = out;
    }

    /**
     * Sets the login timeout and applies it globally via {@link DriverManager#setLoginTimeout(int)}.
     *
     * <p><b>Note:</b> {@code DriverManager.setLoginTimeout()} is a JVM-wide setting and affects
     * all connections created through {@link DriverManager}, not just connections from this
     * {@code OjpDataSource} instance. This is an inherent limitation of the
     * {@link DriverManager}-based {@link javax.sql.DataSource} implementation.
     *
     * @param seconds the login timeout in seconds; 0 means no timeout
     */
    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        this.loginTimeout = seconds;
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("getParentLogger not supported");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
}
