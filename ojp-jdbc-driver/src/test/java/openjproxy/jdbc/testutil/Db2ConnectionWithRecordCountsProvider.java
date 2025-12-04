package openjproxy.jdbc.testutil;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.util.stream.Stream;

/**
 * Provides DB2 connection parameters with record counts for parameterized tests.
 * This provider returns connection details from the DB2 TestContainer when DB2 tests are enabled,
 * along with various record counts for testing pagination and large result sets.
 * When DB2 tests are disabled, it returns an empty stream to skip test execution.
 */
public class Db2ConnectionWithRecordCountsProvider implements ArgumentsProvider {
    
    // Record counts to test various scenarios
    private static final int[] RECORD_COUNTS = {1, 99, 100, 101, 1000, 10000};
    
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
        
        // Return arguments for each record count
        return Stream.of(RECORD_COUNTS)
            .map(count -> Arguments.of(count, driverClass, ojpUrl, username, password));
    }
}
