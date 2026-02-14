package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

 class MySQLMultipleTypesIntegrationTest {

    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ParseException {
        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        
        // Skip MariaDB tests if not enabled
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "mysql_multi_types_test", TestDBUtils.SqlSyntax.MYSQL);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into mysql_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "TITLE_1");
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333L);
        psInsert.setInt(5, 127);
        psInsert.setInt(6, 32767);
        psInsert.setBoolean(7, true);
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setByte(10, (byte) 1);
        psInsert.setBytes(11, "AAAA".getBytes());

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        Date valDate = new Date(sdf.parse("2024-12-01").getTime());
        psInsert.setDate(12, valDate);

        SimpleDateFormat sdfTime = new SimpleDateFormat("HH:mm:ss");
        Time valTime = new Time(sdfTime.parse("10:10:10").getTime());
        psInsert.setTime(13, valTime);

        SimpleDateFormat sdfTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Timestamp valTimestamp = new Timestamp(sdfTimestamp.parse("2024-12-01 10:10:10").getTime());
        psInsert.setTimestamp(14, valTimestamp);

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from mysql_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();

        // Validate all data types
        int valInt = resultSet.getInt(1);
        String valVarchar = resultSet.getString(2);
        double valDoublePrecision = resultSet.getDouble(3);
        long valBigint = resultSet.getLong(4);
        int valTinyint = resultSet.getInt(5);
        int valSmallint = resultSet.getInt(6);
        boolean valBoolean = resultSet.getBoolean(7);
        BigDecimal valDecimal = resultSet.getBigDecimal(8);
        float valFloat = resultSet.getFloat(9);
        byte valByte = resultSet.getByte(10);
        byte[] valBinary = resultSet.getBytes(11);
        Date valDateRet = resultSet.getDate(12);
        Time valTimeRet = resultSet.getTime(13);
        Timestamp valTimestampRet = resultSet.getTimestamp(14);

        // Assertions
        assertEquals(1, valInt);
        assertEquals("TITLE_1", valVarchar);
        assertEquals(2.2222d, valDoublePrecision, 0.00001);
        assertEquals(33333333333333L, valBigint);
        assertEquals(127, valTinyint);
        assertEquals(32767, valSmallint);
        assertTrue( valBoolean);
        assertEquals(new BigDecimal(10), valDecimal);
        assertEquals(20.20f, valFloat, 0.001);
        assertEquals((byte) 49, valByte);//Mysql return the byte as a character somehow and the byte value of character 1 is 49 (tested with direct connection)
        assertEquals("AAAA", new String(valBinary));
        assertEquals(valDate, valDateRet);
        assertEquals(valTime, valTimeRet);
        assertEquals(valTimestamp, valTimestampRet);

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void mysqlSpecificTypesTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException {
        // Skip MySQL tests if not enabled
        if (url.toLowerCase().contains("mysql") && !isMySQLTestEnabled) {
            assumeFalse(true, "Skipping MySQL tests");
        }
        
        // Skip MariaDB tests if not enabled  
        if (url.toLowerCase().contains("mariadb") && !isMariaDBTestEnabled) {
            assumeFalse(true, "Skipping MariaDB tests");
        }
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing MySQL-specific types for url -> " + url);

        TestDBUtils.createMySQLSpecificTestTable(conn, "mysql_specific_types_test");

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into mysql_specific_types_test (enum_col, json_col, text_col, mediumtext_col, " +
                        "longtext_col, blob_col, mediumblob_col, longblob_col, set_col, year_col, bit_col) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setString(1, "medium");
        psInsert.setString(2, "{\"key\": \"value\", \"number\": 42}");
        psInsert.setString(3, "This is a TEXT column");
        psInsert.setString(4, "This is a MEDIUMTEXT column with more content");
        psInsert.setString(5, "This is a LONGTEXT column with even more content");
        psInsert.setBytes(6, "BLOB data".getBytes());
        psInsert.setBytes(7, "MEDIUMBLOB data".getBytes());
        psInsert.setBytes(8, "LONGBLOB data".getBytes());
        psInsert.setString(9, "option1,option3");
        psInsert.setInt(10, 2024);
        psInsert.setByte(11, (byte) 0b101010);

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from mysql_specific_types_test where id = LAST_INSERT_ID()");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();

        // Validate MySQL-specific data types
        int id = resultSet.getInt(1);
        String enumCol = resultSet.getString(2);
        String jsonCol = resultSet.getString(3);
        String textCol = resultSet.getString(4);
        String mediumtextCol = resultSet.getString(5);
        String longtextCol = resultSet.getString(6);
        byte[] blobCol = resultSet.getBytes(7);
        byte[] mediumblobCol = resultSet.getBytes(8);
        byte[] longblobCol = resultSet.getBytes(9);
        String setCol = resultSet.getString(10);
        int yearCol = resultSet.getInt(11);
        byte bitCol = resultSet.getByte(12);

        // Assertions
        assertTrue(id > 0);
        assertEquals("medium", enumCol);
        assertEquals("{\"key\": \"value\", \"number\": 42}", jsonCol);
        assertEquals("This is a TEXT column", textCol);
        assertEquals("This is a MEDIUMTEXT column with more content", mediumtextCol);
        assertEquals("This is a LONGTEXT column with even more content", longtextCol);
        assertEquals("BLOB data", new String(blobCol));
        assertEquals("MEDIUMBLOB data", new String(mediumblobCol));
        assertEquals("LONGBLOB data", new String(longblobCol));
        assertEquals("option1,option3", setCol);
        assertEquals(2024, yearCol);
        assertEquals((byte) 0b101010, bitCol);

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }
}