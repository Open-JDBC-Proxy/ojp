package org.openjproxy.grpc.server.util;

/**
 * Utility class for extracting datasource names from OJP JDBC URLs.
 * <p>
 * OJP JDBC URLs follow the format: {@code jdbc:ojp[host:port(datasourceName)]_actualJdbcUrl}
 * </p>
 * <p>
 * Example: {@code jdbc:ojp[localhost:5433(mydb)]_jdbc:postgresql://localhost:5432/testdb}
 * extracts datasource name "mydb".
 * </p>
 */
public final class DatasourceNameExtractor {

    private DatasourceNameExtractor() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Extracts the datasource name from an OJP JDBC URL.
     * <p>
     * Format: {@code jdbc:ojp[host:port(datasourceName)]_actualJdbcUrl}
     * </p>
     *
     * @param url OJP JDBC URL
     * @return datasource name, or null if not found or invalid format
     */
    public static String extractDatasourceName(String url) {
        if (url == null || !url.startsWith("jdbc:ojp")) {
            return null;
        }

        // Look for pattern: [host:port(datasourceName)]
        int startBracket = url.indexOf('[');
        int endBracket = url.indexOf(']');

        if (startBracket < 0 || endBracket < 0 || endBracket <= startBracket) {
            return null;
        }

        String hostPortSection = url.substring(startBracket + 1, endBracket);
        int startParen = hostPortSection.indexOf('(');
        int endParen = hostPortSection.indexOf(')');

        if (startParen < 0 || endParen < 0 || endParen <= startParen) {
            return null;
        }

        String datasourceName = hostPortSection.substring(startParen + 1, endParen);
        return datasourceName.isEmpty() ? null : datasourceName;
    }

    /**
     * Extracts the datasource name from an OJP JDBC URL, returning a default value if not found.
     *
     * @param url OJP JDBC URL
     * @param defaultName default datasource name to return if extraction fails
     * @return datasource name, or defaultName if not found
     */
    public static String extractDatasourceNameOrDefault(String url, String defaultName) {
        String extracted = extractDatasourceName(url);
        return extracted != null ? extracted : defaultName;
    }
}
