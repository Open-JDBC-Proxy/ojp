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

import static openjdbcproxy.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SQL Server-specific BLOB-like integration tests.
 * Tests SQL Server VARBINARY(MAX) functionality (equivalent to BLOB).
 */
public class SQLServerBlobIntegrationTest {

    private static boolean isTestDisabled;
    private String tableName;
    private Connection conn;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        this.tableName = "sqlserver_blob_test";
        conn = DriverManager.getConnection(url, user, pwd);
        
        try {
            executeUpdate(conn, "IF OBJECT_ID('" + tableName + "', 'U') IS NOT NULL DROP TABLE " + tableName);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }
        
        // Create table with SQL Server VARBINARY(MAX) type (equivalent to BLOB)
        executeUpdate(conn, "CREATE TABLE " + tableName + " (" +
                "id INT PRIMARY KEY, " +
                "data_blob VARBINARY(MAX))");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerBlobCreationAndRetrieval(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing SQL Server BLOB creation and retrieval for url -> " + url);

        String testData = "SQL Server BLOB test data - special characters: äöü ñ 中文 🚀";
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
        
        Blob blob = rs.getBlob(1);
        Assert.assertNotNull(blob);
        
        byte[] retrievedBytes = blob.getBytes(1, (int) blob.length());
        String retrievedData = new String(retrievedBytes, "UTF-8");
        
        Assert.assertEquals(testData, retrievedData);
        Assert.assertEquals(dataBytes.length, blob.length());

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerLargeBlobHandling(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing SQL Server large BLOB handling for url -> " + url);

        // Create a large test string (1MB)
        StringBuilder sb = new StringBuilder();
        String pattern = "SQL Server large BLOB test pattern ";
        for (int i = 0; i < 32768; i++) { // Approximately 1MB
            sb.append(pattern).append(i).append("\n");
        }
        String largeTestData = sb.toString();
        byte[] largeDataBytes = largeTestData.getBytes("UTF-8");

        // Insert large BLOB data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 2);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(largeDataBytes));
        psInsert.executeUpdate();

        // Retrieve and verify large BLOB data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 2);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        
        Blob blob = rs.getBlob(1);
        Assert.assertNotNull(blob);
        Assert.assertTrue(blob.length() > 1000000); // Should be > 1MB
        
        // Read first chunk to verify
        byte[] firstChunk = blob.getBytes(1, 1000);
        String firstChunkStr = new String(firstChunk, "UTF-8");
        Assert.assertTrue(firstChunkStr.contains("SQL Server large BLOB test pattern 0"));

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerBlobBinaryStream(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing SQL Server BLOB binary stream for url -> " + url);

        // Test with binary data (not just text)
        byte[] binaryData = new byte[1000];
        for (int i = 0; i < binaryData.length; i++) {
            binaryData[i] = (byte) (i % 256);
        }

        // Insert binary data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 3);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(binaryData));
        psInsert.executeUpdate();

        // Retrieve and verify binary data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 3);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        
        InputStream binaryStream = rs.getBinaryStream(1);
        Assert.assertNotNull(binaryStream);
        
        byte[] retrievedData = binaryStream.readAllBytes();
        Assert.assertEquals(binaryData.length, retrievedData.length);
        
        // Verify each byte
        for (int i = 0; i < binaryData.length; i++) {
            Assert.assertEquals("Byte mismatch at position " + i, binaryData[i], retrievedData[i]);
        }

        psInsert.close();
        psSelect.close();
        rs.close();
        binaryStream.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerBlobUpdate(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing SQL Server BLOB update for url -> " + url);

        String originalData = "Original SQL Server BLOB data";
        String updatedData = "Updated SQL Server BLOB data with more content";
        
        // Insert original data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 4);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(originalData.getBytes("UTF-8")));
        psInsert.executeUpdate();

        // Update the BLOB data
        PreparedStatement psUpdate = conn.prepareStatement(
                "UPDATE " + tableName + " SET data_blob = ? WHERE id = ?"
        );
        psUpdate.setBinaryStream(1, new ByteArrayInputStream(updatedData.getBytes("UTF-8")));
        psUpdate.setInt(2, 4);
        psUpdate.executeUpdate();

        // Retrieve and verify updated data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 4);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        
        Blob blob = rs.getBlob(1);
        Assert.assertNotNull(blob);
        
        byte[] retrievedBytes = blob.getBytes(1, (int) blob.length());
        String retrievedData = new String(retrievedBytes, "UTF-8");
        
        Assert.assertEquals(updatedData, retrievedData);
        Assert.assertNotEquals(originalData, retrievedData);

        psInsert.close();
        psUpdate.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerBlobNullHandling(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing SQL Server BLOB null handling for url -> " + url);

        // Insert null BLOB
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 5);
        psInsert.setNull(2, java.sql.Types.VARBINARY);
        psInsert.executeUpdate();

        // Retrieve and verify null BLOB
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 5);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        
        Blob blob = rs.getBlob(1);
        Assert.assertNull(blob);
        
        InputStream binaryStream = rs.getBinaryStream(1);
        Assert.assertNull(binaryStream);

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerEmptyBlob(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing SQL Server empty BLOB for url -> " + url);

        // Insert empty BLOB
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 6);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(new byte[0]));
        psInsert.executeUpdate();

        // Retrieve and verify empty BLOB
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 6);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        
        Blob blob = rs.getBlob(1);
        Assert.assertNotNull(blob);
        Assert.assertEquals(0, blob.length());
        
        byte[] retrievedBytes = blob.getBytes(1, (int) blob.length());
        Assert.assertEquals(0, retrievedBytes.length);

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }
}