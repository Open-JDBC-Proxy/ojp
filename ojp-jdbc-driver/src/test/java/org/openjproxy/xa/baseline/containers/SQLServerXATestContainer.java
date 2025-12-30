package org.openjproxy.xa.baseline.containers;

import org.testcontainers.containers.MSSQLServerContainer;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Singleton SQL Server XA test container for all SQL Server XA integration tests.
 * This ensures that all tests share the same SQL Server instance to improve test performance
 * and reduce resource usage.
 */
public class SQLServerXATestContainer {
    
    // SQL Server Docker image version
    private static final String MSSQL_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_PASSWORD = "TestPassword123!";
    private static final String TEST_DATABASE = "xatestdb";
    
    private static MSSQLServerContainer<?> container;
    private static boolean isStarted = false;
    private static boolean shutdownHookRegistered = false;
    private static ReentrantLock initLock = new ReentrantLock();
    
    /**
     * Gets or creates the shared SQL Server XA test container instance.
     * The container is automatically started on first access.
     * 
     * @return the shared MSSQLServerContainer instance
     */
    public static MSSQLServerContainer<?> getInstance() {
        // Fast-path: if container already created and running, return it without locking
        MSSQLServerContainer<?> local = container;
        if (local != null && local.isRunning()) {
            return local;
        }
        
        initLock.lock();
        try {
            if (container == null) {
                container = new MSSQLServerContainer<>(MSSQL_IMAGE)
                    .acceptLicense()
                    .withInitScript("xa-baseline/sql/sqlserver-xa-setup.sql");
            }
            
            if (!isStarted) {
                container.start();
                isStarted = true;
                
                // Post-start initialization for XA features
                try {
                    installXaStoredProcedures();
                    createTestDatabase();
                    createTestUser();
                    grantXaPermissions();
                } catch (Exception e) {
                    System.err.println("[SQLServerXATestContainer] Warning: Failed to initialize XA: " + e.getMessage());
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
     * Installs Microsoft SQL Server XA stored procedures.
     */
    private static void installXaStoredProcedures() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = getInstance().getUsername();
        final String saPassword = getInstance().getPassword();
        
        String[] cmd = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword,
            "-d", "master", "-C", "-Q", "EXEC sp_sqljdbc_xa_install;"
        };
        
        org.testcontainers.containers.Container.ExecResult res = getInstance().execInContainer(cmd);
        if (res.getExitCode() != 0) {
            throw new IllegalStateException("sp_sqljdbc_xa_install failed: " + res.getStderr());
        }
    }
    
    /**
     * Creates the test database.
     */
    private static void createTestDatabase() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = getInstance().getUsername();
        final String saPassword = getInstance().getPassword();
        
        String[] cmd = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword, "-C", "-Q",
            "IF DB_ID('" + TEST_DATABASE + "') IS NULL CREATE DATABASE " + TEST_DATABASE + ";"
        };
        getInstance().execInContainer(cmd);
    }
    
    /**
     * Creates the test user.
     */
    private static void createTestUser() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = getInstance().getUsername();
        final String saPassword = getInstance().getPassword();
        
        // Create login
        String[] createLogin = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword, "-C", "-Q",
            "IF NOT EXISTS (SELECT * FROM sys.sql_logins WHERE name = '" + TEST_USERNAME + "') " +
            "CREATE LOGIN " + TEST_USERNAME + " WITH PASSWORD = '" + TEST_PASSWORD + "';"
        };
        getInstance().execInContainer(createLogin);
        
        // Create user in test database
        String[] createUser = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword,
            "-d", TEST_DATABASE, "-C", "-Q",
            "IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = '" + TEST_USERNAME + "') " +
            "BEGIN CREATE USER " + TEST_USERNAME + " FOR LOGIN " + TEST_USERNAME + "; " +
            "ALTER ROLE db_owner ADD MEMBER " + TEST_USERNAME + "; END"
        };
        getInstance().execInContainer(createUser);
    }
    
    /**
     * Grants XA permissions to the test user.
     */
    private static void grantXaPermissions() throws Exception {
        final String sqlcmd = "/opt/mssql-tools18/bin/sqlcmd";
        final String saUser = getInstance().getUsername();
        final String saPassword = getInstance().getPassword();
        
        String grantScript = String.join("\n",
            "IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = '" + TEST_USERNAME + "') BEGIN",
            "  CREATE USER " + TEST_USERNAME + " FOR LOGIN " + TEST_USERNAME + ";",
            "END",
            "GRANT EXECUTE ON xp_sqljdbc_xa_init TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_start TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_end TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_prepare TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_commit TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_rollback TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_recover TO " + TEST_USERNAME + ";",
            "GRANT EXECUTE ON xp_sqljdbc_xa_forget TO " + TEST_USERNAME + ";",
            "IF NOT EXISTS (SELECT * FROM sys.database_principals WHERE name = 'SqlJDBCXAUser' AND type = 'R') BEGIN",
            "  CREATE ROLE [SqlJDBCXAUser];",
            "END",
            "ALTER ROLE [SqlJDBCXAUser] ADD MEMBER " + TEST_USERNAME + ";"
        );
        
        String[] cmd = new String[] {
            sqlcmd, "-S", "localhost", "-U", saUser, "-P", saPassword,
            "-d", "master", "-C", "-Q", grantScript
        };
        getInstance().execInContainer(cmd);
    }
    
    /**
     * Gets the JDBC URL for connecting to the test container and test database.
     * 
     * @return JDBC URL string
     */
    public static String getJdbcUrl() {
        return getInstance().getJdbcUrl() + ";databaseName=" + TEST_DATABASE;
    }
    
    /**
     * Gets the test username.
     * 
     * @return username string
     */
    public static String getTestUsername() {
        return TEST_USERNAME;
    }
    
    /**
     * Gets the test password.
     * 
     * @return password string
     */
    public static String getTestPassword() {
        return TEST_PASSWORD;
    }
    
    /**
     * Gets the SA username from the container.
     * 
     * @return SA username
     */
    public static String getUsername() {
        return getInstance().getUsername();
    }
    
    /**
     * Gets the SA password from the container.
     * 
     * @return SA password
     */
    public static String getPassword() {
        return getInstance().getPassword();
    }
    
    /**
     * Gets the test database name.
     * 
     * @return database name
     */
    public static String getTestDatabase() {
        return TEST_DATABASE;
    }
    
    /**
     * Checks if SQL Server XA tests are enabled via system property.
     * 
     * @return true if SQL Server XA tests should run
     */
    public static boolean isEnabled() {
        // Reuse existing enableSqlServerTests property for consistency
        return Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }
}
