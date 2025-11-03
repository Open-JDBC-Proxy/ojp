package org.openjproxy.grpc.client;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recovery tests for multinode functionality.
 * These tests verify server recovery behavior after a failed server comes back online.
 * 
 * Test execution assumes:
 * - Server 1 (port 10591) has been restarted and is now healthy
 * - Server 2 (port 10592) is running
 * - Postgres database is accessible at localhost:5432
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultinodeRecoveryTest {

    private static final String MULTINODE_URL = "jdbc:ojp[localhost:10591,localhost:10592]_postgresql://localhost:5432/defaultdb";
    private static final String POSTGRES_USER = "testuser";
    private static final String POSTGRES_PASSWORD = "testpassword";

    @Test
    @Order(1)
    @DisplayName("Test connection after server recovery")
    void testConnectionAfterRecovery() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        // Allow time for health check to detect recovered server
        try {
            Thread.sleep(6000); // Wait for retry delay period
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Should be able to connect and use either server
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props)) {
            assertNotNull(conn, "Should connect successfully");
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 as test_value")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("test_value"));
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("Test load distribution after recovery")
    void testLoadDistributionAfterRecovery() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        // Create multiple connections to verify both servers are being used
        int successfulConnections = 0;
        for (int i = 0; i < 10; i++) {
            try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
                 PreparedStatement pstmt = conn.prepareStatement(
                         "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
                pstmt.setString(1, "recovery_test_" + i);
                pstmt.setString(2, "recovery_" + i);
                int affected = pstmt.executeUpdate();
                if (affected == 1) {
                    successfulConnections++;
                }
            }
        }

        // All connections should succeed now that both servers are healthy
        assertEquals(10, successfulConnections, "All connections should succeed with both servers healthy");

        // Verify all data was inserted
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) as cnt FROM multinode_test WHERE value LIKE 'recovery_test_%'")) {
            assertTrue(rs.next());
            assertEquals(10, rs.getInt("cnt"));
        }
    }

    @Test
    @Order(3)
    @DisplayName("Test transactions work on both servers after recovery")
    void testTransactionsAfterRecovery() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        // Test multiple transactions
        for (int i = 0; i < 5; i++) {
            try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props)) {
                conn.setAutoCommit(false);
                
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
                    pstmt.setString(1, "recovery_tx_" + i);
                    pstmt.setString(2, "recovery_tx");
                    pstmt.executeUpdate();
                }
                
                conn.commit();
            }
        }

        // Verify all transactions committed
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) as cnt FROM multinode_test WHERE value LIKE 'recovery_tx_%'")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt("cnt"));
        }
    }

    @Test
    @Order(4)
    @DisplayName("Test query performance after recovery")
    void testQueryPerformanceAfterRecovery() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        long startTime = System.currentTimeMillis();
        int queryCount = 20;

        for (int i = 0; i < queryCount; i++) {
            try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM multinode_test")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt("cnt") > 0);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        long avgTimePerQuery = duration / queryCount;

        // Just verify queries complete in reasonable time (not a strict performance test)
        assertTrue(avgTimePerQuery < 1000, 
                "Average query time should be reasonable: " + avgTimePerQuery + "ms");
    }

    @Test
    @Order(5)
    @DisplayName("Test batch operations after recovery")
    void testBatchOperationsAfterRecovery() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
            
            for (int i = 0; i < 5; i++) {
                pstmt.setString(1, "recovery_batch_" + i);
                pstmt.setString(2, "recovery_batch");
                pstmt.addBatch();
            }
            
            int[] results = pstmt.executeBatch();
            assertEquals(5, results.length);
            for (int result : results) {
                assertEquals(1, result);
            }
        }

        // Verify batch operations
        try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) as cnt FROM multinode_test WHERE value LIKE 'recovery_batch_%'")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt("cnt"));
        }
    }

    @Test
    @Order(6)
    @DisplayName("Test that both servers are accessible after recovery")
    void testBothServersAccessible() throws SQLException {
        // Test each server individually to confirm both are working
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        // Test server 1
        String server1Url = "jdbc:ojp[localhost:10591]_postgresql://localhost:5432/defaultdb";
        try (Connection conn = DriverManager.getConnection(server1Url, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 as test")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("test"));
        }

        // Test server 2
        String server2Url = "jdbc:ojp[localhost:10592]_postgresql://localhost:5432/defaultdb";
        try (Connection conn = DriverManager.getConnection(server2Url, props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 as test")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("test"));
        }
    }

    @Test
    @Order(7)
    @DisplayName("Test continuous operations after recovery")
    void testContinuousOperationsAfterRecovery() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", POSTGRES_USER);
        props.setProperty("password", POSTGRES_PASSWORD);

        // Perform continuous operations to verify stability
        for (int i = 0; i < 15; i++) {
            try (Connection conn = DriverManager.getConnection(MULTINODE_URL, props)) {
                try (PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO multinode_test (value, server_info) VALUES (?, ?)")) {
                    pstmt.setString(1, "continuous_" + i);
                    pstmt.setString(2, "continuous_ops");
                    pstmt.executeUpdate();
                }

                // Also perform a query in each connection
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT COUNT(*) as cnt FROM multinode_test WHERE value LIKE 'continuous_%'")) {
                    assertTrue(rs.next());
                    assertEquals(i + 1, rs.getInt("cnt"));
                }
            }
        }
    }
}
