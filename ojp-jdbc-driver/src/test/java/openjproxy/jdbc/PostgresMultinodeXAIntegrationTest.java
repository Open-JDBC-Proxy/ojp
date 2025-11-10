package openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.xa.OjpXADataSource;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests for XA transaction support with PostgreSQL in multinode setups.
 * These tests require:
 * 1. Two running OJP servers (localhost:10591 and localhost:10592)
 * 2. A PostgreSQL database with XA support (max_prepared_transactions > 0)
 * 3. Multinode configuration with cluster health tracking
 * 
 * This test validates that Atomikos XA pools properly adapt their size when servers
 * go down and come back up in a multinode cluster.
 */
@Slf4j
public class PostgresMultinodeXAIntegrationTest {

    private static boolean isTestDisabled;
    private XAConnection xaConnection;
    private Connection connection;

    @BeforeAll
    public static void checkTestConfiguration() {
        // Enable only when multinodeTestsEnabled is true
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("multinodeTestsEnabled", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(isTestDisabled, "Multinode XA tests are disabled. Enable with -DmultinodeTestsEnabled=true");
        
        log.info("Setting up multinode XA test with URL: {}", url);
        
        // Create XA DataSource
        OjpXADataSource xaDataSource = new OjpXADataSource();
        xaDataSource.setUrl(url);
        xaDataSource.setUser(user);
        xaDataSource.setPassword(password);
        
        // Get XA Connection
        xaConnection = xaDataSource.getXAConnection(user, password);
        connection = xaConnection.getConnection();
        
        log.info("Successfully created XA connection for multinode test");
    }

    @AfterEach
    public void tearDown() {
        TestDBUtils.closeQuietly(connection);
        if (xaConnection != null) {
            try {
                xaConnection.close();
            } catch (Exception e) {
                log.warn("Error closing XA connection: {}", e.getMessage());
            }
        }
    }

    /**
     * Test basic XA connection creation and closure in multinode setup.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/multinode_xa_connection.csv")
    public void testXAConnectionBasics(String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);
        
        assertNotNull(xaConnection, "XA connection should be created");
        assertNotNull(connection, "Logical connection should be created");
        assertFalse(connection.isClosed(), "Connection should not be closed");
        
        // Get XA Resource
        XAResource xaResource = xaConnection.getXAResource();
        assertNotNull(xaResource, "XA resource should not be null");
        
        // Verify connection is not auto-commit (XA connections should never be auto-commit)
        assertFalse(connection.getAutoCommit(), "XA connection should not be auto-commit");
        
        log.info("✓ XA connection basics test passed in multinode setup");
    }

    /**
     * Test XA transaction with simple CRUD operations in multinode setup.
     * This tests: xaStart -> executeUpdate -> xaEnd -> xaPrepare -> xaCommit
     * 
     * This test runs continuously while servers are being restarted in the workflow,
     * validating that XA pool recreation doesn't disrupt active transactions.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/multinode_xa_connection.csv")
    public void testXATransactionWithCRUD(String driverClass, String url, String user, String password) throws Exception {
        // Run this test twice to increase pool connection activity
        for (int iteration = 0; iteration < 2; iteration++) {
            setUp(driverClass, url, user, password);
            
            XAResource xaResource = xaConnection.getXAResource();
            
            // Create test table (use the same OJP URL for setup)
            try (Connection setupConn = java.sql.DriverManager.getConnection(url, user, password)) {
                Statement stmt = setupConn.createStatement();
                stmt.execute("DROP TABLE IF EXISTS xa_multinode_test_table");
            stmt.execute("CREATE TABLE xa_multinode_test_table (id SERIAL PRIMARY KEY, value VARCHAR(100))");
            log.info("Created test table for multinode XA test");
        }
        
        // Run multiple XA transactions to test pool behavior during server changes
        for (int i = 0; i < 20; i++) {
            // Generate unique XID for this transaction
            byte[] gtrid = ("gtrid-multinode-" + System.currentTimeMillis() + "-" + i).getBytes();
            byte[] bqual = ("bqual-multinode-" + i).getBytes();
            Xid xid = new javax.transaction.xa.Xid() {
                @Override
                public int getFormatId() { return 1; }
                @Override
                public byte[] getGlobalTransactionId() { return gtrid; }
                @Override
                public byte[] getBranchQualifier() { return bqual; }
            };
            
            // Start XA transaction
            xaResource.start(xid, XAResource.TMNOFLAGS);
            
            // Insert data
            PreparedStatement pstmt = connection.prepareStatement(
                    "INSERT INTO xa_multinode_test_table (value) VALUES (?)");
            pstmt.setString(1, "test-value-" + i);
            int rowsInserted = pstmt.executeUpdate();
            assertEquals(1, rowsInserted, "Should insert 1 row");
            pstmt.close();
            
            // End XA transaction
            xaResource.end(xid, XAResource.TMSUCCESS);
            
            // Prepare
            int prepareResult = xaResource.prepare(xid);
            assertEquals(XAResource.XA_OK, prepareResult, "Prepare should succeed");
            
            // Commit
            xaResource.commit(xid, false);
            
            log.info("✓ XA transaction {} completed successfully in multinode setup", i);
            
            // Small delay to allow time for server changes in the workflow
            Thread.sleep(100);
        }
        
        // Verify all rows were inserted (use the same OJP URL)
        try (Connection verifyConn = java.sql.DriverManager.getConnection(url, user, password)) {
            Statement stmt = verifyConn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM xa_multinode_test_table");
            assertTrue(rs.next(), "Should have result");
            assertEquals(20, rs.getInt(1), "Should have 20 rows inserted");
            log.info("✓ All 20 XA transactions committed successfully in multinode setup (iteration " + (iteration + 1) + ")");
        }
        
        // Cleanup
        try (Connection cleanupConn = java.sql.DriverManager.getConnection(url, user, password)) {
            Statement stmt = cleanupConn.createStatement();
            stmt.execute("DROP TABLE IF EXISTS xa_multinode_test_table");
        }
            tearDown();
        }
    }

    /**
     * Test XA transaction rollback in multinode setup.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/multinode_xa_connection.csv")
    public void testXATransactionRollback(String driverClass, String url, String user, String password) throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            setUp(driverClass, url, user, password);
        
        XAResource xaResource = xaConnection.getXAResource();
        
        // Create test table (use the same OJP URL)
        try (Connection setupConn = java.sql.DriverManager.getConnection(url, user, password)) {
            Statement stmt = setupConn.createStatement();
            stmt.execute("DROP TABLE IF EXISTS xa_multinode_rollback_test");
            stmt.execute("CREATE TABLE xa_multinode_rollback_test (id SERIAL PRIMARY KEY, value VARCHAR(100))");
        }
        
        // Generate unique XID
        byte[] gtrid = ("gtrid-multinode-rollback-" + System.currentTimeMillis()).getBytes();
        byte[] bqual = "bqual-multinode-rollback".getBytes();
        Xid xid = new javax.transaction.xa.Xid() {
            @Override
            public int getFormatId() { return 1; }
            @Override
            public byte[] getGlobalTransactionId() { return gtrid; }
            @Override
            public byte[] getBranchQualifier() { return bqual; }
        };
        
        // Start XA transaction
        xaResource.start(xid, XAResource.TMNOFLAGS);
        
        // Insert data
        PreparedStatement pstmt = connection.prepareStatement(
                "INSERT INTO xa_multinode_rollback_test (value) VALUES (?)");
        pstmt.setString(1, "test-rollback-value");
        pstmt.executeUpdate();
        pstmt.close();
        
        // End XA transaction
        xaResource.end(xid, XAResource.TMSUCCESS);
        
        // Prepare
        xaResource.prepare(xid);
        
        // Rollback instead of commit
        xaResource.rollback(xid);
        
        log.info("✓ XA transaction rolled back in multinode setup");
        
        // Verify no rows were inserted (use the same OJP URL)
        try (Connection verifyConn = java.sql.DriverManager.getConnection(url, user, password)) {
            Statement stmt = verifyConn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM xa_multinode_rollback_test");
            assertTrue(rs.next(), "Should have result");
            assertEquals(0, rs.getInt(1), "Should have 0 rows after rollback");
            log.info("✓ XA rollback verified - no rows inserted (iteration " + (iteration + 1) + ")");
        }
        
        // Cleanup
        try (Connection cleanupConn = java.sql.DriverManager.getConnection(url, user, password)) {
            Statement stmt = cleanupConn.createStatement();
            stmt.execute("DROP TABLE IF EXISTS xa_multinode_rollback_test");
        }
            tearDown();
        }
    }
}
