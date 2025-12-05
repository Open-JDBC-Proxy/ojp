package openjproxy.jdbc.testutil;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton CockroachDB test container for all CockroachDB integration tests.
 * This ensures that all tests share the same CockroachDB instance to improve test performance
 * and reduce resource usage.
 */
@Slf4j
public class CockroachDBTestContainer {
    
    // CockroachDB Docker image version
    private static final String COCKROACHDB_IMAGE = "cockroachdb/cockroach:v24.3.4";
    private static final int COCKROACHDB_PORT = 26257;
    
    private static GenericContainer<?> container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    
    /**
     * Gets or creates the shared CockroachDB test container instance.
     * The container is automatically started on first access.
     * 
     * @return the shared GenericContainer instance
     */
    public static synchronized GenericContainer<?> getInstance() {
        if (container == null) {
            container = new GenericContainer<>(DockerImageName.parse(COCKROACHDB_IMAGE))
                    .withCommand("start-single-node", "--insecure")
                    .withExposedPorts(COCKROACHDB_PORT);
        }
        
        if (!isStarted) {
            container.start();
            isStarted = true;
            log.info("CockroachDB TestContainer started successfully on port: {}", container.getMappedPort(COCKROACHDB_PORT));
            
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
     * CockroachDB uses the PostgreSQL wire protocol.
     * 
     * @return JDBC URL string
     */
    public static String getJdbcUrl() {
        GenericContainer<?> instance = getInstance();
        String host = instance.getHost();
        Integer port = instance.getMappedPort(COCKROACHDB_PORT);
        return "jdbc:postgresql://" + host + ":" + port + "/defaultdb?sslmode=disable";
    }
    
    /**
     * Gets the username for connecting to the test container.
     * CockroachDB in insecure mode uses "root" by default.
     * 
     * @return username string
     */
    public static String getUsername() {
        return "root";
    }
    
    /**
     * Gets the password for connecting to the test container.
     * CockroachDB in insecure mode doesn't require a password.
     * 
     * @return password string (empty for insecure mode)
     */
    public static String getPassword() {
        return "";
    }
    
    /**
     * Gets the database name for the test container.
     * 
     * @return database name string
     */
    public static String getDatabaseName() {
        return "defaultdb";
    }
    
    /**
     * Checks if CockroachDB tests are enabled via system property.
     * 
     * @return true if CockroachDB tests should run
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }
}
