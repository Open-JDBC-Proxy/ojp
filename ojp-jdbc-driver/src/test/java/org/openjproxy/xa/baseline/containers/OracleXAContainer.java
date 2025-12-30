package org.openjproxy.xa.baseline.containers;

import oracle.jdbc.xa.client.OracleXADataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.XADataSource;
import java.sql.SQLException;

/**
 * TestContainer wrapper for Oracle Database with XA configuration.
 * 
 * This class provides a ready-to-use Oracle database container with:
 * - XA transaction support enabled
 * - Required XA permissions granted
 * - Test user configured
 * - Initialization scripts executed
 * 
 * Usage:
 * <pre>
 * OracleXAContainer oracle = new OracleXAContainer();
 * oracle.start();
 * XADataSource xaDataSource = oracle.createXADataSource();
 * </pre>
 */
public class OracleXAContainer extends OracleContainer {
    
    private static final Logger logger = LoggerFactory.getLogger(OracleXAContainer.class);
    
    // Oracle XE image - free, lightweight version suitable for testing
    private static final DockerImageName ORACLE_IMAGE = 
        DockerImageName.parse("gvenzl/oracle-xe:21-slim")
            .asCompatibleSubstituteFor("gvenzl/oracle-xe");
    
    // Default credentials
    private static final String DEFAULT_DATABASE_NAME = "XEPDB1";
    private static final String DEFAULT_USERNAME = "testuser";
    private static final String DEFAULT_PASSWORD = "testpass";
    
    /**
     * Creates Oracle XA container with default configuration.
     */
    public OracleXAContainer() {
        this(ORACLE_IMAGE);
    }
    
    /**
     * Creates Oracle XA container with specified image.
     * 
     * @param dockerImageName the Oracle Docker image to use
     */
    public OracleXAContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        
        // Configure container
        withDatabaseName(DEFAULT_DATABASE_NAME);
        withUsername(DEFAULT_USERNAME);
        withPassword(DEFAULT_PASSWORD);
        
        // Add initialization script for XA setup
        withInitScript("xa-baseline/sql/oracle-xa-setup.sql");
        
        // Increase startup timeout for Oracle (can be slow)
        withStartupTimeoutSeconds(120);
        
        logger.info("Oracle XA Container configured with database: {}, user: {}", 
                   DEFAULT_DATABASE_NAME, DEFAULT_USERNAME);
    }
    
    /**
     * Creates an XADataSource configured to connect to this container.
     * 
     * @return configured XADataSource
     * @throws SQLException if DataSource creation fails
     */
    public XADataSource createXADataSource() throws SQLException {
        if (!isRunning()) {
            throw new IllegalStateException("Oracle container is not running. Call start() first.");
        }
        
        OracleXADataSource xaDataSource = new OracleXADataSource();
        
        // Configure connection properties
        xaDataSource.setURL(getJdbcUrl());
        xaDataSource.setUser(getUsername());
        xaDataSource.setPassword(getPassword());
        
        // Optional: Configure connection pool properties
        // xaDataSource.setConnectionCachingEnabled(true);
        // xaDataSource.setConnectionCacheProperties(props);
        
        logger.info("Created Oracle XADataSource for URL: {}", getJdbcUrl());
        
        return xaDataSource;
    }
    
    /**
     * Gets the JDBC URL for this container.
     * Overrides parent to ensure we use the pluggable database.
     * 
     * @return JDBC URL
     */
    @Override
    public String getJdbcUrl() {
        // Oracle XE uses pluggable databases
        // Format: jdbc:oracle:thin:@//host:port/service_name
        return "jdbc:oracle:thin:@//" + getHost() + ":" + getOraclePort() + "/" + getDatabaseName();
    }
    
    /**
     * Gets the Oracle-specific port (usually 1521).
     * 
     * @return the Oracle port
     */
    public Integer getOraclePort() {
        return getMappedPort(1521); // Oracle default port
    }
    
    /**
     * Gets the database name (service name).
     * 
     * @return database name
     */
    @Override
    public String getDatabaseName() {
        // For Oracle XE, we use the pluggable database
        return DEFAULT_DATABASE_NAME;
    }
    
    /**
     * Logs container startup information.
     */
    @Override
    protected void containerIsStarted(com.github.dockerjava.api.command.InspectContainerResponse containerInfo) {
        super.containerIsStarted(containerInfo);
        logger.info("Oracle XA Container started successfully");
        logger.info("JDBC URL: {}", getJdbcUrl());
        logger.info("Username: {}", getUsername());
        logger.info("Container ID: {}", getContainerId());
    }
    
    /**
     * Executes a SQL script against the database.
     * Useful for additional setup after container starts.
     * 
     * @param scriptContent SQL script content
     * @throws SQLException if script execution fails
     */
    public void executeScript(String scriptContent) throws SQLException {
        // This would require additional implementation to execute SQL
        // For now, initialization scripts are handled via withInitScript
        logger.debug("Script execution not yet implemented. Use withInitScript instead.");
    }
}
