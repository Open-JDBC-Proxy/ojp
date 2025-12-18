package openjproxy.jdbc;

import lombok.SneakyThrows;
import openjproxy.jdbc.testutil.TestDBUtils;
import openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class PostgresSavepointTests {

    private static boolean isTestEnabled;
    private ConnectionResult connectionResult;
    private Connection connection;

    @BeforeAll
    public static void checkTestConfiguration() {
      isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException {
        assumeFalse(!isTestEnabled, "PostgreSQL tests are disabled");

        connectionResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        connection = connectionResult.getConnection();
        
        // For non-XA connections, set autocommit to false
        if (!isXA) {
            connection.setAutoCommit(false);
        }
        
        connection.createStatement().execute(
                "DROP TABLE IF EXISTS savepoint_test_table"
        );
        connectionResult.commit();
        
        // Start new transaction for table creation
        connectionResult.startXATransactionIfNeeded();
        connection.createStatement().execute(
            "CREATE TABLE savepoint_test_table (id INT PRIMARY KEY, name VARCHAR(255))"
        );
        connectionResult.commit();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (connectionResult != null) {
            connectionResult.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void testSavepoint(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException {
        setUp(driverClass, url, user, pwd, isXA);
        
        // Start transaction for test
        connectionResult.startXATransactionIfNeeded();
        
        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (1, 'Alice')");
        Savepoint savepoint = connection.setSavepoint();

        connection.createStatement().execute("INSERT INTO savepoint_test_table (id, name) VALUES (2, 'Bob')");
        connection.rollback(savepoint);

        // Commit the transaction to finalize the first insert
        connectionResult.commit();

        // Start new transaction for query
        connectionResult.startXATransactionIfNeeded();
        ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM savepoint_test_table order by id desc");
        assertTrue(resultSet.next());
        assertEquals(1, resultSet.getInt("id"));
    }
}