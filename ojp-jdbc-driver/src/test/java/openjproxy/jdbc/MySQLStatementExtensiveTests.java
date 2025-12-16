package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class MySQLStatementExtensiveTests {

    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;
    private ConnectionResult connectionResult;

    private Connection connection;
    private Statement statement;

    @BeforeAll
    public static void checkTestConfiguration() {
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        assumeFalse(!isMySQLTestEnabled, "MySQL tests are not enabled");
        assumeFalse(!isMariaDBTestEnabled, "MariaDB tests are not enabled");

        connectionResult = TestDBUtils.createConnection(url, user, password, isXA);
        connection = connectionResult.getConnection();
        
        // For non-XA connections, set autocommit to false for transaction control
        if (!isXA) {
            connection.setAutoCommit(false);
        }
        statement = connection.createStatement();
        TestDBUtils.createBasicTestTable(connection, "mysql_statement_test", TestDBUtils.SqlSyntax.MYSQL, true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestDBUtils.closeQuietly(statement, connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testExecuteQuery(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        ResultSet rs = statement.executeQuery("SELECT * FROM mysql_statement_test");
        Assert.assertNotNull(rs);
        Assert.assertTrue(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testExecuteUpdate(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        int rows = statement.executeUpdate("UPDATE mysql_statement_test SET name = 'Updated Alice' WHERE id = 1");
        Assert.assertEquals(1, rows);

        ResultSet rs = statement.executeQuery("SELECT name FROM mysql_statement_test WHERE id = 1");
        Assert.assertTrue(rs.next());
        Assert.assertEquals("Updated Alice", rs.getString("name"));
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testClose(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        Assert.assertFalse(statement.isClosed());
        statement.close();
        Assert.assertTrue(statement.isClosed());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testMaxFieldSize(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        int orig = statement.getMaxFieldSize();
        if (url.toLowerCase().contains("mysql"))
            Assert.assertTrue(orig > 0);
        else
            Assert.assertTrue(orig == 0);
        statement.setMaxFieldSize(5);
        if (url.toLowerCase().contains("mysql"))
            Assert.assertEquals(5, statement.getMaxFieldSize());
        else
            Assert.assertEquals(0, statement.getMaxFieldSize());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testMaxRows(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        Assert.assertEquals(0, statement.getMaxRows());
        statement.setMaxRows(10);
        Assert.assertEquals(10, statement.getMaxRows());
        statement.setMaxRows(0);
        Assert.assertEquals(0, statement.getMaxRows());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testQueryTimeout(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        Assert.assertEquals(0, statement.getQueryTimeout());
        statement.setQueryTimeout(30);
        Assert.assertEquals(30, statement.getQueryTimeout());
        statement.setQueryTimeout(0);
        Assert.assertEquals(0, statement.getQueryTimeout());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testWarnings(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        // Initial warnings might be null
        statement.getWarnings();
        statement.clearWarnings();
        Assert.assertNull(statement.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testExecute(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        boolean hasResultSet = statement.execute("SELECT * FROM mysql_statement_test");
        Assert.assertTrue(hasResultSet);
        
        ResultSet rs = statement.getResultSet();
        Assert.assertNotNull(rs);
        Assert.assertTrue(rs.next());
        rs.close();
        
        hasResultSet = statement.execute("UPDATE mysql_statement_test SET name = 'Test' WHERE id = 1");
        Assert.assertFalse(hasResultSet);
        Assert.assertEquals(1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testGetResultSet(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        statement.execute("SELECT * FROM mysql_statement_test");
        ResultSet rs = statement.getResultSet();
        Assert.assertNotNull(rs);
        rs.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testGetUpdateCount(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        statement.execute("UPDATE mysql_statement_test SET name = 'Test Update' WHERE id = 1");
        Assert.assertEquals(1, statement.getUpdateCount());
        
        statement.execute("SELECT * FROM mysql_statement_test");
        Assert.assertEquals(-1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testGetMoreResults(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        statement.execute("SELECT * FROM mysql_statement_test");
        if (url.toLowerCase().contains("mysql"))
            Assert.assertFalse(statement.getMoreResults());
        else
            Assert.assertThrows(SQLException.class, () -> statement.getMoreResults());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testFetchDirection(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        Assert.assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        Assert.assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testFetchSize(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        int originalFetchSize = statement.getFetchSize();
        statement.setFetchSize(100);
        Assert.assertEquals(100, statement.getFetchSize());
        statement.setFetchSize(originalFetchSize);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testResultSetConcurrency(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        int concurrency = statement.getResultSetConcurrency();
        Assert.assertTrue(concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testResultSetType(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        int type = statement.getResultSetType();
        Assert.assertTrue(type == ResultSet.TYPE_FORWARD_ONLY || 
                   type == ResultSet.TYPE_SCROLL_INSENSITIVE || 
                   type == ResultSet.TYPE_SCROLL_SENSITIVE);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testAddBatch(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        statement.addBatch("INSERT INTO mysql_statement_test (id, name) VALUES (10, 'Batch1')");
        statement.addBatch("INSERT INTO mysql_statement_test (id, name) VALUES (11, 'Batch2')");
        statement.clearBatch();
        
        statement.addBatch("INSERT INTO mysql_statement_test (id, name) VALUES (12, 'Batch3')");
        int[] results = statement.executeBatch();
        Assert.assertEquals(1, results.length);
        Assert.assertEquals(1, results[0]);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testGetConnection(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        Assert.assertSame(connection, statement.getConnection());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testGetGeneratedKeys(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        
        // Create table with auto-increment
        try {
            statement.execute("DROP TABLE mysql_auto_test");
        } catch (SQLException e) {
            // Ignore if table doesn't exist
        }
        statement.execute("CREATE TABLE mysql_auto_test (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100))");
        
        statement.execute("INSERT INTO mysql_auto_test (name) VALUES ('Test')", Statement.RETURN_GENERATED_KEYS);
        ResultSet keys = statement.getGeneratedKeys();
        Assert.assertNotNull(keys);
        Assert.assertTrue(keys.next());
        Assert.assertTrue(keys.getInt(1) > 0);
        keys.close();
        
        statement.execute("DROP TABLE mysql_auto_test");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testExecuteUpdateWithGeneratedKeys(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        
        // Create table with auto-increment
        try{
            statement.execute("DROP TABLE mysql_auto_test2");
        } catch (SQLException e) {
            // Ignore if table doesn't exist
        }
        statement.execute("CREATE TABLE mysql_auto_test2 (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100))");
        
        int rows = statement.executeUpdate("INSERT INTO mysql_auto_test2 (name) VALUES ('Test')", Statement.RETURN_GENERATED_KEYS);
        Assert.assertEquals(1, rows);
        
        ResultSet keys = statement.getGeneratedKeys();
        Assert.assertNotNull(keys);
        Assert.assertTrue(keys.next());
        Assert.assertTrue(keys.getInt(1) > 0);
        keys.close();
        
        statement.execute("DROP TABLE mysql_auto_test2");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testResultSetHoldability(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        int holdability = statement.getResultSetHoldability();
        Assert.assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT || 
                   holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testCancel(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        // Test that cancel doesn't throw an exception
        statement.cancel();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testEscapeProcessing(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        statement.setEscapeProcessing(true);
        statement.setEscapeProcessing(false);
        // Just verify these calls don't throw exceptions
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testCursorName(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        // MySQL may not support named cursors in all configurations
        if (url.toLowerCase().contains("mysql"))
            statement.setCursorName("test_cursor");
        else
            Assert.assertThrows(SQLException.class, () -> statement.setCursorName("test_cursor"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    public void testPoolable(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        boolean poolable = statement.isPoolable();
        statement.setPoolable(!poolable);
        if (url.toLowerCase().contains("mysql"))
            Assert.assertEquals(!poolable, statement.isPoolable());
        else
            Assert.assertEquals(false, statement.isPoolable());


        statement.setPoolable(poolable);
    }
}