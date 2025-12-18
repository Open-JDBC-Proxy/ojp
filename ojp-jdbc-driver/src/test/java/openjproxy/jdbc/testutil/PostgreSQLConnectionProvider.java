package openjproxy.jdbc.testutil;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.util.stream.Stream;

/**
 * Custom ArgumentsProvider for PostgreSQL integration tests.
 * Provides connection details from TestContainers when PostgreSQL tests are enabled.
 * This allows tests to use TestContainers instead of external PostgreSQL instances.
 * 
 * Note: Tests must manage their own OJPContainer instance using @Container annotation.
 */
public class PostgreSQLConnectionProvider implements ArgumentsProvider {
    
    // JDBC URL prefix to be removed when building OJP URL
    private static final String JDBC_PREFIX = "jdbc:";
    
    // OJP proxy server configuration - can be overridden via system property
    private static final String OJP_PROXY_HOST = System.getProperty("ojp.proxy.host", "localhost");
    private static final String OJP_PROXY_PORT = System.getProperty("ojp.proxy.port", "1059");
    private static final String OJP_PROXY_ADDRESS = OJP_PROXY_HOST + ":" + OJP_PROXY_PORT;
    
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        if (!PostgreSQLTestContainer.isEnabled()) {
            // Return empty stream when tests are disabled
            return Stream.empty();
        }
        
        // Initialize and start the PostgreSQL TestContainer
        PostgreSQLTestContainer.getInstance();
        
        // Get PostgreSQL connection details
        String postgresNetworkUrl = PostgreSQLTestContainer.getNetworkJdbcUrl();
        String username = PostgreSQLTestContainer.getUsername();
        String password = PostgreSQLTestContainer.getPassword();
        
        // Build OJP JDBC URL from the PostgreSQL network URL
        // Network URL format: jdbc:postgresql://postgres:5432/defaultdb
        // OJP format: jdbc:ojp[localhost:1059]_postgresql://postgres:5432/defaultdb
        String driverClass = "org.openjproxy.jdbc.Driver";
        
        // Remove "jdbc:" prefix and add OJP wrapper
        String urlWithoutPrefix = postgresNetworkUrl.startsWith(JDBC_PREFIX) 
            ? postgresNetworkUrl.substring(JDBC_PREFIX.length()) 
            : postgresNetworkUrl;
        String ojpUrl = JDBC_PREFIX + "ojp[" + OJP_PROXY_ADDRESS + "]_" + urlWithoutPrefix;
        
        // Return a single set of arguments with the TestContainer connection details
        return Stream.of(
            Arguments.of(driverClass, ojpUrl, username, password)
        );
    }
}
