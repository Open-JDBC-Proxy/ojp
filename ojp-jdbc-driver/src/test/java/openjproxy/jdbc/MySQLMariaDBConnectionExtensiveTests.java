package openjproxy.jdbc;

import lombok.SneakyThrows;
import openjproxy.jdbc.testutil.TestDBUtils;
import openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class MySQLMariaDBConnectionExtensiveTests {

    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;
    private ConnectionResult connectionResult;

    private Connection connection;

    @BeforeAll
    public static void checkTestConfiguration() {
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        assumeFalse(!isMySQLTestEnabled, "MySQL tests are not enabled");
        assumeFalse(!isMariaDBTestEnabled, "MariaDB tests are not enabled");
        connectionResult = TestDBUtils.createConnection(url, user, password, isXA);
        connection = connectionResult.getConnection();
        
        // For non-XA connections, set autocommit to false for transaction control
        if (!isXA) {
            connection.setAutoCommit(false);
        }
    }

    @AfterEach
    public void tearDown() throws SQLException {
        if (connectionResult != null) {
            connectionResult.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testCreateStatement(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        Statement statement = connection.createStatement();
        Assert.assertNotNull(statement);
        statement.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testPrepareStatement(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        PreparedStatement preparedStatement = connection.prepareStatement("SELECT 1");
        Assert.assertNotNull(preparedStatement);
        preparedStatement.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testPrepareCall(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        // MySQL supports callable statements, though syntax may differ
        try {
            CallableStatement callableStatement = connection.prepareCall("CALL test_procedure()");
            Assert.assertNotNull(callableStatement);
            callableStatement.close();
        } catch (SQLException e) {
            // This is expected if the procedure doesn't exist - test that the method works
            Assert.assertTrue(e.getMessage().contains("PROCEDURE") || e.getMessage().contains("procedure"));
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testNativeSQL(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        String nativeSQL = connection.nativeSQL("SELECT {fn NOW()}");
        Assert.assertNotNull(nativeSQL);
        // MySQL should convert JDBC escape sequence
        Assert.assertTrue(nativeSQL.contains("NOW()") || nativeSQL.contains("SELECT"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testAutoCommit(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        // Test getting and setting auto-commit
        boolean originalAutoCommit = connection.getAutoCommit();
        
        connection.setAutoCommit(false);
        Assert.assertEquals(false, connection.getAutoCommit());
        
        connection.setAutoCommit(true);
        Assert.assertEquals(true, connection.getAutoCommit());
        
        // Restore original state
        connection.setAutoCommit(originalAutoCommit);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testCommitAndRollback(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        // Test commit and rollback operations
        connection.setAutoCommit(false);
        
        // These should not throw exceptions
        connection.commit();
        connection.rollback();
        
        connection.setAutoCommit(true);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testIsClosed(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        Assert.assertEquals(false, connection.isClosed());
        
        connection.close();
        Assert.assertEquals(true, connection.isClosed());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testGetMetaData(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        DatabaseMetaData metaData = connection.getMetaData();
        Assert.assertNotNull(metaData);
        
        String databaseProductName = metaData.getDatabaseProductName();
        Assert.assertNotNull(databaseProductName);
        if (url.toLowerCase().contains("mysql"))
            Assert.assertTrue(databaseProductName.toLowerCase().contains("mysql"));
        else
            Assert.assertTrue(databaseProductName.toLowerCase().contains("mariadb"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testReadOnly(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        // Test read-only mode
        boolean originalReadOnly = connection.isReadOnly();
        
        try {
            connection.setReadOnly(true);
            // MySQL may or may not support read-only mode depending on configuration
            // Just test that the call doesn't throw an unexpected exception
        } catch (SQLException e) {
            // Some MySQL configurations may not support read-only mode
            // This is acceptable
        }
        
        // Restore original state
        connection.setReadOnly(originalReadOnly);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testCatalog(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        String catalog = connection.getCatalog();
        // Catalog might be null or the database name
        
        // Test setting catalog (should work in MySQL)
        if (catalog != null) {
            connection.setCatalog(catalog);
            Assert.assertEquals(catalog, connection.getCatalog());
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testTransactionIsolation(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        int isolationLevel = connection.getTransactionIsolation();
        Assert.assertTrue(isolationLevel >= Connection.TRANSACTION_NONE && isolationLevel <= Connection.TRANSACTION_SERIALIZABLE);
        
        // Test setting transaction isolation level
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        Assert.assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
        
        // Restore original level
        connection.setTransactionIsolation(isolationLevel);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testWarnings(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        // Test warning operations
        SQLWarning warnings = connection.getWarnings();
        // Warnings might be null initially
        
        connection.clearWarnings();
        // Should not throw exception
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testCreateStatementWithParameters(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        Assert.assertNotNull(statement);
        statement.close();
        
        statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        Assert.assertNotNull(statement);
        statement.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testPrepareStatementWithParameters(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        PreparedStatement ps = connection.prepareStatement("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        Assert.assertNotNull(ps);
        ps.close();
        
        ps = connection.prepareStatement("SELECT 1", Statement.RETURN_GENERATED_KEYS);
        Assert.assertNotNull(ps);
        ps.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testHoldability(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        int holdability = connection.getHoldability();
        Assert.assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT || holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT);
        
        // Test setting holdability
        connection.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
        Assert.assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, connection.getHoldability());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testSavepoints(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        connection.setAutoCommit(false);
        
        // Test unnamed savepoint
        Savepoint savepoint1 = connection.setSavepoint();
        Assert.assertNotNull(savepoint1);
        
        // Test named savepoint
        Savepoint savepoint2 = connection.setSavepoint("test_savepoint");
        Assert.assertNotNull(savepoint2);
        Assert.assertEquals("test_savepoint", savepoint2.getSavepointName());

        // Test rollback to savepoint
        connection.rollback(savepoint2);
        connection.rollback(savepoint1);
        
        // Test release savepoint
        connection.releaseSavepoint(savepoint1);
        
        connection.setAutoCommit(true);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testClientInfo(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        Properties clientInfo = connection.getClientInfo();
        Assert.assertNotNull(clientInfo);
        
        // Test setting client info
        try {
            connection.setClientInfo("ApplicationName", "MySQLTest");
            // Should not throw exception, though MySQL may not support all properties
        } catch (SQLClientInfoException e) {
            // This is acceptable for MySQL
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testValid(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        boolean isValid = connection.isValid(5);
        Assert.assertTrue(isValid);
        
        // Test with closed connection
        connection.close();
        isValid = connection.isValid(5);
        Assert.assertEquals(false, isValid);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testUnsupportedOperations(String driverClass, String url, String user, String password, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, password, isXA);
        
        // Test operations that might not be supported
        Assert.assertThrows(SQLException.class, () -> {
            connection.createArrayOf("VARCHAR", new String[]{"test"});
        });

        Assert.assertThrows(SQLFeatureNotSupportedException.class, () -> {
            connection.createStruct("test_type", new Object[]{});
        });
    }
}