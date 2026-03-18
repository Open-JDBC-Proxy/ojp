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
 * Integration test for the next-page prefetch cache feature with an Oracle backend.
 *
 * <p>Oracle 12c+ supports the ANSI SQL {@code OFFSET m ROWS FETCH NEXT n ROWS ONLY} pagination
 * syntax, which is recognised by the OJP {@code PaginationDetector}.
 *
 * <p>The test is parameterized over several record counts (99, 100, 101, 567, 1000) to exercise
 * boundary conditions around the 100-record page size.  For each count the test:
 * <ol>
 *   <li>Creates a dedicated table with multiple column types, including a {@code BLOB} column.</li>
 *   <li>Inserts the requested number of rows with fully deterministic, per-row values.</li>
 *   <li>Paginates through all rows using {@code OFFSET … ROWS FETCH NEXT 100 ROWS ONLY} against an
 *       OJP server instance that has {@code ojp.server.nextPageCache.enabled=true} (port 10594).</li>
 *   <li>Asserts <em>every</em> column value, including a byte-exact comparison of the
 *       {@code BLOB} column.</li>
 *   <li>Drops the table on completion.</li>
 * </ol>
 *
 * <p>This test is disabled by default and is activated by passing
 * {@code -DenableOraclePrefetchCacheTests=true} to the Maven Surefire plugin in CI.
 * The target OJP server must already be running on port 10594 with the prefetch cache enabled.
 *
 * <p><b>Oracle type notes:</b>
 * <ul>
 *   <li>No native BOOLEAN SQL type (until Oracle 23c) → {@code NUMBER(1)} (0/1) is used.</li>
 *   <li>No BIGINT → {@code NUMBER(19,0)}.</li>
 *   <li>No TEXT → {@code VARCHAR2(255)}.</li>
 *   <li>Binary data → {@code BLOB}.</li>
 * </ul>
 */
class OraclePaginationCacheIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OraclePaginationCacheIntegrationTest.class);

    /** Number of rows per page used throughout these tests. */
    private static final int PAGE_SIZE = 100;

    private static boolean isTestEnabled;

    @BeforeAll
    static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(
                System.getProperty("enableOraclePrefetchCacheTests", "false"));
    }

    // -------------------------------------------------------------------------
    // Parameterized test – one run per row in the CSV
    // -------------------------------------------------------------------------

    /**
     * Core pagination test for Oracle.
     */
    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_prefetch_cache_connections_with_record_counts.csv")
    void testPaginationWithPrefetchCache(int recordCount, String driverClass,
                                         String url, String user, String pwd)
            throws SQLException, ClassNotFoundException {

        assumeTrue(isTestEnabled,
                "Oracle prefetch-cache tests are disabled " +
                "(pass -DenableOraclePrefetchCacheTests=true to enable)");

        Class.forName(driverClass);
        logger.info("Prefetch-cache pagination test: recordCount={}, url={}", recordCount, url);

        String tableName = "ojp_pfx_ora_" + recordCount;

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
     *   id         NUMBER(10)   PRIMARY KEY   – 1-based row identifier
     *   name       VARCHAR2(100) NOT NULL     – "record_{id}"
     *   val_int    NUMBER(10)   NOT NULL      – id × 10
     *   val_bigint NUMBER(19,0) NOT NULL      – id × 1,000,000
     *   val_bool   NUMBER(1)    NOT NULL      – 1 when id is even, else 0
     *   val_text   VARCHAR2(255) NOT NULL     – "text_value_for_row_{id}"
     *   val_blob   BLOB         NOT NULL      – four deterministic bytes derived from id
     * </pre>
     */
    private static void createTable(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE " + tableName);
        } catch (SQLException e) {
            // Table does not exist – ignore
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TABLE " + tableName + " (" +
                    "  id         NUMBER(10)    NOT NULL," +
                    "  name       VARCHAR2(100) NOT NULL," +
                    "  val_int    NUMBER(10)    NOT NULL," +
                    "  val_bigint NUMBER(19,0)  NOT NULL," +
                    "  val_bool   NUMBER(1)     NOT NULL," +
                    "  val_text   VARCHAR2(255) NOT NULL," +
                    "  val_blob   BLOB          NOT NULL," +
                    "  CONSTRAINT pk_" + tableName + " PRIMARY KEY (id)" +
                    ")");
        }
        logger.debug("Created table {}", tableName);
    }

    private static void insertRows(Connection conn, String tableName, int recordCount)
            throws SQLException {
        String sql = "INSERT INTO " + tableName +
                     " (id, name, val_int, val_bigint, val_bool, val_text, val_blob)" +
                     " VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= recordCount; i++) {
                ps.setInt(1, i);
                ps.setString(2, "record_" + i);
                ps.setInt(3, i * 10);
                ps.setLong(4, i * 1_000_000L);
                ps.setInt(5, i % 2 == 0 ? 1 : 0);
                ps.setString(6, "text_value_for_row_" + i);
                ps.setBytes(7, expectedBlob(i));
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

        // Oracle 12c+ OFFSET/FETCH syntax
        String sql = "SELECT id, name, val_int, val_bigint, val_bool, val_text, val_blob" +
                     " FROM " + tableName +
                     " ORDER BY id" +
                     " OFFSET " + offset + " ROWS FETCH NEXT " + PAGE_SIZE + " ROWS ONLY";

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
                assertEquals(id % 2 == 0 ? 1 : 0, rs.getInt("val_bool"),
                        "val_bool mismatch for id=" + id);
                assertEquals("text_value_for_row_" + id, rs.getString("val_text"),
                        "val_text mismatch for id=" + id);

                byte[] actualBlob = toBlobBytes(rs, "val_blob", id);
                assertNotNull(actualBlob, "val_blob for id=" + id + " must not be null");
                assertArrayEquals(expectedBlob(id), actualBlob,
                        "val_blob bytes do not match for id=" + id);

                rowsOnPage++;
            }
        }

        assertEquals(expectedRowsOnPage, rowsOnPage,
                "Page at offset=" + offset + " expected " + expectedRowsOnPage + " rows");
        return rowsOnPage;
    }

    /**
     * Reads a BLOB column as a {@code byte[]}.
     *
     * <p>The prefetch cache materialises BLOBs as {@code byte[]} when serving from cache,
     * whereas a live DB query returns a {@link java.sql.Blob} object.  Both are handled here.
     */
    private static byte[] toBlobBytes(ResultSet rs, String column, int id) throws SQLException {
        Object obj = rs.getObject(column);
        if (obj == null) {
            return null;
        }
        if (obj instanceof byte[]) {
            return (byte[]) obj;
        }
        if (obj instanceof java.sql.Blob) {
            java.sql.Blob blob = (java.sql.Blob) obj;
            return blob.getBytes(1, (int) blob.length());
        }
        return rs.getBytes(column);
    }

    private static void dropTable(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE " + tableName);
            logger.debug("Dropped table {}", tableName);
        } catch (SQLException e) {
            logger.warn("Could not drop table {}: {}", tableName, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Data-generation helpers
    // -------------------------------------------------------------------------

    private static byte[] expectedBlob(int rowId) {
        return new byte[]{
            (byte) (rowId & 0xFF),
            (byte) ((rowId >> 8) & 0xFF),
            (byte) ((rowId * 3) & 0xFF),
            (byte) ((rowId * 7) & 0xFF)
        };
    }
}
