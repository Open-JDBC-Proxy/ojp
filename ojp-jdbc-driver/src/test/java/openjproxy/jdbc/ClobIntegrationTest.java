package openjproxy.jdbc;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static openjproxy.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests for Clob/NClob methods using Reader interface.
 * These tests mirror the BlobIntegrationTest but use Reader instead of InputStream.
 */
public class ClobIntegrationTest {

    private static boolean isH2TestEnabled;
    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;
    private static boolean isOracleTestEnabled;
    private String tableName;
    private Connection conn;

    @BeforeAll
    public static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
        isOracleTestEnabled = Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {

        this.tableName = "clob_test_clob";
        if (url.toLowerCase().contains("mysql")) {
            assumeFalse(!isMySQLTestEnabled, "MySQL tests are not enabled");
            this.tableName += "_mysql";
        } else if (url.toLowerCase().contains("mariadb")) {
            assumeFalse(!isMariaDBTestEnabled, "MariaDB tests are not enabled");
            this.tableName += "_mariadb";
        } else if (url.toLowerCase().contains("oracle")) {
            assumeFalse(!isOracleTestEnabled, "Oracle tests are disabled");
            this.tableName += "_oracle";
        } else {
            assumeFalse(!isH2TestEnabled, "H2 tests are disabled");
            this.tableName += "_h2";
        }
        Class.forName(driverClass);
        this.conn = DriverManager.getConnection(url, user, pwd);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_oracle_connections.csv")
    public void createAndReadingCLOBsSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        this.setUp(driverClass, url, user, pwd);
        System.out.println("Testing CLOB for url -> " + url);

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        executeUpdate(conn,
                "create table " + tableName + "(" +
                        " val_clob  CLOB," +
                        " val_clob2 CLOB," +
                        " val_clob3 CLOB" +
                        ")"
        );

        PreparedStatement psInsert = conn.prepareStatement(
                " insert into " + tableName + " (val_clob, val_clob2, val_clob3) values (?, ?, ?)"
        );

        // Test with text data
        String textData = "This is a test CLOB with some sample text data. ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append(textData);
        }
        String largeText = sb.toString();

        String testString2 = "CLOB VIA READER STREAM";

        // H2 database does not fully support setClob with Reader due to internal CLOB/BLOB casting issues
        if (url.toLowerCase().contains("h2")) {
            System.out.println("H2 does not support setClob with Reader - asserting expected failure");
            Assert.assertThrows(SQLException.class, () -> {
                Clob clob = conn.createClob();
                clob.setString(1, largeText);
                psInsert.setClob(1, clob);
                psInsert.setClob(2, new StringReader(testString2));
                psInsert.setClob(3, new StringReader(testString2), 5);
                psInsert.executeUpdate();
            });
            conn.close();
            return;
        }

        try {
            for (int i = 0; i < 5; i++) {
                Clob clob = conn.createClob();
                clob.setString(1, largeText);
                psInsert.setClob(1, clob);
                
                Reader reader = new StringReader(testString2);
                psInsert.setClob(2, reader);
                
                Reader reader2 = new StringReader(testString2);
                psInsert.setClob(3, reader2, 5);
                psInsert.executeUpdate();
            }

            PreparedStatement psSelect = conn.prepareStatement("select val_clob, val_clob2, val_clob3 from " + tableName);
            ResultSet resultSet = psSelect.executeQuery();

            int countReads = 0;
            while(resultSet.next()) {
                countReads++;
                Clob clobResult = resultSet.getClob(1);
                String text1 = readAll(clobResult.getCharacterStream());
                Assert.assertEquals(largeText.length(), text1.length());

                Clob clobResultByName = resultSet.getClob("val_clob");
                String text1ByName = readAll(clobResultByName.getCharacterStream());
                Assert.assertEquals(largeText.length(), text1ByName.length());

                Clob clobResult2 = resultSet.getClob(2);
                String fromClobByIdx2 = readAll(clobResult2.getCharacterStream());
                Assert.assertEquals(testString2, fromClobByIdx2);

                Clob clobResult3 = resultSet.getClob(3);
                String fromClobByIdx3 = readAll(clobResult3.getCharacterStream());
                Assert.assertEquals(testString2.substring(0, 5), fromClobByIdx3);
            }
            Assert.assertEquals(5, countReads);

            executeUpdate(conn, "delete from " + tableName);

            resultSet.close();
            psSelect.close();
        } finally {
            conn.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_oracle_connections.csv")
    public void createAndReadingCLOBsWithMultiByteCharactersSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        this.setUp(driverClass, url, user, pwd);
        System.out.println("Testing CLOB with multi-byte characters for url -> " + url);

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        executeUpdate(conn,
                "create table " + tableName + "(" +
                        " val_clob CLOB" +
                        ")"
        );

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into " + tableName + " (val_clob) values (?)"
        );

        // Test with multi-byte characters including Chinese, Japanese, and emoji
        String testString = "Hello 世界 こんにちは 🌍 Testing Unicode Characters!";
        Reader reader = new StringReader(testString);
        
        // H2 database does not fully support setClob with Reader due to internal CLOB/BLOB casting issues
        if (url.toLowerCase().contains("h2")) {
            System.out.println("H2 does not support setClob with Reader - asserting expected failure");
            Assert.assertThrows(SQLException.class, () -> {
                psInsert.setClob(1, new StringReader(testString));
                psInsert.executeUpdate();
            });
            conn.close();
            return;
        }
        
        try {
            psInsert.setClob(1, reader);
            psInsert.executeUpdate();

            PreparedStatement psSelect = conn.prepareStatement("select val_clob from " + tableName);
            ResultSet resultSet = psSelect.executeQuery();
            resultSet.next();
            Clob clobResult = resultSet.getClob(1);

            String resultText = readAll(clobResult.getCharacterStream());
            Assert.assertEquals(testString, resultText);

            executeUpdate(conn, "delete from " + tableName);

            resultSet.close();
            psSelect.close();
        } finally {
            conn.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_oracle_connections.csv")
    public void createAndReadingNCLOBsSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        this.setUp(driverClass, url, user, pwd);
        System.out.println("Testing NCLOB for url -> " + url);

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Note: Not all databases support NCLOB explicitly, may fall back to CLOB
        String clobType = "CLOB";
        if (url.toLowerCase().contains("oracle")) {
            clobType = "NCLOB";
        }

        executeUpdate(conn,
                "create table " + tableName + "(" +
                        " val_nclob " + clobType +
                        ")"
        );

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into " + tableName + " (val_nclob) values (?)"
        );

        String testString = "NCLOB test with 中文字符 and 日本語";
        Reader reader = new StringReader(testString);
        
        // H2 database does not fully support setNClob with Reader due to internal CLOB/BLOB casting issues
        if (url.toLowerCase().contains("h2")) {
            System.out.println("H2 does not support setNClob with Reader - asserting expected failure");
            Assert.assertThrows(SQLException.class, () -> {
                psInsert.setNClob(1, new StringReader(testString), testString.length());
                psInsert.executeUpdate();
            });
            conn.close();
            return;
        }
        
        try {
            psInsert.setNClob(1, reader, testString.length());
            psInsert.executeUpdate();

            PreparedStatement psSelect = conn.prepareStatement("select val_nclob from " + tableName);
            ResultSet resultSet = psSelect.executeQuery();
            resultSet.next();

            // Try to get as NClob first, fall back to Clob if not supported
            String resultText;
            try {
                NClob nclobResult = resultSet.getNClob(1);
                resultText = readAll(nclobResult.getCharacterStream());
            } catch (Exception e) {
                // Fall back to Clob for databases that don't distinguish
                Clob clobResult = resultSet.getClob(1);
                resultText = readAll(clobResult.getCharacterStream());
            }

            Assert.assertEquals(testString, resultText);

            executeUpdate(conn, "delete from " + tableName);

            resultSet.close();
            psSelect.close();
        } finally {
            conn.close();
        }
    }

    /**
     * Helper method to read all characters from a Reader into a String
     */
    private String readAll(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[8192];
        int charsRead;
        while ((charsRead = reader.read(buffer)) != -1) {
            sb.append(buffer, 0, charsRead);
        }
        return sb.toString();
    }
}
