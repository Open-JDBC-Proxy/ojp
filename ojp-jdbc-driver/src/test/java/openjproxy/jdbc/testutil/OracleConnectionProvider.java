package openjproxy.jdbc.testutil;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.util.stream.Stream;

/**
 * Custom ArgumentsProvider for Oracle integration tests.
 * Provides connection details from TestContainers when Oracle tests are enabled.
 * This allows tests to use TestContainers instead of external Oracle instances.
 */
public class OracleConnectionProvider implements ArgumentsProvider {
    
    // JDBC URL prefix to be removed when building OJP URL
    private static final String JDBC_PREFIX = "jdbc:";
    
    // OJP proxy server configuration - can be overridden via system property
    private static final String OJP_PROXY_HOST = System.getProperty("ojp.proxy.host", "localhost");
    private static final String OJP_PROXY_PORT = System.getProperty("ojp.proxy.port", "1059");
    private static final String OJP_PROXY_ADDRESS = OJP_PROXY_HOST + ":" + OJP_PROXY_PORT;
    
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        if (!OracleTestContainer.isEnabled()) {
            // Return empty stream when tests are disabled
            return Stream.empty();
        }
        
        // Initialize and start the TestContainer
        OracleTestContainer.getInstance();
        
        // Get connection details from the TestContainer
        String containerJdbcUrl = OracleTestContainer.getJdbcUrl();
        String username = OracleTestContainer.getUsername();
        String password = OracleTestContainer.getPassword();
        
        // Build OJP JDBC URL from the container URL
        // TestContainer URL format: jdbc:oracle:thin:@localhost:RANDOM_PORT/XEPDB1
        // OJP wraps this by removing 'jdbc:' and prepending 'jdbc:ojp[host:port]_'
        // Result format: jdbc:ojp[localhost:1059]_oracle:thin:@localhost:RANDOM_PORT/XEPDB1
        String driverClass = "org.openjproxy.jdbc.Driver";
        
        // Remove "jdbc:" prefix and add OJP wrapper
        String urlWithoutPrefix = containerJdbcUrl.startsWith(JDBC_PREFIX) 
            ? containerJdbcUrl.substring(JDBC_PREFIX.length()) 
            : containerJdbcUrl;
        String ojpUrl = JDBC_PREFIX + "ojp[" + OJP_PROXY_ADDRESS + "]_" + urlWithoutPrefix;
        
        // Return a single set of arguments with the TestContainer connection details
        return Stream.of(
            Arguments.of(driverClass, ojpUrl, username, password)
        );
    }
}
