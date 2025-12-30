package org.openjproxy.xa.baseline.containers;

import org.testcontainers.containers.Db2Container;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton DB2 XA test container for all DB2 XA integration tests.
 * This ensures that all tests share the same DB2 instance to improve test performance
 * and reduce resource usage.
 */
public class DB2XATestContainer {
    
    // DB2 Docker image version
    private static final String DB2_IMAGE = "ibmcom/db2:11.5.9.0";
    private static final String DEFAULT_USERNAME = "db2inst1";
    private static final String DEFAULT_PASSWORD = "testpass123";
    private static final String DEFAULT_DATABASE = "testdb";
    
    private static Db2Container container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    private static ReentrantLock initLock = new ReentrantLock();
    
    /**
     * Gets or creates the shared DB2 XA test container instance.
     * The container is automatically started on first access.
     * 
     * @return the shared Db2Container instance
     */
    public static Db2Container getInstance() {
        // Fast-path: if container already created and running, return it without locking
        Db2Container local = container;
        if (local != null && local.isRunning()) {
            return local;
        }
        
        initLock.lock();
        try {
            if (container == null) {
                container = new Db2Container(
                    DockerImageName.parse(DB2_IMAGE)
                        .asCompatibleSubstituteFor("ibmcom/db2")
                )
                .withUsername(DEFAULT_USERNAME)
                .withPassword(DEFAULT_PASSWORD)
                .withDatabaseName(DEFAULT_DATABASE)
                .acceptLicense()
                .withInitScript("xa-baseline/sql/db2-xa-setup.sql")
                .withStartupTimeoutSeconds(180); // DB2 can be slow to start
            }
            
            if (!isStarted) {
                container.start();
                isStarted = true;
                
                // Post-start initialization for XA features
                try {
                    configureTmDatabase();
                } catch (Exception e) {
                    System.err.println("[DB2XATestContainer] Warning: Failed to configure TM_DATABASE: " + e.getMessage());
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
        } finally {
            initLock.unlock();
        }
    }
    
    /**
     * Configures DB2 for XA transactions by setting TM_DATABASE.
     */
    private static void configureTmDatabase() throws Exception {
        // Update DB2 configuration for transaction manager database
        String[] cmd = new String[] {
            "su", "-", DEFAULT_USERNAME, "-c",
            "db2 UPDATE DBM CFG USING TM_DATABASE " + DEFAULT_DATABASE + " IMMEDIATE"
        };
        
        org.testcontainers.containers.Container.ExecResult res = getInstance().execInContainer(cmd);
        if (res.getExitCode() != 0) {
            System.err.println("TM_DATABASE configuration warning: " + res.getStderr());
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
        return DEFAULT_USERNAME;
    }
    
    /**
     * Gets the password for connecting to the test container.
     * 
     * @return password string
     */
    public static String getPassword() {
        return DEFAULT_PASSWORD;
    }
    
    /**
     * Gets the database name.
     * 
     * @return database name
     */
    public static String getDatabaseName() {
        return DEFAULT_DATABASE;
    }
    
    /**
     * Checks if DB2 XA tests are enabled via system property.
     * 
     * @return true if DB2 XA tests should run
     */
    public static boolean isEnabled() {
        // Use a dedicated property for DB2 tests
        return Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }
}
