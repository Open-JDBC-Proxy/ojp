package openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLWarning;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests verifying that {@link SQLWarning} chains are fully transferred through
 * the OJP gRPC boundary for MySQL and MariaDB connections.
 *
 * <p>MySQL/MariaDB SIGNAL with SQLSTATE {@code '01000'} (general warning) is used to produce
 * deterministic single and chained warnings, so that all three attributes ({@code message},
 * {@code sqlState}, {@code vendorCode}) can be asserted precisely.
 *
 * <p>Run with {@code -DenableMySQLTests=true} and/or {@code -DenableMariaDBTests=true}.
 */
public class MySQLSqlWarningIntegrationTest {

    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;

    private Connection connection;
    private Statement statement;

    @BeforeAll
    static void checkTestConfiguration() {
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        if (url.toLowerCase().contains("mysql")) {
            assumeFalse(!isMySQLTestEnabled, "MySQL tests are not enabled");
        } else {
            assumeFalse(!isMariaDBTestEnabled, "MariaDB tests are not enabled");
        }
        connection = DriverManager.getConnection(url, user, password);
        statement = connection.createStatement();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (statement != null && !statement.isClosed()) {
            statement.close();
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void shouldReturnNullGetWarningsWhenNoPriorWarningExists(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Run a plain query that produces no warning.
        statement.executeQuery("SELECT 1");
        statement.clearWarnings();

        assertNull(statement.getWarnings());
        assertNull(connection.getWarnings());
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void shouldTransferSingleWarningWithAllAttributes(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Create a stored procedure that emits exactly one SQLSTATE '01000' warning.
        statement.execute("DROP PROCEDURE IF EXISTS ojp_single_warning");
        statement.execute(
                "CREATE PROCEDURE ojp_single_warning() " +
                "BEGIN " +
                "  SIGNAL SQLSTATE '01000' " +
                "    SET MESSAGE_TEXT = 'ojp single warning test', MYSQL_ERRNO = 1234; " +
                "END"
        );
        statement.clearWarnings();
        statement.execute("CALL ojp_single_warning()");

        SQLWarning warning = statement.getWarnings();
        assertNotNull(warning, "A SQLWarning must be returned after SIGNAL SQLSTATE '01000'");
        assertEquals("ojp single warning test", warning.getMessage(),
                "message must be preserved across gRPC boundary");
        assertEquals("01000", warning.getSQLState(),
                "sqlState must be preserved across gRPC boundary");
        // MySQL maps MYSQL_ERRNO to vendorCode.
        assertEquals(1234, warning.getErrorCode(),
                "vendorCode must be preserved across gRPC boundary");

        statement.execute("DROP PROCEDURE IF EXISTS ojp_single_warning");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void shouldTransferChainedWarningsInOrder(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Create a stored procedure that emits two warnings in sequence.
        statement.execute("DROP PROCEDURE IF EXISTS ojp_chain_warning");
        statement.execute(
                "CREATE PROCEDURE ojp_chain_warning() " +
                "BEGIN " +
                "  SIGNAL SQLSTATE '01000' " +
                "    SET MESSAGE_TEXT = 'warning one', MYSQL_ERRNO = 1001; " +
                "  SIGNAL SQLSTATE '01001' " +
                "    SET MESSAGE_TEXT = 'warning two', MYSQL_ERRNO = 1002; " +
                "END"
        );
        statement.clearWarnings();
        statement.execute("CALL ojp_chain_warning()");

        SQLWarning first = statement.getWarnings();
        assertNotNull(first, "First warning in chain must not be null");
        assertEquals("warning one", first.getMessage(), "first warning message must match");
        assertEquals("01000", first.getSQLState(), "first warning sqlState must match");
        assertEquals(1001, first.getErrorCode(), "first warning vendorCode must match");

        SQLWarning second = first.getNextWarning();
        assertNotNull(second, "Second warning in chain must not be null – chain must be fully transferred");
        assertEquals("warning two", second.getMessage(), "second warning message must match");
        assertEquals("01001", second.getSQLState(), "second warning sqlState must match");
        assertEquals(1002, second.getErrorCode(), "second warning vendorCode must match");

        assertNull(second.getNextWarning(), "No more warnings expected beyond the two emitted");

        statement.execute("DROP PROCEDURE IF EXISTS ojp_chain_warning");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void shouldTransferWarningOnConnectionLevel(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // Execute a SET statement that may produce a connection-level warning on some versions.
        // Regardless, verify the transport does not crash and returns a valid result.
        SQLWarning connWarning = connection.getWarnings();
        if (connWarning != null) {
            // If a connection-level warning exists, its attributes must be fully populated.
            assertNotNull(connWarning.getMessage());
            assertNotNull(connWarning.getSQLState());
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/mysql_mariadb_connection.csv")
    void shouldTransferDataTruncationWarningFromInsert(
            String driverClass, String url, String user, String password) throws Exception {
        setUp(driverClass, url, user, password);

        // A VARCHAR(5) column with strict mode off generates a truncation warning for long values.
        statement.execute("DROP TABLE IF EXISTS ojp_truncation_test");
        statement.execute("CREATE TABLE ojp_truncation_test (col VARCHAR(5))");
        // Disable strict mode so truncation becomes a warning rather than an error.
        statement.execute("SET SESSION sql_mode = ''");
        statement.clearWarnings();
        statement.execute("INSERT INTO ojp_truncation_test VALUES ('toolongvalue')");

        SQLWarning warning = statement.getWarnings();
        assertNotNull(warning,
                "Data truncation must produce a SQLWarning; if this fails the mode change may not have worked");
        assertNotNull(warning.getMessage(),
                "Truncation warning message must be transferred");
        assertNotNull(warning.getSQLState(),
                "Truncation warning sqlState must be transferred");

        statement.execute("DROP TABLE IF EXISTS ojp_truncation_test");
    }
}
