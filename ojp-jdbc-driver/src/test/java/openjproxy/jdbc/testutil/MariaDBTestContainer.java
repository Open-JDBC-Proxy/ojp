package openjproxy.jdbc.testutil;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Singleton MariaDB test container for all MariaDB integration tests.
 * This ensures that all tests share the same MariaDB instance to improve test performance
 * and reduce resource usage.
 */
@Slf4j
public class MariaDBTestContainer {
    
    // MariaDB Docker image version
    private static final String MARIADB_IMAGE = "mariadb:10.11";
    private static final String TEST_DATABASE = "defaultdb";
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_PASSWORD = "testpassword";
    
    private static MariaDBContainer<?> container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    
    /**
     * Gets or creates the shared MariaDB test container instance.
     * The container is automatically started on first access.
     * 
     * @return the shared MariaDBContainer instance
     */
    public static synchronized MariaDBContainer<?> getInstance() {
        if (container == null) {
            container = new MariaDBContainer<>(MARIADB_IMAGE)
                    .withDatabaseName(TEST_DATABASE)
                    .withUsername(TEST_USERNAME)
                    .withPassword(TEST_PASSWORD);
        }
        
        if (!isStarted) {
            log.info("Starting MariaDB TestContainer...");
            container.start();
            isStarted = true;
            log.info("MariaDB TestContainer started successfully at: {}", container.getJdbcUrl());
            
            // Add shutdown hook to stop container when JVM exits
            if (!shutdownHookRegistered) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (container != null && container.isRunning()) {
                        log.info("Stopping MariaDB TestContainer...");
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
     * Checks if MariaDB tests are enabled via system property.
     * 
     * @return true if MariaDB tests should run
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }
}
