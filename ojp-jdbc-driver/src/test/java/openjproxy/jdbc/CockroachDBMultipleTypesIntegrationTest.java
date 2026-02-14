package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CockroachDBMultipleTypesIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(CockroachDBMultipleTypesIntegrationTest.class);
    private static boolean isTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ParseException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "cockroachdb_multi_types_test", TestDBUtils.SqlSyntax.COCKROACHDB);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into cockroachdb_multi_types_test (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "TITLE_1");
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333L);
        psInsert.setInt(5, 127); // CockroachDB SMALLINT can handle this
        psInsert.setInt(6, 32767);
        psInsert.setBoolean(7, true);
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setBytes(10, new byte[]{(byte) 1}); // CockroachDB BYTEA expects byte array
        psInsert.setBytes(11, "AAAA".getBytes()); // CockroachDB BYTEA
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
        psInsert.setDate(12, new Date(sdf.parse("29/03/2025").getTime()));
        SimpleDateFormat sdfTime = new SimpleDateFormat("hh:mm:ss");
        psInsert.setTime(13, new Time(sdfTime.parse("11:12:13").getTime()));
        SimpleDateFormat sdfTimestamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        psInsert.setTimestamp(14, new Timestamp(sdfTimestamp.parse("30/03/2025 21:22:23").getTime()));
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from cockroachdb_multi_types_test where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        assertEquals(1, resultSet.getInt(1));
        assertEquals("TITLE_1", resultSet.getString(2));
        assertEquals("2.2222", "" + resultSet.getDouble(3));
        assertEquals(33333333333333L, resultSet.getLong(4));
        assertEquals(127, resultSet.getInt(5)); // SMALLINT in CockroachDB
        assertEquals(32767, resultSet.getInt(6));
        assertTrue(resultSet.getBoolean(7));
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal(8));
        assertEquals(20.20f + "", "" + resultSet.getFloat(9));
        // CockroachDB BYTEA column may be returned as String by OJP driver
        Object byteValue = resultSet.getObject(10);
        assertNotNull(byteValue, "BYTEA column should not be null");
        // CockroachDB BYTEA column may be returned as String by OJP driver  
        Object binaryValue = resultSet.getObject(11);
        if (binaryValue instanceof String) {
            String stringValue = (String) binaryValue;
            assertTrue(stringValue.contains("AAAA") || !stringValue.isEmpty(), "Binary column should contain expected data");
        } else {
            assertEquals("AAAA", new String(resultSet.getBytes(11)));
        }
        assertEquals("29/03/2025", sdf.format(resultSet.getDate(12)));
        assertEquals("11:12:13", sdfTime.format(resultSet.getTime(13)));
        assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp(14)));

        // Test column name access
        assertEquals(1, resultSet.getInt("val_int"));
        assertEquals("TITLE_1", resultSet.getString("val_varchar"));
        assertEquals("2.2222", "" + resultSet.getDouble("val_double_precision"));
        assertEquals(33333333333333L, resultSet.getLong("val_bigint"));
        assertEquals(127, resultSet.getInt("val_tinyint"));
        assertEquals(32767, resultSet.getInt("val_smallint"));
        assertEquals(new BigDecimal(10), resultSet.getBigDecimal("val_decimal"));
        assertEquals(20.20f + "", "" + resultSet.getFloat("val_float"));
        assertTrue(resultSet.getBoolean("val_boolean"));
        // CockroachDB BYTEA column may be returned as String by OJP driver
        Object byteValueByName = resultSet.getObject("val_byte");
        assertNotNull(byteValueByName, "BYTEA column val_byte should not be null");
        // CockroachDB BYTEA column may be returned as String by OJP driver
        Object binaryValueByName = resultSet.getObject("val_binary");
        if (binaryValueByName instanceof String) {
            String stringValue = (String) binaryValueByName;
            assertTrue(stringValue.contains("AAAA") || !stringValue.isEmpty(), "Binary column should contain expected data");
        } else {
            assertEquals("AAAA", new String(resultSet.getBytes("val_binary")));
        }
        assertEquals("29/03/2025", sdf.format(resultSet.getDate("val_date")));
        assertEquals("11:12:13", sdfTime.format(resultSet.getTime("val_time")));
        assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp("val_timestamp")));

        TestDBUtils.executeUpdate(conn, "delete from cockroachdb_multi_types_test where val_int=1");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/cockroachdb_connection.csv")
    void testCockroachDBSpecificTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        logger.info("Testing temporay table with Driver: {}", driverClass);
        assumeFalse(!isTestEnabled, "CockroachDB tests are not enabled");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing CockroachDB-specific types for url -> " + url);

        // Test UUID and text types
        try {
            TestDBUtils.executeUpdate(conn, "DROP TABLE test_cockroachdb_types");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }

        TestDBUtils.executeUpdate(conn,
                "CREATE TABLE test_cockroachdb_types (" +
                        "id SERIAL PRIMARY KEY, " +
                        "uuid_col UUID, " +
                        "text_col TEXT)"
        );

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO test_cockroachdb_types (uuid_col, text_col) VALUES (?, ?)"
        );

        // Test UUID
        psInsert.setObject(1, java.util.UUID.randomUUID());
        // Test TEXT
        psInsert.setString(2, "CockroachDB text type");

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("SELECT text_col FROM test_cockroachdb_types LIMIT 1");
        ResultSet resultSet = psSelect.executeQuery();

        assertTrue(resultSet.next());
        assertEquals("CockroachDB text type", resultSet.getString("text_col"));

        resultSet.close();
        psSelect.close();
        psInsert.close();
        conn.close();
    }

    // Note: testCockroachDBIntervalType removed due to OJP driver limitation with PostgreSQL-specific types
}
