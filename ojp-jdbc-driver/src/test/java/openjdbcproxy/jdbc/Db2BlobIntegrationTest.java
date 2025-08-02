package openjdbcproxy.jdbc;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static openjdbcproxy.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * DB2-specific BLOB integration tests.
 * Tests DB2 BLOB functionality and performance.
 */
public class Db2BlobIntegrationTest {

    private static boolean isTestDisabled;
    private String tableName;
    private Connection conn;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "DB2 tests are disabled");
        
        this.tableName = "DB2INST1.db2_blob_test";
        conn = DriverManager.getConnection(url, user, pwd);
        
        // Set schema explicitly to avoid "object not found" errors
        try (Statement schemaStmt = conn.createStatement()) {
            schemaStmt.execute("SET SCHEMA DB2INST1");
        }
        
        try {
            executeUpdate(conn, "DROP TABLE " + tableName);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }
        
        // Create table with DB2 BLOB type
        executeUpdate(conn, "CREATE TABLE " + tableName + " (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "data_blob BLOB(1M))");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testDb2BlobCreationAndRetrieval(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing DB2 BLOB creation and retrieval for url -> " + url);

        String testData = "DB2 BLOB test data - special characters: äöü ñ 中文 🚀";
        byte[] dataBytes = testData.getBytes("UTF-8");

        // Insert BLOB data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 1);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(dataBytes));
        psInsert.executeUpdate();

        // Retrieve and verify BLOB data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        
        Blob blob = rs.getBlob("data_blob");
        Assert.assertNotNull(blob);
        
        InputStream blobStream = blob.getBinaryStream();
        byte[] retrievedBytes = blobStream.readAllBytes();
        String retrievedData = new String(retrievedBytes, "UTF-8");
        
        Assert.assertEquals(testData, retrievedData);
        
        // Cleanup
        psSelect.close();
        psInsert.close();
        
        executeUpdate(conn, "DROP TABLE " + tableName);
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/db2_connection.csv")
    public void testDb2BlobPerformance(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing DB2 BLOB performance for url -> " + url);

        // Create larger test data (10KB)
        byte[] largeData = new byte[10240];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        long startTime = System.currentTimeMillis();

        // Insert multiple BLOB records
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        
        for (int i = 1; i <= 10; i++) {
            psInsert.setInt(1, i);
            psInsert.setBinaryStream(2, new ByteArrayInputStream(largeData));
            psInsert.executeUpdate();
        }

        long insertTime = System.currentTimeMillis() - startTime;
        
        // Retrieve BLOB records
        startTime = System.currentTimeMillis();
        
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT id, data_blob FROM " + tableName + " ORDER BY id"
        );
        ResultSet rs = psSelect.executeQuery();
        
        int count = 0;
        while (rs.next()) {
            Blob blob = rs.getBlob("data_blob");
            InputStream blobStream = blob.getBinaryStream();
            byte[] retrievedBytes = blobStream.readAllBytes();
            Assert.assertEquals(largeData.length, retrievedBytes.length);
            count++;
        }
        
        long retrieveTime = System.currentTimeMillis() - startTime;
        
        Assert.assertEquals(10, count);
        
        System.out.println("DB2 BLOB Performance - Insert: " + insertTime + "ms, Retrieve: " + retrieveTime + "ms");
        
        // Cleanup
        rs.close();
        psSelect.close();
        psInsert.close();
        
        executeUpdate(conn, "DROP TABLE " + tableName);
        conn.close();
    }
}