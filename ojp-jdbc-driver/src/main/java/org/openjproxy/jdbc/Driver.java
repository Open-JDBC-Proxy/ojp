package org.openjproxy.jdbc;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.MultinodeConnectionManager;
import org.openjproxy.grpc.client.MultinodeStatementService;
import org.openjproxy.grpc.client.MultinodeUrlParser;
import org.openjproxy.grpc.client.ServerEndpoint;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.grpc.client.StatementServiceGrpcClient;

import java.io.IOException;
import java.io.InputStream;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.openjproxy.jdbc.Constants.PASSWORD;
import static org.openjproxy.jdbc.Constants.USER;

@Slf4j
public class Driver implements java.sql.Driver {

    static {
        try {
            log.debug("Registering OpenJProxy Driver");
            DriverManager.registerDriver(new Driver());
        } catch (SQLException var1) {
            log.error("Can't register OJP driver!", var1);
        }
    }

    // Cache of statement services keyed by server configuration
    private static final Map<String, StatementService> statementServiceCache = new ConcurrentHashMap<>();

    public Driver() {
        // Services are created per-URL configuration in connect()
    }

    @Override
    public java.sql.Connection connect(String url, Properties info) throws SQLException {
        log.debug("connect: url={}, info={}", url, info);
        
        // Parse URL to extract dataSource name and clean URL
        UrlParser.UrlParseResult urlParseResult = UrlParser.parseUrlWithDataSource(url);
        String cleanUrl = urlParseResult.cleanUrl;
        String dataSourceName = urlParseResult.dataSourceName;
        
        log.debug("Parsed URL - clean: {}, dataSource: {}", cleanUrl, dataSourceName);
        
        // Detect multinode vs single-node configuration and get the URL to use for connection
        ServiceAndUrl serviceAndUrl = getOrCreateStatementService(cleanUrl);
        StatementService statementService = serviceAndUrl.service;
        String connectionUrl = serviceAndUrl.connectionUrl;
        
        // Load ojp.properties file and extract datasource-specific configuration
        Properties ojpProperties = loadOjpPropertiesForDataSource(dataSourceName);
        
        ConnectionDetails.Builder connBuilder = ConnectionDetails.newBuilder()
                .setUrl(connectionUrl)  // Use the possibly-modified URL with single endpoint
                .setUser((String) ((info.get(USER) != null)? info.get(USER) : ""))
                .setPassword((String) ((info.get(PASSWORD) != null) ? info.get(PASSWORD) : ""))
                .setClientUUID(ClientUUID.getUUID());
        
        if (ojpProperties != null && !ojpProperties.isEmpty()) {
            // Convert Properties to Map<String, Object>
            Map<String, Object> propertiesMap = new HashMap<>();
            for (String key : ojpProperties.stringPropertyNames()) {
                propertiesMap.put(key, ojpProperties.getProperty(key));
            }
            connBuilder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
            log.debug("Loaded ojp.properties with {} properties for dataSource: {}", ojpProperties.size(), dataSourceName);
        }
        
        log.info("Calling connect() on statement service with URL: {}", connectionUrl);
        SessionInfo sessionInfo;
        try {
            sessionInfo = statementService.connect(connBuilder.build());
            log.info("Connection established - sessionUUID: {}, connHash: {}", 
                    sessionInfo.getSessionUUID(), sessionInfo.getConnHash());
        } catch (Exception e) {
            log.error("Failed to establish connection", e);
            throw e;
        }
        log.debug("Returning new Connection with sessionInfo: {}", sessionInfo);
        return new Connection(sessionInfo, statementService, DatabaseUtils.resolveDbName(cleanUrl));
    }
    
    /**
     * Helper class to return both the service and the connection URL.
     */
    private static class ServiceAndUrl {
        final StatementService service;
        final String connectionUrl;
        
        ServiceAndUrl(StatementService service, String connectionUrl) {
            this.service = service;
            this.connectionUrl = connectionUrl;
        }
    }
    
    /**
     * Gets or creates a StatementService implementation based on the URL.
     * Returns both the service and the connection URL (which may be modified for multinode).
     * For multinode URLs, creates a MultinodeStatementService with load balancing and failover.
     * For single-node URLs, creates a StatementServiceGrpcClient.
     */
    private ServiceAndUrl getOrCreateStatementService(String url) {
        try {
            // Try to parse as multinode URL
            List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);
            
            if (endpoints.size() > 1) {
                // Multinode configuration detected - use MultinodeStatementService
                log.info("Multinode URL detected with {} endpoints: {}", 
                        endpoints.size(), MultinodeUrlParser.formatServerList(endpoints));
                
                // Create a cache key based on all endpoints to ensure same config reuses same service
                String cacheKey = "multinode:" + MultinodeUrlParser.formatServerList(endpoints);
                StatementService service = statementServiceCache.computeIfAbsent(cacheKey, k -> {
                    log.debug("Creating MultinodeStatementService for endpoints: {}", 
                            MultinodeUrlParser.formatServerList(endpoints));
                    MultinodeConnectionManager connectionManager = new MultinodeConnectionManager(endpoints);
                    return new MultinodeStatementService(connectionManager, url);
                });
                
                // For multinode, we need to pass a URL that can be parsed by the server
                // Use the original URL with the first endpoint for connection metadata
                String connectionUrl = MultinodeUrlParser.replaceBracketsWithSingleEndpoint(url, endpoints.get(0));
                
                return new ServiceAndUrl(service, connectionUrl);
            } else {
                // Single-node configuration - use traditional client
                String cacheKey = "single:" + endpoints.get(0).getAddress();
                StatementService service = statementServiceCache.computeIfAbsent(cacheKey, k -> {
                    log.debug("Creating StatementServiceGrpcClient for single-node");
                    return new StatementServiceGrpcClient();
                });
                
                return new ServiceAndUrl(service, url);
            }
        } catch (IllegalArgumentException e) {
            // URL parsing failed, fall back to single-node client
            log.debug("URL not recognized as multinode format, using single-node client: {}", e.getMessage());
            StatementService service = statementServiceCache.computeIfAbsent("default", k -> {
                log.debug("Creating default StatementServiceGrpcClient");
                return new StatementServiceGrpcClient();
            });
            
            return new ServiceAndUrl(service, url);
        }
    }
    
    
    /**
     * Load ojp.properties and extract configuration specific to the given dataSource.
     */
    protected Properties loadOjpPropertiesForDataSource(String dataSourceName) {
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
        
        // If we found any properties, also include the dataSource name as a single property
        // Note: The dataSource-prefixed properties (e.g., "webApp.ojp.connection.pool.*") 
        // are sent to the server with their prefixes removed (e.g., "ojp.connection.pool.*"),
        // and the dataSource name itself is sent separately as "ojp.datasource.name"
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
        try (InputStream is = Driver.class.getClassLoader().getResourceAsStream("ojp.properties")) {
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
    public boolean acceptsURL(String url) throws SQLException {
        log.debug("acceptsURL: {}", url);
        if (url == null) {
            log.error("URL is null");
            throw new SQLException("URL is null");
        } else {
            boolean accepts = url.startsWith("jdbc:ojp");
            log.debug("acceptsURL returns: {}", accepts);
            return accepts;
        }
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        log.debug("getPropertyInfo: url={}, info={}", url, info);
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        log.debug("getMajorVersion called");
        return 0;
    }

    @Override
    public int getMinorVersion() {
        log.debug("getMinorVersion called");
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        log.debug("jdbcCompliant called");
        return false;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        log.debug("getParentLogger called");
        return null;
    }
}