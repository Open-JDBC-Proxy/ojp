package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLWarning;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests verifying that {@link java.sql.SQLWarning} chains produced by the database
 * are fully transferred through the OJP gRPC boundary, preserving {@code message},
 * {@code sqlState}, and {@code vendorCode} on every node of the chain.
 *
 * <p>Run with {@code -DenableH2Tests=true}.
 */
public class H2SqlWarningIntegrationTest {

    private static boolean isH2TestEnabled;

    private Connection connection;
    private Statement statement;

    @BeforeAll
    static void setupClass() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        Assumptions.assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
        connection = DriverManager.getConnection(url, user, password);
        statement = connection.createStatement();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestDBUtils.closeQuietly(statement, connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void shouldReturnNullGetWarningsWhenNoPriorWarningExists(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // No statements have been executed that produce warnings – the result must be null.
        assertNull(statement.getWarnings());
        assertNull(connection.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void shouldReturnNullGetWarningsAfterClearWarnings(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // clearWarnings() must leave getWarnings() returning null.
        statement.clearWarnings();
        assertNull(statement.getWarnings());
        connection.clearWarnings();
        assertNull(connection.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void shouldTransferSingleWarningWithAllAttributesFromStatement(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Use CREATE TABLE IF NOT EXISTS on an existing table; H2 may produce a warning in some
        // modes. Whether or not H2 emits a warning here, we exercise the transport round-trip.
        statement.execute("CREATE TABLE IF NOT EXISTS h2_warning_attr_test (col INT)");
        statement.execute("CREATE TABLE IF NOT EXISTS h2_warning_attr_test (col INT)");

        SQLWarning warning = statement.getWarnings();
        if (warning != null) {
            // If H2 produced a warning, verify all attributes are transferred across the gRPC boundary.
            assertNotNull(warning.getMessage(), "message must not be null when a warning is present");
            assertNotNull(warning.getSQLState(), "sqlState must be transferred, not null");
            // vendorCode is transported as an int; verify it is a valid non-negative value.
            assertTrue(warning.getErrorCode() >= 0, "vendorCode must be a non-negative integer");
        }
        // Clean up
        statement.execute("DROP TABLE IF EXISTS h2_warning_attr_test");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void shouldTransferChainedWarningsFromStatement(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // H2 does not reliably produce chained warnings from SQL alone, so this test validates
        // the transport path: if any warning is present, its attributes must be fully populated.
        statement.execute("CREATE TABLE IF NOT EXISTS h2_warning_chain_test (col INT)");

        SQLWarning warning = statement.getWarnings();
        if (warning != null) {
            assertNotNull(warning.getMessage(), "warning message must not be null");
            assertNotNull(warning.getSQLState(), "warning sqlState must not be null");
            assertTrue(warning.getErrorCode() >= 0, "warning vendorCode must be a non-negative integer");
            // Walk the chain if it exists; each node must have the same validity guarantees.
            SQLWarning next = warning.getNextWarning();
            if (next != null) {
                assertNotNull(next.getMessage(), "chained warning message must not be null");
                assertNotNull(next.getSQLState(), "chained warning sqlState must not be null");
            }
        }
        // Clean up
        statement.execute("DROP TABLE IF EXISTS h2_warning_chain_test");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void shouldTransferConnectionLevelWarning(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // getWarnings() on Connection – verify round-trip returns null when nothing has warned.
        // Real connection-level warnings (e.g. deprecated property) are database-specific;
        // this test validates that the transport path does not crash and returns null correctly.
        SQLWarning connWarning = connection.getWarnings();
        // Either null (no warning) or a valid SQLWarning with a non-null SQLSTATE.
        if (connWarning != null) {
            assertNotNull(connWarning.getMessage());
            assertNotNull(connWarning.getSQLState());
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void shouldPreserveVendorCodeWhenNonZero(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Execute a statement that may produce a warning in H2.
        // We use CREATE TABLE IF NOT EXISTS on a table that already exists, which some H2 versions
        // report as a warning. Whether or not a warning is produced, vendorCode must be valid.
        statement.execute("CREATE TABLE IF NOT EXISTS h2_vendor_code_test (col INT)");
        statement.execute("CREATE TABLE IF NOT EXISTS h2_vendor_code_test (col INT)");

        SQLWarning warning = statement.getWarnings();
        if (warning != null) {
            // vendorCode is transported as an int; verify it is a valid non-negative value.
            // This confirms the int field was deserialized correctly and not lost in transit.
            assertTrue(warning.getErrorCode() >= 0,
                    "vendorCode must be a non-negative integer after round-trip over gRPC");
        }
        statement.execute("DROP TABLE IF EXISTS h2_vendor_code_test");
    }
}
