package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.MultinodeUrlParser.Endpoint;
import org.openjproxy.jdbc.MultinodeUrlParser.ParseResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MultinodeUrlParser.
 */
class MultinodeUrlParserTest {
    
    @Test
    void testParseMultinodeUrl() {
        String url = "jdbc:ojp[host1:1059,host2:1060]_postgresql://localhost/mydb";
        ParseResult result = MultinodeUrlParser.parse(url);
        
        assertTrue(result.isMultinode());
        assertEquals(2, result.getEndpoints().size());
        assertEquals("host1", result.getEndpoints().get(0).getHost());
        assertEquals(1059, result.getEndpoints().get(0).getPort());
        assertEquals("host2", result.getEndpoints().get(1).getHost());
        assertEquals(1060, result.getEndpoints().get(1).getPort());
    }
    
    @Test
    void testParseSingleNodeUrl() {
        String url = "jdbc:ojp[localhost:1059]_h2:mem:test";
        ParseResult result = MultinodeUrlParser.parse(url);
        
        assertFalse(result.isMultinode());
        assertEquals(1, result.getEndpoints().size());
        assertEquals("localhost", result.getEndpoints().get(0).getHost());
        assertEquals(1059, result.getEndpoints().get(0).getPort());
    }
    
    @Test
    void testParseThreeEndpoints() {
        String url = "jdbc:ojp[server1:1059,server2:1059,server3:1059]_mysql://localhost/db";
        ParseResult result = MultinodeUrlParser.parse(url);
        
        assertTrue(result.isMultinode());
        assertEquals(3, result.getEndpoints().size());
        assertEquals("server1", result.getEndpoints().get(0).getHost());
        assertEquals("server2", result.getEndpoints().get(1).getHost());
        assertEquals("server3", result.getEndpoints().get(2).getHost());
    }
    
    @Test
    void testParseWithDataSourceName() {
        String url = "jdbc:ojp[host1:1059(webApp),host2:1060(webApp)]_postgresql://localhost/mydb";
        ParseResult result = MultinodeUrlParser.parse(url);
        
        assertTrue(result.isMultinode());
        assertEquals(2, result.getEndpoints().size());
        assertEquals("host1", result.getEndpoints().get(0).getHost());
        assertEquals(1059, result.getEndpoints().get(0).getPort());
        assertEquals("host2", result.getEndpoints().get(1).getHost());
        assertEquals(1060, result.getEndpoints().get(1).getPort());
    }
    
    @Test
    void testParseWithSpaces() {
        String url = "jdbc:ojp[host1:1059 , host2:1060]_postgresql://localhost/mydb";
        ParseResult result = MultinodeUrlParser.parse(url);
        
        assertTrue(result.isMultinode());
        assertEquals(2, result.getEndpoints().size());
        assertEquals("host1", result.getEndpoints().get(0).getHost());
        assertEquals("host2", result.getEndpoints().get(1).getHost());
    }
    
    @Test
    void testParseNullUrl() {
        ParseResult result = MultinodeUrlParser.parse(null);
        
        assertFalse(result.isMultinode());
        assertEquals(0, result.getEndpoints().size());
    }
    
    @Test
    void testParseInvalidUrl() {
        String url = "jdbc:postgresql://localhost/mydb";
        ParseResult result = MultinodeUrlParser.parse(url);
        
        assertFalse(result.isMultinode());
        assertEquals(0, result.getEndpoints().size());
    }
    
    @Test
    void testParseEmptyEndpoints() {
        String url = "jdbc:ojp[]_postgresql://localhost/mydb";
        ParseResult result = MultinodeUrlParser.parse(url);
        
        assertFalse(result.isMultinode());
        assertEquals(0, result.getEndpoints().size());
    }
    
    @Test
    void testEndpointEquality() {
        Endpoint ep1 = new Endpoint("host1", 1059);
        Endpoint ep2 = new Endpoint("host1", 1059);
        Endpoint ep3 = new Endpoint("host2", 1059);
        
        assertEquals(ep1, ep2);
        assertNotEquals(ep1, ep3);
        assertEquals(ep1.hashCode(), ep2.hashCode());
    }
    
    @Test
    void testEndpointToString() {
        Endpoint endpoint = new Endpoint("localhost", 1059);
        assertEquals("localhost:1059", endpoint.toString());
    }
    
    @Test
    void testParseWithDifferentPorts() {
        String url = "jdbc:ojp[host1:8080,host2:9090,host3:7070]_postgresql://localhost/mydb";
        ParseResult result = MultinodeUrlParser.parse(url);
        
        assertTrue(result.isMultinode());
        assertEquals(3, result.getEndpoints().size());
        assertEquals(8080, result.getEndpoints().get(0).getPort());
        assertEquals(9090, result.getEndpoints().get(1).getPort());
        assertEquals(7070, result.getEndpoints().get(2).getPort());
    }
}
