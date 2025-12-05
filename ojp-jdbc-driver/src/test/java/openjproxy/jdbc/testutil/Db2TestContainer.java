package openjproxy.jdbc.testutil;

import org.testcontainers.containers.Db2Container;

import java.time.Duration;

/**
 * Singleton DB2 test container for all DB2 integration tests.
 * This ensures that all tests share the same DB2 instance to improve test performance
 * and reduce resource usage.
 * 
 * <p>Thread-safe singleton implementation using synchronized methods to ensure
 * only one container instance is created across multiple test threads.</p>
 */
public class Db2TestContainer {
    
    // DB2 Docker image version
    private static final String DB2_IMAGE = "icr.io/db2_community/db2:11.5.9.0";
    
    private static Db2Container container;
    private static boolean isStarted = false;
    private static volatile boolean shutdownHookRegistered = false;
    
    /**
     * Gets or creates the shared DB2 test container instance.
     * The container is automatically started on first access.
     * 
     * @return the shared Db2Container instance
     */
    public static synchronized Db2Container getInstance() {
        if (container == null) {
            container = new Db2Container(DB2_IMAGE)
                    .acceptLicense()
                    .withStartupTimeout(Duration.ofMinutes(120));;
        }
        
        if (!isStarted) {
            container.start();
            isStarted = true;
        }
        
        // Add shutdown hook to stop container when JVM exits (thread-safe check)
        if (!shutdownHookRegistered) {
            synchronized (Db2TestContainer.class) {
                if (!shutdownHookRegistered) {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (container != null && container.isRunning()) {
                            container.stop();
                        }
                    }));
                    shutdownHookRegistered = true;
                }
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
     * Gets the database name for connecting to the test container.
     * 
     * @return database name string
     */
    public static String getDatabaseName() {
        return getInstance().getDatabaseName();
    }
    
    /**
     * Checks if DB2 tests are enabled via system property.
     * 
     * @return true if DB2 tests should run
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }
}
