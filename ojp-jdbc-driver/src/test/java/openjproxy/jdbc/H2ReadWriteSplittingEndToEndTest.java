package openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for read/write traffic splitting through OJP JDBC driver and server.
 * 
 * <h2>⚠️ CRITICAL: Server-Side Configuration Required</h2>
 * 
 * <p>
 * <b>These tests WILL FAIL unless the OJP server is configured with read/write splitting settings.</b>
 * Read/write splitting configuration MUST be configured on the server side in ojp-server.properties 
 * or via JVM system properties when starting the OJP server. It <b>cannot</b> be passed from the client.
 * </p>
 * 
 * <h3>How to Configure the OJP Server for These Tests</h3>
 * 
 * <p><b>Option 1: Using ojp-server.properties file</b></p>
 * <p>Create or edit <code>ojp-server.properties</code> in the OJP server's working directory:</p>
 * 
 * <pre>
 * # Enable read/write splitting for the rw_e2e_ds datasource
 * rw_e2e_ds.ojp.readwrite.enabled=true
 * 
 * # Configure sticky session timeout (5 seconds for tests)
 * rw_e2e_ds.ojp.readwrite.stickySessionTimeoutSeconds=5
 * 
 * # Configure rw_e2e_ds connection (primary database)
 * rw_e2e_ds.ojp.connection.url=jdbc:h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1
 * rw_e2e_ds.ojp.connection.username=sa
 * rw_e2e_ds.ojp.connection.password=
 * 
 * # Register rw_e2e_replica as a replica of rw_e2e_ds
 * rw_e2e_replica.ojp.connection.url=jdbc:h2:mem:rw_e2e_replica;DB_CLOSE_DELAY=-1
 * rw_e2e_replica.ojp.connection.username=sa
 * rw_e2e_replica.ojp.connection.password=
 * rw_e2e_replica.ojp.readwrite.primary=rw_e2e_ds
 * </pre>
 * 
 * <p><b>Option 2: Using JVM System Properties</b></p>
 * <p>Start the OJP server with these JVM arguments:</p>
 * 
 * <pre>
 * java -jar ojp-server.jar \
 *   -Drw_e2e_ds.ojp.readwrite.enabled=true \
 *   -Drw_e2e_ds.ojp.readwrite.stickySessionTimeoutSeconds=5 \
 *   -Drw_e2e_ds.ojp.connection.url=jdbc:h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1 \
 *   -Drw_e2e_ds.ojp.connection.username=sa \
 *   -Drw_e2e_ds.ojp.connection.password= \
 *   -Drw_e2e_replica.ojp.connection.url=jdbc:h2:mem:rw_e2e_replica;DB_CLOSE_DELAY=-1 \
 *   -Drw_e2e_replica.ojp.connection.username=sa \
 *   -Drw_e2e_replica.ojp.connection.password= \
 *   -Drw_e2e_replica.ojp.readwrite.primary=rw_e2e_ds
 * </pre>
 * 
 * <h3>Client Connection Pattern (What These Tests Do)</h3>
 * 
 * <p>
 * The test client connects using standard OJP JDBC URLs and passes client properties 
 * (datasource name, credentials) via a Properties object:
 * </p>
 * 
 * <pre>
 * String url = "jdbc:ojp[localhost:1059]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
 * Properties props = new Properties();
 * props.setProperty("user", "sa");
 * props.setProperty("password", "");
 * props.setProperty("ojp.datasource.name", "rw_e2e_ds");
 * Connection conn = DriverManager.getConnection(url, props);
 * </pre>
 * 
 * <h2>Test Strategy: Dual Unsynchronized H2 Databases</h2>
 * 
 * <p>
 * These tests use <b>two separate, intentionally UNSYNCHRONIZED</b> H2 in-memory databases:
 * </p>
 * <ul>
 *   <li><b>Primary Database</b> (rw_e2e_primary): Contains id=1, source="primary"</li>
 *   <li><b>Replica Database</b> (rw_e2e_replica): Contains id=2, source="replica"</li>
 * </ul>
 * 
 * <p>
 * By having different data in each database, we can verify routing correctness:
 * </p>
 * <ul>
 *   <li>If SELECT returns id=2, source="replica" → query routed to replica ✓</li>
 *   <li>If SELECT returns id=1, source="primary" → query routed to primary ✓</li>
 * </ul>
 * 
 * <h3>Why H2 In-Memory?</h3>
 * 
 * <p>
 * H2 in-memory databases are scoped to the ClassLoader/VM. Direct JDBC connections create separate
 * instances from OJP server connections. Therefore, <b>all operations</b> (setup, test execution, 
 * verification) must go through the OJP stack to ensure consistent database state.
 * </p>
 * 
 * <h3>Test Execution Requirements</h3>
 * 
 * <ul>
 *   <li>OJP server running on localhost:1059 with read/write configuration (see above)</li>
 *   <li>Enable with <code>-DenableH2Tests=true</code> Maven flag</li>
 *   <li>Server must have rw_e2e_ds configured as primary and rw_e2e_replica as replica</li>
 * </ul>
 * 
 * @see org.openjproxy.grpc.server.readwrite.ReadWriteRouter
 * @see org.openjproxy.grpc.server.readwrite.ReplicaSelector
 * @see org.openjproxy.grpc.server.readwrite.SqlClassifier
 */
public class H2ReadWriteSplittingEndToEndTest {
    
    private static final String OJP_HOST = "localhost:1059";
    private static final String USER = "sa";
    private static final String PASSWORD = "";
    private static final String PRIMARY_DATASOURCE_NAME = "rw_e2e_ds";
    private static final String REPLICA_DATASOURCE_NAME = "rw_e2e_replica";
    
    private static boolean isH2TestEnabled;
    
    private Connection connection;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // Ignore close errors
            }
        }
    }

    /**
     * Sets up the dual unsynchronized H2 databases through OJP connections.
     * Creates test_data table with different data in primary vs replica.
     * 
     * @throws SQLException if setup fails
     */
    private void setupDatabases() throws SQLException {
        // Setup primary database (id=1, source="primary")
        String primaryUrl = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
        Properties primaryProps = new Properties();
        primaryProps.setProperty("user", USER);
        primaryProps.setProperty("password", PASSWORD);
        primaryProps.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
        
        try (Connection primaryConn = DriverManager.getConnection(primaryUrl, primaryProps);
             Statement stmt = primaryConn.createStatement()) {
            
            // Drop table if exists
            try {
                stmt.execute("DROP TABLE IF EXISTS test_data");
            } catch (SQLException e) {
                // Ignore - table might not exist
            }
            
            // Create table and insert primary data
            stmt.execute("CREATE TABLE test_data (id INT PRIMARY KEY, source VARCHAR(50))");
            stmt.execute("INSERT INTO test_data VALUES (1, 'primary')");
        }
        
        // Setup replica database (id=2, source="replica")
        String replicaUrl = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_replica;DB_CLOSE_DELAY=-1";
        Properties replicaProps = new Properties();
        replicaProps.setProperty("user", USER);
        replicaProps.setProperty("password", PASSWORD);
        replicaProps.setProperty("ojp.datasource.name", REPLICA_DATASOURCE_NAME);
        
        try (Connection replicaConn = DriverManager.getConnection(replicaUrl, replicaProps);
             Statement stmt = replicaConn.createStatement()) {
            
            // Drop table if exists
            try {
                stmt.execute("DROP TABLE IF EXISTS test_data");
            } catch (SQLException e) {
                // Ignore - table might not exist
            }
            
            // Create table and insert replica data
            stmt.execute("CREATE TABLE test_data (id INT PRIMARY KEY, source VARCHAR(50))");
            stmt.execute("INSERT INTO test_data VALUES (2, 'replica')");
        }
    }

    /**
     * Tests that SELECT queries route to the replica database when not in a transaction.
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>SELECT should route to replica</li>
     *   <li>Should retrieve id=2, source="replica"</li>
     * </ul>
     */
    @Test
    void testSelectGoesToReplica_WithoutTransaction() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        setupDatabases();
        
        String url = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
        
        connection = DriverManager.getConnection(url, props);
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, source FROM test_data")) {
            
            assertTrue(rs.next(), "Should have at least one row");
            int id = rs.getInt("id");
            String source = rs.getString("source");
            
            // If this fails with id=1, read/write splitting is not configured on the server
            assertEquals(2, id, "SELECT should route to replica (id=2). " +
                    "If you see id=1, read/write splitting is NOT configured on the OJP server. " +
                    "See class javadoc for configuration instructions.");
            assertEquals("replica", source, "SELECT should route to replica (source='replica')");
        }
    }

    /**
     * Tests that multiple sequential SELECT queries all route to replica.
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>All SELECT queries should route to replica</li>
     *   <li>All should retrieve id=2, source="replica"</li>
     * </ul>
     */
    @Test
    void testMultipleReads_AllGoToReplica() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        setupDatabases();
        
        String url = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
        
        connection = DriverManager.getConnection(url, props);
        
        // Execute multiple SELECTs - all should go to replica
        for (int i = 0; i < 3; i++) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, source FROM test_data")) {
                
                assertTrue(rs.next(), "Should have at least one row in iteration " + i);
                int id = rs.getInt("id");
                String source = rs.getString("source");
                
                assertEquals(2, id, "SELECT #" + i + " should route to replica (id=2)");
                assertEquals("replica", source, "SELECT #" + i + " should route to replica");
            }
        }
    }

    /**
     * Tests that INSERT statements route to the primary database.
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>INSERT should route to primary</li>
     *   <li>Subsequent SELECT on primary should show the new row</li>
     * </ul>
     */
    @Test
    void testInsertGoesToPrimary() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        setupDatabases();
        
        String url = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
        
        connection = DriverManager.getConnection(url, props);
        
        try (Statement stmt = connection.createStatement()) {
            // INSERT should go to primary
            int rowsAffected = stmt.executeUpdate("INSERT INTO test_data VALUES (100, 'inserted')");
            assertEquals(1, rowsAffected, "INSERT should affect 1 row");
            
            // Verify the INSERT went to primary by checking primary database directly
            String primaryUrl = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
            Properties primaryProps = new Properties();
            primaryProps.setProperty("user", USER);
            primaryProps.setProperty("password", PASSWORD);
            primaryProps.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
            
            try (Connection verifyConn = DriverManager.getConnection(primaryUrl, primaryProps);
                 Statement verifyStmt = verifyConn.createStatement();
                 ResultSet rs = verifyStmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE id = 100")) {
                
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Primary database should have the inserted row");
            }
        }
    }

    /**
     * Tests that UPDATE statements route to the primary database.
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>UPDATE should route to primary</li>
     *   <li>Primary database should be modified</li>
     * </ul>
     */
    @Test
    void testUpdateGoesToPrimary() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        setupDatabases();
        
        String url = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
        
        connection = DriverManager.getConnection(url, props);
        
        try (Statement stmt = connection.createStatement()) {
            // UPDATE should go to primary
            int rowsAffected = stmt.executeUpdate("UPDATE test_data SET source = 'updated' WHERE id = 1");
            assertEquals(1, rowsAffected, "UPDATE should affect 1 row");
            
            // Verify the UPDATE went to primary by checking primary database directly
            String primaryUrl = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
            Properties primaryProps = new Properties();
            primaryProps.setProperty("user", USER);
            primaryProps.setProperty("password", PASSWORD);
            primaryProps.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
            
            try (Connection verifyConn = DriverManager.getConnection(primaryUrl, primaryProps);
                 Statement verifyStmt = verifyConn.createStatement();
                 ResultSet rs = verifyStmt.executeQuery("SELECT source FROM test_data WHERE id = 1")) {
                
                assertTrue(rs.next());
                assertEquals("updated", rs.getString("source"), "Primary database should show updated value");
            }
        }
    }

    /**
     * Tests that DELETE statements route to the primary database.
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>DELETE should route to primary</li>
     *   <li>Primary database should have the row removed</li>
     * </ul>
     */
    @Test
    void testDeleteGoesToPrimary() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        setupDatabases();
        
        String url = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
        
        connection = DriverManager.getConnection(url, props);
        
        try (Statement stmt = connection.createStatement()) {
            // DELETE should go to primary
            int rowsAffected = stmt.executeUpdate("DELETE FROM test_data WHERE id = 1");
            assertEquals(1, rowsAffected, "DELETE should affect 1 row");
            
            // Verify the DELETE went to primary by checking primary database directly
            String primaryUrl = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
            Properties primaryProps = new Properties();
            primaryProps.setProperty("user", USER);
            primaryProps.setProperty("password", PASSWORD);
            primaryProps.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
            
            try (Connection verifyConn = DriverManager.getConnection(primaryUrl, primaryProps);
                 Statement verifyStmt = verifyConn.createStatement();
                 ResultSet rs = verifyStmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE id = 1")) {
                
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Primary database should not have the deleted row");
            }
        }
    }

    /**
     * Tests write-then-read without sticky session demonstrates eventual consistency.
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>INSERT routes to primary (writes always go to primary)</li>
     *   <li>SELECT routes to replica (reads go to replica when not in transaction and no sticky session)</li>
     *   <li>SELECT does NOT see the inserted row (demonstrating eventual consistency)</li>
     * </ul>
     */
    @Test
    void testWriteThenRead_WithoutStickySession_DoesNotSeeWrite() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        setupDatabases();
        
        String url = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
        
        connection = DriverManager.getConnection(url, props);
        
        try (Statement stmt = connection.createStatement()) {
            // INSERT goes to primary
            stmt.executeUpdate("INSERT INTO test_data VALUES (150, 'eventual_consistency_test')");
            
            // SELECT goes to replica - should NOT see the inserted row
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE id = 150")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), 
                    "Replica should not have the row just inserted into primary (demonstrates eventual consistency)");
            }
        }
    }

    /**
     * Tests that all operations within a transaction route to the primary database.
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>SELECT within transaction should route to primary (not replica)</li>
     *   <li>INSERT within transaction should route to primary</li>
     *   <li>Both operations should see consistent data</li>
     * </ul>
     */
    @Test
    void testTransaction_AllOperationsGoToPrimary() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        setupDatabases();
        
        String url = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
        
        connection = DriverManager.getConnection(url, props);
        connection.setAutoCommit(false);
        
        try (Statement stmt = connection.createStatement()) {
            // SELECT within transaction should go to primary (id=1)
            try (ResultSet rs = stmt.executeQuery("SELECT id, source FROM test_data")) {
                assertTrue(rs.next(), "Should have at least one row");
                int id = rs.getInt("id");
                String source = rs.getString("source");
                
                assertEquals(1, id, "SELECT in transaction should route to primary (id=1)");
                assertEquals("primary", source, "SELECT in transaction should route to primary (source='primary')");
            }
            
            // INSERT within transaction
            stmt.executeUpdate("INSERT INTO test_data VALUES (200, 'tx_inserted')");
            
            // SELECT the inserted row (still in transaction, should see it)
            try (ResultSet rs = stmt.executeQuery("SELECT source FROM test_data WHERE id = 200")) {
                assertTrue(rs.next(), "Should see inserted row in same transaction");
                assertEquals("tx_inserted", rs.getString("source"));
            }
            
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Tests that after transaction commit, reads go back to replica.
     * 
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>Within transaction: SELECT routes to primary</li>
     *   <li>After COMMIT and autocommit restored: SELECT routes to replica</li>
     *   <li>Post-transaction SELECT from replica does not see committed data (eventual consistency)</li>
     * </ul>
     */
    @Test
    void testAfterTransactionCommit_ReadsGoToReplica() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        setupDatabases();
        
        String url = "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
        
        connection = DriverManager.getConnection(url, props);
        connection.setAutoCommit(false);
        
        try (Statement stmt = connection.createStatement()) {
            // Within transaction: INSERT goes to primary
            stmt.executeUpdate("INSERT INTO test_data VALUES (250, 'post_tx_test')");
            connection.commit();
        }
        
        // After commit, restore autocommit
        connection.setAutoCommit(true);
        
        // Now SELECT should go to replica and NOT see the committed row
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE id = 250")) {
            
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), 
                "After transaction commit, SELECT routes to replica which doesn't have the committed row");
        }
    }
}
