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
        
        // Parse URL to extract dataSource name(s) and clean URL
        UrlParser.UrlParseResult urlParseResult = UrlParser.parseUrlWithDataSource(url);
        String cleanUrl = urlParseResult.cleanUrl;
        String dataSourceName = urlParseResult.dataSourceName;
        List<String> dataSourceNames = urlParseResult.dataSourceNames;
        
        log.debug("Parsed URL - clean: {}, dataSource: {}, dataSources: {}", cleanUrl, dataSourceName, dataSourceNames);
        
        // Get or create the StatementService for these endpoints
        MultinodeUrlParser.ServiceAndUrl serviceAndUrl = MultinodeUrlParser.getOrCreateStatementService(cleanUrl, dataSourceNames);
        StatementService statementService = serviceAndUrl.getService();
        String connectionUrl = serviceAndUrl.getConnectionUrl();
        List<String> serverEndpoints = serviceAndUrl.getServerEndpoints();
        List<ServerEndpoint> serverEndpointsWithDatasources = serviceAndUrl.getServerEndpointsWithDatasources();
        
        // Warn when multiple endpoints carry distinct datasource names so that operators
        // are aware of the per-server routing that will be applied.
        if (serverEndpointsWithDatasources.size() > 1) {
            boolean hasMultipleDatasources = serverEndpointsWithDatasources.stream()
                .map(ServerEndpoint::getDataSourceName)
                .distinct()
                .count() > 1;
            
            if (hasMultipleDatasources) {
                log.warn("Per-endpoint datasources detected. Currently using first datasource '{}' for connection properties. " +
                        "Per-server configuration will be applied based on server endpoint datasource names: {}", 
                        dataSourceName,
                        serverEndpointsWithDatasources.stream()
                            .map(ep -> ep.getAddress() + "=" + ep.getDataSourceName())
                            .collect(java.util.stream.Collectors.joining(", ")));
            }
        }
        
        // Load ojp.properties file and extract datasource-specific configuration
        Properties ojpProperties = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource(dataSourceName);
        
        ConnectionDetails.Builder connBuilder = ConnectionDetails.newBuilder()
                .setUrl(connectionUrl)
                .setUser((String) ((info.get(USER) != null)? info.get(USER) : ""))
                .setPassword((String) ((info.get(PASSWORD) != null) ? info.get(PASSWORD) : ""))
                .setClientUUID(ClientUUID.getUUID());
        
        // Always add all server endpoints for cluster coordination
        connBuilder.addAllServerEndpoints(serverEndpoints);
        log.info("Adding {} server endpoint(s) to ConnectionDetails", serverEndpoints.size());
        
        if (ojpProperties != null && !ojpProperties.isEmpty()) {
            // Convert Properties to Map<String, Object>
            Map<String, Object> propertiesMap = new HashMap<>();
            for (String key : ojpProperties.stringPropertyNames()) {
                propertiesMap.put(key, ojpProperties.getProperty(key));
            }
            
            // Add cache configuration properties to the map
            try {
                CacheConfigurationBuilder.addCachePropertiesToMap(propertiesMap, dataSourceName);
            } catch (Exception e) {
                log.error("Failed to add cache configuration for datasource '{}': {}", dataSourceName, e.getMessage());
                // Continue without cache configuration - caching will be disabled
            }
            
            connBuilder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
            log.debug("Loaded ojp.properties with {} properties for dataSource: {}", propertiesMap.size(), dataSourceName);
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