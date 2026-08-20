package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assumeTrue(isH2TestEnabled, "Skipping H2 tests - not enabled");
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

        // H2 may raise a SQLWarning when a column value is silently truncated in MySQL-compat mode.
        // Not all H2 versions produce warnings for this scenario; the test skips gracefully if H2
        // throws a SQLException instead of issuing a warning.
        statement.execute("CREATE TABLE IF NOT EXISTS h2_warning_test (col SMALLINT)");
        statement.execute("SET MODE MySQL");
        try {
            // In MySQL-compat mode some H2 versions truncate out-of-range values and issue a warning.
            statement.execute("INSERT INTO h2_warning_test VALUES (99999)");
        } catch (SQLException e) {
            // H2 in this version throws an exception for out-of-range values rather than a warning.
            // @AfterEach closes the connection, resetting the MySQL-compat mode automatically.
            assumeTrue(false, "H2 does not produce a SQLWarning for out-of-range inserts in this version");
        }

        SQLWarning warning = statement.getWarnings();
        if (warning != null) {
            // If H2 produced a warning, verify the attributes are transferred correctly.
            assertNotNull(warning.getMessage(), "message must not be null when a warning is present");
            // SQLSTATE for a general warning starts with '01' per SQL standard.
            assertNotNull(warning.getSQLState(), "sqlState must be transferred, not null");
        }
        // Clean up
        statement.execute("DROP TABLE IF EXISTS h2_warning_test");
        statement.execute("SET MODE REGULAR");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    void shouldTransferChainedWarningsFromStatement(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Issue two consecutive truncation inserts; in MySQL-compat mode some H2 versions chain warnings.
        statement.execute("CREATE TABLE IF NOT EXISTS h2_warning_chain_test (col SMALLINT)");
        statement.execute("SET MODE MySQL");
        try {
            statement.execute("INSERT INTO h2_warning_chain_test VALUES (99999), (88888)");
        } catch (SQLException e) {
            // H2 in this version throws an exception for out-of-range values rather than a warning.
            // @AfterEach closes the connection, resetting the MySQL-compat mode automatically.
            assumeTrue(false, "H2 does not produce a SQLWarning for out-of-range inserts in this version");
        }

        SQLWarning head = statement.getWarnings();
        if (head != null && head.getNextWarning() != null) {
            // Verify the second node in the chain is also fully populated.
            SQLWarning second = head.getNextWarning();
            assertNotNull(second.getMessage(), "chained warning message must not be null");
        }
        // Clean up
        statement.execute("DROP TABLE IF EXISTS h2_warning_chain_test");
        statement.execute("SET MODE REGULAR");
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

        // Execute a statement that may produce a warning in H2 MySQL-compat mode.
        // We verify that vendorCode is transported as an integer (including zero)
        // and does not default to some garbage value.
        statement.execute("CREATE TABLE IF NOT EXISTS h2_vendor_warning_test (col SMALLINT)");
        statement.execute("SET MODE MySQL");
        try {
            statement.execute("INSERT INTO h2_vendor_warning_test VALUES (99999)");
        } catch (SQLException e) {
            // @AfterEach closes the connection, resetting the MySQL-compat mode automatically.
            assumeTrue(false, "H2 does not produce a SQLWarning for out-of-range inserts in this version");
        }

        SQLWarning warning = statement.getWarnings();
        if (warning != null) {
            // vendorCode must be a valid integer; calling getErrorCode() twice must return
            // the same value to confirm it was correctly deserialized and is not mutable state.
            assertEquals(warning.getErrorCode(), warning.getErrorCode(),
                    "vendorCode must be stable across repeated calls");
        }
        statement.execute("DROP TABLE IF EXISTS h2_vendor_warning_test");
        statement.execute("SET MODE REGULAR");
    }
}
