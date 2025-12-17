package openjproxy.jdbc.testutil;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.util.stream.Stream;

/**
 * Custom ArgumentsProvider for PostgreSQL integration tests.
 * Provides connection details from TestContainers when PostgreSQL tests are enabled.
 * This allows tests to use TestContainers instead of external PostgreSQL instances.
 */
public class PostgreSQLConnectionProvider implements ArgumentsProvider {
    
    // JDBC URL prefix to be removed when building OJP URL
    private static final String JDBC_PREFIX = "jdbc:";
    
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        if (!PostgreSQLTestContainer.isEnabled()) {
            // Return empty stream when tests are disabled
            return Stream.empty();
        }
        
        // Initialize and start the TestContainers (PostgreSQL + OJP)
        PostgreSQLTestContainer.getInstance();
        
        // Get the OJP container
        var ojpContainer = PostgreSQLTestContainer.getOJPContainer();
        
        // Get PostgreSQL connection details
        String postgresNetworkUrl = PostgreSQLTestContainer.getNetworkJdbcUrl();
        String username = PostgreSQLTestContainer.getUsername();
        String password = PostgreSQLTestContainer.getPassword();
        
        // Build OJP JDBC URL from the PostgreSQL network URL
        // Network URL format: jdbc:postgresql://postgres:5432/defaultdb
        // OJP format: jdbc:ojp[localhost:RANDOM_PORT]_postgresql://postgres:5432/defaultdb
        String driverClass = "org.openjproxy.jdbc.Driver";
        String ojpUrl = ojpContainer.buildJdbcUrl(postgresNetworkUrl);
        
        // Return a single set of arguments with the TestContainer connection details
        return Stream.of(
            Arguments.of(driverClass, ojpUrl, username, password)
        );
    }
}
