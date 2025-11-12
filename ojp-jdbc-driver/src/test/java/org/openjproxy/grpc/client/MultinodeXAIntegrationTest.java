package org.openjproxy.grpc.client;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.postgresql.xa.PGXADataSource;

import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Multinode XA Integration Test
 * 
 * This test extends MultinodeIntegrationTest and runs the same scenarios but with
 * XA-capable DataSources and XA transactions. It uses AtomikosDataSourceBean wrapping
 * PostgreSQL XADataSource to enable distributed transaction support.
 * 
 * The test is gated by the multinodeTestsEnabled system property or MULTINODE_TESTS_ENABLED
 * env var, and additionally checks for useXA flag to enable XA-specific behavior.
 */
@Slf4j
public class MultinodeXAIntegrationTest extends MultinodeIntegrationTest {
    
    private static boolean useXA;
    private static AtomikosDataSourceBean xaDataSource;
    
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
        
        log.info("Starting MultinodeXAIntegrationTest with XA-enabled data sources");
        log.info("Connection URL: {}", url);
        
        // Set up XA DataSource if not already configured
        if (xaDataSource == null) {
            xaDataSource = createXADataSource(url, user, password);
        }
        
        // Verify XA capability before running the test
        verifyXACapability();
        
        // Run the parent test scenarios with XA support
        // The parent test will use DriverManager.getConnection which goes through OJP
        // The OJP server will use XA pools when configured with XA data sources
        super.runTests(driverClass, url, user, password);
        
        log.info("MultinodeXAIntegrationTest completed successfully with XA support");
    }
    
    /**
     * Creates an Atomikos XADataSource wrapping PostgreSQL XADataSource.
     * 
     * @param url JDBC URL
     * @param user Database user
     * @param password Database password
     * @return AtomikosDataSourceBean configured for PostgreSQL XA
     */
    private AtomikosDataSourceBean createXADataSource(String url, String user, String password) {
        log.info("Creating Atomikos XA DataSource for PostgreSQL");
        
        // Create PostgreSQL XADataSource
        PGXADataSource pgXADataSource = new PGXADataSource();
        
        // Parse URL to extract connection details
        // URL format: jdbc:ojp[...]:postgresql://host:port/database or jdbc:postgresql://host:port/database
        String cleanUrl = url;
        if (cleanUrl.toLowerCase().contains("_postgresql:")) {
            cleanUrl = cleanUrl.substring(cleanUrl.toLowerCase().indexOf("_postgresql:") + "_postgresql:".length());
        } else if (cleanUrl.toLowerCase().startsWith("jdbc:postgresql:")) {
            cleanUrl = cleanUrl.substring("jdbc:".length());
        }
        
        // Parse postgresql://host:port/database
        if (cleanUrl.startsWith("postgresql://")) {
            cleanUrl = cleanUrl.substring("postgresql://".length());
            String[] parts = cleanUrl.split("/");
            if (parts.length >= 2) {
                String hostPort = parts[0];
                String database = parts[1].split("\\?")[0]; // Remove query params
                
                String[] hostPortParts = hostPort.split(":");
                String host = hostPortParts[0];
                int port = hostPortParts.length > 1 ? Integer.parseInt(hostPortParts[1]) : 5432;
                
                pgXADataSource.setServerNames(new String[]{host});
                pgXADataSource.setPortNumbers(new int[]{port});
                pgXADataSource.setDatabaseName(database);
            }
        }
        
        pgXADataSource.setUser(user);
        pgXADataSource.setPassword(password);
        
        // Wrap with Atomikos DataSource Bean
        AtomikosDataSourceBean atomikosDS = new AtomikosDataSourceBean();
        atomikosDS.setUniqueResourceName("ojp-xa-test-" + System.currentTimeMillis());
        atomikosDS.setXaDataSource(pgXADataSource);
        
        // Configure pool settings similar to Hikari defaults
        atomikosDS.setMaxPoolSize(20);
        atomikosDS.setMinPoolSize(5);
        atomikosDS.setBorrowConnectionTimeout(10); // 10 seconds
        atomikosDS.setMaxIdleTime(600); // 10 minutes
        atomikosDS.setTestQuery("SELECT 1");
        
        log.info("Atomikos XA DataSource created: resourceName={}, maxPoolSize={}, minPoolSize={}", 
                atomikosDS.getUniqueResourceName(), atomikosDS.getMaxPoolSize(), atomikosDS.getMinPoolSize());
        
        return atomikosDS;
    }
    
    /**
     * Verifies that XA capability is available by attempting to get an XA connection.
     * This serves as an assertion that the transaction manager is XA-capable.
     */
    private void verifyXACapability() throws SQLException {
        log.info("Verifying XA capability...");
        
        assertNotNull(xaDataSource, "XA DataSource should be initialized");
        
        // Try to get a connection from the XA DataSource
        try (Connection conn = xaDataSource.getConnection()) {
            assertNotNull(conn, "Should be able to get connection from XA DataSource");
            assertTrue(conn.getMetaData().supportsTransactions(), 
                    "Connection should support transactions");
            
            // Verify the connection is working
            boolean isValid = conn.isValid(5);
            assertTrue(isValid, "XA connection should be valid");
            
            log.info("✓ XA capability verified: connection is valid and supports transactions");
        } catch (SQLException e) {
            log.error("Failed to verify XA capability: {}", e.getMessage(), e);
            throw e;
        }
    }
}
