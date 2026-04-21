package openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for read/write traffic splitting through OJP JDBC driver and server.
 * 
 * <h2>Test Strategy: Dual Unsynchronized H2 Databases + OJP End-to-End Flow</h2>
 * 
 * <p>
 * These tests validate read/write routing through the complete OJP stack:
 * <ol>
 *   <li>Client connects using OJP JDBC driver (jdbc:ojp[...]...)</li>
 *   <li>Driver sends gRPC requests to OJP server</li>
 *   <li>Server reads read/write configuration from client properties</li>
 *   <li>Server routes queries to primary or replica based on SQL classification</li>
 * </ol>
 * </p>
 * 
 * <h3>Database Setup</h3>
 * <p>
 * Uses <b>two separate, intentionally UNSYNCHRONIZED</b> H2 in-memory databases:
 * </p>
 * <ul>
 *   <li><b>Primary Database</b> (rw_e2e_primary): Contains id=1, name="PRIMARY_DATA", source="primary"</li>
 *   <li><b>Replica Database</b> (rw_e2e_replica): Contains id=2, name="REPLICA_DATA", source="replica"</li>
 * </ul>
 * 
 * <h3>Key Insight: Leveraging Non-Synchronization for Routing Verification</h3>
 * <p>
 * <b>The databases are NOT synchronized</b> - this is intentional and critical to the test design:
 * </p>
 * <ul>
 *   <li>When a write to primary is <b>not visible</b> on a subsequent read → the read went to replica ✓</li>
 *   <li>When a write to primary <b>is visible</b> on a subsequent read → the read went to primary ✓</li>
 * </ul>
 * 
 * <p>
 * This approach provides <b>deterministic verification</b> of routing correctness without needing:
 * </p>
 * <ul>
 *   <li>Real database replication infrastructure</li>
 *   <li>Complex replication lag handling</li>
 *   <li>External database provisioning</li>
 * </ul>
 * 
 * <h3>Important Differences from Production</h3>
 * <p>
 * In production, primary and replicas have the <b>same data</b> (with potential replication lag).
 * In these tests, primary and replicas have <b>different data</b> to enable routing verification.
 * This difference is acceptable because we're testing <i>routing behavior</i>, not data consistency.
 * </p>
 * 
 * <h3>Test Execution</h3>
 * <p>
 * Tests only run when <code>-DenableH2Tests=true</code> is passed to Maven, following OJP
 * testing standards for H2 integration tests.
 * </p>
 * 
 * @see ConnectAction#setupReadWriteSplitting
 * @see ReadWriteRouter
 * @see ReplicaSelector
 * @see SqlClassifier
 */
public class H2ReadWriteSplittingEndToEndTest {
    
    private static boolean isH2TestEnabled;
    
    private Connection connection;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    /**
     * Sets up the test environment by creating two separate H2 databases with different data.
     * This must be called at the start of each test method.
     */
    private void setupDatabases(String driverClass, String baseUrl, String user, String password) throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Extract the base JDBC URL pattern and construct primary/replica databases
        // baseUrl format: jdbc:ojp[localhost:1059]_h2:~/test
        // We need to create: jdbc:ojp[localhost:1059]_h2:mem:rw_e2e_primary and _replica
        
        String ojpPrefix = baseUrl.substring(0, baseUrl.lastIndexOf("_") + 1);
        
        // Setup PRIMARY database
        String primaryUrl = ojpPrefix + "h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(primaryUrl, user, password);
             Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("DROP TABLE IF EXISTS test_data");
            } catch (SQLException ignore) {}
            stmt.execute("CREATE TABLE test_data (id INT PRIMARY KEY, name VARCHAR(100), source VARCHAR(50))");
            stmt.execute("INSERT INTO test_data VALUES (1, 'PRIMARY_DATA', 'primary')");
        }
        
        // Setup REPLICA database
        String replicaUrl = ojpPrefix + "h2:mem:rw_e2e_replica;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(replicaUrl, user, password);
             Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("DROP TABLE IF EXISTS test_data");
            } catch (SQLException ignore) {}
            stmt.execute("CREATE TABLE test_data (id INT PRIMARY KEY, name VARCHAR(100), source VARCHAR(50))");
            stmt.execute("INSERT INTO test_data VALUES (2, 'REPLICA_DATA', 'replica')");
        }
    }

    /**
     * Test that SELECT queries route to replica when sticky sessions are disabled.
     * 
     * <p>Expected behavior: SELECT should return id=2 (replica data), not id=1 (primary data)</p>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testSelectGoesToReplica_WithoutStickySession(String driverClass, String baseUrl, String user, String password) throws SQLException {
        setupDatabases(driverClass, baseUrl, user, password);
        
        // Build OJP JDBC URL for datasource WITHOUT sticky session
        String ojpPrefix = baseUrl.substring(0, baseUrl.lastIndexOf("_") + 1);
        String ojpUrl = ojpPrefix + "h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=non_sticky_ds" +
                "&non_sticky_ds.ojp.readwrite.enabled=true" +
                "&non_sticky_ds.ojp.readwrite.role=PRIMARY" +
                "&non_sticky_ds.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN" +
                "&non_sticky_ds.ojp.readwrite.stickySessionSeconds=0" +
                "&replica1.ojp.connection.url=" + ojpPrefix + "h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=" + user +
                "&replica1.ojp.connection.password=" + password +
                "&replica1.ojp.readwrite.primary=non_sticky_ds";
        
        connection = DriverManager.getConnection(ojpUrl, user, password);
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, source FROM test_data")) {
            
            assertTrue(rs.next(), "Should have at least one row");
            int id = rs.getInt("id");
            String name = rs.getString("name");
            String source = rs.getString("source");
            
            // Should get replica data (id=2) not primary data (id=1)
            assertEquals(2, id, "SELECT should route to replica and return id=2");
            assertEquals("REPLICA_DATA", name, "Should get replica name");
            assertEquals("replica", source, "Should get replica source");
        }
    }
    
    /**
     * Test that INSERT routes to primary.
     * 
     * <p>Expected behavior: INSERT executes on primary (id=1 database)</p>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testInsertGoesToPrimary(String driverClass, String baseUrl, String user, String password) throws SQLException {
        setupDatabases(driverClass, baseUrl, user, password);
        
        String ojpPrefix = baseUrl.substring(0, baseUrl.lastIndexOf("_") + 1);
        String ojpUrl = ojpPrefix + "h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=non_sticky_ds" +
                "&non_sticky_ds.ojp.readwrite.enabled=true" +
                "&non_sticky_ds.ojp.readwrite.role=PRIMARY" +
                "&non_sticky_ds.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN" +
                "&non_sticky_ds.ojp.readwrite.stickySessionSeconds=0" +
                "&replica1.ojp.connection.url=" + ojpPrefix + "h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=" + user +
                "&replica1.ojp.connection.password=" + password +
                "&replica1.ojp.readwrite.primary=non_sticky_ds";
        
        connection = DriverManager.getConnection(ojpUrl, user, password);
        try (Statement stmt = connection.createStatement()) {
            // Insert a new record
            int rowsAffected = stmt.executeUpdate("INSERT INTO test_data VALUES (3, 'NEW_DATA', 'inserted')");
            assertEquals(1, rowsAffected, "INSERT should affect 1 row");
            
            // Verify the insert went to primary by checking if it exists in primary database
            String primaryUrl = ojpPrefix + "h2:mem:rw_e2e_primary";
            try (Connection verifyConn = DriverManager.getConnection(primaryUrl, user, password);
                 Statement verifyStmt = verifyConn.createStatement();
                 ResultSet rs = verifyStmt.executeQuery("SELECT COUNT(*) as cnt FROM test_data WHERE id=3")) {
                
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("cnt"), "INSERT should have created record in primary");
            }
        }
    }
    
    /**
     * Test that with sticky sessions enabled, reads go to primary after a write.
     * 
     * <p>Expected behavior: After INSERT, immediate SELECT should return id=1 (primary data)</p>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testStickySession_ReadYourWrites(String driverClass, String baseUrl, String user, String password) throws SQLException {
        setupDatabases(driverClass, baseUrl, user, password);
        
        String ojpPrefix = baseUrl.substring(0, baseUrl.lastIndexOf("_") + 1);
        String ojpUrl = ojpPrefix + "h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=sticky_ds" +
                "&sticky_ds.ojp.readwrite.enabled=true" +
                "&sticky_ds.ojp.readwrite.role=PRIMARY" +
                "&sticky_ds.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN" +
                "&sticky_ds.ojp.readwrite.stickySessionSeconds=5" +
                "&replica1.ojp.connection.url=" + ojpPrefix + "h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=" + user +
                "&replica1.ojp.connection.password=" + password +
                "&replica1.ojp.readwrite.primary=sticky_ds";
        
        connection = DriverManager.getConnection(ojpUrl, user, password);
        try (Statement stmt = connection.createStatement()) {
            // Perform a write (INSERT)
            stmt.executeUpdate("INSERT INTO test_data VALUES (10, 'STICKY_DATA', 'sticky_test')");
            
            // Immediate read should go to primary due to sticky session
            try (ResultSet rs = stmt.executeQuery("SELECT id, source FROM test_data WHERE id IN (1, 2)")) {
                assertTrue(rs.next(), "Should have at least one row");
                int id = rs.getInt("id");
                
                // Should get primary data (id=1) not replica data (id=2)
                assertEquals(1, id, "After write, SELECT should stick to primary and return id=1");
            }
        }
    }
    
    /**
     * Test that sticky session expires after the configured timeout.
     * 
     * <p>Expected behavior: After timeout, reads should return to replica (id=2)</p>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testStickySession_ExpiresAfterTimeout(String driverClass, String baseUrl, String user, String password) throws Exception {
        setupDatabases(driverClass, baseUrl, user, password);
        
        String ojpPrefix = baseUrl.substring(0, baseUrl.lastIndexOf("_") + 1);
        // Use 2-second sticky window for faster test
        String ojpUrl = ojpPrefix + "h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=sticky_expire_ds" +
                "&sticky_expire_ds.ojp.readwrite.enabled=true" +
                "&sticky_expire_ds.ojp.readwrite.role=PRIMARY" +
                "&sticky_expire_ds.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN" +
                "&sticky_expire_ds.ojp.readwrite.stickySessionSeconds=2" +
                "&replica1.ojp.connection.url=" + ojpPrefix + "h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=" + user +
                "&replica1.ojp.connection.password=" + password +
                "&replica1.ojp.readwrite.primary=sticky_expire_ds";
        
        connection = DriverManager.getConnection(ojpUrl, user, password);
        try (Statement stmt = connection.createStatement()) {
            // Perform a write to trigger sticky session
            stmt.executeUpdate("INSERT INTO test_data VALUES (11, 'EXPIRE_DATA', 'expire_test')");
            
            // Wait for sticky session to expire (2 seconds + buffer)
            Thread.sleep(3000);
            
            // Read after expiration should go back to replica
            try (ResultSet rs = stmt.executeQuery("SELECT id, source FROM test_data WHERE id IN (1, 2)")) {
                assertTrue(rs.next(), "Should have at least one row");
                int id = rs.getInt("id");
                
                // Should get replica data (id=2) after sticky session expires
                assertEquals(2, id, "After sticky session expires, SELECT should route to replica and return id=2");
            }
        }
    }
    
    /**
     * Test that during a transaction, all operations route to primary.
     * 
     * <p>Expected behavior: All reads within transaction should return primary data (id=1)</p>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testTransaction_AllOperationsGoToPrimary(String driverClass, String baseUrl, String user, String password) throws SQLException {
        setupDatabases(driverClass, baseUrl, user, password);
        
        String ojpPrefix = baseUrl.substring(0, baseUrl.lastIndexOf("_") + 1);
        String ojpUrl = ojpPrefix + "h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=tx_ds" +
                "&tx_ds.ojp.readwrite.enabled=true" +
                "&tx_ds.ojp.readwrite.role=PRIMARY" +
                "&tx_ds.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN" +
                "&tx_ds.ojp.readwrite.stickySessionSeconds=0" +
                "&replica1.ojp.connection.url=" + ojpPrefix + "h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=" + user +
                "&replica1.ojp.connection.password=" + password +
                "&replica1.ojp.readwrite.primary=tx_ds";
        
        connection = DriverManager.getConnection(ojpUrl, user, password);
        connection.setAutoCommit(false);
        
        try (Statement stmt = connection.createStatement()) {
            // Within transaction, INSERT should go to primary
            stmt.executeUpdate("INSERT INTO test_data VALUES (12, 'TX_DATA', 'transaction_test')");
            
            // Within transaction, SELECT should also go to primary
            try (ResultSet rs = stmt.executeQuery("SELECT id, source FROM test_data WHERE id IN (1, 2)")) {
                assertTrue(rs.next(), "Should have at least one row");
                int id = rs.getInt("id");
                
                // Should get primary data (id=1) within transaction
                assertEquals(1, id, "Within transaction, SELECT should route to primary and return id=1");
            }
            
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
    }
    
    /**
     * Test that UPDATE routes to primary.
     * 
     * <p>Expected behavior: UPDATE executes on primary database</p>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testUpdateGoesToPrimary(String driverClass, String baseUrl, String user, String password) throws SQLException {
        setupDatabases(driverClass, baseUrl, user, password);
        
        String ojpPrefix = baseUrl.substring(0, baseUrl.lastIndexOf("_") + 1);
        String ojpUrl = ojpPrefix + "h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=update_ds" +
                "&update_ds.ojp.readwrite.enabled=true" +
                "&update_ds.ojp.readwrite.role=PRIMARY" +
                "&update_ds.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN" +
                "&update_ds.ojp.readwrite.stickySessionSeconds=0" +
                "&replica1.ojp.connection.url=" + ojpPrefix + "h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=" + user +
                "&replica1.ojp.connection.password=" + password +
                "&replica1.ojp.readwrite.primary=update_ds";
        
        connection = DriverManager.getConnection(ojpUrl, user, password);
        try (Statement stmt = connection.createStatement()) {
            // Update existing record in primary
            int rowsAffected = stmt.executeUpdate("UPDATE test_data SET name='UPDATED_PRIMARY' WHERE id=1");
            assertEquals(1, rowsAffected, "UPDATE should affect 1 row");
            
            // Verify the update went to primary
            String primaryUrl = ojpPrefix + "h2:mem:rw_e2e_primary";
            try (Connection verifyConn = DriverManager.getConnection(primaryUrl, user, password);
                 Statement verifyStmt = verifyConn.createStatement();
                 ResultSet rs = verifyStmt.executeQuery("SELECT name FROM test_data WHERE id=1")) {
                
                assertTrue(rs.next());
                assertEquals("UPDATED_PRIMARY", rs.getString("name"), "UPDATE should have modified primary");
            }
        }
    }
    
    /**
     * Test that DELETE routes to primary.
     * 
     * <p>Expected behavior: DELETE executes on primary database</p>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void testDeleteGoesToPrimary(String driverClass, String baseUrl, String user, String password) throws SQLException {
        setupDatabases(driverClass, baseUrl, user, password);
        
        String ojpPrefix = baseUrl.substring(0, baseUrl.lastIndexOf("_") + 1);
        
        // First, add a record to primary that we'll delete
        String primaryUrl = ojpPrefix + "h2:mem:rw_e2e_primary";
        try (Connection setupConn = DriverManager.getConnection(primaryUrl, user, password);
             Statement setupStmt = setupConn.createStatement()) {
            setupStmt.executeUpdate("INSERT INTO test_data VALUES (13, 'TO_DELETE', 'delete_test')");
        }
        
        String ojpUrl = ojpPrefix + "h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=delete_ds" +
                "&delete_ds.ojp.readwrite.enabled=true" +
                "&delete_ds.ojp.readwrite.role=PRIMARY" +
                "&delete_ds.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN" +
                "&delete_ds.ojp.readwrite.stickySessionSeconds=0" +
                "&replica1.ojp.connection.url=" + ojpPrefix + "h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=" + user +
                "&replica1.ojp.connection.password=" + password +
                "&replica1.ojp.readwrite.primary=delete_ds";
        
        connection = DriverManager.getConnection(ojpUrl, user, password);
        try (Statement stmt = connection.createStatement()) {
            // Delete the record
            int rowsAffected = stmt.executeUpdate("DELETE FROM test_data WHERE id=13");
            assertEquals(1, rowsAffected, "DELETE should affect 1 row");
            
            // Verify the delete happened on primary
            try (Connection verifyConn = DriverManager.getConnection(primaryUrl, user, password);
                 Statement verifyStmt = verifyConn.createStatement();
                 ResultSet rs = verifyStmt.executeQuery("SELECT COUNT(*) as cnt FROM test_data WHERE id=13")) {
                
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("cnt"), "DELETE should have removed record from primary");
            }
        }
    }
}
