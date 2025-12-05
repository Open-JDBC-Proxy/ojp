package openjproxy.jdbc;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import openjproxy.jdbc.testutil.CockroachDBConnectionProvider;

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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * CockroachDB-specific BLOB integration tests.
 * Tests CockroachDB BYTEA functionality (equivalent to BLOB).
 */
@EnabledIf("openjproxy.jdbc.testutil.CockroachDBTestContainer#isEnabled")
public class CockroachDBBlobIntegrationTest {

    private static boolean isTestDisabled;
    private String tableName;
    private Connection conn;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        assumeFalse(isTestDisabled, "CockroachDB tests are disabled");
        
        this.tableName = "cockroachdb_blob_test";
        conn = DriverManager.getConnection(url, user, pwd);
        
        try {
            executeUpdate(conn, "DROP TABLE " + tableName);
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }
        
        // Create table with CockroachDB BYTEA type (equivalent to BLOB)
        executeUpdate(conn, "CREATE TABLE " + tableName + " (" +
                "id INT PRIMARY KEY, " +
                "data_blob BYTEA)");
    }

    @ParameterizedTest
    @ArgumentsSource(CockroachDBConnectionProvider.class)
    public void testCockroachDBBlobCreationAndRetrieval(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing CockroachDB BLOB creation and retrieval for url -> " + url);

        String testData = "CockroachDB BLOB test data - special characters: äöü ñ 中文 🚀";
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
    @ArgumentsSource(CockroachDBConnectionProvider.class)
    public void testCockroachDBLargeBlobHandling(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing CockroachDB large BLOB handling for url -> " + url);

        // Create a 3MB test data
        byte[] largeData = new byte[3 * 1024 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        // Insert large BLOB
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 2);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(largeData));
        psInsert.executeUpdate();

        // Retrieve and verify
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 2);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        
        Blob blob = rs.getBlob(1);
        Assert.assertNotNull(blob);
        Assert.assertEquals(largeData.length, blob.length());
        
        byte[] retrievedBytes = blob.getBytes(1, (int) blob.length());
        Assert.assertArrayEquals(largeData, retrievedBytes);

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(CockroachDBConnectionProvider.class)
    public void testCockroachDBBlobUpdate(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing CockroachDB BLOB update for url -> " + url);

        String initialData = "Initial data";
        byte[] initialBytes = initialData.getBytes("UTF-8");

        // Insert initial data
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 3);
        psInsert.setBinaryStream(2, new ByteArrayInputStream(initialBytes));
        psInsert.executeUpdate();

        // Update with new data
        String updatedData = "Updated data - much longer than before!";
        byte[] updatedBytes = updatedData.getBytes("UTF-8");
        
        PreparedStatement psUpdate = conn.prepareStatement(
                "UPDATE " + tableName + " SET data_blob = ? WHERE id = ?"
        );
        psUpdate.setBinaryStream(1, new ByteArrayInputStream(updatedBytes));
        psUpdate.setInt(2, 3);
        psUpdate.executeUpdate();

        // Retrieve and verify updated data
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 3);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        
        Blob blob = rs.getBlob(1);
        Assert.assertNotNull(blob);
        
        byte[] retrievedBytes = blob.getBytes(1, (int) blob.length());
        String retrievedData = new String(retrievedBytes, "UTF-8");
        
        Assert.assertEquals(updatedData, retrievedData);

        psInsert.close();
        psUpdate.close();
        psSelect.close();
        rs.close();
        conn.close();
    }

    @ParameterizedTest
    @ArgumentsSource(CockroachDBConnectionProvider.class)
    public void testCockroachDBBlobWithNullValue(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        setUp(driverClass, url, user, pwd);

        System.out.println("Testing CockroachDB BLOB with NULL value for url -> " + url);

        // Insert NULL BLOB
        PreparedStatement psInsert = conn.prepareStatement(
                "INSERT INTO " + tableName + " (id, data_blob) VALUES (?, ?)"
        );
        psInsert.setInt(1, 4);
        psInsert.setNull(2, java.sql.Types.BLOB);
        psInsert.executeUpdate();

        // Retrieve and verify NULL
        PreparedStatement psSelect = conn.prepareStatement(
                "SELECT data_blob FROM " + tableName + " WHERE id = ?"
        );
        psSelect.setInt(1, 4);
        ResultSet rs = psSelect.executeQuery();
        
        Assert.assertTrue(rs.next());
        
        Blob blob = rs.getBlob(1);
        Assert.assertNull(blob);

        psInsert.close();
        psSelect.close();
        rs.close();
        conn.close();
    }
}
