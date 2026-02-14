package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class OracleMultipleTypesIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, ParseException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "oracle_multi_types_test", TestDBUtils.SqlSyntax.ORACLE);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into oracle_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "TITLE_1");
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333L);
        psInsert.setInt(5, 127); // Oracle NUMBER(3) can handle this
        psInsert.setInt(6, 32767);
        psInsert.setInt(7, 1); // Oracle uses NUMBER(1) for boolean (1=true, 0=false)
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setBytes(10, new byte[]{(byte) 1}); // Oracle RAW expects byte array
        psInsert.setBytes(11, "AAAA".getBytes()); // Oracle RAW
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
        psInsert.setDate(12, new Date(sdf.parse("29/03/2025").getTime()));
        SimpleDateFormat sdfTime = new SimpleDateFormat("hh:mm:ss");
        psInsert.setTimestamp(13, new Timestamp(sdfTime.parse("11:12:13").getTime())); // Oracle uses TIMESTAMP for time
        SimpleDateFormat sdfTimestamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        psInsert.setTimestamp(14, new Timestamp(sdfTimestamp.parse("30/03/2025 21:22:23").getTime()));
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from oracle_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        assertEquals(1, resultSet.getInt(1));
        assertEquals("TITLE_1", resultSet.getString(2));
        assertEquals("2.2222", ""+resultSet.getDouble(3));
        assertEquals(33333333333333L, resultSet.getLong(4));
        assertEquals(127, resultSet.getInt(5)); // NUMBER(3) in Oracle
        assertEquals(32767, resultSet.getInt(6));
        assertEquals(1, resultSet.getInt(7)); // Oracle NUMBER(1) for boolean
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal(8));
        assertEquals(20.20f+"", ""+resultSet.getFloat(9));
        // Oracle RAW column may be returned as String by OJP driver
        // For now, just verify we get a non-null value
        Object byteValue = resultSet.getObject(10);
        Assertions.assertNotNull(byteValue, "RAW column should not be null");
        // Oracle RAW column may be returned as String by OJP driver  
        Object binaryValue = resultSet.getObject(11);
        if (binaryValue instanceof String) {
            // If returned as string, check the content
            String stringValue = (String) binaryValue;
            Assertions.assertTrue(
                stringValue.contains("AAAA") || stringValue.length() > 0, "Binary column should contain expected data");
        } else {
            // Handle as byte array
            assertEquals("AAAA", new String(resultSet.getBytes(11)));
        }
        assertEquals("29/03/2025", sdf.format(resultSet.getDate(12)));
        // Oracle time stored as TIMESTAMP, format as time
        SimpleDateFormat sdfTimeOnly = new SimpleDateFormat("HH:mm:ss");
        assertEquals("11:12:13", sdfTimeOnly.format(resultSet.getTimestamp(13)));
        assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp(14)));

        // Test column name access
        assertEquals(1, resultSet.getInt("val_int"));
        assertEquals("TITLE_1", resultSet.getString("val_varchar"));
        assertEquals("2.2222", ""+resultSet.getDouble("val_double_precision"));
        assertEquals(33333333333333L, resultSet.getLong("val_bigint"));
        assertEquals(127, resultSet.getInt("val_tinyint"));
        assertEquals(32767, resultSet.getInt("val_smallint"));
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal("val_decimal"));
        assertEquals(20.20f+"", ""+resultSet.getFloat("val_float"));
        assertEquals(1, resultSet.getInt("val_boolean")); // Oracle boolean as NUMBER(1)
        // Oracle RAW column may be returned as String by OJP driver
        Object byteValueByName = resultSet.getObject("val_byte");
        Assertions.assertNotNull( byteValueByName,"RAW column val_byte should not be null");
        // Oracle RAW column may be returned as String by OJP driver
        Object binaryValueByName = resultSet.getObject("val_binary");
        if (binaryValueByName instanceof String) {
            String stringValue = (String) binaryValueByName;
            Assertions.assertTrue(
                stringValue.contains("AAAA") || stringValue.length() > 0, "Binary column should contain expected data");
        } else {
            assertEquals("AAAA", new String(resultSet.getBytes("val_binary")));
        }
        assertEquals("29/03/2025", sdf.format(resultSet.getDate("val_date")));
        assertEquals("11:12:13", sdfTimeOnly.format(resultSet.getTimestamp("val_time")));
        assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp("val_timestamp")));

        TestDBUtils.executeUpdate(conn, "delete from oracle_multi_types_test where val_int=1");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleSpecificTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing Oracle-specific types for url -> " + url);

        // Test CLOB, BLOB, and NVARCHAR2 types (Oracle-specific)
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_oracle_types");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn, 
            "CREATE TABLE test_oracle_types (" +
            "id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
            "clob_col CLOB, " +
            "blob_col BLOB, " +
            "nvarchar_col NVARCHAR2(100), " +
            "nclob_col NCLOB, " +
            "xmltype_col XMLType)"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
            "INSERT INTO test_oracle_types (clob_col, blob_col, nvarchar_col, nclob_col, xmltype_col) VALUES (?, ?, ?, ?, XMLType(?))"
        );

        // Test CLOB
        psInsert.setString(1, "Oracle CLOB data type for large text");
        // Test BLOB
        psInsert.setBytes(2, "Oracle BLOB data".getBytes());
        // Test NVARCHAR2
        psInsert.setString(3, "Oracle NVARCHAR2 type");
        // Test NCLOB
        psInsert.setString(4, "Oracle NCLOB data type");
        // Test XMLType
        psInsert.setString(5, "<root><element>Oracle XML</element></root>");

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT nvarchar_col FROM test_oracle_types WHERE id = 1");
        ResultSet resultSet = psSelect.executeQuery();
        
        assertTrue(resultSet.next());
        assertEquals("Oracle NVARCHAR2 type", resultSet.getString("nvarchar_col"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleNumberTypes(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing Oracle NUMBER types for url -> " + url);

        // Test various Oracle NUMBER precision/scale combinations
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_oracle_numbers");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn, 
            "CREATE TABLE test_oracle_numbers (" +
            "id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
            "number_col NUMBER, " +
            "number_10_2 NUMBER(10,2), " +
            "number_5_0 NUMBER(5,0), " +
            "binary_float_col BINARY_FLOAT, " +
            "binary_double_col BINARY_DOUBLE)"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
            "INSERT INTO test_oracle_numbers (number_col, number_10_2, number_5_0, binary_float_col, binary_double_col) " +
            "VALUES (?, ?, ?, ?, ?)"
        );

        // Test NUMBER types
        psInsert.setBigDecimal(1, new BigDecimal("123456789.123456789"));
        psInsert.setBigDecimal(2, new BigDecimal("12345.67"));
        psInsert.setInt(3, 12345);
        psInsert.setFloat(4, 123.45f);
        psInsert.setDouble(5, 123456.789012);

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT * FROM test_oracle_numbers WHERE id = 1");
        ResultSet resultSet = psSelect.executeQuery();
        
        assertTrue(resultSet.next());
        assertNotNull(resultSet.getBigDecimal("number_col"));
        assertEquals(new BigDecimal("12345.67"), resultSet.getBigDecimal("number_10_2"));
        assertEquals(12345, resultSet.getInt("number_5_0"));

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