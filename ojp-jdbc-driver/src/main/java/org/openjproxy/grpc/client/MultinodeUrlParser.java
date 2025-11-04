package org.openjproxy.grpc.client;

import org.openjproxy.constants.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class for parsing OJP URLs with multinode support.
 * Supports both single node and comma-separated multinode syntax.
 * <p>
 * Examples:
 * - Single: jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb
 * - Multi: jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/mydb
 */
public class MultinodeUrlParser {
    
    private static final Logger log = LoggerFactory.getLogger(MultinodeUrlParser.class);
    private static final Pattern OJP_PATTERN = Pattern.compile(CommonConstants.OJP_REGEX_PATTERN);

    /**
     * Parses an OJP URL and extracts server endpoints.
     * 
     * @param url The OJP JDBC URL to parse
     * @return List of server endpoints
     * @throws IllegalArgumentException if URL format is invalid
     */
    public static List<ServerEndpoint> parseServerEndpoints(String url) {
        if (url == null) {
            throw new IllegalArgumentException("URL cannot be null");
        }

        Matcher matcher = OJP_PATTERN.matcher(url);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Invalid OJP URL format. Expected: jdbc:ojp[host:port]_actual_jdbc_url");
        }

        String serverListString = matcher.group(1);
        List<ServerEndpoint> endpoints = new ArrayList<>();

        // Split by comma to support multinode
        String[] serverAddresses = serverListString.split(",");

        for (String address : serverAddresses) {
            address = address.trim();
            if (address.isEmpty()) {
                continue;
            }

            String[] hostPort = address.split(":");
            if (hostPort.length != 2) {
                throw new IllegalArgumentException("Invalid server address format: " + address + ". Expected format: host:port");
            }

            String host = hostPort[0].trim();
            int port;
            try {
                port = Integer.parseInt(hostPort[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port number in address: " + address, e);
            }

            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port number must be between 1 and 65535. Got: " + port);
            }

            endpoints.add(new ServerEndpoint(host, port));
        }

        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("No valid server endpoints found in URL: " + url);
        }

        log.debug("Parsed {} server endpoints from URL: {}", endpoints.size(),
                endpoints.stream().map(ServerEndpoint::getAddress).collect(Collectors.toList()));

        return endpoints;
    }

    /**
     * Extracts the actual JDBC URL by removing the OJP prefix.
     * 
     * @param url The OJP JDBC URL
     * @return The actual JDBC URL without OJP prefix
     */
    public static String extractActualJdbcUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll(CommonConstants.OJP_REGEX_PATTERN + "_", "");
    }

    /**
     * Formats a list of server endpoints back into the OJP URL format.
     * 
     * @param endpoints List of server endpoints
     * @return Comma-separated string representation for URL
     */
    public static String formatServerList(List<ServerEndpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return "";
        }

        return endpoints.stream()
                .map(ServerEndpoint::getAddress)
                .collect(Collectors.joining(","));
    }
    
    /**
     * Replaces the server list in a multinode URL with a single endpoint.
     * This is useful for converting multinode URLs to single-node format.
     * 
     * @param url The original OJP URL (possibly with multiple endpoints)
     * @param endpoint The single endpoint to use
     * @return The URL with the server list replaced by a single endpoint
     */
    public static String replaceBracketsWithSingleEndpoint(String url, ServerEndpoint endpoint) {
        if (url == null || endpoint == null) {
            throw new IllegalArgumentException("URL and endpoint cannot be null");
        }
        
        // Replace the entire [server1:port1,server2:port2,...] section with [singleHost:singlePort]
        // The pattern includes "ojp" so we need to keep it in the replacement
        return url.replaceAll(CommonConstants.OJP_REGEX_PATTERN, "ojp[" + endpoint.getAddress() + "]");
    }
}
