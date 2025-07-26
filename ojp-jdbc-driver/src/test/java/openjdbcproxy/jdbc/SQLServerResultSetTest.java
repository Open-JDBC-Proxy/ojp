package openjdbcproxy.jdbc;

import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.*;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SQL Server-specific ResultSet integration tests.
 * Tests SQL Server-specific ResultSet functionality and navigation.
 */
public class SQLServerResultSetTest {

    private static boolean isTestDisabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerBasicResultSetOperations(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server basic ResultSet operations for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_rs_basic_test", TestDBUtils.SqlSyntax.SQLSERVER);

        // Insert test data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_rs_basic_test (id, name) VALUES (?, ?)"
        );
        for (int i = 1; i <= 5; i++) {
            psInsert.setInt(1, i);
            psInsert.setString(2, "Test " + i);
            psInsert.addBatch();
        }
        psInsert.executeBatch();
        psInsert.close();

        // Test basic navigation
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_rs_basic_test ORDER BY id");
        ResultSet rs = ps.executeQuery();

        // Test next()
        int count = 0;
        while (rs.next()) {
            count++;
            Assert.assertEquals(count, rs.getInt("id"));
            Assert.assertEquals("Test " + count, rs.getString("name"));
        }
        Assert.assertEquals(5, count);

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_rs_basic_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerScrollableResultSet(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server scrollable ResultSet for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_rs_scroll_test", TestDBUtils.SqlSyntax.SQLSERVER);

        // Insert test data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_rs_scroll_test (id, name) VALUES (?, ?)"
        );
        for (int i = 1; i <= 10; i++) {
            psInsert.setInt(1, i);
            psInsert.setString(2, "Scroll Test " + i);
            psInsert.addBatch();
        }
        psInsert.executeBatch();
        psInsert.close();

        // Create scrollable ResultSet
        PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM sqlserver_rs_scroll_test ORDER BY id",
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_READ_ONLY
        );
        ResultSet rs = ps.executeQuery();

        // Test scrollable operations
        Assert.assertTrue("Should be able to move to first", rs.first());
        Assert.assertEquals(1, rs.getInt("id"));

        Assert.assertTrue("Should be able to move to last", rs.last());
        Assert.assertEquals(10, rs.getInt("id"));

        Assert.assertTrue("Should be able to move to absolute position", rs.absolute(5));
        Assert.assertEquals(5, rs.getInt("id"));

        Assert.assertTrue("Should be able to move relative", rs.relative(2));
        Assert.assertEquals(7, rs.getInt("id"));

        Assert.assertTrue("Should be able to move previous", rs.previous());
        Assert.assertEquals(6, rs.getInt("id"));

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_rs_scroll_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerResultSetTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server ResultSet types for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_rs_types_test", TestDBUtils.SqlSyntax.SQLSERVER);

        // Insert test data with various types
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_rs_types_test (val_int, val_varchar, val_double_precision, val_bigint, " +
                "val_tinyint, val_smallint, val_boolean, val_decimal) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        );
        psInsert.setInt(1, 42);
        psInsert.setString(2, "Type Test");
        psInsert.setDouble(3, 3.14159);
        psInsert.setLong(4, 9876543210L);
        psInsert.setByte(5, (byte) 255);
        psInsert.setShort(6, (short) 32000);
        psInsert.setBoolean(7, true);
        psInsert.setBigDecimal(8, new java.math.BigDecimal("123.45"));
        psInsert.executeUpdate();
        psInsert.close();

        // Test reading various types
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_rs_types_test");
        ResultSet rs = ps.executeQuery();

        Assert.assertTrue(rs.next());

        // Test getters by index
        Assert.assertEquals(42, rs.getInt(1));
        Assert.assertEquals("Type Test", rs.getString(2));
        Assert.assertEquals(3.14159, rs.getDouble(3), 0.00001);
        Assert.assertEquals(9876543210L, rs.getLong(4));
        Assert.assertEquals(255, rs.getByte(5));
        Assert.assertEquals(32000, rs.getShort(6));
        Assert.assertTrue(rs.getBoolean(7));
        Assert.assertEquals(new java.math.BigDecimal("123.45"), rs.getBigDecimal(8));

        // Test getters by name
        Assert.assertEquals(42, rs.getInt("val_int"));
        Assert.assertEquals("Type Test", rs.getString("val_varchar"));
        Assert.assertEquals(3.14159, rs.getDouble("val_double_precision"), 0.00001);
        Assert.assertEquals(9876543210L, rs.getLong("val_bigint"));
        Assert.assertEquals(255, rs.getByte("val_tinyint"));
        Assert.assertEquals(32000, rs.getShort("val_smallint"));
        Assert.assertTrue(rs.getBoolean("val_boolean"));
        Assert.assertEquals(new java.math.BigDecimal("123.45"), rs.getBigDecimal("val_decimal"));

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_rs_types_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerNullHandling(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server null handling for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_rs_null_test", TestDBUtils.SqlSyntax.SQLSERVER);

        // Insert row with all null values (except required columns)
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_rs_null_test (val_int, val_varchar) VALUES (?, ?)"
        );
        psInsert.setInt(1, 1);
        psInsert.setString(2, "Test");
        psInsert.executeUpdate();
        psInsert.close();

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_rs_null_test");
        ResultSet rs = ps.executeQuery();

        Assert.assertTrue(rs.next());

        // Test non-null values
        Assert.assertEquals(1, rs.getInt("val_int"));
        Assert.assertFalse(rs.wasNull());
        Assert.assertEquals("Test", rs.getString("val_varchar"));
        Assert.assertFalse(rs.wasNull());

        // Test null values
        Assert.assertEquals(0.0, rs.getDouble("val_double_precision"), 0.0);
        Assert.assertTrue(rs.wasNull());

        Assert.assertEquals(0L, rs.getLong("val_bigint"));
        Assert.assertTrue(rs.wasNull());

        Assert.assertNull(rs.getObject("val_double_precision"));
        Assert.assertNull(rs.getObject("val_bigint"));

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_rs_null_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerBinaryData(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server binary data handling for url -> " + url);

        // Create table with binary columns
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_rs_binary_test', 'U') IS NOT NULL DROP TABLE sqlserver_rs_binary_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_rs_binary_test (" +
                "id INT PRIMARY KEY, " +
                "binary_data VARBINARY(100), " +
                "large_binary VARBINARY(MAX))");

        // Insert binary data
        byte[] smallBinary = "Hello Binary World!".getBytes();
        byte[] largeBinary = new byte[1000];
        for (int i = 0; i < largeBinary.length; i++) {
            largeBinary[i] = (byte) (i % 256);
        }

        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_rs_binary_test (id, binary_data, large_binary) VALUES (?, ?, ?)"
        );
        psInsert.setInt(1, 1);
        psInsert.setBytes(2, smallBinary);
        psInsert.setBytes(3, largeBinary);
        psInsert.executeUpdate();
        psInsert.close();

        // Read and verify binary data
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_rs_binary_test");
        ResultSet rs = ps.executeQuery();

        Assert.assertTrue(rs.next());

        byte[] retrievedSmall = rs.getBytes("binary_data");
        Assert.assertArrayEquals(smallBinary, retrievedSmall);

        byte[] retrievedLarge = rs.getBytes("large_binary");
        Assert.assertEquals(largeBinary.length, retrievedLarge.length);
        for (int i = 0; i < 100; i++) { // Check first 100 bytes
            Assert.assertEquals(largeBinary[i], retrievedLarge[i]);
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_rs_binary_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerDateTimeHandling(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server date/time handling for url -> " + url);

        // Create table with various date/time types
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_rs_datetime_test', 'U') IS NOT NULL DROP TABLE sqlserver_rs_datetime_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_rs_datetime_test (" +
                "id INT PRIMARY KEY, " +
                "date_col DATE, " +
                "time_col TIME, " +
                "datetime2_col DATETIME2, " +
                "datetimeoffset_col DATETIMEOFFSET)");

        // Insert date/time data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO sqlserver_rs_datetime_test (id, date_col, time_col, datetime2_col, datetimeoffset_col) " +
                "VALUES (?, ?, ?, ?, ?)"
        );
        psInsert.setInt(1, 1);
        psInsert.setDate(2, Date.valueOf("2025-01-15"));
        psInsert.setTime(3, Time.valueOf("14:30:45"));
        psInsert.setTimestamp(4, Timestamp.valueOf("2025-01-15 14:30:45.123"));
        psInsert.setTimestamp(5, Timestamp.valueOf("2025-01-15 14:30:45.123"));
        psInsert.executeUpdate();
        psInsert.close();

        // Read and verify date/time data
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_rs_datetime_test");
        ResultSet rs = ps.executeQuery();

        Assert.assertTrue(rs.next());

        Date retrievedDate = rs.getDate("date_col");
        Assert.assertEquals(Date.valueOf("2025-01-15"), retrievedDate);

        Time retrievedTime = rs.getTime("time_col");
        Assert.assertEquals(Time.valueOf("14:30:45"), retrievedTime);

        Timestamp retrievedTimestamp = rs.getTimestamp("datetime2_col");
        Assert.assertNotNull(retrievedTimestamp);
        Assert.assertTrue(retrievedTimestamp.toString().contains("2025-01-15"));

        Timestamp retrievedOffset = rs.getTimestamp("datetimeoffset_col");
        Assert.assertNotNull(retrievedOffset);
        Assert.assertTrue(retrievedOffset.toString().contains("2025-01-15"));

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_rs_datetime_test");
        conn.close();
    }
}