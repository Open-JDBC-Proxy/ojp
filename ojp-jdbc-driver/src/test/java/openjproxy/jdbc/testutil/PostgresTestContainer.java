package openjproxy.jdbc.testutil;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton PostgreSQL test container for all PostgreSQL integration tests.
 * This ensures that all tests share the same PostgreSQL instance to improve test performance
 * and reduce resource usage.
 */
@Slf4j
public class PostgresTestContainer {
    
    // PostgreSQL Docker image version
    private static final String POSTGRES_IMAGE = "postgres:17";
    private static final String TEST_PASSWORD = "testpassword";
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_DATABASE = "defaultdb";
    
    private static PostgreSQLContainer<?> container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    
    /**
     * Gets or creates the shared PostgreSQL test container instance.
     * The container is automatically started on first access.
     * 
     * @return the shared PostgreSQLContainer instance
     */
    public static synchronized PostgreSQLContainer<?> getInstance() {
        if (container == null) {
            container = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName(TEST_DATABASE)
                    .withUsername(TEST_USERNAME)
                    .withPassword(TEST_PASSWORD)
                    .withCommand("postgres", "-c", "max_prepared_transactions=100");
        }
        
        if (!isStarted) {
            container.start();
            isStarted = true;
            log.info("PostgreSQL TestContainer started successfully");
            
            // Add shutdown hook to stop container when JVM exits
            if (!shutdownHookRegistered) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (container != null && container.isRunning()) {
                        container.stop();
                    }
                }));
                shutdownHookRegistered = true;
            }
        }
        
        return container;
    }
    
    /**
     * Gets the JDBC URL for connecting to the test container.
     * 
     * @return JDBC URL string
     */
    public static String getJdbcUrl() {
        return getInstance().getJdbcUrl();
    }
    
    /**
     * Gets the username for connecting to the test container.
     * 
     * @return username string
     */
    public static String getUsername() {
        return getInstance().getUsername();
    }
    
    /**
     * Gets the password for connecting to the test container.
     * 
     * @return password string
     */
    public static String getPassword() {
        return getInstance().getPassword();
    }
    
    /**
     * Gets the database name for the test container.
     * 
     * @return database name string
     */
    public static String getDatabaseName() {
        return getInstance().getDatabaseName();
    }
    
    /**
     * Checks if PostgreSQL tests are enabled via system property.
     * 
     * @return true if PostgreSQL tests should run
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }
}
