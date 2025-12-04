package openjproxy.jdbc.testutil;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.util.stream.Stream;

/**
 * Custom ArgumentsProvider for Oracle integration tests that include record counts.
 * Provides connection details from TestContainers along with various record counts for testing.
 */
public class OracleConnectionWithRecordCountsProvider implements ArgumentsProvider {
    
    // JDBC URL prefix to be removed when building OJP URL
    private static final String JDBC_PREFIX = "jdbc:";
    
    // OJP proxy server configuration - can be overridden via system property
    private static final String OJP_PROXY_HOST = System.getProperty("ojp.proxy.host", "localhost");
    private static final String OJP_PROXY_PORT = System.getProperty("ojp.proxy.port", "1059");
    private static final String OJP_PROXY_ADDRESS = OJP_PROXY_HOST + ":" + OJP_PROXY_PORT;
    
    // Test record counts to iterate through
    private static final int[] RECORD_COUNTS = {1, 99, 100, 101, 1000, 10000};
    
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
        String driverClass = "org.openjproxy.jdbc.Driver";
        
        // Remove "jdbc:" prefix and add OJP wrapper
        String urlWithoutPrefix = containerJdbcUrl.startsWith(JDBC_PREFIX) 
            ? containerJdbcUrl.substring(JDBC_PREFIX.length()) 
            : containerJdbcUrl;
        String ojpUrl = JDBC_PREFIX + "ojp[" + OJP_PROXY_ADDRESS + "]_" + urlWithoutPrefix;
        
        // Return arguments for each record count
        return Stream.of(RECORD_COUNTS)
                .map(count -> Arguments.of(count, driverClass, ojpUrl, username, password));
    }
}
