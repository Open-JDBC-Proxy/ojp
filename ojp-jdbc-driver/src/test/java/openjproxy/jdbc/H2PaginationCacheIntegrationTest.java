package openjproxy.jdbc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for the next-page prefetch cache feature with an H2 backend.
 *
 * <p>H2 supports the {@code LIMIT n OFFSET m} pagination syntax.
 *
 * <p>The test is parameterized over several record counts (99, 100, 101, 567, 1000) to exercise
 * boundary conditions around the 100-record page size.  For each count the test:
 * <ol>
 *   <li>Creates a dedicated table with multiple column types, including a {@code VARBINARY} column.</li>
 *   <li>Inserts the requested number of rows with fully deterministic, per-row values.</li>
 *   <li>Paginates through all rows using {@code LIMIT 100 OFFSET …} against an OJP server instance
 *       that has {@code ojp.server.nextPageCache.enabled=true} (port 10594). The client also sets
 *       {@code ojp.nextPageCache.enabled=true} in {@code ojp.properties}, which is the
 *       per-datasource opt-in sent to the server on connect.</li>
 *   <li>Asserts <em>every</em> column value, including a byte-exact comparison of the
 *       {@code VARBINARY} column.</li>
 *   <li>Drops the table on completion.</li>
 * </ol>
 *
 * <p>This test is disabled by default and is activated by passing
 * {@code -DenableH2PrefetchCacheTests=true} to the Maven Surefire plugin in CI.
 * The target OJP server must already be running on port 10594 with
 * {@code ojp.server.nextPageCache.enabled=true}. The client-side per-datasource flag
 * {@code ojp.nextPageCache.enabled=true} is set in {@code ojp.properties} and is sent to
 * the server at connection time to explicitly opt this datasource into the cache.
 */
class H2PaginationCacheIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(H2PaginationCacheIntegrationTest.class);

    /** Number of rows per page used throughout these tests. */
    private static final int PAGE_SIZE = 100;

    private static boolean isTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(
                System.getProperty("enableH2PrefetchCacheTests", "false"));
    }

    // -------------------------------------------------------------------------
    // Parameterized test – one run per row in the CSV
    // -------------------------------------------------------------------------

    /**
     * Core pagination test for H2.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_prefetch_cache_connections_with_record_counts.csv")
    void testPaginationWithPrefetchCache(int recordCount, String driverClass,
                                         String url, String user, String pwd)
            throws SQLException, ClassNotFoundException {

        assumeTrue(isTestEnabled,
                "H2 prefetch-cache tests are disabled " +
                "(pass -DenableH2PrefetchCacheTests=true to enable)");

        Class.forName(driverClass);
        logger.info("Prefetch-cache pagination test: recordCount={}, url={}", recordCount, url);

        String tableName = "OJP_PFX_H2_" + recordCount;

        try (Connection conn = DriverManager.getConnection(url, user, pwd)) {

            createTable(conn, tableName);
            insertRows(conn, tableName, recordCount);

            int totalRetrieved = 0;
            for (int offset = 0; offset < recordCount; offset += PAGE_SIZE) {
                int expectedOnPage = Math.min(PAGE_SIZE, recordCount - offset);
                totalRetrieved += assertPage(conn, tableName, offset, expectedOnPage);
            }

            assertEquals(recordCount, totalRetrieved,
                    "Total rows retrieved across all pages must equal recordCount");

            dropTable(conn, tableName);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Drops (if exists) and re-creates the test table.
     *
     * <p>Schema:
     * <pre>
     *   id         INT           PRIMARY KEY  – 1-based row identifier
     *   name       VARCHAR(100)  NOT NULL     – "record_{id}"
     *   val_int    INT           NOT NULL     – id × 10
     *   val_bigint BIGINT        NOT NULL     – id × 1,000,000
     *   val_bool   BOOLEAN       NOT NULL     – true when id is even
     *   val_text   VARCHAR(255)  NOT NULL     – "text_value_for_row_{id}"
     *   val_binary VARBINARY(32) NOT NULL     – four deterministic bytes derived from id
     * </pre>
     */
    private static void createTable(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            stmt.execute(
                    "CREATE TABLE " + tableName + " (" +
                    "  id         INT           PRIMARY KEY," +
                    "  name       VARCHAR(100)  NOT NULL," +
                    "  val_int    INT           NOT NULL," +
                    "  val_bigint BIGINT        NOT NULL," +
                    "  val_bool   BOOLEAN       NOT NULL," +
                    "  val_text   VARCHAR(255)  NOT NULL," +
                    "  val_binary VARBINARY(32) NOT NULL" +
                    ")");
        }
        logger.debug("Created table {}", tableName);
    }

    private static void insertRows(Connection conn, String tableName, int recordCount)
            throws SQLException {
        String sql = "INSERT INTO " + tableName +
                     " (id, name, val_int, val_bigint, val_bool, val_text, val_binary)" +
                     " VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= recordCount; i++) {
                ps.setInt(1, i);
                ps.setString(2, "record_" + i);
                ps.setInt(3, i * 10);
                ps.setLong(4, i * 1_000_000L);
                ps.setBoolean(5, i % 2 == 0);
                ps.setString(6, "text_value_for_row_" + i);
                ps.setBytes(7, expectedBinary(i));
                ps.addBatch();

                if (i % 500 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        logger.debug("Inserted {} rows into {}", recordCount, tableName);
    }

    private static int assertPage(Connection conn, String tableName,
                                   int offset, int expectedRowsOnPage)
            throws SQLException {

        String sql = "SELECT id, name, val_int, val_bigint, val_bool, val_text, val_binary" +
                     " FROM " + tableName +
                     " ORDER BY id" +
                     " LIMIT " + PAGE_SIZE + " OFFSET " + offset;

        int rowsOnPage = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int expectedId = offset + rowsOnPage + 1;
                int id = rs.getInt("id");

                assertEquals(expectedId, id,
                        "id mismatch at offset=" + offset + " row=" + rowsOnPage);
                assertEquals("record_" + id, rs.getString("name"),
                        "name mismatch for id=" + id);
                assertEquals(id * 10, rs.getInt("val_int"),
                        "val_int mismatch for id=" + id);
                assertEquals(id * 1_000_000L, rs.getLong("val_bigint"),
                        "val_bigint mismatch for id=" + id);
                assertEquals(id % 2 == 0, rs.getBoolean("val_bool"),
                        "val_bool mismatch for id=" + id);
                assertEquals("text_value_for_row_" + id, rs.getString("val_text"),
                        "val_text mismatch for id=" + id);

                Object binObj = rs.getObject("val_binary");
                assertNotNull(binObj, "val_binary for id=" + id + " must not be null");
                byte[] actualBytes = binObj instanceof byte[] ? (byte[]) binObj
                        : rs.getBytes("val_binary");
                assertArrayEquals(expectedBinary(id), actualBytes,
                        "val_binary bytes do not match for id=" + id);

                rowsOnPage++;
            }
        }

        assertEquals(expectedRowsOnPage, rowsOnPage,
                "Page at offset=" + offset + " expected " + expectedRowsOnPage + " rows");
        return rowsOnPage;
    }

    private static void dropTable(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
            logger.debug("Dropped table {}", tableName);
        } catch (SQLException e) {
            logger.warn("Could not drop table {}: {}", tableName, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Data-generation helpers
    // -------------------------------------------------------------------------

    private static byte[] expectedBinary(int rowId) {
        return new byte[]{
            (byte) (rowId & 0xFF),
            (byte) ((rowId >> 8) & 0xFF),
            (byte) ((rowId * 3) & 0xFF),
            (byte) ((rowId * 7) & 0xFF)
        };
    }
}
