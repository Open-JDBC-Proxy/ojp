package org.openjproxy.grpc.server.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatasourceNameExtractorTest {

    @Test
    void testExtractDatasourceName_validUrl() {
        String url = "jdbc:ojp[localhost:5433(mydb)]_jdbc:postgresql://localhost:5432/testdb";
        assertEquals("mydb", DatasourceNameExtractor.extractDatasourceName(url));
    }

    @Test
    void testExtractDatasourceName_multipleParentheses() {
        String url = "jdbc:ojp[host:123(datasource1)]_jdbc:mysql://host:3306/db";
        assertEquals("datasource1", DatasourceNameExtractor.extractDatasourceName(url));
    }

    @Test
    void testExtractDatasourceName_emptyDatasourceName() {
        String url = "jdbc:ojp[localhost:5433()]_jdbc:postgresql://localhost:5432/testdb";
        assertNull(DatasourceNameExtractor.extractDatasourceName(url));
    }

    @Test
    void testExtractDatasourceName_noDatasourceName() {
        String url = "jdbc:ojp[localhost:5433]_jdbc:postgresql://localhost:5432/testdb";
        assertNull(DatasourceNameExtractor.extractDatasourceName(url));
    }

    @Test
    void testExtractDatasourceName_noBrackets() {
        String url = "jdbc:ojp_jdbc:postgresql://localhost:5432/testdb";
        assertNull(DatasourceNameExtractor.extractDatasourceName(url));
    }

    @Test
    void testExtractDatasourceName_notOjpUrl() {
        String url = "jdbc:postgresql://localhost:5432/testdb";
        assertNull(DatasourceNameExtractor.extractDatasourceName(url));
    }

    @Test
    void testExtractDatasourceName_nullUrl() {
        assertNull(DatasourceNameExtractor.extractDatasourceName(null));
    }

    @Test
    void testExtractDatasourceName_malformedBrackets() {
        String url = "jdbc:ojp]localhost:5433(mydb)[_jdbc:postgresql://localhost:5432/testdb";
        assertNull(DatasourceNameExtractor.extractDatasourceName(url));
    }

    @Test
    void testExtractDatasourceName_malformedParentheses() {
        String url = "jdbc:ojp[localhost:5433)mydb(_jdbc:postgresql://localhost:5432/testdb";
        assertNull(DatasourceNameExtractor.extractDatasourceName(url));
    }

    @Test
    void testExtractDatasourceNameOrDefault_withValidUrl() {
        String url = "jdbc:ojp[localhost:5433(mydb)]_jdbc:postgresql://localhost:5432/testdb";
        assertEquals("mydb", DatasourceNameExtractor.extractDatasourceNameOrDefault(url, "default"));
    }

    @Test
    void testExtractDatasourceNameOrDefault_withInvalidUrl() {
        String url = "jdbc:postgresql://localhost:5432/testdb";
        assertEquals("default", DatasourceNameExtractor.extractDatasourceNameOrDefault(url, "default"));
    }

    @Test
    void testExtractDatasourceNameOrDefault_withNullUrl() {
        assertEquals("fallback", DatasourceNameExtractor.extractDatasourceNameOrDefault(null, "fallback"));
    }

    @Test
    void testExtractDatasourceName_withSpecialCharactersInName() {
        String url = "jdbc:ojp[localhost:5433(my-db_123)]_jdbc:postgresql://localhost:5432/testdb";
        assertEquals("my-db_123", DatasourceNameExtractor.extractDatasourceName(url));
    }
}
