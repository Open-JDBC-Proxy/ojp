package openjdbcproxy.jdbc;

import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class SQLServerMultipleTypesIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, ParseException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_multi_types_test", TestDBUtils.SqlSyntax.SQLSERVER);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "TITLE_1");
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333l);
        psInsert.setInt(5, 255); // SQL Server TINYINT is 0-255
        psInsert.setInt(6, 32767);
        psInsert.setBoolean(7, true); // SQL Server BIT type
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setBytes(10, new byte[]{(byte) 1}); // SQL Server VARBINARY
        psInsert.setBytes(11, "AAAA".getBytes()); // SQL Server VARBINARY
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
        psInsert.setDate(12, new Date(sdf.parse("29/03/2025").getTime()));
        SimpleDateFormat sdfTime = new SimpleDateFormat("HH:mm:ss");
        psInsert.setTime(13, new Time(sdfTime.parse("11:12:13").getTime())); // SQL Server TIME type
        SimpleDateFormat sdfTimestamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        psInsert.setTimestamp(14, new Timestamp(sdfTimestamp.parse("30/03/2025 21:22:23").getTime()));
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from sqlserver_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        Assert.assertEquals(1, resultSet.getInt(1));
        Assert.assertEquals("TITLE_1", resultSet.getString(2));
        Assert.assertEquals("2.2222", ""+resultSet.getDouble(3));
        Assert.assertEquals(33333333333333L, resultSet.getLong(4));
        Assert.assertEquals(255, resultSet.getInt(5)); // SQL Server TINYINT max value
        Assert.assertEquals(32767, resultSet.getInt(6));
        Assert.assertEquals(true, resultSet.getBoolean(7)); // SQL Server BIT
        Assert.assertEquals(new BigDecimal(10), resultSet.getBigDecimal(8));
        Assert.assertEquals(20.20f+"", ""+resultSet.getFloat(9));
        
        // SQL Server VARBINARY columns 
        byte[] byteValue = resultSet.getBytes(10);
        Assert.assertNotNull("VARBINARY column should not be null", byteValue);
        Assert.assertEquals(1, byteValue.length);
        Assert.assertEquals((byte) 1, byteValue[0]);
        
        byte[] binaryValue = resultSet.getBytes(11);
        Assert.assertNotNull("VARBINARY column should not be null", binaryValue);
        Assert.assertEquals("AAAA", new String(binaryValue));
        
        Assert.assertEquals("29/03/2025", sdf.format(resultSet.getDate(12)));
        // SQL Server TIME type
        SimpleDateFormat sdfTimeOnly = new SimpleDateFormat("HH:mm:ss");
        Assert.assertEquals("11:12:13", sdfTimeOnly.format(resultSet.getTime(13)));
        Assert.assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp(14)));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_multi_types_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerSpecificTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server specific types for url -> " + url);

        // Create table with SQL Server-specific types
        TestDBUtils.createSqlServerSpecificTestTable(conn, "sqlserver_specific_types_test");

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_specific_types_test (ntext_col, text_col, money_col, smallmoney_col, " +
                        "uniqueidentifier_col, datetimeoffset_col, datetime2_col, smalldatetime_col) " +
                        "values (?, ?, ?, ?, NEWID(), ?, ?, ?)"
        );

        psInsert.setString(1, "NTEXT content with Unicode: 中文 🚀");
        psInsert.setString(2, "TEXT content");
        psInsert.setBigDecimal(3, new BigDecimal("123.45")); // MONEY
        psInsert.setBigDecimal(4, new BigDecimal("67.89")); // SMALLMONEY
        // UNIQUEIDENTIFIER is auto-generated with NEWID()
        psInsert.setTimestamp(5, new Timestamp(System.currentTimeMillis())); // DATETIMEOFFSET
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis())); // DATETIME2
        psInsert.setTimestamp(7, new Timestamp(System.currentTimeMillis())); // SMALLDATETIME

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from sqlserver_specific_types_test");
        ResultSet resultSet = psSelect.executeQuery();
        
        Assert.assertTrue(resultSet.next());
        
        // Verify NTEXT with Unicode
        String ntextValue = resultSet.getString("ntext_col");
        Assert.assertEquals("NTEXT content with Unicode: 中文 🚀", ntextValue);
        
        // Verify TEXT
        String textValue = resultSet.getString("text_col");
        Assert.assertEquals("TEXT content", textValue);
        
        // Verify MONEY
        BigDecimal moneyValue = resultSet.getBigDecimal("money_col");
        Assert.assertEquals(new BigDecimal("123.45"), moneyValue);
        
        // Verify SMALLMONEY
        BigDecimal smallmoneyValue = resultSet.getBigDecimal("smallmoney_col");
        Assert.assertEquals(new BigDecimal("67.89"), smallmoneyValue);
        
        // Verify UNIQUEIDENTIFIER was generated
        String guidValue = resultSet.getString("uniqueidentifier_col");
        Assert.assertNotNull(guidValue);
        Assert.assertTrue(guidValue.length() > 30); // GUID format
        
        // Verify date/time types are not null
        Assert.assertNotNull(resultSet.getTimestamp("datetimeoffset_col"));
        Assert.assertNotNull(resultSet.getTimestamp("datetime2_col"));
        Assert.assertNotNull(resultSet.getTimestamp("smalldatetime_col"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_specific_types_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerNullValues(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server null values for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_null_test", TestDBUtils.SqlSyntax.SQLSERVER);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_null_test (val_int, val_varchar) values (?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "Test");
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from sqlserver_null_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        
        // Verify non-null values
        Assert.assertEquals(1, resultSet.getInt("val_int"));
        Assert.assertEquals("Test", resultSet.getString("val_varchar"));
        
        // Verify null values for columns not inserted
        Assert.assertEquals(0.0, resultSet.getDouble("val_double_precision"), 0.0);
        Assert.assertTrue(resultSet.wasNull());
        
        Assert.assertEquals(0L, resultSet.getLong("val_bigint"));
        Assert.assertTrue(resultSet.wasNull());
        
        Assert.assertNull(resultSet.getBytes("val_byte"));
        Assert.assertNull(resultSet.getDate("val_date"));
        Assert.assertNull(resultSet.getTime("val_time"));
        Assert.assertNull(resultSet.getTimestamp("val_timestamp"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_null_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerLargeTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing SQL Server large types for url -> " + url);

        // Create table with large types
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_large_types_test', 'U') IS NOT NULL DROP TABLE sqlserver_large_types_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_large_types_test (" +
                "id INT IDENTITY(1,1) PRIMARY KEY, " +
                "nvarchar_max NVARCHAR(MAX), " +
                "varchar_max VARCHAR(MAX), " +
                "varbinary_max VARBINARY(MAX))");

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into sqlserver_large_types_test (nvarchar_max, varchar_max, varbinary_max) values (?, ?, ?)"
        );

        // Create large text data
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeText.append("This is a large text string for testing purposes. ");
        }
        String largeTextStr = largeText.toString();
        
        // Create large binary data
        byte[] largeBinary = new byte[10000];
        for (int i = 0; i < largeBinary.length; i++) {
            largeBinary[i] = (byte) (i % 256);
        }

        psInsert.setString(1, largeTextStr + " Unicode: 中文 🚀"); // NVARCHAR(MAX)
        psInsert.setString(2, largeTextStr); // VARCHAR(MAX)
        psInsert.setBytes(3, largeBinary); // VARBINARY(MAX)

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from sqlserver_large_types_test");
        ResultSet resultSet = psSelect.executeQuery();
        
        Assert.assertTrue(resultSet.next());
        
        // Verify large NVARCHAR(MAX)
        String nvarcharValue = resultSet.getString("nvarchar_max");
        Assert.assertTrue(nvarcharValue.length() > 40000);
        Assert.assertTrue(nvarcharValue.contains("Unicode: 中文 🚀"));
        
        // Verify large VARCHAR(MAX)
        String varcharValue = resultSet.getString("varchar_max");
        Assert.assertTrue(varcharValue.length() > 40000);
        
        // Verify large VARBINARY(MAX)
        byte[] varbinaryValue = resultSet.getBytes("varbinary_max");
        Assert.assertNotNull(varbinaryValue);
        Assert.assertEquals(10000, varbinaryValue.length);
        
        // Verify binary data integrity
        for (int i = 0; i < 100; i++) { // Check first 100 bytes
            Assert.assertEquals((byte) (i % 256), varbinaryValue[i]);
        }

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_large_types_test");
        conn.close();
    }
}