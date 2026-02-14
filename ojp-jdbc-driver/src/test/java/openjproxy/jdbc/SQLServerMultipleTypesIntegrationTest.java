package openjproxy.jdbc;

import openjproxy.jdbc.testutil.SQLServerConnectionProvider;
import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledIf("openjproxy.jdbc.testutil.SQLServerTestContainer#isEnabled")
public class SQLServerMultipleTypesIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, ParseException {
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
        psInsert.setLong(4, 33333333333333L);
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
        assertEquals(1, resultSet.getInt(1));
        assertEquals("TITLE_1", resultSet.getString(2));
        assertEquals("2.2222", ""+resultSet.getDouble(3));
        assertEquals(33333333333333L, resultSet.getLong(4));
        assertEquals(255, resultSet.getInt(5)); // SQL Server TINYINT max value
        assertEquals(32767, resultSet.getInt(6));
        assertTrue( resultSet.getBoolean(7)); // SQL Server BIT
        assertEquals(new BigDecimal("10.00"), resultSet.getBigDecimal(8));
        assertEquals(20.20f+"", ""+resultSet.getFloat(9));
        
        // SQL Server VARBINARY columns 
        byte[] byteValue = resultSet.getBytes(10);
        Assertions.assertNotNull( byteValue,"VARBINARY column should not be null");
        assertEquals(1, byteValue.length);
        assertEquals((byte) 1, byteValue[0]);
        
        byte[] binaryValue = resultSet.getBytes(11);
        Assertions.assertNotNull(binaryValue,"VARBINARY column should not be null");
        assertEquals("AAAA", new String(binaryValue));
        
        assertEquals("29/03/2025", sdf.format(resultSet.getDate(12)));
        // SQL Server TIME type
        SimpleDateFormat sdfTimeOnly = new SimpleDateFormat("HH:mm:ss");
        assertEquals("11:12:13", sdfTimeOnly.format(resultSet.getTime(13)));
        assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp(14)));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_multi_types_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerSpecificTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
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
        
        assertTrue(resultSet.next());
        
        // Verify NTEXT with Unicode
        String ntextValue = resultSet.getString("ntext_col");
        assertEquals("NTEXT content with Unicode: 中文 🚀", ntextValue);
        
        // Verify TEXT
        String textValue = resultSet.getString("text_col");
        assertEquals("TEXT content", textValue);
        
        // Verify MONEY
        BigDecimal moneyValue = resultSet.getBigDecimal("money_col");
        assertEquals(new BigDecimal("123.4500"), moneyValue);
        
        // Verify SMALLMONEY
        BigDecimal smallmoneyValue = resultSet.getBigDecimal("smallmoney_col");
        assertEquals(new BigDecimal("67.8900"), smallmoneyValue);
        
        // Verify UNIQUEIDENTIFIER was generated
        String guidValue = resultSet.getString("uniqueidentifier_col");
        assertNotNull(guidValue);
        assertTrue(guidValue.length() > 30); // GUID format
        
        // Verify date/time types are not null
        assertNotNull(resultSet.getObject("datetimeoffset_col", java.time.OffsetDateTime.class));
        assertNotNull(resultSet.getTimestamp("datetime2_col"));
        assertNotNull(resultSet.getTimestamp("smalldatetime_col"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_specific_types_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerNullValues(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
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
        assertEquals(1, resultSet.getInt("val_int"));
        assertEquals("Test", resultSet.getString("val_varchar"));
        
        // Verify null values for columns not inserted
        assertEquals(0.0, resultSet.getDouble("val_double_precision"), 0.0);
        assertTrue(resultSet.wasNull());
        
        assertEquals(0L, resultSet.getLong("val_bigint"));
        assertTrue(resultSet.wasNull());
        
        assertNull(resultSet.getBytes("val_byte"));
        assertNull(resultSet.getDate("val_date"));
        assertNull(resultSet.getTime("val_time"));
        assertNull(resultSet.getTimestamp("val_timestamp"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_null_test");
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(SQLServerConnectionProvider.class)
    void testSqlServerLargeTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
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
        
        assertTrue(resultSet.next());
        
        // Verify large NVARCHAR(MAX)
        String nvarcharValue = resultSet.getString("nvarchar_max");
        assertTrue(nvarcharValue.length() > 40000);
        assertTrue(nvarcharValue.contains("Unicode: 中文 🚀"));
        
        // Verify large VARCHAR(MAX)
        String varcharValue = resultSet.getString("varchar_max");
        assertTrue(varcharValue.length() > 40000);
        
        // Verify large VARBINARY(MAX)
        byte[] varbinaryValue = resultSet.getBytes("varbinary_max");
        assertNotNull(varbinaryValue);
        assertEquals(10000, varbinaryValue.length);
        
        // Verify binary data integrity
        for (int i = 0; i < 100; i++) { // Check first 100 bytes
            assertEquals((byte) (i % 256), varbinaryValue[i]);
        }

        resultSet.close();
        psSelect.close();
        psInsert.close();
        
        // Clean up
        TestDBUtils.cleanupTestTables(conn, "sqlserver_large_types_test");
        conn.close();
    }
}