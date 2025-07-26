package openjdbcproxy.jdbc;

import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.*;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SQL Server-specific Statement integration tests.
 * Tests SQL Server-specific Statement functionality and SQL syntax.
 */
public class SQLServerStatementExtensiveTests {

    private static boolean isTestDisabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerBasicStatementOperations(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server basic Statement operations for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_stmt_basic_test", TestDBUtils.SqlSyntax.SQLSERVER);

        Statement stmt = conn.createStatement();

        // Test INSERT
        int insertCount = stmt.executeUpdate(
                "INSERT INTO sqlserver_stmt_basic_test (id, name) VALUES (1, N'Statement Test')"
        );
        Assert.assertEquals(1, insertCount);

        // Test SELECT
        ResultSet rs = stmt.executeQuery("SELECT * FROM sqlserver_stmt_basic_test WHERE id = 1");
        Assert.assertTrue(rs.next());
        Assert.assertEquals(1, rs.getInt("id"));
        Assert.assertEquals("Statement Test", rs.getString("name"));
        rs.close();

        // Test UPDATE
        int updateCount = stmt.executeUpdate(
                "UPDATE sqlserver_stmt_basic_test SET name = N'Updated Test' WHERE id = 1"
        );
        Assert.assertEquals(1, updateCount);

        // Verify update
        rs = stmt.executeQuery("SELECT name FROM sqlserver_stmt_basic_test WHERE id = 1");
        Assert.assertTrue(rs.next());
        Assert.assertEquals("Updated Test", rs.getString("name"));
        rs.close();

        // Test DELETE
        int deleteCount = stmt.executeUpdate("DELETE FROM sqlserver_stmt_basic_test WHERE id = 1");
        Assert.assertEquals(1, deleteCount);

        // Verify delete
        rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_stmt_basic_test");
        Assert.assertTrue(rs.next());
        Assert.assertEquals(0, rs.getInt(1));
        rs.close();

        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_stmt_basic_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerSpecificSyntax(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server specific syntax for url -> " + url);

        Statement stmt = conn.createStatement();

        // Create table with SQL Server-specific features
        stmt.execute("IF OBJECT_ID('sqlserver_syntax_test', 'U') IS NOT NULL DROP TABLE sqlserver_syntax_test");
        
        stmt.execute("CREATE TABLE sqlserver_syntax_test (" +
                "id INT IDENTITY(1,1) PRIMARY KEY, " +
                "name NVARCHAR(50) NOT NULL, " +
                "created_date DATETIME2 DEFAULT GETDATE(), " +
                "guid_col UNIQUEIDENTIFIER DEFAULT NEWID())");

        // Test SQL Server-specific INSERT with IDENTITY
        stmt.execute("INSERT INTO sqlserver_syntax_test (name) VALUES (N'Test 1')");
        stmt.execute("INSERT INTO sqlserver_syntax_test (name) VALUES (N'Test 2')");
        stmt.execute("INSERT INTO sqlserver_syntax_test (name) VALUES (N'Test 3')");

        // Test SQL Server-specific TOP clause
        ResultSet rs = stmt.executeQuery("SELECT TOP 2 id, name FROM sqlserver_syntax_test ORDER BY id");
        int count = 0;
        while (rs.next()) {
            count++;
            Assert.assertEquals(count, rs.getInt("id"));
            Assert.assertEquals("Test " + count, rs.getString("name"));
        }
        Assert.assertEquals(2, count);
        rs.close();

        // Test SQL Server-specific OFFSET/FETCH
        rs = stmt.executeQuery(
                "SELECT id, name FROM sqlserver_syntax_test ORDER BY id OFFSET 1 ROWS FETCH NEXT 1 ROWS ONLY"
        );
        Assert.assertTrue(rs.next());
        Assert.assertEquals(2, rs.getInt("id"));
        Assert.assertEquals("Test 2", rs.getString("name"));
        Assert.assertFalse(rs.next());
        rs.close();

        // Test SQL Server-specific OUTPUT clause
        int insertResult = stmt.executeUpdate(
                "INSERT INTO sqlserver_syntax_test (name) OUTPUT INSERTED.id VALUES (N'Test with OUTPUT')"
        );
        Assert.assertEquals(1, insertResult);

        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_syntax_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerBatchStatements(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server batch statements for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_batch_stmt_test", TestDBUtils.SqlSyntax.SQLSERVER);

        Statement stmt = conn.createStatement();

        // Add multiple statements to batch
        stmt.addBatch("INSERT INTO sqlserver_batch_stmt_test (id, name) VALUES (1, N'Batch 1')");
        stmt.addBatch("INSERT INTO sqlserver_batch_stmt_test (id, name) VALUES (2, N'Batch 2')");
        stmt.addBatch("INSERT INTO sqlserver_batch_stmt_test (id, name) VALUES (3, N'Batch 3')");
        stmt.addBatch("UPDATE sqlserver_batch_stmt_test SET name = N'Updated Batch 1' WHERE id = 1");

        // Execute batch
        int[] results = stmt.executeBatch();
        Assert.assertEquals(4, results.length);
        for (int i = 0; i < 3; i++) {
            Assert.assertEquals(1, results[i]); // Each INSERT affects 1 row
        }
        Assert.assertEquals(1, results[3]); // UPDATE affects 1 row

        // Verify batch results
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_batch_stmt_test");
        Assert.assertTrue(rs.next());
        Assert.assertEquals(3, rs.getInt(1));
        rs.close();

        rs = stmt.executeQuery("SELECT name FROM sqlserver_batch_stmt_test WHERE id = 1");
        Assert.assertTrue(rs.next());
        Assert.assertEquals("Updated Batch 1", rs.getString(1));
        rs.close();

        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_batch_stmt_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerStoredProcedures(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server stored procedures for url -> " + url);

        Statement stmt = conn.createStatement();

        TestDBUtils.createBasicTestTable(conn, "sqlserver_proc_test", TestDBUtils.SqlSyntax.SQLSERVER);

        // Create a simple stored procedure
        try {
            stmt.execute("DROP PROCEDURE IF EXISTS GetTestData");
        } catch (SQLException e) {
            // Ignore if procedure doesn't exist
        }

        stmt.execute("CREATE PROCEDURE GetTestData @id INT AS " +
                "BEGIN " +
                "SELECT * FROM sqlserver_proc_test WHERE id = @id " +
                "END");

        // Insert test data
        stmt.execute("INSERT INTO sqlserver_proc_test (id, name) VALUES (1, N'Procedure Test')");

        // Test calling stored procedure
        CallableStatement cs = conn.prepareCall("{call GetTestData(?)}");
        cs.setInt(1, 1);
        ResultSet rs = cs.executeQuery();

        Assert.assertTrue(rs.next());
        Assert.assertEquals(1, rs.getInt("id"));
        Assert.assertEquals("Procedure Test", rs.getString("name"));
        rs.close();
        cs.close();

        // Clean up
        stmt.execute("DROP PROCEDURE GetTestData");
        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_proc_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerTransactionStatements(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server transaction statements for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_txn_stmt_test", TestDBUtils.SqlSyntax.SQLSERVER);

        Statement stmt = conn.createStatement();
        conn.setAutoCommit(false);

        try {
            // Insert data in transaction
            stmt.execute("INSERT INTO sqlserver_txn_stmt_test (id, name) VALUES (1, N'Transaction Test')");

            // Verify data exists in transaction
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_txn_stmt_test");
            Assert.assertTrue(rs.next());
            Assert.assertEquals(1, rs.getInt(1));
            rs.close();

            // Test explicit transaction commands
            stmt.execute("SAVE TRANSACTION SP1");
            
            stmt.execute("INSERT INTO sqlserver_txn_stmt_test (id, name) VALUES (2, N'Savepoint Test')");
            
            // Verify both records
            rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_txn_stmt_test");
            Assert.assertTrue(rs.next());
            Assert.assertEquals(2, rs.getInt(1));
            rs.close();

            // Rollback to savepoint
            stmt.execute("ROLLBACK TRANSACTION SP1");

            // Should only have first record
            rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_txn_stmt_test");
            Assert.assertTrue(rs.next());
            Assert.assertEquals(1, rs.getInt(1));
            rs.close();

            conn.commit();

        } finally {
            conn.setAutoCommit(true);
            stmt.close();
            TestDBUtils.cleanupTestTables(conn, "sqlserver_txn_stmt_test");
            conn.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerLargeQueries(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server large queries for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_large_query_test", TestDBUtils.SqlSyntax.SQLSERVER);

        Statement stmt = conn.createStatement();

        // Insert multiple records
        for (int i = 1; i <= 100; i++) {
            stmt.execute("INSERT INTO sqlserver_large_query_test (id, name) VALUES (" + i + ", N'Record " + i + "')");
        }

        // Test large result set
        ResultSet rs = stmt.executeQuery("SELECT * FROM sqlserver_large_query_test ORDER BY id");
        int count = 0;
        while (rs.next()) {
            count++;
            Assert.assertEquals(count, rs.getInt("id"));
            Assert.assertEquals("Record " + count, rs.getString("name"));
        }
        Assert.assertEquals(100, count);
        rs.close();

        // Test complex query with joins (self-join)
        rs = stmt.executeQuery(
                "SELECT a.id, a.name, b.name as other_name " +
                "FROM sqlserver_large_query_test a " +
                "INNER JOIN sqlserver_large_query_test b ON a.id = b.id - 1 " +
                "WHERE a.id <= 10 " +
                "ORDER BY a.id"
        );

        count = 0;
        while (rs.next()) {
            count++;
            Assert.assertEquals(count, rs.getInt("id"));
            Assert.assertEquals("Record " + count, rs.getString("name"));
            Assert.assertEquals("Record " + (count + 1), rs.getString("other_name"));
        }
        Assert.assertEquals(10, count);
        rs.close();

        stmt.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_large_query_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerStatementProperties(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server statement properties for url -> " + url);

        Statement stmt = conn.createStatement();

        // Test default properties
        Assert.assertEquals(ResultSet.TYPE_FORWARD_ONLY, stmt.getResultSetType());
        Assert.assertEquals(ResultSet.CONCUR_READ_ONLY, stmt.getResultSetConcurrency());

        // Test fetch size
        stmt.setFetchSize(50);
        Assert.assertEquals(50, stmt.getFetchSize());

        // Test max rows
        stmt.setMaxRows(100);
        Assert.assertEquals(100, stmt.getMaxRows());

        // Test query timeout
        stmt.setQueryTimeout(30);
        Assert.assertEquals(30, stmt.getQueryTimeout());

        // Test escape processing
        stmt.setEscapeProcessing(false);
        stmt.setEscapeProcessing(true);

        stmt.close();

        // Test different result set types
        Statement scrollableStmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_READ_ONLY
        );
        Assert.assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, scrollableStmt.getResultSetType());
        scrollableStmt.close();

        conn.close();
    }
}