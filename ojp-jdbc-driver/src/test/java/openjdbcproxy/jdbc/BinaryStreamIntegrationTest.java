package openjdbcproxy.jdbc;

import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static openjdbcproxy.helpers.SqlHelper.executeUpdate;

public class BinaryStreamIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    public static void setup() {
        isTestDisabled = Boolean.parseBoolean(System.getProperty("disablePostgresTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void createAndReadingBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        Assumptions.assumeFalse(isTestDisabled, "Skipping Postgres tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing for url -> " + url);

        try {
            executeUpdate(conn, "drop table test_table_blob");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        executeUpdate(conn,
                "create table test_table_blob(" +
                        " val_blob1 BYTEA," +
                        " val_blob2 BYTEA" +
                        ")"
        );

        conn.setAutoCommit(false);

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into test_table_blob (val_blob1, val_blob2) values (?, ?)"
        );

        String testString = "BLOB VIA INPUT STREAM";
        InputStream inputStream = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(1, inputStream);

        InputStream inputStream2 = new ByteArrayInputStream(testString.getBytes());
        psInsert.setBinaryStream(2, inputStream2, 5);
        psInsert.executeUpdate();

        conn.commit();

        PreparedStatement psSelect = conn.prepareStatement("select val_blob1, val_blob2 from test_table_blob ");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        InputStream blobResult = resultSet.getBinaryStream(1);
        String fromBlobByIdx = new String(blobResult.readAllBytes());

        Assert.assertEquals(testString, fromBlobByIdx);

        InputStream blobResultByName = resultSet.getBinaryStream("val_blob1");
        String fromBlobByName = new String(blobResultByName.readAllBytes());
        Assert.assertEquals(testString, fromBlobByName);

        InputStream blobResult2 = resultSet.getBinaryStream(2);
        String fromBlobByIdx2 = new String(blobResult2.readAllBytes());
        Assert.assertEquals(testString.substring(0, 5), fromBlobByIdx2);

        executeUpdate(conn, "delete from test_table_blob"
        );

        resultSet.close();
        psSelect.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void createAndReadingLargeBinaryStreamSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        Assumptions.assumeFalse(isTestDisabled, "Skipping Postgres tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing for url -> " + url);

        try {
            executeUpdate(conn, "drop table test_table_blob");
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        executeUpdate(conn,
                "create table test_table_blob(" +
                        " val_blob  BYTEA" +
                        ")"
        );

        PreparedStatement psInsert = conn.prepareStatement(
                "insert into test_table_blob (val_blob) values (?)"
        );


        InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");
        psInsert.setBinaryStream(1, inputStream);

        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement("select val_blob from test_table_blob ");
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        InputStream inputStreamBlob = resultSet.getBinaryStream(1);

        InputStream inputStreamTestFile = this.getClass().getClassLoader().getResourceAsStream("largeTextFile.txt");

        int byteFile = inputStreamTestFile.read();
        while (byteFile != -1) {
            int blobByte = inputStreamBlob.read();

            Assert.assertEquals(byteFile, blobByte);

            byteFile = inputStreamTestFile.read();
        }

        executeUpdate(conn, "delete from test_table_blob");

        resultSet.close();
        psSelect.close();
        conn.close();
    }

}
