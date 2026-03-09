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
 * Integration tests for XA transaction support with MariaDB.
 * These tests require:
 * 1. A running OJP server (localhost:1059)
 * 2. A MariaDB database with XA support
 */
@Slf4j
class MariaDBXAIntegrationTest {

    private static boolean isTestEnabled;
    private XAConnection xaConnection;
    private Connection connection;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

    private void setUp(String url, String user, String password) throws SQLException {
        assumeFalse(!isTestEnabled, "MariaDB XA tests are disabled. Enable with -DenableMariaDBTests=true");

        // Create XA DataSource
        OjpXADataSource xaDataSource = new OjpXADataSource();
        xaDataSource.setUrl(url);
        xaDataSource.setUser(user);
        xaDataSource.setPassword(password);

        // Get XA Connection
        xaConnection = xaDataSource.getXAConnection(user, password);
        connection = xaConnection.getConnection();
    }

    @AfterEach
    void tearDown() {
        TestDBUtils.closeQuietly(connection);
        if (xaConnection != null) {
            try {
                xaConnection.close();
            } catch (Exception e) {
                log.warn("Error closing XA connection: {}", e.getMessage());
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mariadb_xa_connection.csv")
    void testXAConnectionBasics(String driverClass, String url, String user, String password) throws Exception {
        setUp(url, user, password);

        assertNotNull(xaConnection, "XA connection should be created");
        assertNotNull(connection, "Logical connection should be created");
        assertFalse(connection.isClosed(), "Connection should not be closed");

        // Get XA Resource
        XAResource xaResource = xaConnection.getXAResource();
        assertNotNull(xaResource, "XA resource should not be null");

        // Verify connection is not auto-commit
        assertFalse(connection.getAutoCommit(), "XA connection should not be auto-commit");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mariadb_xa_connection.csv")
    void testXATransactionWithCRUD(String driverClass, String url, String user, String password) throws Exception {
        setUp(url, user, password);

        XAResource xaResource = xaConnection.getXAResource();
        String tableName = "mariadb_xa_crud_test";

        // Clean up and create table
        try (Connection setupConn = TestDBUtils.createConnection(url, user, password, false).getConnection()) {
            try (Statement stmt = setupConn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + tableName);
                stmt.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, val VARCHAR(50))");
            }
        }

        // 1. Start XA Transaction
        Xid xid = createXid(1);
        xaResource.start(xid, XAResource.TMNOFLAGS);

        // 2. Execute DML
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " VALUES (?, ?)")) {
            ps.setInt(1, 1);
            ps.setString(2, "XA_VAL_1");
            ps.executeUpdate();
        }

        // 3. End and Commit
        xaResource.end(xid, XAResource.TMSUCCESS);
        int prepareResult = xaResource.prepare(xid);
        assertEquals(XAResource.XA_OK, prepareResult);
        xaResource.commit(xid, false);

        // 4. Verify data
        try (Connection verifyConn = TestDBUtils.createConnection(url, user, password, false).getConnection()) {
            try (PreparedStatement ps = verifyConn.prepareStatement("SELECT val FROM " + tableName + " WHERE id = 1")) {
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals("XA_VAL_1", rs.getString(1));
            }
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mariadb_xa_connection.csv")
    void testXARollback(String driverClass, String url, String user, String password) throws Exception {
        setUp(url, user, password);

        XAResource xaResource = xaConnection.getXAResource();
        String tableName = "mariadb_xa_rollback_test";

        // Clean up and create table
        try (Connection setupConn = TestDBUtils.createConnection(url, user, password, false).getConnection()) {
            try (Statement stmt = setupConn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + tableName);
                stmt.execute("CREATE TABLE " + tableName + " (id INT PRIMARY KEY, val VARCHAR(50))");
            }
        }

        // 1. Start XA Transaction
        Xid xid = createXid(2);
        xaResource.start(xid, XAResource.TMNOFLAGS);

        // 2. Execute DML
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + " VALUES (?, ?)")) {
            ps.setInt(1, 1);
            ps.setString(2, "SHOULD_ROLLBACK");
            ps.executeUpdate();
        }

        // 3. End and Rollback
        xaResource.end(xid, XAResource.TMSUCCESS);
        xaResource.rollback(xid);

        // 4. Verify absence of data
        try (Connection verifyConn = TestDBUtils.createConnection(url, user, password, false).getConnection()) {
            try (PreparedStatement ps = verifyConn.prepareStatement("SELECT COUNT(*) FROM " + tableName)) {
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    private Xid createXid(int id) {
        byte[] gtrid = new byte[] { (byte) (0x10 + id), 0x01, 0x02 };
        byte[] bqual = new byte[] { (byte) (0x20 + id), 0x03, 0x04 };
        return new TestXid(0x1234, gtrid, bqual);
    }

    private static class TestXid implements Xid {
        private final int formatId;
        private final byte[] globalTransactionId;
        private final byte[] branchQualifier;

        TestXid(int formatId, byte[] globalTransactionId, byte[] branchQualifier) {
            this.formatId = formatId;
            this.globalTransactionId = globalTransactionId;
            this.branchQualifier = branchQualifier;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalTransactionId;
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchQualifier;
        }
    }
}
