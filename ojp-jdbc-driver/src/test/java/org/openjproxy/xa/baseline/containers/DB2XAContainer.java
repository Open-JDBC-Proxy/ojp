package org.openjproxy.xa.baseline.containers;

import com.ibm.db2.jcc.DB2XADataSource;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.utility.DockerImageName;

import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * TestContainer wrapper for IBM DB2 with XA transaction support.
 * 
 * Phase 7: DB2 Setup
 * 
 * Configures DB2 container with:
 * - XA transaction support (TM_DATABASE configuration)
 * - DBADM privileges for test user
 * - Test database and table
 * - XA permission grants
 */
public class DB2XAContainer extends Db2Container {
    
    private static final String DB2_IMAGE = "icr.io/db2_community/db2:11.5.9.0";
    private static final String DB_NAME = "xatestdb";
    private static final String USERNAME = "db2inst1";
    private static final String PASSWORD = "testpass123";
    
    public DB2XAContainer() {
        super(DockerImageName.parse(DB2_IMAGE)
                .asCompatibleSubstituteFor("ibmcom/db2"));
        
        // Configure DB2 with XA support
        withDatabaseName(DB_NAME);
        withUsername(USERNAME);
        withPassword(PASSWORD);
        
        // Accept DB2 license
        withEnv("LICENSE", "accept");
        
        // Enable archive logging (required for XA)
        withEnv("ARCHIVE_LOGS", "true");
        
        // Set larger shared memory for XA transactions
        withEnv("DBNAME", DB_NAME);
        
        // Increase startup timeout for DB2
        withStartupTimeout(java.time.Duration.ofMinutes(5));
    }
    
    @Override
    public void start() {
        super.start();
        
        // Initialize XA support after container starts
        try {
            initializeXASupport();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize DB2 XA support", e);
        }
    }
    
    /**
     * Initialize DB2 XA transaction support.
     * This includes:
     * - Setting up TM_DATABASE for XA coordination
     * - Granting necessary privileges
     * - Creating test table and sequence
     */
    private void initializeXASupport() throws Exception {
        try (Connection conn = createConnection(getJdbcUrl(), getUsername(), getPassword());
             Statement stmt = conn.createStatement()) {
            
            // Read and execute setup SQL
            String setupSQL = loadSetupSQL();
            
            // Execute each statement separately (DB2 doesn't support multiple statements)
            String[] statements = setupSQL.split(";");
            for (String sql : statements) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    try {
                        stmt.execute(trimmed);
                    } catch (Exception e) {
                        // Log but don't fail on individual statement errors
                        // Some statements may be idempotent
                        System.err.println("Warning: DB2 setup statement failed: " + trimmed);
                        System.err.println("Error: " + e.getMessage());
                    }
                }
            }
            
            conn.commit();
        }
    }
    
    /**
     * Load DB2 XA setup SQL from resources.
     */
    private String loadSetupSQL() {
        try {
            return new String(getClass().getClassLoader()
                    .getResourceAsStream("xa-baseline/sql/db2-xa-setup.sql")
                    .readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load db2-xa-setup.sql", e);
        }
    }
    
    /**
     * Create XADataSource for DB2.
     * 
     * @return Configured DB2XADataSource
     */
    public XADataSource createXADataSource() {
        DB2XADataSource xaDataSource = new DB2XADataSource();
        
        xaDataSource.setServerName(getHost());
        xaDataSource.setPortNumber(getMappedPort(DB2_PORT));
        xaDataSource.setDatabaseName(getDatabaseName());
        xaDataSource.setUser(getUsername());
        xaDataSource.setPassword(getPassword());
        
        // Enable XA support
        xaDataSource.setDriverType(4);  // Type 4 driver (pure Java)
        
        return xaDataSource;
    }
    
    /**
     * Helper to create JDBC connection for setup.
     */
    private Connection createConnection(String url, String user, String password) throws Exception {
        Class.forName("com.ibm.db2.jcc.DB2Driver");
        return java.sql.DriverManager.getConnection(url, user, password);
    }
}
