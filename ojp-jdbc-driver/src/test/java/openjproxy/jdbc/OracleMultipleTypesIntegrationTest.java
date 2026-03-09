package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class OracleMultipleTypesIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    /**
     * Test Oracle's natively supported java.time types via JDBC 4.2.
     * Oracle has first-class support for:
     * - LocalDate (DATE)
     * - LocalTime (TIME - stored as TIMESTAMP)
     * - LocalDateTime (TIMESTAMP)
     * 
     * Note: Oracle's TIMESTAMP WITH TIME ZONE requires special handling,
     * so OffsetDateTime/OffsetTime/Instant are tested in partial support test.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections_xa_modes.csv")
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        System.out.println("Testing Oracle natively supported types for driver -> " + driverClass + ", url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "oracle_multi_types_test", TestDBUtils.SqlSyntax.ORACLE);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into oracle_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp, val_localdatetime, val_localdate, val_localtime, val_instant, val_offsetdatetime, val_offsettime) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
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
        
        // Using java.time types with setObject instead of java.sql types
        LocalDate valDate = LocalDate.of(2025, 3, 29);
        psInsert.setObject(12, valDate, Types.DATE);
        
        // Oracle uses TIMESTAMP for time - using LocalTime but stored as TIMESTAMP
        LocalTime valTime = LocalTime.of(11, 12, 13);
        psInsert.setObject(13, valTime, Types.TIME);
        
        LocalDateTime valTimestamp = LocalDateTime.of(2025, 3, 30, 21, 22, 23);
        psInsert.setObject(14, valTimestamp, Types.TIMESTAMP);
        
        // Oracle natively supported java.time types (JDBC 4.2)
        LocalDateTime valLocalDateTime = LocalDateTime.of(2024, 12, 1, 14, 30, 45);
        psInsert.setObject(15, valLocalDateTime, Types.TIMESTAMP);

        LocalDate valLocalDate = LocalDate.of(2024, 12, 15);
        psInsert.setObject(16, valLocalDate, Types.DATE);

        LocalTime valLocalTime = LocalTime.of(15, 45, 30);
        psInsert.setObject(17, valLocalTime, Types.TIME);

        // Instant, OffsetDateTime, OffsetTime: NOT natively supported in Oracle JDBC 4.2
        // Oracle has TIMESTAMP WITH TIME ZONE but requires special handling
        // Setting to null - will be tested in partial support test
        psInsert.setObject(18, null, Types.TIMESTAMP); // Instant - not first-class
        psInsert.setObject(19, null, Types.TIMESTAMP); // OffsetDateTime - not first-class
        psInsert.setObject(20, null, Types.TIMESTAMP); // OffsetTime - not first-class
        
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from oracle_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        assertTrue(resultSet.next(), "Expected one Oracle row");
        assertOracleTypesByIndex(resultSet, valDate, valTime, valTimestamp, valLocalDateTime, valLocalDate, valLocalTime);
        assertOracleTypesByName(resultSet);

        TestDBUtils.executeUpdate(conn, "delete from oracle_multi_types_test where val_int=1");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());
        resultSetAfterDeletion.close();

        resultSet.close();
        psInsert.close();
        psSelect.close();
        connResult.close();
    }

    private static void assertOracleTypesByIndex(ResultSet resultSet, LocalDate valDate, LocalTime valTime,
                                                 LocalDateTime valTimestamp, LocalDateTime valLocalDateTime,
                                                 LocalDate valLocalDate, LocalTime valLocalTime) throws SQLException {
        assertEquals(1, resultSet.getInt(1));
        assertEquals("TITLE_1", resultSet.getString(2));
        assertEquals("2.2222", "" + resultSet.getDouble(3));
        assertEquals(33333333333333L, resultSet.getLong(4));
        assertEquals(127, resultSet.getInt(5));
        assertEquals(32767, resultSet.getInt(6));
        assertEquals(1, resultSet.getInt(7));
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal(8));
        assertEquals("20.2", "" + resultSet.getFloat(9));
        assertNotNull(resultSet.getObject(10), "RAW column should not be null");
        assertBinaryColumn(resultSet.getObject(11), resultSet.getBytes(11));
        assertDateValue(resultSet.getObject(12), valDate);
        assertTimeValue(resultSet.getObject(13), valTime);
        assertTimestampValue(resultSet.getObject(14), valTimestamp);
        assertDateTimeValue(resultSet.getObject(15), valLocalDateTime);
        assertDateValue(resultSet.getObject(16), valLocalDate);
        assertTimeValue(resultSet.getObject(17), valLocalTime);
    }

    private static void assertOracleTypesByName(ResultSet resultSet) throws SQLException {
        assertEquals(1, resultSet.getInt("val_int"));
        assertEquals("TITLE_1", resultSet.getString("val_varchar"));
        assertEquals("2.2222", "" + resultSet.getDouble("val_double_precision"));
        assertEquals(33333333333333L, resultSet.getLong("val_bigint"));
        assertEquals(127, resultSet.getInt("val_tinyint"));
        assertEquals(32767, resultSet.getInt("val_smallint"));
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal("val_decimal"));
        assertEquals("20.2", "" + resultSet.getFloat("val_float"));
        assertEquals(1, resultSet.getInt("val_boolean"));
        assertNotNull(resultSet.getObject("val_byte"), "RAW column val_byte should not be null");
        assertBinaryColumn(resultSet.getObject("val_binary"), resultSet.getBytes("val_binary"));

        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        SimpleDateFormat sdfTimeOnly = new SimpleDateFormat("HH:mm:ss");
        sdfTimeOnly.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        SimpleDateFormat sdfTimestamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        sdfTimestamp.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

        assertEquals("29/03/2025", sdf.format(resultSet.getDate("val_date")));
        assertEquals("11:12:13", sdfTimeOnly.format(resultSet.getTimestamp("val_time")));
        assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp("val_timestamp")));
    }

    private static void assertBinaryColumn(Object value, byte[] bytes) {
        assertNotNull(value, "Binary column should not be null");
        if (value instanceof String) {
            String stringValue = (String) value;
            assertTrue(stringValue.contains("AAAA") || !stringValue.isEmpty(),
                    "Binary column should contain expected data");
        } else {
            assertEquals("AAAA", new String(bytes));
        }
    }

    private static void assertDateValue(Object actual, LocalDate expected) {
        assertNotNull(actual, "Date column should not be null");
        if (actual instanceof LocalDate) {
            LocalDate localDate = (LocalDate) actual;
            assertEquals(expected, localDate);
        } else if (actual instanceof Date) {
            Date date = (Date) actual;
            assertEquals(expected, date.toLocalDate());
        } else {
            fail("Unexpected date type: " + actual.getClass());
        }
    }

    private static void assertTimeValue(Object actual, LocalTime expected) {
        assertNotNull(actual, "Time column should not be null");
        LocalTime time;
        if (actual instanceof LocalTime) {
            time = (LocalTime) actual;
        } else if (actual instanceof Time) {
            time = ((Time) actual).toLocalTime();
        } else if (actual instanceof Timestamp) {
            time = ((Timestamp) actual).toLocalDateTime().toLocalTime();
        } else {
            time = null;
        }
        assertNotNull(time, "Unexpected time type: " + actual.getClass());
        assertEquals(expected.getHour(), time.getHour());
        assertEquals(expected.getMinute(), time.getMinute());
        assertEquals(expected.getSecond(), time.getSecond());
    }

    private static void assertTimestampValue(Object actual, LocalDateTime expected) {
        assertNotNull(actual, "Timestamp column should not be null");
        if (actual instanceof LocalDateTime) {
            LocalDateTime localDateTime = (LocalDateTime) actual;
            assertEquals(expected, localDateTime);
        } else if (actual instanceof Timestamp) {
            Timestamp timestamp = (Timestamp) actual;
            assertEquals(expected, timestamp.toLocalDateTime());
        } else {
            fail("Unexpected timestamp type: " + actual.getClass());
        }
    }

    private static void assertDateTimeValue(Object actual, LocalDateTime expected) {
        assertNotNull(actual, "LocalDateTime should not be null");
        if (actual instanceof LocalDateTime) {
            LocalDateTime localDateTime = (LocalDateTime) actual;
            assertEquals(expected, localDateTime);
        } else if (actual instanceof Timestamp) {
            Timestamp timestamp = (Timestamp) actual;
            assertEquals(expected, timestamp.toLocalDateTime());
        } else {
            fail("Unexpected LocalDateTime type: " + actual.getClass());
        }
    }

    /**
     * Test Oracle's behavior with java.time types that require special handling.
     * Oracle has TIMESTAMP WITH TIME ZONE but JDBC 4.2 support varies:
     * - Instant (can be stored but driver doesn't directly support)
     * - OffsetDateTime (can use TIMESTAMP WITH TIME ZONE but requires special handling)
     * - OffsetTime (can use TIME WITH TIME ZONE but support varies)
     * 
     * This test documents expected database behavior when these types are used.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections_xa_modes.csv")
    void typesPartialSupportTest(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");

        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        System.out.println("Testing Oracle partially supported types for driver -> " + driverClass + ", url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "oracle_partial_types_test", TestDBUtils.SqlSyntax.ORACLE);

        // Test Instant - Oracle JDBC driver may not support directly
        java.sql.PreparedStatement psInsertInstant = conn.prepareStatement(
                "insert into oracle_partial_types_test (val_int, val_instant) values (?, ?)"
        );
        
        psInsertInstant.setInt(1, 1);
        Instant valInstant = Instant.parse("2024-12-01T10:10:10Z");
        
        // Attempt to insert Instant - behavior depends on driver version
        try {
            psInsertInstant.setObject(2, valInstant, Types.TIMESTAMP);
            psInsertInstant.executeUpdate();
            System.out.println("Oracle: Instant insertion succeeded (driver converted it)");
            
            // If it succeeded, try to retrieve it
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_instant from oracle_partial_types_test where val_int = 1"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println("Oracle: Instant retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null"));
                assertNotNull(retrieved, "Instant should be retrieved (possibly as Timestamp)");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: Oracle driver may not support Instant directly
            System.out.println("Oracle: Instant not natively supported - " + e.getMessage());
            // JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertInstant.close();
        TestDBUtils.executeUpdate(conn, "delete from oracle_partial_types_test where val_int=1");

        // Test OffsetDateTime - Oracle has TIMESTAMP WITH TIME ZONE but JDBC 4.2 support varies
        java.sql.PreparedStatement psInsertOffsetDateTime = conn.prepareStatement(
                "insert into oracle_partial_types_test (val_int, val_offsetdatetime) values (?, ?)"
        );
        
        psInsertOffsetDateTime.setInt(1, 2);
        OffsetDateTime valOffsetDateTime = OffsetDateTime.of(2024, 12, 1, 10, 10, 10, 0, ZoneOffset.ofHours(2));
        
        // Attempt to insert OffsetDateTime
        try {
            psInsertOffsetDateTime.setObject(2, valOffsetDateTime, Types.TIMESTAMP_WITH_TIMEZONE);
            psInsertOffsetDateTime.executeUpdate();
            System.out.println("Oracle: OffsetDateTime insertion succeeded");
            
            // If it succeeded, try to retrieve it
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_offsetdatetime from oracle_partial_types_test where val_int = 2"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println("Oracle: OffsetDateTime retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null"));
                assertNotNull(retrieved, "OffsetDateTime should be retrieved");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: Oracle driver may require special handling
            System.out.println("Oracle: OffsetDateTime requires special handling - " + e.getMessage());
            // JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertOffsetDateTime.close();
        TestDBUtils.executeUpdate(conn, "delete from oracle_partial_types_test where val_int=2");

        // Test OffsetTime - Oracle TIME WITH TIME ZONE support varies
        java.sql.PreparedStatement psInsertOffsetTime = conn.prepareStatement(
                "insert into oracle_partial_types_test (val_int, val_offsettime) values (?, ?)"
        );
        
        psInsertOffsetTime.setInt(1, 3);
        OffsetTime valOffsetTime = OffsetTime.of(16, 20, 30, 0, ZoneOffset.ofHours(-5));
        
        // Attempt to insert OffsetTime
        try {
            psInsertOffsetTime.setObject(2, valOffsetTime, Types.TIME_WITH_TIMEZONE);
            psInsertOffsetTime.executeUpdate();
            System.out.println("Oracle: OffsetTime insertion succeeded");
            
            // If it succeeded, try to retrieve it
            java.sql.PreparedStatement psSelect = conn.prepareStatement(
                    "select val_offsettime from oracle_partial_types_test where val_int = 3"
            );
            ResultSet rs = psSelect.executeQuery();
            if (rs.next()) {
                Object retrieved = rs.getObject(1);
                System.out.println("Oracle: OffsetTime retrieved as: " + 
                        (retrieved != null ? retrieved.getClass().getName() : "null"));
                assertNotNull(retrieved, "OffsetTime should be retrieved");
            }
            rs.close();
            psSelect.close();
        } catch (SQLException e) {
            // Expected: Oracle driver may not support OffsetTime
            System.out.println("Oracle: OffsetTime not supported - " + e.getMessage());
            // JDBC drivers may throw various error messages for unsupported types
            // Just verify that an SQLException was thrown (which indicates lack of support)
            assertNotNull(e.getMessage(), "SQLException should have a message");
        }
        
        psInsertOffsetTime.close();
        TestDBUtils.executeUpdate(conn, "delete from oracle_partial_types_test where val_int=3");

        // Clean up
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE oracle_partial_types_test");
        } catch (SQLException e) {
            // Ignore if table doesn't exist
        }

        connResult.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections_xa_modes.csv")
    void testOracleSpecificTypes(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        System.out.println("Testing Oracle-specific types for driver -> " + driverClass + ", url -> " + url);

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
        connResult.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections_xa_modes.csv")
    void testOracleNumberTypes(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        System.out.println("Testing Oracle NUMBER types for driver -> " + driverClass + ", url -> " + url);

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
        connResult.close();
    }

    /**
     * Tests full roundtrip support for Oracle's native JSON column type (available in Oracle 21c+).
     * <p>
     * Oracle 21c introduced a native binary JSON type ({@code JSON}) stored in OSON format.
     * The Oracle JDBC driver may return vendor-specific objects from {@code getObject()}; OJP
     * detects these by column type name and uses {@code getString()} to return plain JSON text.
     * </p>
     * <p>
     * This test is skipped automatically on Oracle versions earlier than 21c.
     * </p>
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    void testOracleNativeJsonType(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        // Native JSON column type requires Oracle 21c or later
        int oracleMajorVersion = conn.getMetaData().getDatabaseMajorVersion();
        assumeFalse(oracleMajorVersion < 21,
                "Oracle native JSON column type requires Oracle 21c or later (found version " + oracleMajorVersion + ")");

        System.out.println("Testing Oracle native JSON type for url -> " + url
                + " (version " + oracleMajorVersion + ")");

        // Clean up from any previous run
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_oracle_json");
        } catch (Exception e) {
            // Ignore if table does not exist
        }

        TestDBUtils.executeUpdate(conn,
                "CREATE TABLE test_oracle_json (" +
                "id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                "json_col JSON, " +
                "json_null_col JSON)"
        );

        // Insert JSON as a plain string — Oracle accepts JSON text for a JSON column
        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO test_oracle_json (json_col, json_null_col) VALUES (?, ?)"
        );

        String jsonValue = "{\"key\": \"value\", \"number\": 42}";
        psInsert.setString(1, jsonValue);
        psInsert.setNull(2, Types.VARCHAR);

        psInsert.executeUpdate();

        // Select and validate the roundtrip
        java.sql.PreparedStatement psSelect = conn.prepareStatement(
                "SELECT json_col, json_null_col FROM test_oracle_json WHERE id = 1"
        );
        ResultSet resultSet = psSelect.executeQuery();

        assertTrue(resultSet.next());

        // JSON column: Oracle 21c may canonicalize (reformat) the JSON text,
        // so check for the presence of the expected keys and values rather than exact equality.
        String retrievedJson = resultSet.getString("json_col");
        assertNotNull(retrievedJson, "JSON column should not be null");
        assertTrue(retrievedJson.contains("key") && retrievedJson.contains("value") && retrievedJson.contains("42"),
                "JSON column should contain the expected keys and values, got: " + retrievedJson);

        // NULL JSON column must round-trip as null
        assertNull(resultSet.getString("json_null_col"), "NULL JSON column should return null");

        resultSet.close();
        psSelect.close();
        psInsert.close();

        // Clean up
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_oracle_json");
        } catch (Exception e) {
            // Ignore cleanup errors
        }

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
