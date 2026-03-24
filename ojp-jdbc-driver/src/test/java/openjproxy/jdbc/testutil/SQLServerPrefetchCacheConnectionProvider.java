package openjproxy.jdbc.testutil;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import java.util.stream.Stream;

/**
 * Custom {@link ArgumentsProvider} for SQL Server prefetch-cache integration tests.
 *
 * <p>Provides connection details pointing to the OJP server (default port 1059) with the
 * next-page prefetch cache enabled via the client-side property {@code ojp.nextPageCache.enabled}.
 * The actual SQL Server instance is still supplied by {@link SQLServerTestContainer}.
 */
public class SQLServerPrefetchCacheConnectionProvider implements ArgumentsProvider {

    private static final String JDBC_PREFIX = "jdbc:";

    /** OJP server host:port used for prefetch-cache tests (defaults to standard port 1059). */
    private static final String PREFETCH_CACHE_PORT =
            System.getProperty("ojp.prefetch.cache.port", "1059");
    private static final String OJP_PROXY_HOST =
            System.getProperty("ojp.proxy.host", "localhost");
    private static final String PREFETCH_CACHE_ADDRESS = OJP_PROXY_HOST + ":" + PREFETCH_CACHE_PORT;

    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
        if (!SQLServerTestContainer.isEnabled()) {
            return Stream.empty();
        }

        ConnectionProps result = getConnectionProps();
        return Stream.of(
            Arguments.of(result.driverClass, result.ojpUrl, result.username, result.password)
        );
    }

    @NotNull
    private static ConnectionProps getConnectionProps() {
        SQLServerTestContainer.getInstance();

        String containerJdbcUrl = SQLServerTestContainer.getJdbcUrl();
        String username = SQLServerTestContainer.getUsername();
        String password = SQLServerTestContainer.getPassword();

        String driverClass = "org.openjproxy.jdbc.Driver";
        String urlWithoutPrefix = containerJdbcUrl.startsWith(JDBC_PREFIX)
                ? containerJdbcUrl.substring(JDBC_PREFIX.length())
                : containerJdbcUrl;

        if (!urlWithoutPrefix.toLowerCase().contains("databasename=")) {
            urlWithoutPrefix = urlWithoutPrefix + ";databaseName=defaultdb";
        }

        String ojpUrl = JDBC_PREFIX + "ojp[" + PREFETCH_CACHE_ADDRESS + "]_" + urlWithoutPrefix;
        return new ConnectionProps(username, password, driverClass, ojpUrl);
    }

    private static class ConnectionProps {
        private final String username;
        private final String password;
        private final String driverClass;
        private final String ojpUrl;

        ConnectionProps(String username, String password, String driverClass, String ojpUrl) {
            this.username = username;
            this.password = password;
            this.driverClass = driverClass;
            this.ojpUrl = ojpUrl;
        }
    }
}
