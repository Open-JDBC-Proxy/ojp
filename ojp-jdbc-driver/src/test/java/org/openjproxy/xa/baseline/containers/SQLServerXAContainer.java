package org.openjproxy.xa.baseline.containers;

import com.microsoft.sqlserver.jdbc.SQLServerXADataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.XADataSource;

/**
 * TestContainer wrapper for SQL Server with XA configuration.
 * 
 * This class provides a ready-to-use SQL Server container with:
 * - XA transaction support enabled via sp_sqljdbc_xa_install
 * - Required XA permissions granted (SqlJDBCXAUser role)
 * - Test database configured
 * - Initialization scripts executed
 * 
 * SQL Server XA Requirements:
 * - Must run sp_sqljdbc_xa_install stored procedure
 * - User must be member of SqlJDBCXAUser role
 * - MS DTC service must be enabled (handled by docker image)
 * 
 * Usage:
 * <pre>
 * SQLServerXAContainer sqlServer = new SQLServerXAContainer();
 * sqlServer.start();
 * XADataSource xaDataSource = sqlServer.createXADataSource();
 * </pre>
 */
public class SQLServerXAContainer extends MSSQLServerContainer<SQLServerXAContainer> {
    
    private static final Logger logger = LoggerFactory.getLogger(SQLServerXAContainer.class);
    
    // SQL Server 2022 image - includes XA support
    private static final DockerImageName SQLSERVER_IMAGE = 
        DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest")
            .asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server");
    
    // Default credentials
    private static final String DEFAULT_PASSWORD = "YourStrong!Passw0rd";
    
    /**
     * Creates SQL Server XA container with default configuration.
     */
    public SQLServerXAContainer() {
        this(SQLSERVER_IMAGE);
    }
    
    /**
     * Creates SQL Server XA container with specified image.
     */
    public SQLServerXAContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        
        // Set strong password (SQL Server requirement)
        withPassword(DEFAULT_PASSWORD);
        
        // Accept EULA
        acceptLicense();
        
        // Load initialization script for XA setup
        withInitScript("xa-baseline/sql/sqlserver-xa-setup.sql");
        
        // Increase startup timeout for XA setup
        withStartupTimeoutSeconds(180);
        
        logger.info("SQL Server XA container configured with image: {}", dockerImageName);
    }
    
    /**
     * Creates an XADataSource for this SQL Server instance.
     * 
     * @return Configured SQLServerXADataSource
     */
    public XADataSource createXADataSource() {
        SQLServerXADataSource xaDataSource = new SQLServerXADataSource();
        
        // Set connection properties
        xaDataSource.setServerName(getHost());
        xaDataSource.setPortNumber(getMappedPort(MS_SQL_SERVER_PORT));
        xaDataSource.setDatabaseName("tempdb"); // Use tempdb for tests
        xaDataSource.setUser("sa");
        xaDataSource.setPassword(getPassword());
        
        // Trust server certificate (for testing)
        xaDataSource.setTrustServerCertificate(true);
        xaDataSource.setEncrypt(false);
        
        logger.info("Created SQLServerXADataSource: {}:{}", getHost(), getMappedPort(MS_SQL_SERVER_PORT));
        return xaDataSource;
    }
    
    /**
     * Gets the JDBC URL for this SQL Server instance.
     * 
     * @return JDBC URL
     */
    @Override
    public String getJdbcUrl() {
        return "jdbc:sqlserver://" + getHost() + ":" + getMappedPort(MS_SQL_SERVER_PORT) + 
               ";databaseName=tempdb;trustServerCertificate=true;encrypt=false";
    }
    
    /**
     * Gets the username for this SQL Server instance.
     * 
     * @return Username (always "sa")
     */
    @Override
    public String getUsername() {
        return "sa";
    }
}
