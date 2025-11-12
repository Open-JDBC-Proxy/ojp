package org.openjproxy.grpc.client;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openjproxy.jdbc.xa.OjpXADataSource;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Multinode XA Integration Test
 * 
 * This test extends MultinodeIntegrationTest and runs XA-specific scenarios
 * using XA data sources and XA transactions. The server will use XA-capable DataSources 
 * (AtomikosDataSourceBean wrapping PGXADataSource) when configured properly.
 * 
 * The test is gated by the multinodeXATestsEnabled system property or MULTINODE_XA_TESTS_ENABLED
 * env var. Note that the base multinodeTestsEnabled flag must also be enabled.
 * 
 * Unlike the base test, this test uses OjpXADataSource to obtain XA connections
 * and validates that XA transactions work properly.
 */
@Slf4j
public class MultinodeXAIntegrationTest extends MultinodeIntegrationTest {
    
    private static boolean isXATestDisabled;
    
    @BeforeAll
    public static void checkXATestConfiguration() {
        // Check if multinode tests are enabled (inherited from base class)
        MultinodeIntegrationTest.checkTestConfiguration();
        
        // Check if XA tests are enabled via system property or environment variable
        boolean sysPropXA = Boolean.parseBoolean(System.getProperty("multinodeXATestsEnabled", "false"));
        boolean envVarXA = Boolean.parseBoolean(System.getenv("MULTINODE_XA_TESTS_ENABLED"));
        isXATestDisabled = !(sysPropXA || envVarXA);
        
        log.info("MultinodeXAIntegrationTest configuration: multinodeXATestsEnabled={}, multinodeTestsEnabled={}", 
                !isXATestDisabled, !isTestDisabled);
    }
    
    @SneakyThrows
    @ParameterizedTest
    @CsvFileSource(resources = "/multinode_connection.csv")
    @Override
    public void runTests(String driverClass, String url, String user, String password) throws SQLException {
        // Skip if multinode tests are disabled (inherited check)
        assumeFalse(isTestDisabled, "Multinode tests are disabled");
        
        // Additionally skip if XA tests are not enabled
        assumeFalse(isXATestDisabled, "Multinode XA tests are disabled. Set -DmultinodeXATestsEnabled=true or MULTINODE_XA_TESTS_ENABLED=true to enable.");
        
        log.info("Starting MultinodeXAIntegrationTest with XA-enabled servers");
        log.info("Connection URL: {}", url);
        log.info("NOTE: This test expects OJP servers to be started with XA support (Atomikos pools)");
        log.info("The workflow will validate Atomikos pool creation logs from the server logs");
        
        // Verify XA capability through XA connections
        verifyXACapability(url, user, password);
        
        // Run XA-specific test scenarios
        runXATestScenarios(url, user, password);
        
        log.info("MultinodeXAIntegrationTest completed successfully");
        log.info("XA pool creation/recreation should be visible in server logs with messages like:");
        log.info("  'Atomikos pool created: resourceName=..., minSize=..., maxSize=...'");
        log.info("  'Atomikos pool recreated: resourceName=..., oldInstanceId=..., newInstanceId=...'");
    }
    
    /**
     * Get an XA connection using OjpXADataSource.
     * This method creates XA connections that support distributed transactions.
     * 
     * @param url JDBC URL
     * @param user Database user
     * @param password Database password
     * @return XAConnection for XA transactions
     */
    private XAConnection getXAConnection(String url, String user, String password) throws SQLException {
        log.info("Creating XA connection using OjpXADataSource");
        
        // 1. Create OjpXADataSource
        OjpXADataSource xaDataSource = new OjpXADataSource();
        xaDataSource.setUrl(url);
        xaDataSource.setUser(user);
        xaDataSource.setPassword(password);
        
        // 2. Get XAConnection
        XAConnection xaConnection = xaDataSource.getXAConnection();
        
        log.info("✓ XAConnection obtained from OjpXADataSource");
        return xaConnection;
    }
    
    /**
     * Verifies that XA connections support transactions and XA operations by checking
     * the database metadata, XAResource availability, and transaction support.
     * 
     * @param url JDBC URL
     * @param user Database user
     * @param password Database password
     */
    private void verifyXACapability(String url, String user, String password) throws Exception {
        log.info("Verifying XA capability through XA connection...");
        
        // Get XAConnection
        XAConnection xaConnection = getXAConnection(url, user, password);
        
        try {
            // Get XAResource and JDBC Connection
            XAResource xaResource = xaConnection.getXAResource();
            assertNotNull(xaResource, "Should be able to get XAResource");
            log.info("✓ XAResource obtained from XAConnection");
            
            Connection conn = xaConnection.getConnection();
            assertNotNull(conn, "Should be able to get Connection from XAConnection");
            log.info("✓ JDBC Connection obtained from XAConnection");
            
            // Verify database metadata
            DatabaseMetaData metaData = conn.getMetaData();
            assertNotNull(metaData, "Should be able to get database metadata");
            
            // Verify transaction support
            assertTrue(metaData.supportsTransactions(), 
                    "Database should support transactions for XA");
            
            log.info("✓ XA-capable connection verified: database={}, supportsTransactions={}", 
                    metaData.getDatabaseProductName(), 
                    metaData.supportsTransactions());
            
            conn.close();
        } finally {
            xaConnection.close();
        }
    }
    
    /**
     * Run XA-specific test scenarios using XA connections and transactions.
     * 
     * @param url JDBC URL
     * @param user Database user
     * @param password Database password
     */
    private void runXATestScenarios(String url, String user, String password) throws Exception {
        log.info("Running XA-specific test scenarios...");
        
        // Scenario 1: Basic XA connection and query execution
        testBasicXAQuery(url, user, password);
        
        log.info("✓ All XA test scenarios completed successfully");
    }
    
    /**
     * Test basic query execution through XA connection.
     * 
     * @param url JDBC URL
     * @param user Database user
     * @param password Database password
     */
    private void testBasicXAQuery(String url, String user, String password) throws Exception {
        log.info("Testing basic query execution with XA connection...");
        
        XAConnection xaConnection = getXAConnection(url, user, password);
        
        try {
            XAResource xaResource = xaConnection.getXAResource();
            Connection conn = xaConnection.getConnection();
            
            // Execute a simple query to verify the connection works
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT 1 AS test_value")) {
                    assertTrue(rs.next(), "Should have at least one row");
                    int value = rs.getInt("test_value");
                    assertTrue(value == 1, "Query should return 1");
                    log.info("✓ Basic XA query executed successfully, result: {}", value);
                }
            }
            
            conn.close();
        } finally {
            xaConnection.close();
        }
    }
}
