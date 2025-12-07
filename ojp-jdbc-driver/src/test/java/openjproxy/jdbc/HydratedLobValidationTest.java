package openjproxy.jdbc;

import org.junit.Assert;
import org.junit.jupiter.api.Assumptions;
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

import static openjproxy.helpers.SqlHelper.executeUpdate;

/**
 * Test to validate the hydrated LOB approach.
 * This test specifically validates that LOBs are handled as complete byte arrays
 * rather than streamed, ensuring consistent behavior across all databases.
 */
public class HydratedLobValidationTest {

    private static boolean isH2TestEnabled;
    private String tableName;
    private Connection conn;

    @BeforeAll
    public static void checkH2TestsEnabled() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "true"));
        Assumptions.assumeTrue(isH2TestEnabled, "H2 tests are not enabled");
    }

    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        this.tableName = "hydrated_lob_test";
        conn = DriverManager.getConnection(url, user, pwd);
        
        try {
            executeUpdate(conn, "DROP TABLE " + tableName);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }
        
        // Create table for testing hydrated LOB behavior
        executeUpdate(conn, "CREATE TABLE " + tableName + " (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "small_blob BLOB, " +
                "medium_blob BLOB, " +
                "large_blob BLOB)");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    public void testHydratedLobBehavior(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing hydrated LOB behavior for url -> " + url);

        // Test data of different sizes to validate hydrated approach
        byte[] smallData = "Small LOB data".getBytes("UTF-8");
        byte[] mediumData = "M".repeat(1000).getBytes("UTF-8"); // 1KB
        byte[] largeData = "L".repeat(10000).getBytes("UTF-8"); // 10KB

        // Insert LOBs of different sizes
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, small_blob, medium_blob, large_blob) VALUES (?, ?, ?, ?)"
        );
        psInsert.setInt(1, 1);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(smallData));
        psInsert.setBinaryStream(3, new ByteArrayInputStream(mediumData));
        psInsert.setBinaryStream(4, new ByteArrayInputStream(largeData));
        psInsert.executeUpdate();

        // Retrieve and verify all LOBs
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT small_blob, medium_blob, large_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 1);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue("ResultSet should have data", rs.next());
        
        // Test small BLOB - should be hydrated (loaded entirely in memory)
        Blob smallBlob = rs.getBlob("small_blob");
        Assert.assertNotNull("Small BLOB should not be null", smallBlob);
        byte[] retrievedSmallData = smallBlob.getBinaryStream().readAllBytes();
        Assert.assertArrayEquals("Small BLOB data should match", smallData, retrievedSmallData);
        
        // Test medium BLOB - should be hydrated
        Blob mediumBlob = rs.getBlob("medium_blob");
        Assert.assertNotNull("Medium BLOB should not be null", mediumBlob);
        byte[] retrievedMediumData = mediumBlob.getBinaryStream().readAllBytes();
        Assert.assertArrayEquals("Medium BLOB data should match", mediumData, retrievedMediumData);
        
        // Test large BLOB - should be hydrated (not streamed)
        Blob largeBlob = rs.getBlob("large_blob");
        Assert.assertNotNull("Large BLOB should not be null", largeBlob);
        byte[] retrievedLargeData = largeBlob.getBinaryStream().readAllBytes();
        Assert.assertArrayEquals("Large BLOB data should match", largeData, retrievedLargeData);
        
        // Validate that multiple reads of the same BLOB work (hydrated data should be reusable)
        byte[] secondRead = largeBlob.getBinaryStream().readAllBytes();
        Assert.assertArrayEquals("Second read of large BLOB should match", largeData, secondRead);
        
        // Test BLOB length - should be available immediately (hydrated)
        Assert.assertEquals("Small BLOB length should be correct", smallData.length, smallBlob.length());
        Assert.assertEquals("Medium BLOB length should be correct", mediumData.length, mediumBlob.length());
        Assert.assertEquals("Large BLOB length should be correct", largeData.length, largeBlob.length());
        
        // Test getBytes method - should work with hydrated data
        byte[] partialData = largeBlob.getBytes(1, 100);
        Assert.assertEquals("Partial data length should be 100", 100, partialData.length);
        
        // Verify partial data matches the beginning of the original data
        for (int i = 0; i < 100; i++) {
            Assert.assertEquals("Partial data should match original at position " + i, 
                    largeData[i], partialData[i]);
        }

        // Cleanup
        rs.close();
        psSelect.close();
        psInsert.close();
        
        executeUpdate(conn, "DROP TABLE " + tableName);
        conn.close();
        
        System.out.println("Hydrated LOB validation completed successfully");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    public void testHydratedBinaryStreamBehavior(String driverClass, String url, String user, String pwd) 
            throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing hydrated binary stream behavior for url -> " + url);

        // Test data that would previously require streaming
        String testString = "Hydrated binary stream test data with special chars: äöü ñ 中文 🚀";
        byte[] testData = testString.getBytes("UTF-8");

        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, small_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 2);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(testData));
        psInsert.executeUpdate();

        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT small_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 2);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue("ResultSet should have data", rs.next());
        
        // Test getBinaryStream returns the complete hydrated data
        InputStream binaryStream = rs.getBinaryStream("small_blob");
        Assert.assertNotNull("Binary stream should not be null", binaryStream);
        
        byte[] retrievedData = binaryStream.readAllBytes();
        String retrievedString = new String(retrievedData, "UTF-8");
        
        Assert.assertEquals("Retrieved string should match original", testString, retrievedString);
        Assert.assertArrayEquals("Retrieved data should match original", testData, retrievedData);

        // Cleanup
        rs.close();
        psSelect.close();
        psInsert.close();
        
        executeUpdate(conn, "DROP TABLE " + tableName);
        conn.close();
        
        System.out.println("Hydrated binary stream validation completed successfully");
    }
}