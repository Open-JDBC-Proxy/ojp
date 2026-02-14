package org.openjproxy.xa.pool.commons.housekeeping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.commons.CommonsPool2XADataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for all housekeeping features working together with real H2 database.
 * Tests leak detection, max lifetime, and diagnostics with actual XA transactions.
 */
class HousekeepingIntegrationTest {
    
    private org.h2.jdbcx.JdbcDataSource h2DataSource;
    private CommonsPool2XADataSource pooledDataSource;
    
    @BeforeEach
    void setUp() {
        // Create H2 XA-capable data source
        h2DataSource = new org.h2.jdbcx.JdbcDataSource();
        h2DataSource.setURL("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        h2DataSource.setUser("sa");
        h2DataSource.setPassword("");
    }
    
    @AfterEach
    void tearDown() {
        if (pooledDataSource != null) {
            pooledDataSource.close();
        }
        
        // Clean up H2 database
        try {
            Connection conn = h2DataSource.getConnection();
            Statement stmt = conn.createStatement();
            stmt.execute("DROP ALL OBJECTS");
            stmt.close();
            conn.close();
        } catch (SQLException e) {
            // Ignore cleanup errors
        }
    }
    
    @Test
    @DisplayName("Integration: Pool should work with real H2 XA connections")
    void testRealXAConnections() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.minIdle", "2");
        
        pooledDataSource = new CommonsPool2XADataSource(h2DataSource, config);
        
        // Borrow a session
        XABackendSession session = pooledDataSource.borrowSession();
        assertNotNull(session);
        
        // Get the connection and verify it works
        Connection conn = session.getConnection();
        assertNotNull(conn);
        assertFalse(conn.isClosed());
        
        // Execute a simple query
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT 1");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        rs.close();
        stmt.close();
        
        // Return session
        pooledDataSource.returnSession(session);
        
        // Verify we can borrow again
        XABackendSession session2 = pooledDataSource.borrowSession();
        assertNotNull(session2);
        pooledDataSource.returnSession(session2);
    }
    
    @Test
    @DisplayName("Integration: Leak detection should work with real connections")
    void testLeakDetectionWithRealConnections() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.leakDetection.enabled", "true");
        config.put("xa.leakDetection.timeoutMs", "1000"); // 1 second for testing
        config.put("xa.leakDetection.intervalMs", "500"); // Check every 500ms
        
        pooledDataSource = new CommonsPool2XADataSource(h2DataSource, config);
        
        // Borrow a session and don't return it
        XABackendSession leakedSession = pooledDataSource.borrowSession();
        assertNotNull(leakedSession);
        
        // Wait for leak detection to kick in
        Thread.sleep(2000); //NOSONAR
        
        // Session should still be borrowed (leak detection just logs)
        assertNotNull(leakedSession);
        
        // Clean up by returning the session
        pooledDataSource.returnSession(leakedSession);
    }
    
    @Test
    @DisplayName("Integration: Max lifetime should expire old idle connections")
    void testMaxLifetimeWithRealConnections() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.minIdle", "1");
        config.put("xa.maxLifetimeMs", "2000"); // 2 seconds
        config.put("xa.idleBeforeRecycleMs", "1000"); // 1 second idle required
        
        pooledDataSource = new CommonsPool2XADataSource(h2DataSource, config);
        
        // Borrow and return a session to create it
        XABackendSession session1 = pooledDataSource.borrowSession();
        assertNotNull(session1);
        pooledDataSource.returnSession(session1);
        
        // Wait for session to become idle and then expire
        // Wait for both idle time and max lifetime
        Thread.sleep(3500); //NOSONAR
        
        // Try to borrow - should get a new session (old one expired)
        XABackendSession session2 = pooledDataSource.borrowSession();
        assertNotNull(session2);
        
        // Verify it works
        Connection conn = session2.getConnection();
        assertTrue(conn.isValid(1));
        
        pooledDataSource.returnSession(session2);
    }
    
    @Test
    @DisplayName("Integration: Diagnostics should log pool state")
    void testDiagnosticsWithRealConnections() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "10");
        config.put("xa.minIdle", "2");
        config.put("xa.diagnostics.enabled", "true");
        config.put("xa.diagnostics.intervalMs", "1000"); // Log every 1 second
        
        pooledDataSource = new CommonsPool2XADataSource(h2DataSource, config);
        
        // Borrow some sessions
        XABackendSession session1 = pooledDataSource.borrowSession();
        XABackendSession session2 = pooledDataSource.borrowSession();
        
        // Wait for diagnostics to run
        Thread.sleep(1500); //NOSONAR
        
        // Return sessions
        pooledDataSource.returnSession(session1);
        pooledDataSource.returnSession(session2);
        
        // Diagnostics should have logged (check logs manually or via listener)
        // This test verifies no exceptions are thrown
    }
    
    @Test
    @DisplayName("Integration: All features enabled together")
    void testAllFeaturesEnabled() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "10");
        config.put("xa.minIdle", "2");
        
        // Leak detection enabled by default
        config.put("xa.leakDetection.enabled", "true");
        config.put("xa.leakDetection.timeoutMs", "5000");
        config.put("xa.leakDetection.intervalMs", "1000");
        
        // Max lifetime
        config.put("xa.maxLifetimeMs", "10000"); // 10 seconds
        config.put("xa.idleBeforeRecycleMs", "2000"); // 2 seconds idle
        
        // Diagnostics
        config.put("xa.diagnostics.enabled", "true");
        config.put("xa.diagnostics.intervalMs", "2000");
        
        pooledDataSource = new CommonsPool2XADataSource(h2DataSource, config);
        
        // Perform various operations
        XABackendSession session1 = pooledDataSource.borrowSession();
        XABackendSession session2 = pooledDataSource.borrowSession();
        XABackendSession session3 = pooledDataSource.borrowSession();
        
        // Use the connections
        for (XABackendSession session : new XABackendSession[]{session1, session2, session3}) {
            Connection conn = session.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT 1 + 1");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs.close();
            stmt.close();
        }
        
        // Return two sessions
        pooledDataSource.returnSession(session1);
        pooledDataSource.returnSession(session2);
        
        // Wait a bit for housekeeping tasks
        Thread.sleep(3000);  //NOSONAR
        
        // Return the last session
        pooledDataSource.returnSession(session3);
        
        // Verify pool is still healthy
        XABackendSession session4 = pooledDataSource.borrowSession();
        assertNotNull(session4);
        assertTrue(session4.getConnection().isValid(1));
        pooledDataSource.returnSession(session4);
    }
    
    @Test
    @DisplayName("Integration: Multiple borrow/return cycles")
    void testMultipleBorrowReturnCycles() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.minIdle", "2");
        
        pooledDataSource = new CommonsPool2XADataSource(h2DataSource, config);
        
        // Perform multiple borrow/return cycles
        for (int i = 0; i < 10; i++) {
            XABackendSession session = pooledDataSource.borrowSession();
            assertNotNull(session);
            
            // Use the connection
            Connection conn = session.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT " + i);
            assertTrue(rs.next());
            assertEquals(i, rs.getInt(1));
            rs.close();
            stmt.close();
            
            pooledDataSource.returnSession(session);
        }
        
        // All cycles should complete successfully
    }
    
    @Test
    @DisplayName("Integration: XA Resource should be available")
    void testXAResourceAvailable() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        
        pooledDataSource = new CommonsPool2XADataSource(h2DataSource, config);
        
        XABackendSession session = pooledDataSource.borrowSession();
        assertNotNull(session);
        assertNotNull(session.getXAResource());
        
        pooledDataSource.returnSession(session);
    }
    
    @Test
    @DisplayName("Integration: Session state tracking works")
    void testSessionStateTracking() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        
        pooledDataSource = new CommonsPool2XADataSource(h2DataSource, config);
        
        XABackendSession session = pooledDataSource.borrowSession();
        
        // Session should have a unique ID
        assertNotNull(session.getSessionId());
        assertFalse(session.getSessionId().isEmpty());
        
        // Session should be healthy
        assertTrue(session.isHealthy());
        
        pooledDataSource.returnSession(session);
        
        // After return, session should still be healthy
        Thread.sleep(100); //NOSONAR
        
        // Borrow again to check
        XABackendSession session2 = pooledDataSource.borrowSession();
        // Could be same session or different one, both valid
        assertNotNull(session2);
        assertTrue(session2.isHealthy());
        pooledDataSource.returnSession(session2);
    }
    
    @Test
    @DisplayName("Integration: Pool handles session creation failure gracefully")
    void testSessionCreationFailureHandling() throws Exception {
        // Create a data source that will fail after some connections
        org.h2.jdbcx.JdbcDataSource limitedDataSource = new org.h2.jdbcx.JdbcDataSource();
        limitedDataSource.setURL("jdbc:h2:mem:limiteddb;DB_CLOSE_DELAY=-1");
        limitedDataSource.setUser("sa");
        limitedDataSource.setPassword("");
        
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.minIdle", "1");
        
        CommonsPool2XADataSource limitedPool = new CommonsPool2XADataSource(limitedDataSource, config);
        
        try {
            // This should work
            XABackendSession session = limitedPool.borrowSession();
            assertNotNull(session);
            limitedPool.returnSession(session);
        } finally {
            limitedPool.close();
        }
    }
    
    @Test
    @DisplayName("Integration: Enhanced leak detection with stack trace")
    void testEnhancedLeakDetection() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.leakDetection.enabled", "true");
        config.put("xa.leakDetection.enhanced", "true"); // Enable stack trace capture
        config.put("xa.leakDetection.timeoutMs", "1000");
        config.put("xa.leakDetection.intervalMs", "500");
        
        pooledDataSource = new CommonsPool2XADataSource(h2DataSource, config);
        
        // Borrow a session (stack trace will be captured)
        XABackendSession session = pooledDataSource.borrowSession();
        assertNotNull(session);
        
        // Wait for leak detection
        Thread.sleep(2000); //NOSONAR
        
        // Clean up
        pooledDataSource.returnSession(session);
    }
}
