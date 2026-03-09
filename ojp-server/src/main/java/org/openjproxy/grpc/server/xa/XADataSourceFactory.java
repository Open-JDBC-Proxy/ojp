package org.openjproxy.grpc.server.xa;

import com.openjproxy.grpc.ConnectionDetails;
import lombok.extern.slf4j.Slf4j;

import javax.sql.XADataSource;
import java.sql.SQLException;

/**
 * Factory for creating XADataSource instances for different database types.
 * Uses Class.forName() to check driver availability before attempting to create XADataSource.
 * This avoids compile-time dependencies on proprietary database JDBC drivers.
 */
@Slf4j
public class XADataSourceFactory {

    public static final String POSTGRESQL_XA_DATASOURCE = "org.postgresql.xa.PGXADataSource";
    private static final String SET_DRIVER_TYPE = "setDriverType";
    private static final String SET_SERVER_NAME = "setServerName";
    private static final String SET_SERVER_NAMES = "setServerNames";
    private static final String SET_PORT_NUMBER = "setPortNumber";
    private static final String SET_PORT_NUMBERS = "setPortNumbers";
    private static final String SET_DATABASE_NAME = "setDatabaseName";
    private static final String SET_SERVICE_NAME = "setServiceName";
    private static final String SET_URL = "setUrl";
    private static final String SET_URL_SQL_SERVER = "setURL";
    private static final String SET_USER = "setUser";
    private static final String SET_PASSWORD = "setPassword";
    private static final String SET_CONNECTION_PROPERTIES = "setConnectionProperties";

    /**
     * Creates an XADataSource for the specified database type based on the URL.
     * 
     * @param url JDBC URL
     * @param connectionDetails Connection details including credentials
     * @return XADataSource instance for the database
     * @throws SQLException if XADataSource creation fails or database type not supported
     */
    public static XADataSource createXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        String lowerUrl = url.toLowerCase();
        try {
            if (lowerUrl.contains("postgresql")) {
                return createPostgreSQLXADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("mariadb")) {
                return createMariaDBXADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("mysql")) {
                return createMySQLXADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("oracle")) {
                return createOracleXADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("sqlserver")) {
                return createSQLServerXADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("db2")) {
                return createDB2XADataSource(url, connectionDetails);
            } else if (lowerUrl.contains("cockroachdb") || lowerUrl.contains("cockroach")) {
                // CockroachDB uses PostgreSQL protocol/driver
                return createCockroachDBXADataSource(url, connectionDetails);
            } else {
                throw new SQLException("XA transactions not supported for database type in URL: " + url);
            }
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create XADataSource: {}", e.getMessage(), e);
            throw new SQLException("Failed to create XADataSource: " + e.getMessage(), e);
        }
    }

    private static ClassLoader currentClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    @SuppressWarnings("unchecked")
    private static <T> T newReflectiveInstance(String className, ClassLoader classLoader) throws ReflectiveOperationException {
        return (T) Class.forName(className, true, classLoader)
                .getDeclaredConstructor()
                .newInstance();
    }

    private static void setStringProperty(Object target, String methodName, String value) throws ReflectiveOperationException {
        target.getClass().getMethod(methodName, String.class).invoke(target, value);
    }

    private static void setIntProperty(Object target, String methodName, int value) throws ReflectiveOperationException {
        target.getClass().getMethod(methodName, int.class).invoke(target, value);
    }

    private static void setStringArrayProperty(Object target, String methodName, String[] value)
            throws ReflectiveOperationException {
        Object[] args = {value};
        target.getClass().getMethod(methodName, String[].class).invoke(target, args);
    }

    private static void setIntArrayProperty(Object target, String methodName, int[] value)
            throws ReflectiveOperationException {
        Object[] args = {value};
        target.getClass().getMethod(methodName, int[].class).invoke(target, args);
    }

    private static void setCredentials(Object target, ConnectionDetails connectionDetails) throws ReflectiveOperationException {
        setStringProperty(target, SET_USER, connectionDetails.getUser());
        setStringProperty(target, SET_PASSWORD, connectionDetails.getPassword());
    }

    @FunctionalInterface
    private interface XaDataSourceConfigurer {
        void configure(XADataSource xaDataSource) throws ReflectiveOperationException;
    }

    private static XADataSource createConfiguredXADataSource(
            String driverClassName,
            String driverMissingMessage,
            String failureMessagePrefix,
            String successMessage,
            String successValue,
            XaDataSourceConfigurer configurer) throws SQLException {
        try {
            XADataSource xaDS = newReflectiveInstance(driverClassName, currentClassLoader());
            configurer.configure(xaDS);
            log.info(successMessage, successValue);
            return xaDS;
        } catch (ClassNotFoundException e) {
            throw new SQLException(driverMissingMessage, e);
        } catch (Exception e) {
            throw new SQLException(failureMessagePrefix + e.getMessage(), e);
        }
    }

    /**
     * Creates a PostgreSQL XADataSource.
     */
    private static XADataSource createPostgreSQLXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        try {
            XADataSource xaDS = createPostgreSQLCompatibleXADataSource(url, connectionDetails, 5432, false);
            String[] serverNames = (String[]) xaDS.getClass().getMethod("getServerNames").invoke(xaDS);
            String host = (serverNames != null && serverNames.length > 0) ? serverNames[0] : "unknown";
            log.info("Created PostgreSQL XADataSource for host: {}", host);
            return xaDS;
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC driver not found. Add postgresql JDBC driver to classpath.", e);
        } catch (Exception e) {
            throw new SQLException("Failed to create PostgreSQL XADataSource: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a MySQL XADataSource.
     */
    private static XADataSource createMySQLXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        return createConfiguredXADataSource(
                "com.mysql.cj.jdbc.MysqlXADataSource",
                "MySQL JDBC driver not found. Add mysql-connector-j to classpath.",
                "Failed to create MySQL XADataSource: ",
                "Created MySQL XADataSource for URL: {}",
                url,
                xaDS -> {
                    setStringProperty(xaDS, SET_URL, url);
                    setCredentials(xaDS, connectionDetails);
                });
    }

    /**
     * Creates a MariaDB XADataSource.
     */
    private static XADataSource createMariaDBXADataSource(String url, ConnectionDetails connectionDetails)
            throws SQLException {
        return createConfiguredXADataSource(
                "org.mariadb.jdbc.MariaDbDataSource",
                "MariaDB JDBC driver not found. Add mariadb-java-client to classpath.",
                "Failed to create MariaDB XADataSource: ",
                "Created MariaDB XADataSource for URL: {}",
                url,
                xaDS -> {
                    setStringProperty(xaDS, SET_URL, url);
                    setCredentials(xaDS, connectionDetails);
                });
    }

    /**
     * Creates an Oracle XADataSource.
     * 
     * NOTE: Oracle XA requires specific database privileges for the user:
     * - GRANT SELECT ON sys.dba_pending_transactions TO user;
     * - GRANT SELECT ON sys.pending_trans$ TO user;
     * - GRANT SELECT ON sys.dba_2pc_pending TO user;
     * - GRANT EXECUTE ON sys.dbms_system TO user;
     * - GRANT FORCE ANY TRANSACTION TO user;
     * 
     * If the user doesn't have these privileges, XA operations will fail with ORA-6550 or similar errors.
     * For testing/development, you can grant DBA role or execute: GRANT XA_RECOVER_ADMIN TO user;
     */
    private static XADataSource createOracleXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        try {
            XADataSource xaDS = newReflectiveInstance("oracle.jdbc.xa.client.OracleXADataSource", currentClassLoader());
            // Clean the URL - remove OJP wrapper if present
            String cleanUrl = url;
            if (cleanUrl.toLowerCase().contains("_oracle:")) {
                cleanUrl = "jdbc:oracle:" + cleanUrl.substring(cleanUrl.toLowerCase().indexOf("_oracle:") + 8);
            }
            // Parse Oracle connection URL to extract components
            // Format: jdbc:oracle:thin:@host:port/service or jdbc:oracle:thin:@host:port:sid
            if (cleanUrl.toLowerCase().startsWith("jdbc:oracle:thin:@")) {
                String connectionPart = cleanUrl.substring("jdbc:oracle:thin:@".length());
                // Parse host:port/service or host:port:sid
                String host = "localhost";
                int port = 1521;
                String serviceName = null;
                // Set driver type first - required for Oracle to construct proper URL internally
                setStringProperty(xaDS, SET_DRIVER_TYPE, "thin");
                if (connectionPart.contains("/")) {
                    // Service name format: host:port/service
                    String[] parts = connectionPart.split("/");
                    String[] hostPort = parts[0].split(":");
                    host = hostPort[0];
                    if (hostPort.length > 1) {
                        port = Integer.parseInt(hostPort[1]);
                    }
                    serviceName = parts[1];
                    setStringProperty(xaDS, SET_SERVER_NAME, host);
                    setIntProperty(xaDS, SET_PORT_NUMBER, port);
                    setStringProperty(xaDS, SET_SERVICE_NAME, serviceName);
                } else if (connectionPart.contains(":")) {
                    // SID format: host:port:sid
                    String[] parts = connectionPart.split(":");
                    host = parts[0];
                    if (parts.length > 1) {
                        port = Integer.parseInt(parts[1]);
                    }
                    if (parts.length > 2) {
                        String sid = parts[2];
                        setStringProperty(xaDS, SET_SERVER_NAME, host);
                        setIntProperty(xaDS, SET_PORT_NUMBER, port);
                        setStringProperty(xaDS, SET_DATABASE_NAME, sid);
                    }
                } else {
                    // Fallback: try setting just the service name from the connection part
                    setStringProperty(xaDS, SET_SERVER_NAME, host);
                    setIntProperty(xaDS, SET_PORT_NUMBER, port);
                    setStringProperty(xaDS, SET_SERVICE_NAME, connectionPart);
                }
            } else {
                // For non-thin URLs or unparseable formats, set driver type and try to parse
                setStringProperty(xaDS, SET_DRIVER_TYPE, "thin");
                // Set sensible defaults
                setStringProperty(xaDS, SET_SERVER_NAME, "localhost");
                setIntProperty(xaDS, SET_PORT_NUMBER, 1521);
            }
            setCredentials(xaDS, connectionDetails);
            // Oracle XA requires specific properties to work correctly
            // Set connection properties that enable XA support
            try {
                // Enable XA connection mode explicitly
                java.util.Properties props = new java.util.Properties();
                props.setProperty("user", connectionDetails.getUser());
                props.setProperty("password", connectionDetails.getPassword());
                // Oracle XA specific properties
                props.setProperty("v$session.program", "OJP-XA");
                xaDS.getClass().getMethod(SET_CONNECTION_PROPERTIES, java.util.Properties.class).invoke(xaDS, props);
            } catch (Exception e) {
                log.warn("Could not set connection properties on Oracle XADataSource: {}", e.getMessage());
            }
            log.info("Created Oracle XADataSource for URL: {}", url);
            return xaDS;
        } catch (ClassNotFoundException e) {
            throw new SQLException("Oracle JDBC driver not found. Add ojdbc (ojdbc8 or ojdbc11) to classpath.", e);
        } catch (Exception e) {
            throw new SQLException("Failed to create Oracle XADataSource: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a SQL Server XADataSource.
     */
    private static XADataSource createSQLServerXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        return createConfiguredXADataSource(
                "com.microsoft.sqlserver.jdbc.SQLServerXADataSource",
                "SQL Server JDBC driver not found. Add mssql-jdbc to classpath.",
                "Failed to create SQL Server XADataSource: ",
                "Created SQL Server XADataSource for URL: {}",
                url,
                xaDS -> {
                    setStringProperty(xaDS, SET_URL_SQL_SERVER, url);
                    setCredentials(xaDS, connectionDetails);
                });
    }

    /**
     * Creates a DB2 XADataSource.
     */
    private static XADataSource createDB2XADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        return createConfiguredXADataSource(
                "com.ibm.db2.jcc.DB2XADataSource",
                "DB2 JDBC driver not found. Add db2jcc or db2jcc4 to classpath.",
                "Failed to create DB2 XADataSource: ",
                "Created DB2 XADataSource for URL: {}",
                url,
                xaDS -> {
                    // Parse DB2 URL: jdbc:db2://host:port/database
                    String cleanUrl = url;
                    if (cleanUrl.toLowerCase().contains("_db2:")) {
                        cleanUrl = cleanUrl.substring(cleanUrl.toLowerCase().indexOf("_db2:") + 1);
                    } else if (cleanUrl.toLowerCase().startsWith("jdbc:db2:")) {
                        cleanUrl = cleanUrl.substring("jdbc:".length());
                    }
                    // Parse db2://host:port/database
                    if (cleanUrl.startsWith("db2://")) {
                        cleanUrl = cleanUrl.substring("db2://".length());
                        String[] parts = cleanUrl.split("/");
                        if (parts.length >= 2) {
                            String hostPort = parts[0];
                            String database = parts[1].split("\\?")[0]; // Remove query params
                            String[] hostPortParts = hostPort.split(":");
                            String host = hostPortParts[0];
                            int port = hostPortParts.length > 1 ? Integer.parseInt(hostPortParts[1]) : 50000;
                            setStringProperty(xaDS, SET_SERVER_NAME, host);
                            setIntProperty(xaDS, SET_PORT_NUMBER, port);
                            setStringProperty(xaDS, SET_DATABASE_NAME, database);
                            setIntProperty(xaDS, SET_DRIVER_TYPE, 4); // Type 4 driver
                        }
                    }
                    setCredentials(xaDS, connectionDetails);
                });
    }

    /**
     * Creates a CockroachDB XADataSource.
     * CockroachDB is PostgreSQL-compatible, so we use the PostgreSQL XADataSource.
     */
    private static XADataSource createCockroachDBXADataSource(String url, ConnectionDetails connectionDetails) throws SQLException {
        try {
            XADataSource xaDS = createPostgreSQLCompatibleXADataSource(url, connectionDetails, 26257, true);
            String[] serverNames = (String[]) xaDS.getClass().getMethod("getServerNames").invoke(xaDS);
            String host = (serverNames != null && serverNames.length > 0) ? serverNames[0] : "unknown";
            log.info("Created CockroachDB XADataSource (using PostgreSQL driver) for host: {}", host);
            return xaDS;
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC driver not found (required for CockroachDB). Add postgresql JDBC driver to classpath.", e);
        } catch (Exception e) {
            throw new SQLException("Failed to create CockroachDB XADataSource: " + e.getMessage(), e);
        }
    }

    private static XADataSource createPostgreSQLCompatibleXADataSource(
            String url,
            ConnectionDetails connectionDetails,
            int defaultPort,
            boolean cockroach) throws ReflectiveOperationException {
        XADataSource xaDS = newReflectiveInstance(POSTGRESQL_XA_DATASOURCE, currentClassLoader());
        String cleanUrl = normalizePostgreSqlUrl(url, cockroach);
        if (cleanUrl.startsWith("postgresql://")) {
            String connectionPart = cleanUrl.substring("postgresql://".length());
            String[] parts = connectionPart.split("/");
            if (parts.length >= 2) {
                String hostPort = parts[0];
                String database = parts[1].split("\\?")[0];
                String[] hostPortParts = hostPort.split(":");
                String host = hostPortParts[0];
                int port = hostPortParts.length > 1 ? Integer.parseInt(hostPortParts[1]) : defaultPort;
                setStringArrayProperty(xaDS, SET_SERVER_NAMES, new String[]{host});
                setIntArrayProperty(xaDS, SET_PORT_NUMBERS, new int[]{port});
                setStringProperty(xaDS, SET_DATABASE_NAME, database);
            }
        }
        setCredentials(xaDS, connectionDetails);
        return xaDS;
    }

    private static String normalizePostgreSqlUrl(String url, boolean cockroach) {
        String cleanUrl = url;
        String lowerUrl = cleanUrl.toLowerCase();
        if (lowerUrl.contains("_postgresql:") || lowerUrl.contains("_cockroach")) {
            int startIdx = lowerUrl.indexOf("_postgresql:");
            if (startIdx == -1) {
                startIdx = lowerUrl.indexOf("_cockroach");
            }
            cleanUrl = cleanUrl.substring(startIdx + 1);
            cleanUrl = cleanUrl.replace("cockroachdb://", "postgresql://");
            cleanUrl = cleanUrl.replace("cockroach://", "postgresql://");
        } else if (lowerUrl.startsWith("jdbc:postgresql:")) {
            cleanUrl = cleanUrl.substring("jdbc:".length());
        } else if (cockroach && lowerUrl.startsWith("jdbc:cockroachdb:")) {
            cleanUrl = cleanUrl.substring("jdbc:".length()).replace("cockroachdb:", "postgresql:");
        }
        return cleanUrl;
    }
}
