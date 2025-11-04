package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for multinode OJP JDBC URLs.
 * Handles URLs with multiple endpoints like: jdbc:ojp[host1:port1,host2:port2]_postgresql://...
 */
@Slf4j
public class MultinodeUrlParser {
    
    private static final Pattern MULTINODE_PATTERN = Pattern.compile("ojp\\[([^\\]]+)\\]");
    
    /**
     * Represents a parsed endpoint with host and port.
     */
    public static class Endpoint {
        private final String host;
        private final int port;
        
        public Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
        
        public String getHost() {
            return host;
        }
        
        public int getPort() {
            return port;
        }
        
        @Override
        public String toString() {
            return host + ":" + port;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Endpoint endpoint = (Endpoint) o;
            return port == endpoint.port && host.equals(endpoint.host);
        }
        
        @Override
        public int hashCode() {
            return 31 * host.hashCode() + port;
        }
    }
    
    /**
     * Result of parsing a multinode URL.
     */
    public static class ParseResult {
        private final List<Endpoint> endpoints;
        private final boolean isMultinode;
        
        public ParseResult(List<Endpoint> endpoints, boolean isMultinode) {
            this.endpoints = endpoints;
            this.isMultinode = isMultinode;
        }
        
        public List<Endpoint> getEndpoints() {
            return endpoints;
        }
        
        public boolean isMultinode() {
            return isMultinode;
        }
    }
    
    /**
     * Parse a JDBC URL to extract multinode endpoints.
     * 
     * Examples:
     * - jdbc:ojp[host1:1059,host2:1059]_postgresql://... -> 2 endpoints
     * - jdbc:ojp[localhost:1059]_h2:mem:test -> 1 endpoint (not multinode)
     * 
     * @param url the JDBC URL
     * @return ParseResult containing endpoints and multinode flag
     */
    public static ParseResult parse(String url) {
        if (url == null || !url.startsWith("jdbc:ojp[")) {
            return new ParseResult(new ArrayList<>(), false);
        }
        
        Matcher matcher = MULTINODE_PATTERN.matcher(url);
        if (!matcher.find()) {
            return new ParseResult(new ArrayList<>(), false);
        }
        
        String endpointsStr = matcher.group(1);
        List<Endpoint> endpoints = parseEndpoints(endpointsStr);
        
        // It's multinode only if there are multiple endpoints
        boolean isMultinode = endpoints.size() > 1;
        
        log.debug("Parsed URL '{}' as {} with {} endpoint(s)", 
                url, isMultinode ? "multinode" : "single-node", endpoints.size());
        
        return new ParseResult(endpoints, isMultinode);
    }
    
    /**
     * Parse the endpoints string (e.g., "host1:1059,host2:1059").
     * Handles datasource names in parentheses: "host1:1059(ds1),host2:1059(ds2)"
     */
    private static List<Endpoint> parseEndpoints(String endpointsStr) {
        List<Endpoint> endpoints = new ArrayList<>();
        
        if (endpointsStr == null || endpointsStr.trim().isEmpty()) {
            return endpoints;
        }
        
        // Remove any datasource name in parentheses
        endpointsStr = endpointsStr.replaceAll("\\([^)]*\\)", "").trim();
        
        String[] parts = endpointsStr.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            
            String[] hostPort = part.split(":");
            if (hostPort.length == 2) {
                try {
                    String host = hostPort[0].trim();
                    int port = Integer.parseInt(hostPort[1].trim());
                    endpoints.add(new Endpoint(host, port));
                } catch (NumberFormatException e) {
                    log.warn("Invalid port in endpoint: {}", part);
                }
            } else {
                log.warn("Invalid endpoint format: {}", part);
            }
        }
        
        return endpoints;
    }
}
