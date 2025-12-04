package openjproxy.jdbc.testutil;

import org.testcontainers.containers.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Oracle test container for all Oracle integration tests.
 * This ensures that all tests share the same Oracle instance to improve test performance
 * and reduce resource usage.
 */
public class OracleTestContainer {
    
    // Oracle Docker image version (using gvenzl/oracle-xe for compatibility)
    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-full";
    private static final String TEST_PASSWORD = "testpassword";
    private static final String TEST_USERNAME = "testuser";
    
    private static OracleContainer container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    
    /**
     * Gets or creates the shared Oracle test container instance.
     * The container is automatically started on first access.
     * 
     * @return the shared OracleContainer instance
     */
    public static synchronized OracleContainer getInstance() {
        if (container == null) {
            container = new OracleContainer(DockerImageName.parse(ORACLE_IMAGE))
                    .withDatabaseName("XEPDB1")
                    .withUsername(TEST_USERNAME)
                    .withPassword(TEST_PASSWORD);
        }
        
        if (!isStarted) {
            container.start();
            isStarted = true;
            
            // Grant XA permissions to the test user
            try {
                grantXAPermissions();
            } catch (Exception e) {
                throw new RuntimeException("Failed to grant XA permissions to Oracle test user", e);
            }
            
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
     * Grants XA permissions to the test user.
     * This is required for XA transaction tests.
     */
    private static void grantXAPermissions() throws Exception {
        // Execute SQL commands to grant XA permissions
        String sqlCommands = 
            "GRANT XA_RECOVER_ADMIN TO " + TEST_USERNAME + ";\n" +
            "GRANT SELECT ON sys.dba_pending_transactions TO " + TEST_USERNAME + ";\n" +
            "GRANT SELECT ON sys.pending_trans$ TO " + TEST_USERNAME + ";\n" +
            "GRANT SELECT ON sys.dba_2pc_pending TO " + TEST_USERNAME + ";\n" +
            "GRANT SELECT ON sys.dba_2pc_neighbors TO " + TEST_USERNAME + ";\n" +
            "GRANT EXECUTE ON sys.dbms_xa TO " + TEST_USERNAME + ";\n" +
            "GRANT EXECUTE ON sys.dbms_system TO " + TEST_USERNAME + ";\n" +
            "GRANT FORCE ANY TRANSACTION TO " + TEST_USERNAME + ";";
        
        // Execute the grants using container.execInContainer
        org.testcontainers.containers.Container.ExecResult result = container.execInContainer(
            "sqlplus", "-s", "system/" + TEST_PASSWORD + "@" + container.getDatabaseName(),
            "/bin/bash", "-c", "echo \"" + sqlCommands + "\" | sqlplus -s system/" + TEST_PASSWORD + "@" + container.getDatabaseName()
        );
        
        if (result.getExitCode() != 0) {
            System.err.println("Failed to grant XA permissions. Output: " + result.getStdout());
            System.err.println("Error: " + result.getStderr());
            // Don't throw exception - XA tests may fail but other tests can still run
        }
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
     * Gets the database name (service name) for the test container.
     * 
     * @return database name string
     */
    public static String getDatabaseName() {
        return getInstance().getDatabaseName();
    }
    
    /**
     * Checks if Oracle tests are enabled via system property.
     * 
     * @return true if Oracle tests should run
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }
}
