package org.openjproxy.jdbc.xa;

import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.client.MultinodeConnectionManager;
import org.openjproxy.grpc.client.MultinodeStatementService;
import org.openjproxy.grpc.client.MultinodeUrlParser;
import org.openjproxy.grpc.client.ServerEndpoint;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.grpc.client.StatementServiceGrpcClient;
import org.openjproxy.jdbc.UrlParser;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Implementation of XADataSource for OJP.
 * This is the entry point for JTA transaction managers to obtain XA connections.
 * Uses the integrated StatementService for all XA operations.
 * 
 * <p>The GRPC connection is initialized once per datasource and reused by all XA connections
 * to avoid the overhead of creating multiple GRPC channels.
 */
@Slf4j
public class OjpXADataSource implements XADataSource {

    @Getter
    @Setter
    private String url;

    @Getter
    @Setter
    private String user;

    @Getter
    @Setter
    private String password;

    @Getter
    @Setter
    private int loginTimeout = 0;

    private PrintWriter logWriter;
    private final Properties properties = new Properties();
    
    // Shared StatementService per datasource - initialized lazily
    private StatementService statementService;
    
    // Parsed URL information
    private String cleanUrl;
    private String dataSourceName;

    public OjpXADataSource() {
        log.debug("Creating OjpXADataSource");
    }

    public OjpXADataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        log.debug("Creating OjpXADataSource with URL: {}", url);
    }
    
    /**
     * Initialize the StatementService and parse URL.
     * This is done lazily when the first XA connection is requested.
     * The GRPC channel is opened once and reused by all XA connections from this datasource.
     * Supports both single-node and multinode configurations.
     */
    private synchronized void initializeIfNeeded() throws SQLException {
        if (statementService != null) {
            return; // Already initialized
        }
        
        if (url == null || url.isEmpty()) {
            throw new SQLException("URL is not set");
        }
        
        // Parse URL to extract datasource name and clean URL
        UrlParser.UrlParseResult urlParseResult = UrlParser.parseUrlWithDataSource(url);
        this.cleanUrl = urlParseResult.cleanUrl;
        this.dataSourceName = urlParseResult.dataSourceName;
        
        log.debug("Parsed URL - clean: {}, dataSource: {}", cleanUrl, dataSourceName);
        
        // Load ojp.properties file and extract datasource-specific configuration
        Properties ojpProperties = loadOjpPropertiesForDataSource(dataSourceName);
        if (ojpProperties != null && !ojpProperties.isEmpty()) {
            // Merge ojp.properties with any manually set properties
            for (String key : ojpProperties.stringPropertyNames()) {
                if (!properties.containsKey(key)) {
                    properties.setProperty(key, ojpProperties.getProperty(key));
                }
            }
            log.debug("Loaded ojp.properties with {} properties for dataSource: {}", ojpProperties.size(), dataSourceName);
        }
        
        // Initialize StatementService based on URL format (single-node vs multinode)
        try {
            // Try to parse as multinode URL
            List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);
            
            if (endpoints.size() > 1) {
                // Multinode configuration detected - use MultinodeStatementService
                log.info("XA Multinode URL detected with {} endpoints: {}", 
                        endpoints.size(), MultinodeUrlParser.formatServerList(endpoints));
                
                MultinodeConnectionManager connectionManager = new MultinodeConnectionManager(endpoints);
                statementService = new MultinodeStatementService(connectionManager, url);
                
                // For multinode, update cleanUrl to use the first endpoint for connection metadata
                cleanUrl = MultinodeUrlParser.replaceBracketsWithSingleEndpoint(cleanUrl, endpoints.get(0));
                
                log.info("MultinodeStatementService initialized for XA datasource: {}. Using {} servers.", 
                        dataSourceName, endpoints.size());
            } else {
                // Single-node configuration - use traditional client
                log.debug("Initializing StatementServiceGrpcClient for single-node XA datasource: {}", dataSourceName);
                statementService = new StatementServiceGrpcClient();
                log.info("StatementService initialized for single-node XA datasource: {}. GRPC channel will open on first use.", dataSourceName);
            }
        } catch (IllegalArgumentException e) {
            // URL parsing failed, fall back to single-node client
            log.debug("XA URL not recognized as multinode format, using single-node client: {}", e.getMessage());
            statementService = new StatementServiceGrpcClient();
            log.info("StatementService initialized for single-node XA datasource: {}. GRPC channel will open on first use.", dataSourceName);
        }
    }
    
    /**
     * Load ojp.properties and extract configuration specific to the given dataSource.
     */
    private Properties loadOjpPropertiesForDataSource(String dataSourceName) {
        Properties allProperties = loadOjpProperties();
        if (allProperties == null || allProperties.isEmpty()) {
            return null;
        }
        
        Properties dataSourceProperties = new Properties();
        
        // Look for dataSource-prefixed properties first: {dataSourceName}.ojp.connection.pool.*
        String prefix = dataSourceName + ".ojp.connection.pool.";
        boolean foundDataSourceSpecific = false;
        
        for (String key : allProperties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                // Remove the dataSource prefix and keep the standard property name
                String standardKey = key.substring(dataSourceName.length() + 1); // Remove "{dataSourceName}."
                dataSourceProperties.setProperty(standardKey, allProperties.getProperty(key));
                foundDataSourceSpecific = true;
            }
        }
        
        // If no dataSource-specific properties found, and this is the "default" dataSource,
        // look for unprefixed properties: ojp.connection.pool.*
        if (!foundDataSourceSpecific && "default".equals(dataSourceName)) {
            for (String key : allProperties.stringPropertyNames()) {
                if (key.startsWith("ojp.connection.pool.")) {
                    dataSourceProperties.setProperty(key, allProperties.getProperty(key));
                }
            }
        }
        
        // Include the dataSource name as a property
        if (!dataSourceProperties.isEmpty()) {
            dataSourceProperties.setProperty("ojp.datasource.name", dataSourceName);
        }
        
        log.debug("Loaded {} properties for dataSource '{}': {}", 
                dataSourceProperties.size(), dataSourceName, dataSourceProperties);
        
        return dataSourceProperties.isEmpty() ? null : dataSourceProperties;
    }
    
    /**
     * Load the raw ojp.properties file from classpath.
     */
    protected Properties loadOjpProperties() {
        Properties properties = new Properties();
        
        // Only try to load from resources/ojp.properties in the classpath
        try (InputStream is = OjpXADataSource.class.getClassLoader().getResourceAsStream("ojp.properties")) {
            if (is != null) {
                properties.load(is);
                log.debug("Loaded ojp.properties from resources folder");
                return properties;
            }
        } catch (IOException e) {
            log.debug("Could not load ojp.properties from resources folder: {}", e.getMessage());
        }
        
        log.debug("No ojp.properties file found, using server defaults");
        return null;
    }

    @Override
    public XAConnection getXAConnection() throws SQLException {
        log.debug("getXAConnection called");
        return getXAConnection(user, password);
    }

    @Override
    public XAConnection getXAConnection(String username, String password) throws SQLException {
        log.debug("getXAConnection called with username: {}", username);
        
        // Initialize on first use (lazily)
        initializeIfNeeded();

        // Create XA connection using the shared StatementService
        // The GRPC channel is already open and will be reused
        // The session will be created lazily when first needed
        return new OjpXAConnection(statementService, cleanUrl, username, password, properties);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("getParentLogger not supported");
    }

    /**
     * Set a connection property.
     */
    public void setProperty(String name, String value) {
        properties.setProperty(name, value);
    }

    /**
     * Get a connection property.
     */
    public String getProperty(String name) {
        return properties.getProperty(name);
    }

    /**
     * Get all properties.
     */
    public Properties getProperties() {
        return new Properties(properties);
    }
}
