package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.*;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * <h3>Test Datasources</h3>
 * <p>
 * The test uses two separate OJP datasource configurations:
 * </p>
 * <ul>
 *   <li><b>non_sticky_ds</b>: Read/write splitting with sticky sessions disabled (default behavior)</li>
 *   <li><b>sticky_ds</b>: Read/write splitting with 5-second sticky session window</li>
 * </ul>
 * 
 * <h3>Important Differences from Production</h3>
 * <p>
 * In production, primary and replicas have the <b>same data</b> (with potential replication lag).
 * In these tests, primary and replicas have <b>different data</b> to enable routing verification.
 * This difference is acceptable because we're testing <i>routing behavior</i>, not data consistency.
 * </p>
 * 
 * <h3>Test Coverage</h3>
 * <ul>
 *   <li>SELECT routing to replica (non-sticky)</li>
 *   <li>INSERT/UPDATE/DELETE routing to primary</li>
 *   <li>Sticky session behavior (reads go to primary after write)</li>
 *   <li>Sticky session expiration (reads return to replica after timeout)</li>
 *   <li>Transaction routing (all operations to primary)</li>
 * </ul>
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class H2ReadWriteSplittingEndToEndTest {
    
    private static boolean isH2TestEnabled;
    
    @BeforeAll
    static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
        if (!isH2TestEnabled) {
            System.out.println("H2 Read/Write Splitting End-to-End tests are disabled. Use -DenableH2Tests=true to enable.");
        }
    }
    
    @BeforeAll
    void setupDatabases() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Populate PRIMARY database using OJP connection
        // This ensures we're using the same H2 VM instance that OJP server will use
        String primaryOjpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1" +
                "?ojp.datasource.name=setup_primary";
        
        try (Connection conn = DriverManager.getConnection(primaryOjpUrl, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE test_data (id INT PRIMARY KEY, name VARCHAR(100), source VARCHAR(50))");
            stmt.execute("INSERT INTO test_data VALUES (1, 'PRIMARY_DATA', 'primary')");
        }
        
        // Populate REPLICA database using OJP connection
        // This ensures we're using the same H2 VM instance that OJP server will use
        String replicaOjpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_replica;DB_CLOSE_DELAY=-1" +
                "?ojp.datasource.name=setup_replica";
        
        try (Connection conn = DriverManager.getConnection(replicaOjpUrl, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE test_data (id INT PRIMARY KEY, name VARCHAR(100), source VARCHAR(50))");
            stmt.execute("INSERT INTO test_data VALUES (2, 'REPLICA_DATA', 'replica')");
        }
    }
    
    @AfterAll
    void teardownDatabases() throws SQLException {
        // Cleanup using OJP connections to match setup
        String primaryOjpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=teardown_primary";
        
        try (Connection conn = DriverManager.getConnection(primaryOjpUrl, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_data");
        } catch (SQLException e) {
            // Ignore cleanup errors
        }
        
        String replicaOjpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_replica" +
                "?ojp.datasource.name=teardown_replica";
        
        try (Connection conn = DriverManager.getConnection(replicaOjpUrl, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_data");
        } catch (SQLException e) {
            // Ignore cleanup errors
        }
    }
    
    /**
     * Test that SELECT queries route to replica when sticky sessions are disabled.
     * 
     * <p>Expected behavior: SELECT should return id=2 (replica data), not id=1 (primary data)</p>
     */
    @Test
    void testSelectGoesToReplica_WithoutStickySession() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Build OJP JDBC URL for datasource WITHOUT sticky session
        String ojpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=non_sticky_ds" +
                "&non_sticky_ds.ojp.readwrite.enabled=true" +
                "&non_sticky_ds.ojp.readwrite.role=PRIMARY" +
                "&non_sticky_ds.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN" +
                "&non_sticky_ds.ojp.readwrite.stickySessionSeconds=0" +
                "&replica1.ojp.connection.url=jdbc:h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=sa" +
                "&replica1.ojp.connection.password=" +
                "&replica1.ojp.readwrite.primary=non_sticky_ds";
        
        try (Connection conn = DriverManager.getConnection(ojpUrl, "sa", "")) {
            try (Statement stmt = conn.createStatement();
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
    }
    
    /**
     * Test that INSERT routes to primary.
     * 
     * <p>Expected behavior: INSERT executes on primary (id=1 database)</p>
     */
    @Test
    void testInsertGoesToPrimary() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        String ojpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=non_sticky_ds" +
                "&non_sticky_ds.ojp.readwrite.enabled=true" +
                "&non_sticky_ds.ojp.readwrite.role=PRIMARY" +
                "&replica1.ojp.connection.url=jdbc:h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=sa" +
                "&replica1.ojp.connection.password=" +
                "&replica1.ojp.readwrite.primary=non_sticky_ds";
        
        try (Connection conn = DriverManager.getConnection(ojpUrl, "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                int rowsInserted = stmt.executeUpdate("INSERT INTO test_data VALUES (100, 'NEW_DATA', 'inserted')");
                assertEquals(1, rowsInserted, "Should insert 1 row");
            }
        }
        
        // Verify the insert went to primary by checking primary via OJP
        String primaryCheckUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=verify_primary";
        try (Connection conn = DriverManager.getConnection(primaryCheckUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE id = 100")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "INSERT should have gone to primary database");
        }
        
        // Verify the insert did NOT go to replica by checking replica via OJP
        String replicaCheckUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_replica" +
                "?ojp.datasource.name=verify_replica";
        try (Connection conn = DriverManager.getConnection(replicaCheckUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE id = 100")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "INSERT should NOT have gone to replica database");
        }
    }
    
    /**
     * Test sticky session behavior: reads go to primary after a write.
     * 
     * <p>Expected behavior: After INSERT, SELECT should return primary data (id=1) for sticky duration</p>
     */
    @Test
    void testStickySession_ReadYourWrites() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Build OJP JDBC URL WITH sticky session (5 seconds)
        String ojpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=sticky_ds" +
                "&sticky_ds.ojp.readwrite.enabled=true" +
                "&sticky_ds.ojp.readwrite.role=PRIMARY" +
                "&sticky_ds.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN" +
                "&sticky_ds.ojp.readwrite.stickySessionSeconds=5" +
                "&replica1.ojp.connection.url=jdbc:h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=sa" +
                "&replica1.ojp.connection.password=" +
                "&replica1.ojp.readwrite.primary=sticky_ds";
        
        try (Connection conn = DriverManager.getConnection(ojpUrl, "sa", "")) {
            // Perform a write
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO test_data VALUES (200, 'STICKY_DATA', 'sticky_test')");
            }
            
            // Immediately read - should go to primary (sticky session active)
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, name, source FROM test_data WHERE id = 1")) {
                
                assertTrue(rs.next(), "Should find primary data during sticky session");
                assertEquals(1, rs.getInt("id"), "Sticky session should route to primary");
                assertEquals("PRIMARY_DATA", rs.getString("name"));
                assertEquals("primary", rs.getString("source"));
            }
        }
    }
    
    /**
     * Test sticky session expiration: reads return to replica after timeout.
     * 
     * <p>Expected behavior: After sticky session expires, SELECT should return replica data (id=2)</p>
     */
    @Test
    void testStickySession_ExpiresAfterTimeout() throws SQLException, InterruptedException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Build OJP JDBC URL WITH sticky session (2 seconds - short for test)
        String ojpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=sticky_ds_short" +
                "&sticky_ds_short.ojp.readwrite.enabled=true" +
                "&sticky_ds_short.ojp.readwrite.role=PRIMARY" +
                "&sticky_ds_short.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN" +
                "&sticky_ds_short.ojp.readwrite.stickySessionSeconds=2" +
                "&replica1.ojp.connection.url=jdbc:h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=sa" +
                "&replica1.ojp.connection.password=" +
                "&replica1.ojp.readwrite.primary=sticky_ds_short";
        
        try (Connection conn = DriverManager.getConnection(ojpUrl, "sa", "")) {
            // Perform a write to activate sticky session
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO test_data VALUES (300, 'EXPIRY_TEST', 'expiry')");
            }
            
            // Wait for sticky session to expire (2 seconds + buffer)
            Thread.sleep(3000);
            
            // Read after expiration - should go back to replica
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, name, source FROM test_data")) {
                
                assertTrue(rs.next(), "Should have data");
                int id = rs.getInt("id");
                
                // After sticky session expires, should route to replica (id=2)
                assertEquals(2, id, "After sticky session expires, should route to replica");
                assertEquals("REPLICA_DATA", rs.getString("name"));
                assertEquals("replica", rs.getString("source"));
            }
        }
    }
    
    /**
     * Test that all operations within a transaction route to primary.
     * 
     * <p>Expected behavior: Both INSERT and SELECT within transaction should use primary</p>
     */
    @Test
    void testTransaction_AllOperationsGoToPrimary() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        String ojpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=tx_ds" +
                "&tx_ds.ojp.readwrite.enabled=true" +
                "&tx_ds.ojp.readwrite.role=PRIMARY" +
                "&replica1.ojp.connection.url=jdbc:h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=sa" +
                "&replica1.ojp.connection.password=" +
                "&replica1.ojp.readwrite.primary=tx_ds";
        
        try (Connection conn = DriverManager.getConnection(ojpUrl, "sa", "")) {
            conn.setAutoCommit(false); // Start transaction
            
            try {
                // INSERT within transaction
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("INSERT INTO test_data VALUES (400, 'TX_DATA', 'transaction')");
                }
                
                // SELECT within transaction should go to primary (id=1 row should be visible)
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT id, name, source FROM test_data WHERE id = 1")) {
                    
                    assertTrue(rs.next(), "Should see primary data in transaction");
                    assertEquals(1, rs.getInt("id"), "Transaction SELECT should route to primary");
                    assertEquals("PRIMARY_DATA", rs.getString("name"));
                    assertEquals("primary", rs.getString("source"));
                }
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }
    
    /**
     * Test UPDATE routing to primary.
     * 
     * <p>Expected behavior: UPDATE executes on primary database</p>
     */
    @Test
    void testUpdateGoesToPrimary() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        String ojpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=update_ds" +
                "&update_ds.ojp.readwrite.enabled=true" +
                "&update_ds.ojp.readwrite.role=PRIMARY" +
                "&replica1.ojp.connection.url=jdbc:h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=sa" +
                "&replica1.ojp.connection.password=" +
                "&replica1.ojp.readwrite.primary=update_ds";
        
        try (Connection conn = DriverManager.getConnection(ojpUrl, "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                int rowsUpdated = stmt.executeUpdate("UPDATE test_data SET name = 'UPDATED' WHERE id = 1");
                assertEquals(1, rowsUpdated, "Should update 1 row on primary");
            }
        }
        
        // Verify update went to primary using OJP connection
        String primaryCheckUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=verify_update_primary";
        try (Connection conn = DriverManager.getConnection(primaryCheckUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM test_data WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("UPDATED", rs.getString("name"), "UPDATE should have modified primary");
        }
    }
    
    /**
     * Test DELETE routing to primary.
     * 
     * <p>Expected behavior: DELETE executes on primary database</p>
     */
    @Test
    void testDeleteGoesToPrimary() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        String ojpUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=delete_ds" +
                "&delete_ds.ojp.readwrite.enabled=true" +
                "&delete_ds.ojp.readwrite.role=PRIMARY" +
                "&replica1.ojp.connection.url=jdbc:h2:mem:rw_e2e_replica" +
                "&replica1.ojp.connection.user=sa" +
                "&replica1.ojp.connection.password=" +
                "&replica1.ojp.readwrite.primary=delete_ds";
        
        // First insert a row to delete using OJP connection to primary
        String primaryInsertUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=setup_delete_primary";
        try (Connection conn = DriverManager.getConnection(primaryInsertUrl, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO test_data VALUES (500, 'TO_DELETE', 'delete_test')");
        }
        
        try (Connection conn = DriverManager.getConnection(ojpUrl, "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                int rowsDeleted = stmt.executeUpdate("DELETE FROM test_data WHERE id = 500");
                assertEquals(1, rowsDeleted, "Should delete 1 row from primary");
            }
        }
        
        // Verify deletion from primary using OJP connection
        String primaryCheckUrl = "jdbc:ojp[localhost:50051]jdbc:h2:mem:rw_e2e_primary" +
                "?ojp.datasource.name=verify_delete_primary";
        try (Connection conn = DriverManager.getConnection(primaryCheckUrl, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE id = 500")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "DELETE should have removed row from primary");
        }
    }
}
