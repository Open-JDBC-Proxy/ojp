package org.openjproxy.grpc.server.utils;

import org.openjproxy.constants.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.openjproxy.grpc.server.Constants.EMPTY_STRING;

/**
 * Utility class for parsing and manipulating URLs.
 * Extracted from StatementServiceImpl to improve modularity.
 */
public class UrlParser {
    private static final Logger logger = LoggerFactory.getLogger(UrlParser.class);

    /**
     * Parses a URL by removing OJP-specific patterns and resolving property placeholders.
     * 
     * <p>This method performs two transformations:
     * <ol>
     *   <li>Removes OJP-specific URL patterns</li>
     *   <li>Resolves property placeholders in the format ${property.name}</li>
     * </ol>
     * </p>
     * 
     * <p>Property placeholders are resolved from JVM system properties or environment variables.
     * This is particularly useful for SSL/TLS certificate paths that need to be configured
     * on the server side.</p>
     * 
     * <p>Example:
     * <pre>
     * Input:  "jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}"
     * Output: "jdbc:postgresql://host:5432/db?sslrootcert=/etc/ojp/certs/ca-cert.pem"
     * </pre>
     * </p>
     *
     * @param url The URL to parse
     * @return The parsed URL with OJP patterns removed and placeholders resolved
     * @throws IllegalArgumentException if a placeholder cannot be resolved
     */
    public static String parseUrl(String url) {
        if (url == null) {
            return url;
        }
        
        // First, remove OJP-specific patterns
        String cleanedUrl = url.replaceAll(CommonConstants.OJP_REGEX_PATTERN + "_", EMPTY_STRING);
        
        // Then, resolve property placeholders if any are present
        if (PropertyPlaceholderResolver.containsPlaceholders(cleanedUrl)) {
            logger.info("Resolving property placeholders in URL");
            cleanedUrl = PropertyPlaceholderResolver.resolvePlaceholders(cleanedUrl);
            logger.info("URL placeholders resolved successfully");
        }
        
        return cleanedUrl;
    }
}