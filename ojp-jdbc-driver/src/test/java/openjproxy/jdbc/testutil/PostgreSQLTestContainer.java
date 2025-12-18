package openjproxy.jdbc.testutil;

import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton PostgreSQL test container for all PostgreSQL integration tests.
 * This ensures that all tests share the same PostgreSQL instance to improve 
 * test performance and reduce resource usage.
 * 
 * The container is configured with max_prepared_transactions=100 to support
 * distributed transaction testing.
 * 
 * Note: OJP container is managed separately by each test class to allow flexibility.
 */
public class PostgreSQLTestContainer {
    
    // PostgreSQL Docker image version
    private static final String POSTGRES_IMAGE = "postgres:17";
    
    // Shared network for PostgreSQL and OJP containers
    private static Network network;
    private static PostgreSQLContainer<?> postgresContainer;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    private static ReentrantLock initLock = new ReentrantLock();
    
    /**
     * Gets or creates the shared PostgreSQL test container instance.
     * The container is automatically started on first access.
     * 
     * @return the shared PostgreSQLContainer instance
     */
    public static PostgreSQLContainer<?> getInstance() {
        // Fast-path: if container already created and running, return it without locking
        PostgreSQLContainer<?> local = postgresContainer;
        if (local != null && local.isRunning()) {
            return local;
        }
        
        initLock.lock();
        try {
            if (network == null) {
                network = Network.newNetwork();
            }
            
            if (postgresContainer == null) {
                postgresContainer = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withNetwork(network)
                    .withNetworkAliases("postgres")
                    .withCommand("postgres", "-c", "max_prepared_transactions=100")
                    .withUsername("testuser")
                    .withPassword("testpassword")
                    .withDatabaseName("defaultdb");
            }
            
            if (!isStarted) {
                postgresContainer.start();
                isStarted = true;
                
                // Add shutdown hook to stop container when JVM exits
                if (!shutdownHookRegistered) {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (postgresContainer != null && postgresContainer.isRunning()) {
                            postgresContainer.stop();
                        }
                        if (network != null) {
                            network.close();
                        }
                    }));
                    shutdownHookRegistered = true;
                }
            }
            
            return postgresContainer;
        } finally {
            initLock.unlock();
        }
    }
    
    /**
     * Gets the JDBC URL for connecting to the test container.
     * This returns the host-accessible URL (not the network alias).
     * 
     * @return JDBC URL string
     */
    public static String getJdbcUrl() {
        return getInstance().getJdbcUrl();
    }
    
    /**
     * Gets the JDBC URL using the network alias for container-to-container communication.
     * This is used to build the OJP URL that OJP container uses to connect to PostgreSQL.
     * 
     * @return JDBC URL string with network alias
     */
    public static String getNetworkJdbcUrl() {
        getInstance(); // Ensure container is started
        return "jdbc:postgresql://postgres:5432/defaultdb";
    }
    
    /**
     * Gets the shared network for containers.
     * 
     * @return Network instance
     */
    public static Network getNetwork() {
        getInstance(); // Ensure network is created
        return network;
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
     * Checks if PostgreSQL tests are enabled via system property.
     * 
     * @return true if PostgreSQL tests should run
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }
}
