package openjproxy.jdbc;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static openjproxy.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
            assumeFalse(!isOracleTestEnabled, "Oracle tests are not enabled");
            this.tableName += "_oracle";
        } else {
            assumeFalse(!isH2TestEnabled, "H2 tests are not enabled");
            this.tableName += "_h2";
        }
        Class.forName(driverClass);
        this.conn = DriverManager.getConnection(url, user, pwd);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_oracle_connections.csv")
    public void createAndReadingCLOBsSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        this.setUp(driverClass, url, user, pwd);
        System.out.println("Testing for url -> " + url);

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Determine appropriate column type based on database
        String clobType = "CLOB";
        if (url.toLowerCase().contains("mysql") || url.toLowerCase().contains("mariadb")) {
            clobType = "TEXT";  // MySQL and MariaDB use TEXT instead of CLOB
        }

        executeUpdate(conn,
                "create table " + tableName + "(" +
                        " val_clob  " + clobType + "," +
                        " val_clob2 " + clobType + "," +
                        " val_clob3 " + clobType +
                        ")"
        );

        PreparedStatement psInsert = conn.prepareStatement(
                " insert into " + tableName + " (val_clob, val_clob2, val_clob3) values (?, ?, ?)"
        );

        String testString1 = "This is a test CLOB string with special characters: !@#$%^&*()";
        String testString2 = "CLOB VIA READER STREAM";
        String testString3 = "CLOB PARTIAL";

        for (int i = 0; i < 5; i++) {
            Clob clob = conn.createClob();
            clob.setString(1, testString1);
            psInsert.setClob(1, clob);
            
            Reader reader = new StringReader(testString2);
            psInsert.setClob(2, reader);
            
            Reader reader2 = new StringReader(testString3);
            psInsert.setClob(3, reader2, 5);
            psInsert.executeUpdate();
        }

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select val_clob, val_clob2, val_clob3 from " + tableName);
        ResultSet resultSet = psSelect.executeQuery();

        int countReads = 0;
        while(resultSet.next()) {
            countReads++;
            Clob clobResult = resultSet.getClob(1);

            // Test getSubString
            String fromClobByIdx = clobResult.getSubString(1, (int)clobResult.length());
            Assert.assertEquals(testString1, fromClobByIdx);

            // Test getCharacterStream
            Clob clobResultByName = resultSet.getClob("val_clob");
            Reader charStream = clobResultByName.getCharacterStream();
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = charStream.read()) != -1) {
                sb.append((char) ch);
            }
            Assert.assertEquals(testString1, sb.toString());

            // Test getAsciiStream
            Clob clobResult2 = resultSet.getClob(2);
            String fromClobAscii2 = new String(clobResult2.getAsciiStream().readAllBytes());
            Assert.assertEquals(testString2, fromClobAscii2);

            Clob clobResult3 = resultSet.getClob(3);
            String fromClobAscii3 = new String(clobResult3.getAsciiStream().readAllBytes());
            Assert.assertEquals(testString3.substring(0, 5), fromClobAscii3);
        }
        Assert.assertEquals(5, countReads);

        executeUpdate(conn, "delete from " + tableName);

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_oracle_connections.csv")
    public void creatingAndReadingLargeCLOBsSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, IOException, ClassNotFoundException {
        this.setUp(driverClass, url, user, pwd);
        System.out.println("Testing for url -> " + url);

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Determine appropriate column type based on database
        String clobType = "CLOB";
        if (url.toLowerCase().contains("mysql") || url.toLowerCase().contains("mariadb")) {
            clobType = "TEXT";  // MySQL and MariaDB use TEXT instead of CLOB
        }

        executeUpdate(conn,
                "create table " + tableName + "(" +
                        " val_clob  " + clobType +
                        ")"
        );

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into " + tableName + " (val_clob) values (?)"
        );

        // Create a large text string
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeText.append("Line ").append(i).append(": This is a test line with some text content.\n");
        }
        String largeTextStr = largeText.toString();

        Reader reader = new StringReader(largeTextStr);
        psInsert.setClob(1, reader);

        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select val_clob from " + tableName);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        Clob clobResult = resultSet.getClob(1);

        Reader clobReader = clobResult.getCharacterStream();
        StringBuilder resultText = new StringBuilder();
        int ch;
        int count = 0;
        while ((ch = clobReader.read()) != -1) {
            count++;
            resultText.append((char) ch);
        }

        Assert.assertEquals(largeTextStr, resultText.toString());
        Assert.assertTrue(count > 0);

        executeUpdate(conn, "delete from " + tableName);

        resultSet.close();
        psSelect.close();
        conn.close();
    }

}
