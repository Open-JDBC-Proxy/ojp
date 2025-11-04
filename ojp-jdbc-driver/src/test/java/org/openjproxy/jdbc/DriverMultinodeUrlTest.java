package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.client.MultinodeUrlParser;
import org.openjproxy.grpc.client.ServerEndpoint;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjproxy.constants.CommonConstants.OJP_REGEX_PATTERN;

/**
 * Tests to validate that multinode URLs are correctly parsed and can be used
 * with the existing StatementServiceGrpcClient infrastructure.
 */
public class DriverMultinodeUrlTest {

    @Test
    public void testMultinodeUrlParsing() {
        String url = "jdbc:ojp[localhost:10591,localhost:10592]_postgresql://localhost:5432/defaultdb";
        
        // Parse the multinode URL
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);
        
        // Should have 2 endpoints
        assertEquals(2, endpoints.size());
        assertEquals("localhost:10591", endpoints.get(0).getAddress());
        assertEquals("localhost:10592", endpoints.get(1).getAddress());
    }

    @Test
    public void testSingleEndpointUrlConversion() {
        String url = "jdbc:ojp[localhost:10591,localhost:10592]_postgresql://localhost:5432/defaultdb";
        
        // Parse endpoints
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);
        
        // Replace with single endpoint
        String singleEndpointUrl = MultinodeUrlParser.replaceBracketsWithSingleEndpoint(url, endpoints.get(0));
        
        // Verify the result
        assertEquals("jdbc:ojp[localhost:10591]_postgresql://localhost:5432/defaultdb", singleEndpointUrl);
    }

    @Test
    public void testSingleEndpointUrlMatchesPattern() {
        String url = "jdbc:ojp[localhost:10591,localhost:10592]_postgresql://localhost:5432/defaultdb";
        
        // Parse and convert to single endpoint
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);
        String singleEndpointUrl = MultinodeUrlParser.replaceBracketsWithSingleEndpoint(url, endpoints.get(0));
        
        // Verify it matches the pattern expected by StatementServiceGrpcClient
        Pattern pattern = Pattern.compile(OJP_REGEX_PATTERN);
        Matcher matcher = pattern.matcher(singleEndpointUrl);
        
        assertTrue(matcher.find(), "Single endpoint URL should match OJP regex pattern");
        
        String hostPort = matcher.group(1);
        assertEquals("localhost:10591", hostPort);
        
        String[] hostPortSplit = hostPort.split(":");
        assertEquals("localhost", hostPortSplit[0]);
        assertEquals("10591", hostPortSplit[1]);
    }

    @Test
    public void testMultipleEndpointsConversion() {
        String url = "jdbc:ojp[server1:1059,server2:1060,server3:1061]_postgresql://localhost:5432/mydb";
        
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);
        assertEquals(3, endpoints.size());
        
        // Test each endpoint conversion
        for (int i = 0; i < endpoints.size(); i++) {
            String singleUrl = MultinodeUrlParser.replaceBracketsWithSingleEndpoint(url, endpoints.get(i));
            
            Pattern pattern = Pattern.compile(OJP_REGEX_PATTERN);
            Matcher matcher = pattern.matcher(singleUrl);
            
            assertTrue(matcher.find(), "Endpoint " + i + " URL should match pattern");
            assertEquals(endpoints.get(i).getAddress(), matcher.group(1));
        }
    }

    @Test
    public void testSingleNodeUrlUnchanged() {
        String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb";
        
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);
        assertEquals(1, endpoints.size());
        
        String singleUrl = MultinodeUrlParser.replaceBracketsWithSingleEndpoint(url, endpoints.get(0));
        
        // Should remain the same
        assertEquals(url, singleUrl);
    }

    @Test
    public void testUrlParserDoesNotThrowOnValidMultinodeUrl() {
        String url = "jdbc:ojp[localhost:10591,localhost:10592]_postgresql://localhost:5432/defaultdb";
        
        // This should not throw any exception
        assertDoesNotThrow(() -> {
            List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(url);
            String singleUrl = MultinodeUrlParser.replaceBracketsWithSingleEndpoint(url, endpoints.get(0));
            
            Pattern pattern = Pattern.compile(OJP_REGEX_PATTERN);
            Matcher matcher = pattern.matcher(singleUrl);
            
            if (!matcher.find()) {
                throw new RuntimeException("Invalid OJP host or port.");
            }
            
            String hostPort = matcher.group(1);
            String[] hostPortSplit = hostPort.split(":");
            String host = hostPortSplit[0];
            int port = Integer.parseInt(hostPortSplit[1]);
            
            // Verify extracted values
            assertEquals("localhost", host);
            assertEquals(10591, port);
        });
    }

    @Test
    public void testDriverUrlParsingFlow() {
        // Simulate what Driver.connect() does
        String url = "jdbc:ojp[localhost:10591,localhost:10592]_postgresql://localhost:5432/defaultdb";
        
        // Parse URL with data source
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(url);
        String cleanUrl = result.cleanUrl;
        
        // Try to parse as multinode
        List<ServerEndpoint> endpoints = MultinodeUrlParser.parseServerEndpoints(cleanUrl);
        
        assertNotNull(endpoints);
        assertTrue(endpoints.size() > 1, "Should detect multinode configuration");
        
        // Replace with single endpoint
        String connectionUrl = MultinodeUrlParser.replaceBracketsWithSingleEndpoint(cleanUrl, endpoints.get(0));
        
        // Verify the connection URL is valid for StatementServiceGrpcClient
        Pattern pattern = Pattern.compile(OJP_REGEX_PATTERN);
        Matcher matcher = pattern.matcher(connectionUrl);
        
        assertTrue(matcher.find(), "Connection URL must match OJP pattern");
        
        String hostPort = matcher.group(1);
        assertFalse(hostPort.contains(","), "Host:port should not contain comma");
        assertEquals("localhost:10591", hostPort);
    }
}
