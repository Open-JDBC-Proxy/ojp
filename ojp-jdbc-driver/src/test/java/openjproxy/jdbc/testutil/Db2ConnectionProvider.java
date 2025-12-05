package openjproxy.jdbc.testutil;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.util.stream.Stream;

/**
 * Provides DB2 connection parameters for parameterized tests.
 * This provider returns connection details from the DB2 TestContainer when DB2 tests are enabled.
 * When DB2 tests are disabled, it returns an empty stream to skip test execution.
 */
public class Db2ConnectionProvider implements ArgumentsProvider {
    
    // JDBC URL prefix to be removed when building OJP URL
    private static final String JDBC_PREFIX = "jdbc:";
    
    // OJP proxy server configuration - can be overridden via system property
    private static final String OJP_PROXY_HOST = System.getProperty("ojp.proxy.host", "localhost");
    private static final String OJP_PROXY_PORT = System.getProperty("ojp.proxy.port", "1059");
    private static final String OJP_PROXY_ADDRESS = OJP_PROXY_HOST + ":" + OJP_PROXY_PORT;
    
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        // Return empty stream if DB2 tests are disabled - prevents test execution
        if (!Db2TestContainer.isEnabled()) {
            return Stream.empty();
        }
        
        // Initialize and start the TestContainer
        Db2TestContainer.getInstance();
        
        // Get connection parameters from the DB2 TestContainer
        String driverClass = "org.openjproxy.jdbc.Driver";
        String containerJdbcUrl = Db2TestContainer.getJdbcUrl();
        String username = Db2TestContainer.getUsername();
        String password = Db2TestContainer.getPassword();
        
        // Build OJP JDBC URL from the container URL
        // TestContainer URL format: jdbc:db2://localhost:RANDOM_PORT/test
        // We need to extract the connection string and wrap it with OJP format
        // OJP format: jdbc:ojp[localhost:1059]_db2://...
        
        // Remove "jdbc:" prefix and add OJP wrapper
        String urlWithoutPrefix = containerJdbcUrl.startsWith(JDBC_PREFIX) 
            ? containerJdbcUrl.substring(JDBC_PREFIX.length()) 
            : containerJdbcUrl;
        String ojpUrl = JDBC_PREFIX + "ojp[" + OJP_PROXY_ADDRESS + "]_" + urlWithoutPrefix;
        
        return Stream.of(
            Arguments.of(driverClass, ojpUrl, username, password)
        );
    }
}
