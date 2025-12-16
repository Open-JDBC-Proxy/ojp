package openjproxy.jdbc;

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
import openjproxy.jdbc.testutil.TestDBUtils;
import openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static openjproxy.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Oracle-specific BLOB integration tests.
 * Tests Oracle BLOB functionality and performance.
 */
public class OracleBlobIntegrationTest {

    private static boolean isTestDisabled;
    private String tableName;
    private ConnectionResult connectionResult;
    private Connection conn;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        this.tableName = "oracle_blob_test";
        connectionResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        conn = connectionResult.getConnection();
        
        // For non-XA connections, set autocommit to false for transaction control
        if (!isXA) {
            conn.setAutoCommit(false);
        }
        
        try {
            executeUpdate(conn, "DROP TABLE " + tableName);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }
        
        // Create table with Oracle BLOB type
        executeUpdate(conn, "CREATE TABLE " + tableName + " (" +
                "id NUMBER(10) PRIMARY KEY, " +
                "data_blob BLOB)");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    public void testOracleBlobCreationAndRetrieval(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd, isXA);

        System.out.println("Testing Oracle BLOB creation and retrieval for url -> " + url);

        String testData = "Oracle BLOB test data - special characters: äöü ñ 中文 🚀";
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
        connectionResult.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    public void testOracleLargeBlobHandling(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd, isXA);

        System.out.println("Testing Oracle large BLOB handling for url -> " + url);

        // Create a large test string (1MB)
        StringBuilder sb = new StringBuilder();
        String pattern = "Oracle large BLOB test pattern ";
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
        Assert.assertTrue(firstChunkStr.contains("Oracle large BLOB test pattern 0"));

        psInsert.close();
        psSelect.close();
        rs.close();
        connectionResult.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    public void testOracleBlobBinaryStream(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd, isXA);

        System.out.println("Testing Oracle BLOB binary stream for url -> " + url);

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
        connectionResult.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    public void testOracleBlobUpdate(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd, isXA);

        System.out.println("Testing Oracle BLOB update for url -> " + url);

        String originalData = "Original Oracle BLOB data";
        String updatedData = "Updated Oracle BLOB data with more content";

        // Insert original data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 4);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(originalData.getBytes("UTF-8")));
        psInsert.executeUpdate();

        // Update BLOB data
        PreparedStatement psUpdate = conn.prepareStatement(
                "UPDATE " + tableName + " SET data_blob = ? WHERE id = ?"
        );
        psUpdate.setBinaryStream(1, new ByteArrayInputStream(updatedData.getBytes("UTF-8")));
        psUpdate.setInt(2, 4);
        psUpdate.executeUpdate();

        // Verify updated data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 4);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        
        Blob blob = rs.getBlob(1);
        String retrievedData = new String(blob.getBytes(1, (int) blob.length()), "UTF-8");
        
        Assert.assertEquals(updatedData, retrievedData);

        psInsert.close();
        psUpdate.close();
        psSelect.close();
        rs.close();
        connectionResult.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    public void testOracleEmptyAndNullBlob(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd, isXA);

        System.out.println("Testing Oracle empty and null BLOB for url -> " + url);

        // Insert empty BLOB
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 5);
        psInsert.setBinaryStream(2, null);
        psInsert.executeUpdate();

        // Insert NULL BLOB
        psInsert.setInt(1, 6);
        psInsert.setBlob(2, (Blob) null);
        psInsert.executeUpdate();

        // Verify empty BLOB
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 5);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        Blob blob = rs.getBlob(1);
        Assert.assertNull(blob);

        // Verify NULL BLOB
        psSelect.setInt(1, 6);
        rs = psSelect.executeQuery();
        Assert.assertTrue(rs.next());
        blob = rs.getBlob(1);
        Assert.assertNull(blob);

        psInsert.close();
        psSelect.close();
        rs.close();
        connectionResult.close();
    }
}