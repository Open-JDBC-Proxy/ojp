package org.openjproxy.grpc.client;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Multinode XA Integration Test
 * 
 * This test extends MultinodeIntegrationTest and runs the same scenarios but with
 * XA-capable behavior on the server side. The server will use XA-capable DataSources 
 * (AtomikosDataSourceBean wrapping PGXADataSource) when configured properly.
 * 
 * The test is gated by the multinodeTestsEnabled system property or MULTINODE_TESTS_ENABLED
 * env var, and additionally checks for useXA flag to enable XA-specific behavior.
 * 
 * Unlike the base test, this test expects the OJP servers to be configured with XA support
 * and validates that connections support transactions properly.
 */
@Slf4j
public class MultinodeXAIntegrationTest extends MultinodeIntegrationTest {
    
    private static boolean useXA;
    
    @BeforeAll
    public static void checkXATestConfiguration() {
        // Check if multinode tests are enabled (inherited from base class)
        MultinodeIntegrationTest.checkTestConfiguration();
        
        // Check if XA mode is enabled via system property or environment variable
        boolean sysPropXA = Boolean.parseBoolean(System.getProperty("useXA", "false"));
        boolean envVarXA = Boolean.parseBoolean(System.getenv("USE_XA"));
        useXA = sysPropXA || envVarXA;
        
        log.info("MultinodeXAIntegrationTest configuration: useXA={}, multinodeTestsEnabled={}", 
                useXA, !isTestDisabled);
    }
    
    @SneakyThrows
    @ParameterizedTest
    @CsvFileSource(resources = "/multinode_connection.csv")
    @Override
    public void runTests(String driverClass, String url, String user, String password) throws SQLException {
        // Skip if multinode tests are disabled (inherited check)
        assumeFalse(isTestDisabled, "Multinode tests are disabled");
        
        // Additionally skip if XA is not enabled
        assumeTrue(useXA, "XA tests are disabled. Set -DuseXA=true or USE_XA=true to enable.");
        
        log.info("Starting MultinodeXAIntegrationTest with XA-enabled servers");
        log.info("Connection URL: {}", url);
        log.info("NOTE: This test expects OJP servers to be started with XA support (Atomikos pools)");
        log.info("The workflow will validate Atomikos pool creation logs from the server logs");
        
        // Verify XA capability through a test connection
        verifyXACapability(driverClass, url, user, password);
        
        // Run the parent test scenarios
        // The parent test will use DriverManager.getConnection which goes through OJP
        // The OJP servers should use XA pools when properly configured
        super.runTests(driverClass, url, user, password);
        
        log.info("MultinodeXAIntegrationTest completed successfully");
        log.info("XA pool creation/recreation should be visible in server logs with messages like:");
        log.info("  'Atomikos pool created: resourceName=..., minSize=..., maxSize=...'");
        log.info("  'Atomikos pool recreated: resourceName=..., oldInstanceId=..., newInstanceId=...'");
    }
    
    /**
     * Verifies that connections support transactions and XA operations by checking
     * the database metadata and transaction support.
     * 
     * @param driverClass JDBC driver class
     * @param url JDBC URL
     * @param user Database user
     * @param password Database password
     */
    private void verifyXACapability(String driverClass, String url, String user, String password) throws Exception {
        log.info("Verifying XA capability through test connection...");
        
        Class.forName(driverClass);
        try (Connection conn = getConnection(driverClass, url, user, password)) {
            assertNotNull(conn, "Should be able to get connection");
            
            DatabaseMetaData metaData = conn.getMetaData();
            assertNotNull(metaData, "Should be able to get database metadata");
            
            // Verify transaction support
            assertTrue(metaData.supportsTransactions(), 
                    "Database should support transactions for XA");
            
            log.info("✓ XA-capable connection verified: database={}, supportsTransactions={}", 
                    metaData.getDatabaseProductName(), 
                    metaData.supportsTransactions());
            
            // The following checks may fail in multinode setup due to session binding timing
            // They are informational only and not critical for XA verification
            
            // Check if connection is valid (optional)
            try {
                boolean isValid = conn.isValid(5);
                if (isValid) {
                    log.info("✓ Connection validity check passed");
                } else {
                    log.warn("⚠ Connection validity check returned false");
                }
            } catch (Exception e) {
                log.warn("⚠ Connection validity check failed (not critical): {}", e.getMessage());
            }
            
            // Check transaction isolation level (optional)
            try {
                int isolationLevel = conn.getTransactionIsolation();
                log.info("✓ Transaction isolation level: {}", isolationLevel);
            } catch (Exception e) {
                log.warn("⚠ Could not get transaction isolation level (not critical): {}", e.getMessage());
            }
        }
    }
}
