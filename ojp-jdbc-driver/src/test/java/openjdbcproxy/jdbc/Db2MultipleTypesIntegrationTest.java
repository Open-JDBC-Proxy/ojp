package openjdbcproxy.jdbc;

import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class Db2MultipleTypesIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, ParseException, UnsupportedEncodingException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        // Set schema explicitly to avoid "object not found" errors
        try (java.sql.Statement schemaStmt = conn.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }

        System.out.println("Testing for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "DB2INST1.db2_multi_types_test", TestDBUtils.SqlSyntax.DB2);

        String dbEncoding = "ISO-8859-1"; // default fallback

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into DB2INST1.db2_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, new String("TITLE_1".getBytes(), dbEncoding));
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333l);
        psInsert.setInt(5, 127); // DB2 SMALLINT can handle this
        psInsert.setInt(6, 32767);
        psInsert.setBoolean(7, true); // DB2 has native boolean support
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setBytes(10, new byte[]{(byte) 1}); // DB2 VARBINARY expects byte array
        psInsert.setBytes(11, "AAAA".getBytes(StandardCharsets.UTF_8)); // DB2 VARBINARY with UTF-8
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
        psInsert.setDate(12, new Date(sdf.parse("29/03/2025").getTime()));
        SimpleDateFormat sdfTime = new SimpleDateFormat("hh:mm:ss");
        psInsert.setTime(13, new Time(sdfTime.parse("11:12:13").getTime()));
        SimpleDateFormat sdfTimestamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        psInsert.setTimestamp(14, new Timestamp(sdfTimestamp.parse("30/03/2025 21:22:23").getTime()));
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from DB2INST1.db2_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        Assert.assertEquals(1, resultSet.getInt(1));
        Assert.assertEquals("TITLE_1", resultSet.getString(2));
        Assert.assertEquals("2.2222", ""+resultSet.getDouble(3));
        Assert.assertEquals(33333333333333L, resultSet.getLong(4));
        Assert.assertEquals(127, resultSet.getInt(5)); // SMALLINT in DB2
        Assert.assertEquals(32767, resultSet.getInt(6));
        Assert.assertEquals(true, resultSet.getBoolean(7)); // DB2 native boolean
        Assert.assertEquals(new BigDecimal("10.00"), resultSet.getBigDecimal(8));
        Assert.assertEquals(20.20f+"", ""+resultSet.getFloat(9));
        // DB2 VARBINARY column
        byte[] byteValue = resultSet.getBytes(10);
        Assert.assertNotNull("VARBINARY column should not be null", byteValue);
        Assert.assertEquals(1, byteValue.length);
        Assert.assertEquals(1, byteValue[0]);
        // DB2 VARBINARY column
        Assert.assertEquals("AAAA", new String(resultSet.getBytes(11), StandardCharsets.UTF_8));
        Assert.assertEquals("29/03/2025", sdf.format(resultSet.getDate(12)));
        Assert.assertEquals("11:12:13", sdfTime.format(resultSet.getTime(13)));
        Assert.assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp(14)));

        // Test column name access
        Assert.assertEquals(1, resultSet.getInt("val_int"));
        Assert.assertEquals("TITLE_1", resultSet.getString("val_varchar"));
        Assert.assertEquals("2.2222", ""+resultSet.getDouble("val_double_precision"));
        Assert.assertEquals(33333333333333L, resultSet.getLong("val_bigint"));
        Assert.assertEquals(127, resultSet.getInt("val_tinyint"));
        Assert.assertEquals(32767, resultSet.getInt("val_smallint"));
        Assert.assertEquals(new BigDecimal("10.00"), resultSet.getBigDecimal("val_decimal"));
        Assert.assertEquals(20.20f+"", ""+resultSet.getFloat("val_float"));
        Assert.assertEquals(true, resultSet.getBoolean("val_boolean")); // DB2 native boolean
        // DB2 VARBINARY column
        byte[] byteValueByName = resultSet.getBytes("val_byte");
        Assert.assertNotNull("VARBINARY column val_byte should not be null", byteValueByName);
        Assert.assertEquals(1, byteValueByName.length);
        Assert.assertEquals(1, byteValueByName[0]);
        Assert.assertEquals("AAAA", new String(resultSet.getBytes("val_binary")));
        Assert.assertEquals("29/03/2025", sdf.format(resultSet.getDate("val_date")));
        Assert.assertEquals("11:12:13", sdfTime.format(resultSet.getTime("val_time")));
        Assert.assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp("val_timestamp")));

        TestDBUtils.executeUpdate(conn, "delete from db2_multi_types_test where val_int=1");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        Assert.assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testDb2SpecificTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing DB2-specific types for url -> " + url);

        // Test CLOB, BLOB, and GRAPHIC types (DB2-specific)
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_db2_types");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn, 
            "CREATE TABLE test_db2_types (" +
            "id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
            "clob_col CLOB(1M), " +
            "blob_col BLOB(1M), " +
            "graphic_col GRAPHIC(50), " +
            "dbclob_col DBCLOB(1M))"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
            "INSERT INTO test_db2_types (clob_col, blob_col, graphic_col, dbclob_col) VALUES (?, ?, ?, ?)"
        );

        // Test CLOB
        psInsert.setString(1, "DB2 CLOB data type for large text");
        // Test BLOB
        psInsert.setBytes(2, "DB2 BLOB data".getBytes());
        // Test GRAPHIC (fixed-length double-byte character)
        psInsert.setString(3, "DB2 GRAPHIC type");
        // Test DBCLOB
        psInsert.setString(4, "DB2 DBCLOB data type");

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT graphic_col FROM test_db2_types WHERE id = 1");
        ResultSet resultSet = psSelect.executeQuery();
        
        Assert.assertTrue(resultSet.next());
        String graphicValue = resultSet.getString("graphic_col");
        Assert.assertNotNull(graphicValue);
        Assert.assertTrue(graphicValue.contains("DB2 GRAPHIC type"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testDb2NumericTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        // Set schema explicitly to avoid "object not found" errors
        try (java.sql.Statement schemaStmt = conn.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }

        System.out.println("Testing DB2 numeric types for url -> " + url);

        // Test various DB2 numeric types
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE DB2INST1.test_db2_numbers");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn, 
            "CREATE TABLE DB2INST1.test_db2_numbers (" +
            "id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
            "smallint_col SMALLINT, " +
            "integer_col INTEGER, " +
            "bigint_col BIGINT, " +
            "decimal_col DECIMAL(10,2), " +
            "numeric_col NUMERIC(15,5), " +
            "real_col REAL, " +
            "double_col DOUBLE)"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
            "INSERT INTO DB2INST1.test_db2_numbers (smallint_col, integer_col, bigint_col, decimal_col, numeric_col, real_col, double_col) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)"
        );

        // Test numeric types
        psInsert.setShort(1, (short) 32767);
        psInsert.setInt(2, 2147483647);
        psInsert.setLong(3, 9223372036854775807L);
        psInsert.setBigDecimal(4, new BigDecimal("12345.67"));
        psInsert.setBigDecimal(5, new BigDecimal("1234567890.12345"));
        psInsert.setFloat(6, 123.45f);
        psInsert.setDouble(7, 123456.789012);

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM DB2INST1.test_db2_numbers WHERE id = 1");
        ResultSet resultSet = psSelect.executeQuery();
        
        Assert.assertTrue(resultSet.next());
        Assert.assertEquals(32767, resultSet.getInt("smallint_col")); // Use getInt instead of getShort to avoid ClassCast
        Assert.assertEquals(2147483647, resultSet.getInt("integer_col"));
        Assert.assertEquals(9223372036854775807L, resultSet.getLong("bigint_col"));
        Assert.assertEquals(new BigDecimal("12345.67"), resultSet.getBigDecimal("decimal_col"));
        Assert.assertNotNull(resultSet.getBigDecimal("numeric_col"));
        Assert.assertEquals(123.45f, resultSet.getFloat("real_col"), 0.001);
        Assert.assertEquals(123456.789012, resultSet.getDouble("double_col"), 0.0001);

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testDb2DateTimeTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing DB2 date/time types for url -> " + url);

        // Test DB2 date/time types
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_db2_datetime");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn, 
            "CREATE TABLE test_db2_datetime (" +
            "id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
            "date_col DATE, " +
            "time_col TIME, " +
            "timestamp_col TIMESTAMP)"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
            "INSERT INTO test_db2_datetime (date_col, time_col, timestamp_col) VALUES (?, ?, ?)"
        );

        Date testDate = Date.valueOf("2025-03-29");
        Time testTime = Time.valueOf("11:12:13");
        Timestamp testTimestamp = Timestamp.valueOf("2025-03-30 21:22:23");

        psInsert.setDate(1, testDate);
        psInsert.setTime(2, testTime);
        psInsert.setTimestamp(3, testTimestamp);

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM test_db2_datetime WHERE id = 1");
        ResultSet resultSet = psSelect.executeQuery();
        
        Assert.assertTrue(resultSet.next());
        Assert.assertEquals(testDate.toString(), resultSet.getDate("date_col").toString());
        Assert.assertEquals(testTime.toString(), resultSet.getTime("time_col").toString());
        Assert.assertEquals(testTimestamp.toString(), resultSet.getTimestamp("timestamp_col").toString());

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    /**
     * Helper method to convert hex string to byte array
     */
    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}