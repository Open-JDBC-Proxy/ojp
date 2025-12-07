package openjproxy.jdbc;

import openjproxy.jdbc.testutil.MariaDBConnectionProvider;
import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledIf("openjproxy.jdbc.testutil.MariaDBTestContainer#isEnabled")
public class MariaDBStatementExtensiveTests {

    private static boolean isMariaDBTestEnabled;
    private static boolean isMariaDBTestDisabled;
    private Connection connection;
    private Statement statement;

    @BeforeAll
    public static void checkTestConfiguration() {
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
        isMariaDBTestDisabled = Boolean.parseBoolean(System.getProperty("disableMariaDBTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        // MariaDB tests controlled by @EnabledIf annotation
        assumeFalse(isMariaDBTestDisabled, "MariaDB tests are disabled");

        connection = DriverManager.getConnection(url, user, password);
        statement = connection.createStatement();
        TestDBUtils.createBasicTestTable(connection, "mysql_statement_test", TestDBUtils.SqlSyntax.MYSQL, true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestDBUtils.closeQuietly(statement, connection);
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testExecuteQuery(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        ResultSet rs = statement.executeQuery("SELECT * FROM mysql_statement_test");
        Assert.assertNotNull(rs);
        Assert.assertTrue(rs.next());
        rs.close();
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
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
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testClose(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        Assert.assertFalse(statement.isClosed());
        statement.close();
        Assert.assertTrue(statement.isClosed());
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
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
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testMaxRows(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        Assert.assertEquals(0, statement.getMaxRows());
        statement.setMaxRows(10);
        Assert.assertEquals(10, statement.getMaxRows());
        statement.setMaxRows(0);
        Assert.assertEquals(0, statement.getMaxRows());
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testQueryTimeout(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        Assert.assertEquals(0, statement.getQueryTimeout());
        statement.setQueryTimeout(30);
        Assert.assertEquals(30, statement.getQueryTimeout());
        statement.setQueryTimeout(0);
        Assert.assertEquals(0, statement.getQueryTimeout());
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testWarnings(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        // Initial warnings might be null
        statement.getWarnings();
        statement.clearWarnings();
        Assert.assertNull(statement.getWarnings());
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
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
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testGetResultSet(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        statement.execute("SELECT * FROM mysql_statement_test");
        ResultSet rs = statement.getResultSet();
        Assert.assertNotNull(rs);
        rs.close();
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testGetUpdateCount(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        statement.execute("UPDATE mysql_statement_test SET name = 'Test Update' WHERE id = 1");
        Assert.assertEquals(1, statement.getUpdateCount());
        
        statement.execute("SELECT * FROM mysql_statement_test");
        Assert.assertEquals(-1, statement.getUpdateCount());
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testGetMoreResults(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        statement.execute("SELECT * FROM mysql_statement_test");
        if (url.toLowerCase().contains("mysql"))
            Assert.assertFalse(statement.getMoreResults());
        else
            Assert.assertThrows(SQLException.class, () -> statement.getMoreResults());
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testFetchDirection(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        Assert.assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
        statement.setFetchDirection(ResultSet.FETCH_FORWARD);
        Assert.assertEquals(ResultSet.FETCH_FORWARD, statement.getFetchDirection());
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testFetchSize(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        int originalFetchSize = statement.getFetchSize();
        statement.setFetchSize(100);
        Assert.assertEquals(100, statement.getFetchSize());
        statement.setFetchSize(originalFetchSize);
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testResultSetConcurrency(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        int concurrency = statement.getResultSetConcurrency();
        Assert.assertTrue(concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE);
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testResultSetType(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        int type = statement.getResultSetType();
        Assert.assertTrue(type == ResultSet.TYPE_FORWARD_ONLY || 
                   type == ResultSet.TYPE_SCROLL_INSENSITIVE || 
                   type == ResultSet.TYPE_SCROLL_SENSITIVE);
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
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
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testGetConnection(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        Assert.assertSame(connection, statement.getConnection());
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
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
    @ArgumentsSource(MariaDBConnectionProvider.class)
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
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testResultSetHoldability(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        int holdability = statement.getResultSetHoldability();
        Assert.assertTrue(holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT || 
                   holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testCancel(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        // Test that cancel doesn't throw an exception
        statement.cancel();
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testEscapeProcessing(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        statement.setEscapeProcessing(true);
        statement.setEscapeProcessing(false);
        // Just verify these calls don't throw exceptions
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
    public void testCursorName(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        // MySQL may not support named cursors in all configurations
        if (url.toLowerCase().contains("mysql"))
            statement.setCursorName("test_cursor");
        else
            Assert.assertThrows(SQLException.class, () -> statement.setCursorName("test_cursor"));
    }

    @ParameterizedTest
    @ArgumentsSource(MariaDBConnectionProvider.class)
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