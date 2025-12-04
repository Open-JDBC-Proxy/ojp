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
    
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        // Return empty stream if DB2 tests are disabled - prevents test execution
        if (!Db2TestContainer.isEnabled()) {
            return Stream.empty();
        }
        
        // Get connection parameters from the DB2 TestContainer
        String driverClass = "org.openjproxy.jdbc.Driver";
        String jdbcUrl = Db2TestContainer.getJdbcUrl();
        String username = Db2TestContainer.getUsername();
        String password = Db2TestContainer.getPassword();
        
        // Wrap the DB2 container URL with OJP driver URL
        String ojpUrl = "jdbc:ojp[localhost:1059]_db2://" + jdbcUrl.substring("jdbc:db2://".length());
        
        return Stream.of(
            Arguments.of(driverClass, ojpUrl, username, password)
        );
    }
}
