package org.openjproxy.grpc.server.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for UrlParser with property placeholder resolution.
 */
class UrlParserTest {

    @BeforeEach
    void setUp() {
        // Clear any test properties before each test
        System.clearProperty("ojp.server.sslrootcert");
        System.clearProperty("ojp.server.sslcert");
        System.clearProperty("ojp.server.sslkey");
    }

    @Test
    void testParseUrlWithoutPlaceholders() {
        String input = "jdbc:postgresql://host:5432/db?ssl=true";
        String result = UrlParser.parseUrl(input);

        assertEquals(input, result);
    }

    @Test
    void testParseUrlWithOjpPatternAndPlaceholder() {
        System.setProperty("ojp.server.sslrootcert", "/etc/ojp/certs/ca-cert.pem");

        String input = "jdbc:ojp[localhost:1059]_postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}";
        String expected = "jdbc:postgresql://host:5432/db?sslrootcert=/etc/ojp/certs/ca-cert.pem";
        String result = UrlParser.parseUrl(input);

        assertEquals(expected, result);
    }

    @Test
    void testParseUrlWithPlaceholderOnly() {
        System.setProperty("ojp.server.sslrootcert", "/etc/ojp/certs/ca-cert.pem");

        String input = "jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}";
        String expected = "jdbc:postgresql://host:5432/db?sslrootcert=/etc/ojp/certs/ca-cert.pem";
        String result = UrlParser.parseUrl(input);

        assertEquals(expected, result);
    }

    @Test
    void testParseUrlWithMultiplePlaceholders() {
        System.setProperty("ojp.server.sslrootcert", "/etc/ojp/certs/ca-cert.pem");
        System.setProperty("ojp.server.sslcert", "/etc/ojp/certs/client-cert.pem");
        System.setProperty("ojp.server.sslkey", "/etc/ojp/certs/client-key.pem");

        String input = "jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}&sslcert=${ojp.server.sslcert}&sslkey=${ojp.server.sslkey}";
        String expected = "jdbc:postgresql://host:5432/db?sslrootcert=/etc/ojp/certs/ca-cert.pem&sslcert=/etc/ojp/certs/client-cert.pem&sslkey=/etc/ojp/certs/client-key.pem";
        String result = UrlParser.parseUrl(input);

        assertEquals(expected, result);
    }

    @Test
    void testParseUrlNullInput() {
        String result = UrlParser.parseUrl(null);
        assertNull(result);
    }

    @Test
    void testParseUrlMissingPlaceholderThrowsException() {
        String input = "jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}";

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> UrlParser.parseUrl(input));

        assertTrue(exception.getMessage().contains("ojp.server.sslrootcert"));
    }

    @Test
    void testParsePostgreSQLWithSSLPlaceholders() {
        System.setProperty("ojp.server.sslrootcert", "/etc/ojp/certs/ca-cert.pem");

        String input = "jdbc:postgresql://dbhost:5432/mydb?ssl=true&sslmode=verify-full&sslrootcert=${ojp.server.sslrootcert}";
        String expected = "jdbc:postgresql://dbhost:5432/mydb?ssl=true&sslmode=verify-full&sslrootcert=/etc/ojp/certs/ca-cert.pem";
        String result = UrlParser.parseUrl(input);

        assertEquals(expected, result);
    }
}
