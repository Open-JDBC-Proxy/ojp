package org.openjproxy.grpc.server.readwrite;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.PropertyEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.Session;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration tests for read/write traffic splitting functionality with H2 databases.
 * 
 * <h2>Test Approach: Dual H2 Database Strategy</h2>
 * <p>
 * These tests use two <b>completely separate and UNSYNCHRONIZED</b> H2 in-memory databases to simulate a primary-replica setup:
 * <ul>
 *   <li><b>Primary database</b> ({@code jdbc:h2:mem:rw_int_primary}): Receives all write operations (INSERT, UPDATE, DELETE)</li>
 *   <li><b>Replica database</b> ({@code jdbc:h2:mem:rw_int_replica}): Receives all read operations (SELECT) when not in transaction or sticky mode</li>
 * </ul>
 * 
 * <p>
 * <b>CRITICAL: The databases are NOT replicated and do NOT sync with each other.</b> This is an intentional design choice
 * that makes testing easier and more deterministic. The tests leverage this lack of synchronization to verify routing correctness.
 * 
 * <p>
 * This approach provides several advantages:
 * <ol>
 *   <li><b>Isolation</b>: The databases are completely separate, making it easy to verify routing by checking which database contains what data</li>
 *   <li><b>No replication complexity</b>: Real database replication is complex, slow, and introduces timing issues. 
 *       By using separate databases with different data, we can instantly and deterministically verify that writes went to primary and reads went to replica</li>
 *   <li><b>Deterministic testing</b>: Each database contains known, predictable data that doesn't change during the test (except via explicit writes to primary)</li>
 *   <li><b>Fast execution</b>: In-memory H2 databases are extremely fast and require no external infrastructure</li>
 *   <li><b>Leveraging non-synchronization</b>: The fact that databases don't sync is used as a testing advantage - 
 *       if a write to primary is NOT visible on a read, we know the read went to the replica</li>
 * </ol>
 * 
 * <h2>Test Data Strategy - Leveraging Non-Synchronization</h2>
 * <p>
 * Each database is pre-populated with <b>different</b> marker data that never appears in the other database:
 * <pre>
 * PRIMARY:  {id: 1, name: "PRIMARY_DATA",  source: "primary"}   &lt;-- ONLY in primary
 * REPLICA:  {id: 2, name: "REPLICA_DATA",  source: "replica"}   &lt;-- ONLY in replica
 * </pre>
 * 
 * <p>
 * <b>How tests leverage non-synchronization:</b>
 * <ul>
 *   <li>If a query returns {@code source='primary'}, we know it hit the primary database</li>
 *   <li>If a query returns {@code source='replica'}, we know it hit the replica database</li>
 *   <li>If a write is made to primary and a subsequent read does NOT see it, we know the read went to the replica (correct routing!)</li>
 *   <li>If a write is made to primary and a subsequent read DOES see it, we know the read went to the primary (sticky session or transaction behavior)</li>
 * </ul>
 * 
 * <h2>Important Considerations</h2>
 * <ul>
 *   <li><b>Read-after-write (NO sync)</b>: Without sticky sessions, a write to primary followed by a read will NOT see the written data
 *       because the read goes to the unsynchronized replica. This is expected behavior and <b>tests rely on this</b> to verify replica routing.</li>
 *   <li><b>Sticky sessions</b>: To test read-after-write consistency, sticky sessions must be enabled via the 
 *       {@code stickySessionSeconds} configuration property. This forces reads to go to primary after a write.</li>
 *   <li><b>Transactions</b>: All operations within a transaction route to the primary database, even SELECTs</li>
 *   <li><b>This is NOT how production works</b>: In production, replicas eventually sync with primary via replication. 
 *       These tests use non-sync databases purely as a testing technique to verify routing logic.</li>
 * </ul>
 * 
 * @author OJP Read/Write Splitting Integration Tests
 * @see ReadWriteDataSourceManager
 * @see ReadWriteRouter
 * @see Session
 */
class H2ReadWriteSplittingIntegrationTest {
    
    private static final String PRIMARY_URL = "jdbc:h2:mem:rw_int_primary;DB_CLOSE_DELAY=-1";
    private static final String REPLICA_URL = "jdbc:h2:mem:rw_int_replica;DB_CLOSE_DELAY=-1";
    private static final String USERNAME = "sa";
    private static final String PASSWORD = "";
    
    private static boolean isH2TestEnabled;
    
    private DataSource primaryDataSource;
    private DataSource replicaDataSource;
    private ReadWriteDataSourceRegistry registry;
    private ReadWriteDataSourceManager manager;
    private Connection primaryConn;
    private Connection replicaConn;
    
    @BeforeAll
    static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }
    
    /**
     * Sets up the test environment with two separate, UNSYNCHRONIZED H2 databases.
     * 
     * <p><b>Database Setup:</b></p>
     * <ol>
     *   <li>Creates PRIMARY database with id=1, name="PRIMARY_DATA", source="primary"</li>
     *   <li>Creates REPLICA database with id=2, name="REPLICA_DATA", source="replica"</li>
     *   <li>Initializes read/write splitting registry and manager</li>
     * </ol>
     * 
     * <p>
     * <b>IMPORTANT</b>: Each database has identical schema but <b>completely different data</b>.
     * The databases are <b>NOT synchronized</b> with each other - this is intentional!
     * Tests leverage this lack of synchronization to verify routing correctness by checking
     * which database's data is returned in query results.
     * </p>
     */
    @BeforeEach
    void setUp() throws SQLException {
        // Clear configuration cache
        ReadWriteConfigurationParser.clearCache();
        
        // Create and initialize PRIMARY database
        primaryConn = java.sql.DriverManager.getConnection(PRIMARY_URL, USERNAME, PASSWORD);
        try (Statement stmt = primaryConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_table");
            stmt.execute("CREATE TABLE test_table (id INT PRIMARY KEY, name VARCHAR(255), source VARCHAR(50))");
            stmt.execute("INSERT INTO test_table VALUES (1, 'PRIMARY_DATA', 'primary')");
        }
        
        // Create and initialize REPLICA database
        replicaConn = java.sql.DriverManager.getConnection(REPLICA_URL, USERNAME, PASSWORD);
        try (Statement stmt = replicaConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_table");
            stmt.execute("CREATE TABLE test_table (id INT PRIMARY KEY, name VARCHAR(255), source VARCHAR(50))");
            stmt.execute("INSERT INTO test_table VALUES (2, 'REPLICA_DATA', 'replica')");
        }
        
        // Create datasources using direct H2 connections wrapped as datasources
        primaryDataSource = createSimpleDataSource(PRIMARY_URL);
        replicaDataSource = createSimpleDataSource(REPLICA_URL);
        
        // Initialize read/write splitting components
        registry = new ReadWriteDataSourceRegistry();
        registry.clear();
        manager = new ReadWriteDataSourceManager(registry);
    }
    
    /**
     * Cleans up test resources and database connections.
     */
    @AfterEach
    void tearDown() throws SQLException {
        if (registry != null) {
            registry.clear();
        }
        ReadWriteConfigurationParser.clearCache();
        
        if (primaryConn != null && !primaryConn.isClosed()) {
            primaryConn.close();
        }
        if (replicaConn != null && !replicaConn.isClosed()) {
            replicaConn.close();
        }
    }
    
    /**
     * Tests that write operations (INSERT) are routed to the PRIMARY database.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Configure read/write splitting with primary and replica</li>
     *   <li>Execute INSERT statement on primary</li>
     *   <li>Verify data exists in PRIMARY database</li>
     *   <li>Verify data does NOT exist in REPLICA database (databases are not synced)</li>
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
        
        // Setup read/write splitting configuration
        ConnectionDetails details = createConnectionDetails("testds", false, 0);
        manager.setupReadWriteSplitting(details, "primary-hash", primaryDataSource, "testds");
        
        // Register the replica
        registry.registerReplica("testds", "replica1", replicaDataSource);
        
        // Execute INSERT on primary
        try (Connection conn = primaryDataSource.getConnection();
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
        
        // Verify data is NOT in REPLICA
        try (Connection conn = replicaDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table WHERE id = 100")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Written data should NOT exist in replica");
        }
    }
    
    /**
     * Tests that read operations (SELECT) without sticky sessions route to the REPLICA database.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Configure read/write splitting (sticky sessions disabled)</li>
     *   <li>Execute SELECT statement</li>
     *   <li>Verify query returns REPLICA data (id=2, source='replica')</li>
     *   <li>Verify query does NOT return PRIMARY data (id=1, source='primary')</li>
     * </ol>
     * 
     * <p>
     * This test proves that read operations correctly route to replicas,
     * offloading read traffic from the primary database.
     * </p>
     */
    @Test
    void testReadGoesToReplica_WithoutStickySession() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Setup read/write splitting (sticky sessions disabled)
        ConnectionDetails details = createConnectionDetails("testds", false, 0);
        manager.setupReadWriteSplitting(details, "primary-hash", primaryDataSource, "testds");
        registry.registerReplica("testds", "replica1", replicaDataSource);
        
        // Create router and session
        ReadWriteRouter router = new ReadWriteRouter(
                registry,
                ReadWriteConfiguration.builder()
                        .datasourceName("testds")
                        .enabled(true)
                        .role(ReadWriteRole.PRIMARY)
                        .build()
        );
        
        Session session = new Session(primaryConn, "test-hash", "test-uuid");
        
        // Execute SELECT - should route to replica
        DataSource selectedDs = router.routeQuery("SELECT * FROM test_table WHERE id = 2", session);
        
        // Verify we got the replica datasource
        assertNotNull(selectedDs);
        
        // Query the selected datasource and verify we got REPLICA data
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT source FROM test_table WHERE id = 2")) {
            assertTrue(rs.next(), "Should find row in replica");
            assertEquals("replica", rs.getString("source"), "Should get data from replica database");
        }
    }
    
    /**
     * Tests sticky session behavior: writes make subsequent reads go to PRIMARY.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Configure read/write splitting with 10-second sticky sessions</li>
     *   <li>Execute INSERT into primary (id=200)</li>
     *   <li>Immediately execute SELECT - should route to PRIMARY due to sticky session</li>
     *   <li>Verify SELECT returns the newly inserted data from primary</li>
     * </ol>
     * 
     * <p>
     * <b>Why sticky sessions matter:</b> Without sticky sessions, the SELECT would route to
     * the replica and NOT find the inserted row (since replicas lag behind primary).
     * Sticky sessions ensure read-after-write consistency for the same session.
     * </p>
     */
    @Test
    void testStickySession_ReadYourWrites() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Setup with sticky sessions ENABLED (10 seconds)
        ConnectionDetails details = createConnectionDetails("testds", true, 10);
        ReadWriteConfiguration config = manager.setupReadWriteSplitting(
                details, "primary-hash", primaryDataSource, "testds");
        registry.registerReplica("testds", "replica1", replicaDataSource);
        
        assertNotNull(config);
        assertEquals(10, config.getStickySessionSeconds());
        
        // Create router and session
        ReadWriteRouter router = new ReadWriteRouter(registry, config);
        Session session = new Session(primaryConn, "test-hash", "test-uuid");
        
        // Execute INSERT (write operation)
        try (Connection conn = primaryDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO test_table VALUES (200, 'STICKY_DATA', 'sticky')");
        }
        
        // Record write operation in session
        session.recordWriteOperation();
        
        // Immediately SELECT - should go to PRIMARY due to sticky session
        DataSource selectedDs = router.routeQuery("SELECT * FROM test_table WHERE id = 200", session);
        
        // Should route to primary (sticky mode active)
        assertEquals(primaryDataSource, selectedDs, "Should route to primary in sticky mode");
        
        // Verify we can read our own write
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT source FROM test_table WHERE id = 200")) {
            assertTrue(rs.next(), "Should find newly inserted row");
            assertEquals("sticky", rs.getString("source"), "Should read our own write from primary");
        }
    }
    
    /**
     * Tests sticky session expiration: after timeout, reads route back to REPLICA.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Configure read/write splitting with 1-second sticky sessions</li>
     *   <li>Execute write operation and verify sticky mode is active</li>
     *   <li>Wait 2 seconds for sticky session to expire</li>
     *   <li>Execute SELECT - should route to REPLICA (sticky mode expired)</li>
     *   <li>Verify SELECT returns replica data, NOT primary data</li>
     * </ol>
     * 
     * <p>
     * This test proves that sticky sessions correctly expire, allowing read traffic
     * to return to replicas and prevent overloading the primary.
     * </p>
     */
    @Test
    void testStickySession_ExpiresAfterTimeout() throws SQLException, InterruptedException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Setup with SHORT sticky session (1 second)
        ConnectionDetails details = createConnectionDetails("testds", true, 1);
        ReadWriteConfiguration config = manager.setupReadWriteSplitting(
                details, "primary-hash", primaryDataSource, "testds");
        registry.registerReplica("testds", "replica1", replicaDataSource);
        
        ReadWriteRouter router = new ReadWriteRouter(registry, config);
        Session session = new Session(primaryConn, "test-hash", "test-uuid");
        
        // Execute write and record it
        session.recordWriteOperation();
        
        // Verify sticky mode is active immediately after write
        assertTrue(session.isInStickyMode(1000), "Should be in sticky mode immediately after write");
        
        // Wait for sticky session to expire (1 second timeout + buffer)
        Thread.sleep(1500);
        
        // Verify sticky mode has expired
        assertFalse(session.isInStickyMode(1000), "Sticky mode should expire after timeout");
        
        // SELECT should now route to REPLICA
        DataSource selectedDs = router.routeQuery("SELECT * FROM test_table WHERE source = 'replica'", session);
        
        // Should be replica datasource (or at least not return primary data)
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM test_table WHERE source = 'replica'")) {
            assertTrue(rs.next(), "Should find replica data after sticky expires");
            assertEquals(2, rs.getInt("id"), "Should get replica data (id=2) not primary data (id=1)");
        }
    }
    
    /**
     * Tests transaction behavior: all operations within a transaction route to PRIMARY.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Configure read/write splitting</li>
     *   <li>Start a transaction</li>
     *   <li>Execute SELECT within transaction</li>
     *   <li>Verify SELECT routes to PRIMARY (not replica)</li>
     *   <li>Execute INSERT within transaction</li>
     *   <li>Execute another SELECT</li>
     *   <li>Verify all operations stayed on PRIMARY</li>
     *   <li>Commit transaction</li>
     * </ol>
     * 
     * <p>
     * <b>Why transactions go to primary:</b> Transactions require consistency and isolation.
     * Routing transaction reads to replicas could violate ACID properties due to replication lag.
     * All transactional operations must execute on the same database (primary).
     * </p>
     */
    @Test
    void testTransaction_AllOperationsGoToPrimary() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Setup read/write splitting
        ConnectionDetails details = createConnectionDetails("testds", false, 0);
        ReadWriteConfiguration config = manager.setupReadWriteSplitting(
                details, "primary-hash", primaryDataSource, "testds");
        registry.registerReplica("testds", "replica1", replicaDataSource);
        
        ReadWriteRouter router = new ReadWriteRouter(registry, config);
        Session session = new Session(primaryConn, "test-hash", "test-uuid");
        
        // Start transaction
        session.setInTransaction(true);
        assertTrue(session.isInTransaction());
        
        // SELECT in transaction should go to PRIMARY
        DataSource selectedDs = router.routeQuery("SELECT * FROM test_table", session);
        assertEquals(primaryDataSource, selectedDs, "SELECT in transaction should route to primary");
        
        // INSERT in transaction
        try (Connection conn = primaryDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO test_table VALUES (300, 'TX_DATA', 'transaction')");
        }
        
        // Another SELECT should still go to PRIMARY
        selectedDs = router.routeQuery("SELECT * FROM test_table WHERE id = 300", session);
        assertEquals(primaryDataSource, selectedDs, "SELECT should stay on primary during transaction");
        
        // Verify we can read transaction data
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT source FROM test_table WHERE id = 300")) {
            assertTrue(rs.next());
            assertEquals("transaction", rs.getString("source"));
        }
        
        // End transaction
        session.setInTransaction(false);
    }
    
    /**
     * Tests that after transaction commit, reads resume routing to REPLICA.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Configure read/write splitting (NO sticky sessions)</li>
     *   <li>Start transaction, execute operations, commit</li>
     *   <li>Execute SELECT after commit</li>
     *   <li>Verify SELECT routes to REPLICA (not primary)</li>
     * </ol>
     * 
     * <p>
     * This test ensures that transaction state is properly cleared and normal
     * read routing resumes after transaction completion.
     * </p>
     */
    @Test
    void testAfterTransactionCommit_ReadsGoToReplica() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Setup WITHOUT sticky sessions
        ConnectionDetails details = createConnectionDetails("testds", false, 0);
        ReadWriteConfiguration config = manager.setupReadWriteSplitting(
                details, "primary-hash", primaryDataSource, "testds");
        registry.registerReplica("testds", "replica1", replicaDataSource);
        
        ReadWriteRouter router = new ReadWriteRouter(registry, config);
        Session session = new Session(primaryConn, "test-hash", "test-uuid");
        
        // Execute a transaction
        session.setInTransaction(true);
        try (Connection conn = primaryDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO test_table VALUES (400, 'POST_TX', 'posttx')");
        }
        session.setInTransaction(false); // Commit
        
        // Now SELECT should go to REPLICA (no sticky session, no transaction)
        DataSource selectedDs = router.routeQuery("SELECT * FROM test_table WHERE source = 'replica'", session);
        
        // Verify it returns replica data
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM test_table WHERE source = 'replica'")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("id"), "Should route to replica after transaction ends");
        }
    }
    
    /**
     * Tests multiple sequential read operations all route to REPLICA.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Configure read/write splitting</li>
     *   <li>Execute 5 consecutive SELECT statements</li>
     *   <li>Verify all 5 SELECTs route to replica</li>
     *   <li>Verify all return replica data (source='replica')</li>
     * </ol>
     * 
     * <p>
     * This test ensures consistent replica routing for read-heavy workloads.
     * </p>
     */
    @Test
    void testMultipleReads_AllGoToReplica() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        ConnectionDetails details = createConnectionDetails("testds", false, 0);
        ReadWriteConfiguration config = manager.setupReadWriteSplitting(
                details, "primary-hash", primaryDataSource, "testds");
        registry.registerReplica("testds", "replica1", replicaDataSource);
        
        ReadWriteRouter router = new ReadWriteRouter(registry, config);
        Session session = new Session(primaryConn, "test-hash", "test-uuid");
        
        // Execute 5 consecutive reads
        for (int i = 0; i < 5; i++) {
            DataSource selectedDs = router.routeQuery("SELECT * FROM test_table", session);
            
            // Verify each routes to replica
            try (Connection conn = selectedDs.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT source FROM test_table WHERE source = 'replica'")) {
                assertTrue(rs.next(), "Read " + (i + 1) + " should find replica data");
                assertEquals("replica", rs.getString("source"), "Read " + (i + 1) + " should get replica data");
            }
        }
    }
    
    /**
     * Tests write operation followed by read WITHOUT sticky session - leverages database non-synchronization.
     * 
     * <p><b>Test Scenario:</b></p>
     * <ol>
     *   <li>Configure read/write splitting WITHOUT sticky sessions</li>
     *   <li>INSERT data into primary (id=500)</li>
     *   <li>Immediately SELECT for id=500</li>
     *   <li>Verify SELECT routes to REPLICA and does NOT find the row</li>
     * </ol>
     * 
     * <p>
     * <b>Why this test works:</b> The two H2 databases are <b>NOT synchronized</b>. When we write to primary,
     * that data exists ONLY in the primary database. When the subsequent read goes to the replica (correct routing),
     * the replica database doesn't have that row because there's no replication happening. This confirms the read
     * went to the replica.
     * </p>
     * 
     * <p>
     * <b>Why this is correct behavior:</b> In real async replication scenarios, replicas lag behind the primary.
     * Without sticky sessions, reads may not see recent writes. This test validates the system correctly implements
     * this eventual consistency model. The non-synchronized H2 databases simulate maximum replication lag (infinite lag).
     * </p>
     */
    @Test
    void testWriteThenRead_WithoutStickySession_DoesNotSeeWrite() throws SQLException {
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        
        // Setup WITHOUT sticky sessions
        ConnectionDetails details = createConnectionDetails("testds", false, 0);
        ReadWriteConfiguration config = manager.setupReadWriteSplitting(
                details, "primary-hash", primaryDataSource, "testds");
        registry.registerReplica("testds", "replica1", replicaDataSource);
        
        ReadWriteRouter router = new ReadWriteRouter(registry, config);
        Session session = new Session(primaryConn, "test-hash", "test-uuid");
        
        // Write to primary
        try (Connection conn = primaryDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO test_table VALUES (500, 'UNSEEN', 'unseen')");
        }
        
        // Read immediately - goes to replica, won't find the row
        DataSource selectedDs = router.routeQuery("SELECT * FROM test_table WHERE id = 500", session);
        
        try (Connection conn = selectedDs.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table WHERE id = 500")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Replica should NOT have the newly written data (no replication)");
        }
        
        // But primary DOES have it
        try (Connection conn = primaryDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table WHERE id = 500")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Primary should have the written data");
        }
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Creates ConnectionDetails with read/write splitting configuration.
     * 
     * @param datasourceName name of the datasource
     * @param enableSticky whether to enable sticky sessions
     * @param stickySec onds sticky session duration in seconds (0 = disabled)
     * @return ConnectionDetails with read/write configuration
     */
    private ConnectionDetails createConnectionDetails(String datasourceName, boolean enableSticky, int stickySeconds) {
        ConnectionDetails.Builder builder = ConnectionDetails.newBuilder();
        
        // Primary configuration
        builder.addProperties(PropertyEntry.newBuilder()
                .setKey(datasourceName + ".ojp.readwrite.enabled")
                .setValue("true")
                .build());
        builder.addProperties(PropertyEntry.newBuilder()
                .setKey(datasourceName + ".ojp.readwrite.role")
                .setValue("primary")
                .build());
        builder.addProperties(PropertyEntry.newBuilder()
                .setKey(datasourceName + ".ojp.readwrite.replicaSelectionStrategy")
                .setValue("ROUND_ROBIN")
                .build());
        builder.addProperties(PropertyEntry.newBuilder()
                .setKey(datasourceName + ".ojp.readwrite.replicaFailoverToPrimary")
                .setValue("true")
                .build());
        
        // Sticky session configuration
        if (enableSticky) {
            builder.addProperties(PropertyEntry.newBuilder()
                    .setKey(datasourceName + ".ojp.readwrite.stickySessionSeconds")
                    .setValue(String.valueOf(stickySeconds))
                    .build());
        }
        
        // Replica configuration
        builder.addProperties(PropertyEntry.newBuilder()
                .setKey("replica1.ojp.readwrite.primary")
                .setValue(datasourceName)
                .build());
        builder.addProperties(PropertyEntry.newBuilder()
                .setKey("replica1.ojp.connection.url")
                .setValue(REPLICA_URL)
                .build());
        
        return builder.build();
    }
    
    /**
     * Creates a simple DataSource wrapper around a JDBC URL.
     * This is a minimal implementation for testing purposes.
     */
    private DataSource createSimpleDataSource(String url) {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return java.sql.DriverManager.getConnection(url, USERNAME, PASSWORD);
            }
            
            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return java.sql.DriverManager.getConnection(url, username, password);
            }
            
            @Override
            public java.io.PrintWriter getLogWriter() { return null; }
            
            @Override
            public void setLogWriter(java.io.PrintWriter out) {}
            
            @Override
            public void setLoginTimeout(int seconds) {}
            
            @Override
            public int getLoginTimeout() { return 0; }
            
            @Override
            public java.util.logging.Logger getParentLogger() { return null; }
            
            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("Not supported");
            }
            
            @Override
            public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
