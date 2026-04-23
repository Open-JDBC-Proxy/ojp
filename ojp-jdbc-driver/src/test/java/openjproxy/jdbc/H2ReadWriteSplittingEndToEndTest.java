package openjproxy.jdbc;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for read/write traffic splitting through OJP JDBC driver and server.
 *
 * <h2>Client-Side Configuration</h2>
 *
 * <p>
 * All read/write splitting configuration is supplied by the client via the {@link java.util.Properties}
 * object passed to {@link java.sql.DriverManager#getConnection(String, Properties)}. No server-side
 * properties file is required. The driver forwards these properties to the OJP server, which uses
 * them to configure the primary/replica datasources on first connection.
 * </p>
 *
 * <p>Example configuration properties used by these tests:</p>
 * <pre>
 * Properties props = new Properties();
 * props.setProperty("user", "sa");
 * props.setProperty("password", "");
 * props.setProperty("ojp.datasource.name",                         "rw_e2e_ds");
 * props.setProperty("rw_e2e_ds.ojp.readwrite.enabled",             "true");
 * props.setProperty("rw_e2e_replica.ojp.readwrite.role",           "replica");
 * props.setProperty("rw_e2e_replica.ojp.readwrite.primary",        "rw_e2e_ds");
 * props.setProperty("rw_e2e_replica.ojp.connection.url",
 *         "jdbc:h2:mem:rw_e2e_replica;DB_CLOSE_DELAY=-1");
 * props.setProperty("rw_e2e_replica.ojp.connection.user",  "sa");
 * props.setProperty("rw_e2e_replica.ojp.connection.password", "");
 * </pre>
 *
 * <h2>Test Strategy: Dual Unsynchronized H2 Databases</h2>
 *
 * <p>
 * Two separate, intentionally <em>unsynchronized</em> H2 in-memory databases are used:
 * </p>
 * <ul>
 *   <li><b>Primary</b> ({@code rw_e2e_primary}): seeded with id=1, source="primary"</li>
 *   <li><b>Replica</b> ({@code rw_e2e_replica}): seeded with id=2, source="replica"</li>
 * </ul>
 * <p>
 * Routing correctness is verified by checking which row is returned:
 * id=2 / source="replica" means the query was routed to the replica;
 * id=1 / source="primary" means it was routed to the primary.
 * </p>
 *
 * <h3>Test Execution Requirements</h3>
 * <ul>
 *   <li>OJP server running on localhost:1059</li>
 *   <li>Enable with {@code -DenableH2Tests=true} Maven flag</li>
 * </ul>
 *
 * @see org.openjproxy.grpc.server.readwrite.ReadWriteRouter
 * @see org.openjproxy.grpc.server.readwrite.ReplicaSelector
 * @see org.openjproxy.grpc.server.readwrite.SqlClassifier
 */
public class H2ReadWriteSplittingEndToEndTest {

    private static final String OJP_HOST = "localhost:1059";
    private static final String USER = "sa";
    private static final String PASSWORD = "";
    private static final String PRIMARY_DATASOURCE_NAME = "rw_e2e_ds";
    private static final String REPLICA_DATASOURCE_NAME = "rw_e2e_replica";

    private static boolean isH2TestEnabled;

    private Connection connection;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns JDBC URL that routes through OJP to the primary H2 database.
     */
    private String primaryUrl() {
        return "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_primary;DB_CLOSE_DELAY=-1";
    }

    /**
     * Returns JDBC URL that routes through OJP to the replica H2 database.
     */
    private String replicaUrl() {
        return "jdbc:ojp[" + OJP_HOST + "]_h2:mem:rw_e2e_replica;DB_CLOSE_DELAY=-1";
    }

    /**
     * Builds the Properties for a primary-datasource connection, including the full
     * read/write splitting configuration so the OJP server can configure routing on
     * first use — no server-side properties file needed.
     */
    private Properties primaryProps() {
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("ojp.datasource.name", PRIMARY_DATASOURCE_NAME);
        // Read/write splitting config forwarded to the OJP server
        props.setProperty(PRIMARY_DATASOURCE_NAME + ".ojp.readwrite.enabled", "true");
        props.setProperty(REPLICA_DATASOURCE_NAME + ".ojp.readwrite.role", "replica");
        props.setProperty(REPLICA_DATASOURCE_NAME + ".ojp.readwrite.primary", PRIMARY_DATASOURCE_NAME);
        props.setProperty(REPLICA_DATASOURCE_NAME + ".ojp.connection.url",
                "jdbc:h2:mem:rw_e2e_replica;DB_CLOSE_DELAY=-1");
        // Replica credentials: server uses these to open the replica pool (does not inherit from primary)
        props.setProperty(REPLICA_DATASOURCE_NAME + ".ojp.connection.user", USER);
        props.setProperty(REPLICA_DATASOURCE_NAME + ".ojp.connection.password", PASSWORD);
        return props;
    }

    /**
     * Builds the Properties for a direct replica-datasource connection (used to seed the
     * replica H2 database during test setup).
     */
    private Properties replicaProps() {
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("ojp.datasource.name", REPLICA_DATASOURCE_NAME);
        return props;
    }

    /**
     * Seeds both H2 databases through OJP so that each test starts from a known state:
     * <ul>
     *   <li>Primary: test_data(id=1, source='primary')</li>
     *   <li>Replica:  test_data(id=2, source='replica')</li>
     * </ul>
     * The first connection to the primary includes the full RW configuration, which causes
     * the OJP server to configure read/write splitting for subsequent requests.
     */
    private void setupDatabases() throws SQLException {
        // Seed primary
        try (Connection c = DriverManager.getConnection(primaryUrl(), primaryProps());
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS test_data");
            s.execute("CREATE TABLE test_data (id INT PRIMARY KEY, source VARCHAR(50))");
            s.execute("INSERT INTO test_data VALUES (1, 'primary')");
        }

        // Seed replica
        try (Connection c = DriverManager.getConnection(replicaUrl(), replicaProps());
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS test_data");
            s.execute("CREATE TABLE test_data (id INT PRIMARY KEY, source VARCHAR(50))");
            s.execute("INSERT INTO test_data VALUES (2, 'replica')");
        }
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * SELECT outside a transaction must be routed to the replica (id=2).
     */
    @Test
    void testSelectGoesToReplica_WithoutTransaction() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        setupDatabases();

        connection = DriverManager.getConnection(primaryUrl(), primaryProps());

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, source FROM test_data")) {

            assertTrue(rs.next(), "Should have at least one row");
            assertEquals(2, rs.getInt("id"),
                    "SELECT outside transaction should route to replica (id=2)");
            assertEquals("replica", rs.getString("source"),
                    "SELECT outside transaction should route to replica");
        }
    }

    /**
     * Multiple sequential SELECTs outside a transaction must all go to the replica.
     */
    @Test
    void testMultipleReads_AllGoToReplica() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        setupDatabases();

        connection = DriverManager.getConnection(primaryUrl(), primaryProps());

        for (int i = 0; i < 3; i++) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, source FROM test_data")) {

                assertTrue(rs.next(), "Should have at least one row in iteration " + i);
                assertEquals(2, rs.getInt("id"),
                        "SELECT #" + i + " should route to replica (id=2)");
                assertEquals("replica", rs.getString("source"),
                        "SELECT #" + i + " should route to replica");
            }
        }
    }

    /**
     * INSERT must be routed to the primary and be visible there.
     */
    @Test
    void testInsertGoesToPrimary() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        setupDatabases();

        connection = DriverManager.getConnection(primaryUrl(), primaryProps());

        try (Statement stmt = connection.createStatement()) {
            int affected = stmt.executeUpdate("INSERT INTO test_data VALUES (100, 'inserted')");
            assertEquals(1, affected, "INSERT should affect 1 row");
        }

        // Verify via a fresh primary connection (write went to primary)
        try (Connection verify = DriverManager.getConnection(primaryUrl(), primaryProps())) {
            // Need to use a transaction to force it to go to primary
            verify.setAutoCommit(false);
            try (Statement s = verify.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM test_data WHERE id = 100")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Primary database should contain the inserted row");
            }
            verify.rollback();
        }
    }

    /**
     * UPDATE must be routed to the primary and the change must be visible there.
     */
    @Test
    void testUpdateGoesToPrimary() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        setupDatabases();

        connection = DriverManager.getConnection(primaryUrl(), primaryProps());

        try (Statement stmt = connection.createStatement()) {
            int affected = stmt.executeUpdate("UPDATE test_data SET source = 'updated' WHERE id = 1");
            assertEquals(1, affected, "UPDATE should affect 1 row");
        }

        try (Connection verify = DriverManager.getConnection(primaryUrl(), primaryProps())) {
            //Force select to got to primary for validation
            verify.setAutoCommit(false);
            try (Statement s = verify.createStatement();
                ResultSet rs = s.executeQuery("SELECT source FROM test_data WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("updated", rs.getString("source"),
                    "Primary database should show the updated value");
            }
            verify.rollback();
        }
    }

    /**
     * DELETE must be routed to the primary; the row must be absent from the primary afterwards.
     */
    @Test
    void testDeleteGoesToPrimary() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        setupDatabases();

        connection = DriverManager.getConnection(primaryUrl(), primaryProps());

        try (Statement stmt = connection.createStatement()) {
            int affected = stmt.executeUpdate("DELETE FROM test_data WHERE id = 1");
            assertEquals(1, affected, "DELETE should affect 1 row");
        }

        try (Connection verify = DriverManager.getConnection(primaryUrl(), primaryProps());
             Statement s = verify.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT COUNT(*) FROM test_data WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1),
                    "Primary database should not contain the deleted row");
        }
    }

    /**
     * A write followed immediately by a read (no sticky session) demonstrates eventual
     * consistency: the read goes to the replica and does not see the write.
     */
    @Test
    void testWriteThenRead_WithoutStickySession_DoesNotSeeWrite() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        setupDatabases();

        connection = DriverManager.getConnection(primaryUrl(), primaryProps());

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO test_data VALUES (150, 'eventual_consistency_test')");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM test_data WHERE id = 150")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1),
                        "Replica should not yet have the row just inserted into the primary");
            }
        }
    }

    /**
     * All operations inside an explicit transaction must route to the primary.
     */
    @Test
    void testTransaction_AllOperationsGoToPrimary() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        setupDatabases();

        connection = DriverManager.getConnection(primaryUrl(), primaryProps());
        connection.setAutoCommit(false);

        try (Statement stmt = connection.createStatement()) {
            // SELECT inside transaction → primary (id=1)
            try (ResultSet rs = stmt.executeQuery("SELECT id, source FROM test_data")) {
                assertTrue(rs.next(), "Should have at least one row");
                assertEquals(1, rs.getInt("id"),
                        "SELECT inside transaction should route to primary (id=1)");
                assertEquals("primary", rs.getString("source"),
                        "SELECT inside transaction should route to primary");
            }

            stmt.executeUpdate("INSERT INTO test_data VALUES (200, 'tx_inserted')");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT source FROM test_data WHERE id = 200")) {
                assertTrue(rs.next(), "Should see the row inserted in the same transaction");
                assertEquals("tx_inserted", rs.getString("source"));
            }

            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * After a transaction commits, reads continue going to primary.
     */
    @SneakyThrows
    @Test
    void testAfterTransactionCommit_ReadsGoToPrimary() throws SQLException {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");

        setupDatabases();

        connection = DriverManager.getConnection(primaryUrl(), primaryProps());
        connection.setAutoCommit(false);

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO test_data VALUES (250, 'post_tx_test')");
            connection.commit();
        }

        connection.setAutoCommit(true);


        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM test_data WHERE id = 250")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1),
                    "After commit, SELECT routes to primary which should have the committed row");
        }
    }
}
