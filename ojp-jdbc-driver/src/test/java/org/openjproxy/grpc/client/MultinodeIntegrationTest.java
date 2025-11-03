package org.openjproxy.grpc.client;

import org.junit.jupiter.api.*;
import org.openjproxy.jdbc.Driver;

import java.sql.*;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for multinode functionality.
 * These tests require two OJP servers running on ports 10591 and 10592,
 * and a Postgres database accessible at localhost:5432.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultinodeIntegrationTest {

    private static final String MULTINODE_URL = "jdbc:ojp[localhost:10591,localhost:10592]_postgresql://localhost:5432/defaultdb";
    private static final String POSTGRES_USER = "testuser";
    private static final String POSTGRES_PASSWORD = "testpassword";

    @BeforeAll
    static void setUp() throws SQLException {
        // Register the driver
        DriverManager.registerDriver(new Driver());
        
        // Create a test table using direct connection
        try (Connection directConn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/defaultdb", POSTGRES_USER, POSTGRES_PASSWORD)) {
            try (Statement stmt = directConn.createStatement()) {
                // Drop table if exists
                stmt.execute("DROP TABLE IF EXISTS multinode_test");
                // Create test table
                stmt.execute("CREATE TABLE multinode_test (id SERIAL PRIMARY KEY, value VARCHAR(100), server_info VARCHAR(50))");
            }
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        // Clean up test table
        try (Connection directConn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/defaultdb", POSTGRES_USER, POSTGRES_PASSWORD)) {
            try (Statement stmt = directConn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS multinode_test");
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("Test URL parsing for multinode configuration")
    void testMultinodeUrlParsing() {
        // Parse the multinode URL
        assertDoesNotThrow(() -> {
            var endpoints = MultinodeUrlParser.parseServerEndpoints(MULTINODE_URL);
            assertEquals(2, endpoints.size(), "Should parse two server endpoints");
            assertEquals("localhost", endpoints.get(0).getHost());
            assertEquals(10591, endpoints.get(0).getPort());
            assertEquals("localhost", endpoints.get(1).getHost());
            assertEquals(10592, endpoints.get(1).getPort());
        });
    }

    @Test
    @Order(2)
    @DisplayName("Test basic connection to multinode cluster")
    void testBasicConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props)) {
            assertNotNull(conn, "Connection should not be null");
            assertFalse(conn.isClosed(), "Connection should not be closed");
            
            // Test basic query
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 as test_value")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("test_value"));
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("Test multiple concurrent connections to verify load distribution")
    void testLoadDistribution() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        // Create multiple connections and insert data
        int numConnections = 10;
        for (int i = 0; i < numConnections; i++) {
            try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
                 PreparedStatement pstmt = conn.prepareStatement(
                         "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
                pstmt.setString(1, "test_value_" + i);
                pstmt.setString(2, "connection_" + i);
                int affected = pstmt.executeUpdate();
                assertEquals(1, affected, "Should insert one row");
            }
        }

        // Verify all data was inserted
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM multinode_test")) {
            assertTrue(rs.next());
            assertEquals(numConnections, rs.getInt("cnt"), "Should have inserted all rows");
        }
    }

    @Test
    @Order(4)
    @DisplayName("Test transaction handling with session stickiness")
    void testTransactionWithSessionStickiness() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props)) {
            conn.setAutoCommit(false);
            
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
                pstmt.setString(1, "transaction_test");
                pstmt.setString(2, "tx_session");
                pstmt.executeUpdate();
            }
            
            // Verify data is visible within transaction
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) as cnt FROM multinode_test WHERE value = 'transaction_test'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("cnt"));
            }
            
            conn.commit();
        }

        // Verify data is persisted after commit
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) as cnt FROM multinode_test WHERE value = 'transaction_test'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("cnt"), "Committed data should be visible");
        }
    }

    @Test
    @Order(5)
    @DisplayName("Test rollback in transaction")
    void testTransactionRollback() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        // Get initial count
        int initialCount;
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM multinode_test")) {
            assertTrue(rs.next());
            initialCount = rs.getInt("cnt");
        }

        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props)) {
            conn.setAutoCommit(false);
            
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
                pstmt.setString(1, "rollback_test");
                pstmt.setString(2, "rollback_session");
                pstmt.executeUpdate();
            }
            
            conn.rollback();
        }

        // Verify data was NOT persisted after rollback
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM multinode_test")) {
            assertTrue(rs.next());
            assertEquals(initialCount, rs.getInt("cnt"), "Count should remain unchanged after rollback");
        }
    }

    @Test
    @Order(6)
    @DisplayName("Test prepared statement with multiple executions")
    void testPreparedStatementMultipleExecutions() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
            
            for (int i = 0; i < 5; i++) {
                pstmt.setString(1, "prepared_test_" + i);
                pstmt.setString(2, "prepared_session");
                int affected = pstmt.executeUpdate();
                assertEquals(1, affected);
            }
        }

        // Verify all inserts succeeded
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) as cnt FROM multinode_test WHERE value LIKE 'prepared_test_%'")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt("cnt"));
        }
    }

    @Test
    @Order(7)
    @DisplayName("Test batch operations")
    void testBatchOperations() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
            
            for (int i = 0; i < 3; i++) {
                pstmt.setString(1, "batch_test_" + i);
                pstmt.setString(2, "batch_session");
                pstmt.addBatch();
            }
            
            int[] results = pstmt.executeBatch();
            assertEquals(3, results.length);
            for (int result : results) {
                assertEquals(1, result);
            }
        }

        // Verify batch inserts
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) as cnt FROM multinode_test WHERE value LIKE 'batch_test_%'")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt("cnt"));
        }
    }

    @Test
    @Order(8)
    @DisplayName("Test backward compatibility with single-node URL format")
    void testBackwardCompatibilitySingleNode() throws SQLException {
        // Test that single-node URLs still work
        String singleNodeUrl = "jdbc:ojp[localhost:10591]_postgresql://localhost:5432/defaultdb";
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        try (Connection conn = DriverManager.getConnection(singleNodeUrl, props)) {
            assertNotNull(conn);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 as test_value")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("test_value"));
            }
        }
    }
}
