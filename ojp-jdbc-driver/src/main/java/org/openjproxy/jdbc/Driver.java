package org.openjproxy.jdbc;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.MultinodeUrlParser;
import org.openjproxy.grpc.client.ServerEndpoint;
import org.openjproxy.grpc.client.StatementService;

import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

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

    public Driver() {
        // Services are created per-URL configuration in connect()
    }

    @Override
    public java.sql.Connection connect(String url, Properties info) throws SQLException {
        log.debug("connect: url={}, info={}", url, info);
        
        // Parse URL and get service
        UrlParser.UrlParseResult urlParseResult = UrlParser.parseUrlWithDataSource(url);
        MultinodeUrlParser.ServiceAndUrl serviceAndUrl = MultinodeUrlParser.getOrCreateStatementService(
                urlParseResult.cleanUrl, urlParseResult.dataSourceNames);
        
        // Warn about multinode datasource configuration if applicable
        warnIfMultipleDatasources(serviceAndUrl.getServerEndpointsWithDatasources(), urlParseResult.dataSourceName);
        
        // Build connection details with merged properties
        ConnectionDetails connectionDetails = buildConnectionDetails(
                serviceAndUrl.getConnectionUrl(),
                serviceAndUrl.getServerEndpoints(),
                urlParseResult.dataSourceName,
                info);
        
        // Connect to server
        SessionInfo sessionInfo = connectToServer(serviceAndUrl.getService(), connectionDetails);
        
        return new Connection(sessionInfo, serviceAndUrl.getService(), 
                DatabaseUtils.resolveDbName(urlParseResult.cleanUrl));
    }
    
    private void warnIfMultipleDatasources(List<ServerEndpoint> serverEndpoints, String dataSourceName) {
        if (serverEndpoints.size() <= 1) {
            return;
        }
        
        boolean hasMultipleDatasources = serverEndpoints.stream()
            .map(ServerEndpoint::getDataSourceName)
            .distinct()
            .count() > 1;
        
        if (hasMultipleDatasources) {
            // Warn when using different datasources per server endpoint in a multinode setup.
            // This is a valid but advanced configuration. Each server may use a different datasource
            // (e.g., geographically distributed databases), but the connection properties are loaded
            // from the first datasource only. The user should ensure all datasources have compatible
            // connection settings or configure each server's datasource properties explicitly.
            log.warn("Per-endpoint datasources detected. Currently using first datasource '{}' for connection properties. " +
                    "Per-server configuration will be applied based on server endpoint datasource names: {}", 
                    dataSourceName,
                    serverEndpoints.stream()
                        .map(ep -> ep.getAddress() + "=" + ep.getDataSourceName())
                        .collect(java.util.stream.Collectors.joining(", ")));
        }
    }
    
    private ConnectionDetails buildConnectionDetails(String connectionUrl, List<String> serverEndpoints,
                                                      String dataSourceName, Properties info) {
        ConnectionDetails.Builder connBuilder = ConnectionDetails.newBuilder()
                .setUrl(connectionUrl)
                .setUser((String) ((info.get(USER) != null) ? info.get(USER) : ""))
                .setPassword((String) ((info.get(PASSWORD) != null) ? info.get(PASSWORD) : ""))
                .setClientUUID(ClientUUID.getUUID())
                .addAllServerEndpoints(serverEndpoints);
        
        log.info("Adding {} server endpoint(s) to ConnectionDetails", serverEndpoints.size());
        
        // Merge properties from file and caller
        Map<String, Object> propertiesMap = mergeProperties(dataSourceName, info);
        
        if (!propertiesMap.isEmpty()) {
            connBuilder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
            log.debug("Sending {} properties to server for dataSource: {}", propertiesMap.size(), dataSourceName);
        }
        
        return connBuilder.build();
    }
    
    private Map<String, Object> mergeProperties(String dataSourceName, Properties info) {
        Map<String, Object> propertiesMap = new HashMap<>();
        
        // Load from ojp.properties file
        Properties ojpProperties = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource(dataSourceName);
        if (ojpProperties != null && !ojpProperties.isEmpty()) {
            for (String key : ojpProperties.stringPropertyNames()) {
                propertiesMap.put(key, ojpProperties.getProperty(key));
            }
            log.debug("Loaded ojp.properties with {} properties for dataSource: {}", propertiesMap.size(), dataSourceName);
        }
        
        // Overlay caller-provided properties (skip standard JDBC user/password)
        if (info != null) {
            for (String key : info.stringPropertyNames()) {
                if (!USER.equals(key) && !PASSWORD.equals(key)) {
                    propertiesMap.put(key, info.getProperty(key));
                }
            }
        }
        
        // Add cache configuration
        try {
            CacheConfigurationBuilder.addCachePropertiesToMap(propertiesMap, dataSourceName);
        } catch (Exception e) {
            log.error("Failed to add cache configuration for datasource '{}': {}", dataSourceName, e.getMessage());
        }
        
        return propertiesMap;
    }
    
    private SessionInfo connectToServer(StatementService statementService, ConnectionDetails connectionDetails) throws SQLException {
        log.info("Calling connect() on statement service with URL: {}", connectionDetails.getUrl());
        try {
            SessionInfo sessionInfo = statementService.connect(connectionDetails);
            log.info("Connection established - sessionUUID: {}, connHash: {}", 
                    sessionInfo.getSessionUUID(), sessionInfo.getConnHash());
            return sessionInfo;
        } catch (Exception e) {
            log.error("Failed to establish connection", e);
            throw e;
        }
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