package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlParserTest {

    @Test
    void testParseSingleEndpointWithDatasource() {
        String url = "jdbc:ojp[localhost:1059(webApp)]_postgresql://localhost/mydb";
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(url);
        
        assertEquals("jdbc:ojp[localhost:1059]_postgresql://localhost/mydb", result.cleanUrl);
        assertEquals("webApp", result.dataSourceName);
        assertEquals(1, result.dataSourceNames.size());
        assertEquals("webApp", result.dataSourceNames.get(0));
    }

    @Test
    void testParseSingleEndpointWithoutDatasource() {
        String url = "jdbc:ojp[localhost:1059]_postgresql://localhost/mydb";
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(url);
        
        assertEquals("jdbc:ojp[localhost:1059]_postgresql://localhost/mydb", result.cleanUrl);
        assertEquals("default", result.dataSourceName);
        assertEquals(1, result.dataSourceNames.size());
        assertEquals("default", result.dataSourceNames.get(0));
    }

    @Test
    void testParseMultipleEndpointsWithSameDatasource() {
        String url = "jdbc:ojp[localhost:10591(multinode),localhost:10592(multinode)]_postgresql://localhost:5432/defaultdb";
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(url);
        
        assertEquals("jdbc:ojp[localhost:10591,localhost:10592]_postgresql://localhost:5432/defaultdb", result.cleanUrl);
        assertEquals("multinode", result.dataSourceName);
        assertEquals(2, result.dataSourceNames.size());
        assertEquals("multinode", result.dataSourceNames.get(0));
        assertEquals("multinode", result.dataSourceNames.get(1));
    }

    @Test
    void testParseMultipleEndpointsWithDifferentDatasources() {
        String url = "jdbc:ojp[localhost:10591(default),localhost:10592(multinode)]_h2:~/test";
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(url);
        
        assertEquals("jdbc:ojp[localhost:10591,localhost:10592]_h2:~/test", result.cleanUrl);
        assertEquals("default", result.dataSourceName); // First datasource for backward compatibility
        assertEquals(2, result.dataSourceNames.size());
        assertEquals("default", result.dataSourceNames.get(0));
        assertEquals("multinode", result.dataSourceNames.get(1));
    }

    @Test
    void testParseMultipleEndpointsMixedDatasources() {
        String url = "jdbc:ojp[server1:1059(ds1),server2:1059,server3:1059(ds3)]_postgresql://localhost/db";
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(url);
        
        assertEquals("jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost/db", result.cleanUrl);
        assertEquals("ds1", result.dataSourceName); // First datasource
        assertEquals(3, result.dataSourceNames.size());
        assertEquals("ds1", result.dataSourceNames.get(0));
        assertEquals("default", result.dataSourceNames.get(1)); // No datasource specified
        assertEquals("ds3", result.dataSourceNames.get(2));
    }

    @Test
    void testParseMultipleEndpointsNoDatasources() {
        String url = "jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost/db";
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(url);
        
        assertEquals("jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost/db", result.cleanUrl);
        assertEquals("default", result.dataSourceName);
        assertEquals(3, result.dataSourceNames.size());
        assertEquals("default", result.dataSourceNames.get(0));
        assertEquals("default", result.dataSourceNames.get(1));
        assertEquals("default", result.dataSourceNames.get(2));
    }

    @Test
    void testParseWithSpaces() {
        String url = "jdbc:ojp[localhost:1059( webApp )]_postgresql://localhost/mydb";
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(url);
        
        assertEquals("jdbc:ojp[localhost:1059]_postgresql://localhost/mydb", result.cleanUrl);
        assertEquals("webApp", result.dataSourceName); // Trimmed
    }

    @Test
    void testParseMultipleEndpointsWithSpaces() {
        String url = "jdbc:ojp[localhost:10591 ( ds1 ) , localhost:10592 ( ds2 )]_h2:~/test";
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(url);
        
        assertEquals("jdbc:ojp[localhost:10591,localhost:10592]_h2:~/test", result.cleanUrl);
        assertEquals("ds1", result.dataSourceName);
        assertEquals(2, result.dataSourceNames.size());
        assertEquals("ds1", result.dataSourceNames.get(0));
        assertEquals("ds2", result.dataSourceNames.get(1));
    }

    @Test
    void testParseNullUrl() {
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(null);
        assertNull(result.cleanUrl);
        assertEquals("default", result.dataSourceName);
    }

    @Test
    void testParseNonOjpUrl() {
        String url = "jdbc:postgresql://localhost:5432/mydb";
        UrlParser.UrlParseResult result = UrlParser.parseUrlWithDataSource(url);
        
        assertEquals(url, result.cleanUrl);
        assertEquals("default", result.dataSourceName);
    }
}
