package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static openjproxy.helpers.SqlHelper.executeUpdate;

/**
 * Integration tests for CharacterStream methods (setCharacterStream, setNCharacterStream).
 * These tests mirror the BinaryStreamIntegrationTest but use Reader instead of InputStream.
 */
public class CharacterStreamIntegrationTest {

    private static boolean isH2TestEnabled;
    private static boolean isPostgresTestEnabled;

    @BeforeAll
    public static void setup() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
        isPostgresTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    public void createAndReadingCharacterStreamSuccessful(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException, IOException {
        if (!isH2TestEnabled && url.toLowerCase().contains("_h2:")) {
            return;
        }
        if (!isPostgresTestEnabled && url.contains("postgresql")) {
            return;
        }

        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        System.out.println("Testing CharacterStream for url -> " + url);

        try {
            executeUpdate(conn, "drop table character_stream_test_clob");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with text/clob types
        String createTableSql = "create table character_stream_test_clob(" +
                    " val_clob1 TEXT," +
                    " val_clob2 TEXT" +
                    ")";

        executeUpdate(conn, createTableSql);

        conn.setAutoCommit(false);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into character_stream_test_clob (val_clob1, val_clob2) values (?, ?)"
        );

        String testString = "CLOB VIA CHARACTER STREAM";
        Reader reader1 = new StringReader(testString);
        psInsert.setCharacterStream(1, reader1);

        Reader reader2 = new StringReader(testString);
        psInsert.setCharacterStream(2, reader2, 5);
        psInsert.executeUpdate();

        connResult.commit();
        
        // Start new transaction for reading
        connResult.startXATransactionIfNeeded();

        PreparedStatement psSelect = conn.prepareStatement("select val_clob1, val_clob2 from character_stream_test_clob ");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        
        Reader clobResult = resultSet.getCharacterStream(1);
        String fromClobByIdx = readAll(clobResult);
        Assert.assertEquals(testString, fromClobByIdx);

        Reader clobResultByName = resultSet.getCharacterStream("val_clob1");
        String fromClobByName = readAll(clobResultByName);
        Assert.assertEquals(testString, fromClobByName);

        Reader clobResult2 = resultSet.getCharacterStream(2);
        String fromClobByIdx2 = readAll(clobResult2);
        Assert.assertEquals(testString.substring(0, 5), fromClobByIdx2);

        executeUpdate(conn, "delete from character_stream_test_clob");

        resultSet.close();
        psSelect.close();
        connResult.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    public void createAndReadingCharacterStreamWithMultiByteCharactersSuccessful(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException, IOException {
        if (!isH2TestEnabled && url.toLowerCase().contains("_h2:")) {
            return;
        }
        if (!isPostgresTestEnabled && url.contains("postgresql")) {
            return;
        }

        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        System.out.println("Testing CharacterStream with multi-byte characters for url -> " + url);

        try {
            executeUpdate(conn, "drop table character_stream_test_clob");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with text/clob types
        String createTableSql = "create table character_stream_test_clob(" +
                    " val_clob TEXT" +
                    ")";

        executeUpdate(conn, createTableSql);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into character_stream_test_clob (val_clob) values (?)"
        );

        // Test with multi-byte characters including Chinese and emoji
        String testString = "Hello 世界 🌍 Testing Unicode";
        Reader reader = new StringReader(testString);
        psInsert.setCharacterStream(1, reader);
        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select val_clob from character_stream_test_clob");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        
        Reader clobResult = resultSet.getCharacterStream(1);
        String fromClob = readAll(clobResult);
        
        Assert.assertEquals(testString, fromClob);

        executeUpdate(conn, "delete from character_stream_test_clob");

        resultSet.close();
        psSelect.close();
        connResult.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    public void createAndReadingNCharacterStreamSuccessful(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException, IOException {
        if (!isH2TestEnabled && url.toLowerCase().contains("_h2:")) {
            return;
        }
        if (!isPostgresTestEnabled && url.contains("postgresql")) {
            return;
        }

        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        System.out.println("Testing NCharacterStream for url -> " + url);

        try {
            executeUpdate(conn, "drop table ncharacter_stream_test_clob");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // Create table with text/clob types
        String createTableSql = "create table ncharacter_stream_test_clob(" +
                    " val_nclob TEXT" +
                    ")";

        executeUpdate(conn, createTableSql);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into ncharacter_stream_test_clob (val_nclob) values (?)"
        );

        String testString = "NCLOB VIA NCHARACTER STREAM with 中文";
        Reader reader = new StringReader(testString);
        psInsert.setNCharacterStream(1, reader, testString.length());
        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select val_nclob from ncharacter_stream_test_clob");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        
        Reader nclobResult = resultSet.getNCharacterStream(1);
        String fromNClob = readAll(nclobResult);
        
        Assert.assertEquals(testString, fromNClob);

        executeUpdate(conn, "delete from ncharacter_stream_test_clob");

        resultSet.close();
        psSelect.close();
        connResult.close();
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
