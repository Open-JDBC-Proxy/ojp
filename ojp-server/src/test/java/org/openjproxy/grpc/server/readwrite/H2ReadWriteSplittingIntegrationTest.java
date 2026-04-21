package org.openjproxy.grpc.server.readwrite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.readwrite.ReadWriteConfiguration.ReplicaSelectionStrategy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for read/write traffic splitting using H2 in-memory databases.
 * 
 * <h2>Test Strategy: Dual Unsynchronized H2 Databases</h2>
 * 
 * <p>
 * These tests use an innovative approach with <b>two separate, intentionally UNSYNCHRONIZED</b>
 * H2 in-memory databases to validate read/write routing behavior. This eliminates the need for
 * real database replication setup while still providing deterministic end-to-end validation.
 * </p>
 * 
 * <h3>Database Setup</h3>
 * <ul>
 *   <li><b>Primary Database</b> (rw_int_primary): Contains id=1, name="PRIMARY_DATA", source="primary"</li>
 *   <li><b>Replica Database</b> (rw_int_replica): Contains id=2, name="REPLICA_DATA", source="replica"</li>
 * </ul>
 * 
 * <h3>Key Insight: Leveraging Non-Synchronization</h3>
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
 * <h3>Test Coverage</h3>
 * <ul>
 *   <li>Write routing to primary (INSERT, UPDATE, DELETE)</li>
 *   <li>Read routing to replica (SELECT)</li>
 *   <li>Multiple read consistency</li>
 *   <li>Failover to primary when no replicas</li>
 *   <li>UNKNOWN SQL routing to primary (safety)</li>
 * </ul>
 * 
 * <h3>Test Execution</h3>
 * <p>
 * Tests only run when <code>-DenableH2Tests=true</code> is passed to Maven, following OJP
 * testing standards for H2 integration tests.
 * </p>
 * 
 * @see ReadWriteRouter
 * @see ReplicaSelector
 * @see SqlClassifier
 */
class H2ReadWriteSplittingIntegrationTest {
    
    private static boolean isH2TestEnabled;
    
    private DataSource primaryDataSource;
    private DataSource replicaDataSource;
    private ReadWriteRouter router;
    private ReplicaSelector replicaSelector;
    private SqlClassifier sqlClassifier;
    
    @BeforeAll
    static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
        if (!isH2TestEnabled) {
            System.out.println("H2 tests are disabled. Use -DenableH2Tests=true to enable.");
        }
    }
    
    @BeforeEach
    void setUp() throws SQLException {
        // Create PRIMARY database (rw_int_primary)
        HikariConfig primaryConfig = new HikariConfig();
        primaryConfig.setJdbcUrl("jdbc:h2:mem:rw_int_primary;DB_CLOSE_DELAY=-1");
        primaryConfig.setUsername("SA");
        primaryConfig.setPassword("");
        primaryConfig.setMaximumPoolSize(5);
        primaryDataSource = new HikariDataSource(primaryConfig);
        
        // Create REPLICA database (rw_int_replica)
        HikariConfig replicaConfig = new HikariConfig();
        replicaConfig.setJdbcUrl("jdbc:h2:mem:rw_int_replica;DB_CLOSE_DELAY=-1");
        replicaConfig.setUsername("SA");
        replicaConfig.setPassword("");
        replicaConfig.setMaximumPoolSize(5);
        replicaDataSource = new HikariDataSource(replicaConfig);
        
        // Initialize schema and data in PRIMARY
        try (Connection conn = primaryDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_table");
            stmt.execute("CREATE TABLE test_table (id INT PRIMARY KEY, name VARCHAR(100), source VARCHAR(50))");
            stmt.executeUpdate("INSERT INTO test_table VALUES (1, 'PRIMARY_DATA', 'primary')");
        }
        
        // Initialize schema and data in REPLICA (different data!)
        try (Connection conn = replicaDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_table");
            stmt.execute("CREATE TABLE test_table (id INT PRIMARY KEY, name VARCHAR(100), source VARCHAR(50))");
            stmt.executeUpdate("INSERT INTO test_table VALUES (2, 'REPLICA_DATA', 'replica')");
        }
        
        // Initialize router and components
        sqlClassifier = new JSqlParserClassifier();
        replicaSelector = new RoundRobinReplicaSelector();
        router = new ReadWriteRouter(sqlClassifier, replicaSelector);
    }
    
    @AfterEach
    void tearDown() {
        if (primaryDataSource instanceof HikariDataSource) {
            ((HikariDataSource) primaryDataSource).close();
        }
        if (replicaDataSource instanceof HikariDataSource) {
            ((HikariDataSource) replicaDataSource).close();
        }
    }
    
    /**
     * Tests that write operations (INSERT, UPDATE, DELETE) route to the PRIMARY database.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Execute INSERT statement on router's selected datasource</li>
     *   <li>Verify written data exists in PRIMARY database</li>
     *   <li>Verify written data does NOT exist in REPLICA database</li>
     * </ol>
     * 
     * <p>
     * This test proves that write operations correctly route to the primary.
     * The verification step that checks the replica does NOT have the data works because
     * <b>the two H2 databases are not synchronized</b> - writes to primary stay in primary only.
     * </p>
     */
    @Test
    void testWriteGoesToPrimary() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        List<DataSource> replicas = new ArrayList<>();
        replicas.add(replicaDataSource);
        
        // SELECT the datasource for an INSERT (write operation)
        DataSource selectedDs = router.selectDataSource(
                null, // no session (not in transaction)
                "INSERT INTO test_table VALUES (100, 'WRITTEN_DATA', 'written')",
                primaryDataSource,
                replicas
        );
        
        // Verify router selected PRIMARY for write
        assertSame(primaryDataSource, selectedDs, "Write operations should route to primary");
        
        // Execute INSERT on the selected datasource
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO test_table VALUES (100, 'WRITTEN_DATA', 'written')");
        }
        
        // Verify data is in PRIMARY
        try (Connection conn = primaryDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table WHERE id = 100")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Written data should exist in primary");
        }
        
        // Verify data is NOT in REPLICA (proves databases are not synchronized)
        try (Connection conn = replicaDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table WHERE id = 100")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Written data should NOT exist in replica (databases not synchronized)");
        }
    }
    
    /**
     * Tests that read operations (SELECT) route to the REPLICA database.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Execute SELECT statement through router</li>
     *   <li>Verify router selects REPLICA datasource</li>
     *   <li>Query returns REPLICA data (id=2, source='replica')</li>
     *   <li>Query does NOT return PRIMARY data (id=1, source='primary')</li>
     * </ol>
     * 
     * <p>
     * This test proves that read operations correctly route to replicas,
     * offloading read traffic from the primary database. Since the databases
     * contain different data, we can deterministically verify routing by checking
     * which dataset we retrieve.
     * </p>
     */
    @Test
    void testReadGoesToReplica() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        List<DataSource> replicas = new ArrayList<>();
        replicas.add(replicaDataSource);
        
        // SELECT the datasource for a SELECT (read operation)
        DataSource selectedDs = router.selectDataSource(
                null, // no session (not in transaction)
                "SELECT * FROM test_table WHERE id = 2",
                primaryDataSource,
                replicas
        );
        
        // Verify router selected REPLICA for read
        assertSame(replicaDataSource, selectedDs, "Read operations should route to replica");
        
        // Query the selected datasource and verify we got REPLICA data
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT source FROM test_table WHERE id = 2")) {
            assertTrue(rs.next(), "Should find row with id=2 in replica");
            assertEquals("replica", rs.getString("source"), "Should get data from replica database");
        }
        
        // Verify we did NOT get PRIMARY data
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Should not find primary data when querying replica");
        }
    }
    
    /**
     * Tests that multiple consecutive read operations all route to REPLICA.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Execute 5 consecutive SELECT statements</li>
     *   <li>Verify ALL route to REPLICA datasource</li>
     *   <li>Verify consistent REPLICA data retrieval</li>
     * </ol>
     * 
     * <p>
     * This validates that read routing is consistent and doesn't accidentally
     * flip between primary and replica for non-transactional reads.
     * </p>
     */
    @Test
    void testMultipleReads_AllGoToReplica() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        List<DataSource> replicas = new ArrayList<>();
        replicas.add(replicaDataSource);
        
        // Execute 5 consecutive reads
        for (int i = 0; i < 5; i++) {
            DataSource selectedDs = router.selectDataSource(
                    null,
                    "SELECT * FROM test_table WHERE source = 'replica'",
                    primaryDataSource,
                    replicas
            );
            
            // Each should route to replica
            assertSame(replicaDataSource, selectedDs, 
                    "Read #" + (i+1) + " should route to replica");
            
            // Verify we get replica data
            try (Connection conn = selectedDs.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table WHERE id = 2")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), 
                        "Read #" + (i+1) + " should find replica data");
            }
        }
    }
    
    /**
     * Tests that UPDATE operations route to PRIMARY, not REPLICA.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Execute UPDATE statement through router</li>
     *   <li>Verify router selects PRIMARY datasource</li>
     *   <li>Verify UPDATE applies to primary database</li>
     *   <li>Verify replica remains unchanged</li>
     * </ol>
     * 
     * <p>
     * This ensures that all write operations (not just INSERT) correctly
     * route to the primary database to maintain data integrity.
     * </p>
     */
    @Test
    void testUpdateGoesToPrimary() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        List<DataSource> replicas = new ArrayList<>();
        replicas.add(replicaDataSource);
        
        // SELECT the datasource for an UPDATE (write operation)
        DataSource selectedDs = router.selectDataSource(
                null,
                "UPDATE test_table SET name = 'UPDATED' WHERE id = 1",
                primaryDataSource,
                replicas
        );
        
        // Verify router selected PRIMARY for write
        assertSame(primaryDataSource, selectedDs, "UPDATE operations should route to primary");
        
        // Execute UPDATE
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement()) {
            int rowsUpdated = stmt.executeUpdate("UPDATE test_table SET name = 'UPDATED' WHERE id = 1");
            assertEquals(1, rowsUpdated, "Should update 1 row in primary");
        }
        
        // Verify update applied to PRIMARY
        try (Connection conn = primaryDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM test_table WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("UPDATED", rs.getString("name"), "Primary should have updated data");
        }
        
        // Verify replica is unchanged (still has original data)
        try (Connection conn = replicaDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM test_table WHERE id = 2")) {
            assertTrue(rs.next());
            assertEquals("REPLICA_DATA", rs.getString("name"), "Replica should be unchanged");
        }
    }
    
    /**
     * Tests that when no replicas are available, reads fallback to PRIMARY.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Execute SELECT with empty replica list</li>
     *   <li>Verify router selects PRIMARY datasource</li>
     *   <li>Verify query returns PRIMARY data</li>
     * </ol>
     * 
     * <p>
     * This validates the failover behavior when replicas are unavailable,
     * ensuring reads can still proceed using the primary database.
     * </p>
     */
    @Test
    void testReadFallsBackToPrimary_WhenNoReplicas() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        List<DataSource> emptyReplicas = new ArrayList<>();
        
        // SELECT with no replicas available
        DataSource selectedDs = router.selectDataSource(
                null,
                "SELECT * FROM test_table WHERE id = 1",
                primaryDataSource,
                emptyReplicas
        );
        
        // Should fallback to primary
        assertSame(primaryDataSource, selectedDs, "Should fallback to primary when no replicas");
        
        // Verify we get PRIMARY data
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT source FROM test_table WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("primary", rs.getString("source"), "Should get data from primary");
        }
    }
    
    /**
     * Tests that DELETE operations route to PRIMARY.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Execute DELETE statement through router</li>
     *   <li>Verify router selects PRIMARY datasource</li>
     * </ol>
     * 
     * <p>
     * Ensures DELETE operations (another write type) correctly route to primary.
     * </p>
     */
    @Test
    void testDeleteGoesToPrimary() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        List<DataSource> replicas = new ArrayList<>();
        replicas.add(replicaDataSource);
        
        // SELECT the datasource for a DELETE (write operation)
        DataSource selectedDs = router.selectDataSource(
                null,
                "DELETE FROM test_table WHERE id = 1",
                primaryDataSource,
                replicas
        );
        
        // Verify router selected PRIMARY for write
        assertSame(primaryDataSource, selectedDs, "DELETE operations should route to primary");
    }
    
    /**
     * Tests that UNKNOWN SQL statements (unparseable by JSqlParser) route to PRIMARY.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Execute a stored procedure call (UNKNOWN SQL type)</li>
     *   <li>Verify router selects PRIMARY datasource</li>
     * </ol>
     * 
     * <p>
     * This is a safety mechanism: when we can't classify a statement, we route
     * to primary to avoid accidentally executing writes on a replica.
     * </p>
     */
    @Test
    void testUnknownSqlGoesToPrimary() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        List<DataSource> replicas = new ArrayList<>();
        replicas.add(replicaDataSource);
        
        // Stored procedure call - JSqlParser can't parse this
        DataSource selectedDs = router.selectDataSource(
                null,
                "CALL MY_STORED_PROCEDURE()",
                primaryDataSource,
                replicas
        );
        
        // Should route to primary for safety
        assertSame(primaryDataSource, selectedDs, "UNKNOWN SQL should route to primary for safety");
    }
}
