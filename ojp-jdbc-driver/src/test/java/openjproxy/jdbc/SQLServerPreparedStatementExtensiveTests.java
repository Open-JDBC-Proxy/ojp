package openjproxy.jdbc;

import openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SQL Server-specific PreparedStatement integration tests.
 * Tests SQL Server-specific PreparedStatement functionality and parameter handling.
 */
@EnabledIf("openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
public class SQLServerPreparedStatementExtensiveTests {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerPreparedStatementBasics(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server PreparedStatement basics for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_ps_test", TestDBUtils.SqlSyntax.SQLSERVER, true);

        // Test basic PreparedStatement operations
        PreparedStatement ps = conn.prepareStatement("INSERT INTO sqlserver_ps_test (id, name) VALUES (?, ?)");
        ps.setInt(1, 100);
        ps.setString(2, "PreparedStatement Test");
        int rowsAffected = ps.executeUpdate();
        assertEquals(1, rowsAffected);

        // Test query with PreparedStatement
        PreparedStatement psSelect = conn.prepareStatement("SELECT id, name FROM sqlserver_ps_test WHERE id = ?");
        psSelect.setInt(1, 100);
        ResultSet rs = psSelect.executeQuery();
        
        assertTrue(rs.next());
        assertEquals(100, rs.getInt("id"));
        assertEquals("PreparedStatement Test", rs.getString("name"));

        rs.close();
        psSelect.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_ps_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerParameterTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server parameter types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_param_test", TestDBUtils.SqlSyntax.SQLSERVER);

        PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sqlserver_param_test (val_int, val_varchar, val_double_precision, val_bigint, " +
                "val_tinyint, val_smallint, val_boolean, val_decimal, val_float, val_date, val_time, val_timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        // Set various parameter types
        ps.setInt(1, 42);
        ps.setString(2, "Parameter Test");
        ps.setDouble(3, 3.14159);
        ps.setLong(4, 9876543210L);
        ps.setByte(5, (byte) 200); // SQL Server TINYINT
        ps.setShort(6, (short) 1000);
        ps.setBoolean(7, true);
        ps.setBigDecimal(8, new BigDecimal("999.99"));
        ps.setFloat(9, 2.718f);
        ps.setDate(10, Date.valueOf("2025-01-15"));
        ps.setTime(11, Time.valueOf("14:30:45"));
        ps.setTimestamp(12, Timestamp.valueOf("2025-01-15 14:30:45"));

        int rowsAffected = ps.executeUpdate();
        assertEquals(1, rowsAffected);

        // Verify the inserted data
        PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM sqlserver_param_test WHERE val_int = ?");
        psSelect.setInt(1, 42);
        ResultSet rs = psSelect.executeQuery();
        
        assertTrue(rs.next());
        assertEquals(42, rs.getInt("val_int"));
        assertEquals("Parameter Test", rs.getString("val_varchar"));
        assertEquals(3.14159, rs.getDouble("val_double_precision"), 0.00001);
        assertEquals(9876543210L, rs.getLong("val_bigint"));
        assertEquals(200, rs.getInt("val_tinyint"));
        assertEquals(1000, rs.getInt("val_smallint"));
        assertTrue(rs.getBoolean("val_boolean"));
        assertEquals(new BigDecimal("999.99"), rs.getBigDecimal("val_decimal"));

        rs.close();
        psSelect.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_param_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerBatchUpdates(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server batch updates for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_batch_test", TestDBUtils.SqlSyntax.SQLSERVER, false);

        Statement statement = conn.createStatement();
        statement.execute("DELETE FROM sqlserver_batch_test");

        PreparedStatement ps = conn.prepareStatement("INSERT INTO sqlserver_batch_test (id, name) VALUES (?, ?)");

        // Add multiple batches
        for (int i = 1; i <= 5; i++) {
            ps.setInt(1, i + 100);
            ps.setString(2, "Batch Item " + i);
            ps.addBatch();
        }

        int[] results = ps.executeBatch();
        assertEquals(5, results.length);
        for (int result : results) {
            assertEquals(1, result); // Each insert should affect 1 row
        }

        // Verify all batched data was inserted
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sqlserver_batch_test");
        assertTrue(rs.next());
        assertEquals(5, rs.getInt(1));

        rs.close();
        stmt.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_batch_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerNullParameters(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server null parameters for url -> " + url);

        TestDBUtils.cleanupTestTables(conn, "sqlserver_null_param_test");
        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_null_param_test", TestDBUtils.SqlSyntax.SQLSERVER);

        PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sqlserver_null_param_test (val_int, val_varchar, val_double_precision, val_date) " +
                "VALUES (?, ?, ?, ?)"
        );

        // Set some null parameters
        ps.setInt(1, 1);
        ps.setNull(2, Types.NVARCHAR);
        ps.setNull(3, Types.FLOAT);
        ps.setNull(4, Types.DATE);

        int rowsAffected = ps.executeUpdate();
        assertEquals(1, rowsAffected);

        // Verify null values were inserted correctly
        PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM sqlserver_null_param_test WHERE val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();
        
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("val_int"));
        assertNull(rs.getString("val_varchar"));
        assertEquals(0.0, rs.getDouble("val_double_precision"), 0.0);
        assertTrue(rs.wasNull());
        assertNull(rs.getDate("val_date"));

        rs.close();
        psSelect.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_null_param_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerBinaryParameters(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server binary parameters for url -> " + url);

        // Create table with binary columns
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_binary_param_test', 'U') IS NOT NULL DROP TABLE sqlserver_binary_param_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_binary_param_test (" +
                "id INT PRIMARY KEY, " +
                "binary_data VARBINARY(100), " +
                "large_binary_data VARBINARY(MAX))");

        PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sqlserver_binary_param_test (id, binary_data, large_binary_data) VALUES (?, ?, ?)"
        );

        byte[] smallBinary = "Small binary data".getBytes();
        byte[] largeBinary = new byte[1000];
        for (int i = 0; i < largeBinary.length; i++) {
            largeBinary[i] = (byte) (i % 256);
        }

        ps.setInt(1, 1);
        ps.setBytes(2, smallBinary);
        ps.setBytes(3, largeBinary);

        int rowsAffected = ps.executeUpdate();
        assertEquals(1, rowsAffected);

        // Verify binary data was inserted correctly
        PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM sqlserver_binary_param_test WHERE id = ?");
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();
        
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("id"));
        
        byte[] retrievedSmall = rs.getBytes("binary_data");
        Assert.assertArrayEquals(smallBinary, retrievedSmall);
        
        byte[] retrievedLarge = rs.getBytes("large_binary_data");
        assertEquals(largeBinary.length, retrievedLarge.length);
        for (int i = 0; i < 100; i++) { // Check first 100 bytes
            assertEquals(largeBinary[i], retrievedLarge[i]);
        }

        rs.close();
        psSelect.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_binary_param_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerUpdateAndDelete(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server UPDATE and DELETE for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_update_test", TestDBUtils.SqlSyntax.SQLSERVER, false);
        Statement statement = conn.createStatement();
        statement.execute("DELETE FROM sqlserver_update_test");

        // Insert test data
        PreparedStatement psInsert = conn.prepareStatement("INSERT INTO sqlserver_update_test (id, name) VALUES (?, ?)");
        psInsert.setInt(1, 1);
        psInsert.setString(2, "Original Name");
        psInsert.executeUpdate();

        // Test UPDATE
        PreparedStatement psUpdate = conn.prepareStatement("UPDATE sqlserver_update_test SET name = ? WHERE id = ?");
        psUpdate.setString(1, "Updated Name");
        psUpdate.setInt(2, 1);
        int updateCount = psUpdate.executeUpdate();
        assertEquals(1, updateCount);

        // Verify update
        PreparedStatement psSelect = conn.prepareStatement("SELECT name FROM sqlserver_update_test WHERE id = ?");
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();
        assertTrue(rs.next());
        assertEquals("Updated Name", rs.getString("name"));
        rs.close();

        // Test DELETE
        PreparedStatement psDelete = conn.prepareStatement("DELETE FROM sqlserver_update_test WHERE id = ?");
        psDelete.setInt(1, 1);
        int deleteCount = psDelete.executeUpdate();
        assertEquals(1, deleteCount);

        // Verify delete
        rs = psSelect.executeQuery();
        assertFalse(rs.next()); // No rows should remain

        rs.close();
        psSelect.close();
        psDelete.close();
        psUpdate.close();
        psInsert.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_update_test");
        conn.close();
    }
}