package org.openjproxy.grpc.client;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failover tests for multinode functionality.
 * These tests verify behavior when one of the servers is unavailable.
 * 
 * Test execution assumes:
 * - Server 1 (port 10591) has been stopped
 * - Server 2 (port 10592) is still running
 * - Postgres database is accessible at localhost:5432
 * 
 * These tests are disabled by default and only run when the system property
 * 'multinodeTestsEnabled' is set to 'true'.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultinodeFailoverTest {
    
    @BeforeAll
    static void checkIfEnabled() {
        String enabled = System.getProperty("multinodeTestsEnabled", "false");
        Assumptions.assumeTrue("true".equalsIgnoreCase(enabled), 
                "Multinode failover tests are disabled. Set -DmultinodeTestsEnabled=true to enable.");
    }

    private static final String MULTINODE_URL = "jdbc:ojp[localhost:10591,localhost:10592]_postgresql://localhost:5432/defaultdb";
    private static final String POSTGRES_USER = "testuser";
    private static final String POSTGRES_PASSWORD = "testpassword";

    @BeforeEach
    void setUp() throws SQLException {
        // Create test table before each test using multinode URL
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        // Use OJP multinode URL for all connections
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement()) {
            // Drop table if it exists and recreate it
            stmt.execute("DROP TABLE IF EXISTS multinode_test");
            stmt.execute("CREATE TABLE multinode_test (id SERIAL PRIMARY KEY, value VARCHAR(100), server_info VARCHAR(50))");
        }
    }

    @AfterEach
    void tearDown() {
        // Clean up test table after each test using multinode URL
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        // Use OJP multinode URL for all connections
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS multinode_test");
        } catch (SQLException e) {
            // Ignore cleanup errors
        }
    }

    @Test
    @Order(1)
    @DisplayName("Test connection when one server is down (should connect to healthy server)")
    void testConnectionWithOneServerDown() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);
        
        // Configure shorter retry for faster test execution
        props.setProperty("ojp.multinode.retryAttempts", "3");
        props.setProperty("ojp.multinode.retryDelayMs", "1000");

        // Should still be able to connect using the healthy server
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props)) {
            assertNotNull(conn, "Should connect to healthy server");
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 as test_value")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("test_value"));
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("Test multiple operations with one server down")
    void testMultipleOperationsWithOneServerDown() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);
        props.setProperty("ojp.multinode.retryAttempts", "3");
        props.setProperty("ojp.multinode.retryDelayMs", "1000");

        // Perform multiple operations
        for (int i = 0; i < 5; i++) {
            try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
                 PreparedStatement pstmt = conn.prepareStatement(
                         "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
                pstmt.setString(1, "failover_test_" + i);
                pstmt.setString(2, "failover_" + i);
                int affected = pstmt.executeUpdate();
                assertEquals(1, affected);
            }
        }

        // Verify all operations succeeded
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) as cnt FROM multinode_test WHERE value LIKE 'failover_test_%'")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt("cnt"), "All operations should succeed on healthy server");
        }
    }

    @Test
    @Order(3)
    @DisplayName("Test transaction with one server down")
    void testTransactionWithOneServerDown() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);
        props.setProperty("ojp.multinode.retryAttempts", "3");
        props.setProperty("ojp.multinode.retryDelayMs", "1000");

        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props)) {
            conn.setAutoCommit(false);
            
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
                pstmt.setString(1, "failover_tx_test");
                pstmt.setString(2, "failover_tx");
                pstmt.executeUpdate();
            }
            
            conn.commit();
        }

        // Verify transaction committed
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) as cnt FROM multinode_test WHERE value = 'failover_tx_test'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("cnt"));
        }
    }

    @Test
    @Order(4)
    @DisplayName("Test query results consistency with one server down")
    void testQueryConsistency() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);
        props.setProperty("ojp.multinode.retryAttempts", "3");
        props.setProperty("ojp.multinode.retryDelayMs", "1000");

        // Insert test data
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
            pstmt.setString(1, "consistency_test");
            pstmt.setString(2, "consistency");
            pstmt.executeUpdate();
        }

        // Query multiple times to verify consistency
        for (int i = 0; i < 3; i++) {
            try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) as cnt FROM multinode_test WHERE value = 'consistency_test'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("cnt"), "Results should be consistent across queries");
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("Test prepared statement reuse with one server down")
    void testPreparedStatementWithOneServerDown() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);
        props.setProperty("ojp.multinode.retryAttempts", "3");
        props.setProperty("ojp.multinode.retryDelayMs", "1000");

        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT value FROM multinode_test WHERE value = ?")) {
            
            pstmt.setString(1, "failover_tx_test");
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Should find the test row");
                assertEquals("failover_tx_test", rs.getString("value"));
            }
        }
    }
}
