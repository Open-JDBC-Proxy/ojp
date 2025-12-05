package openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import openjproxy.jdbc.testutil.TestDBUtils;
import openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
import org.junit.Assert;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import static openjproxy.helpers.SqlHelper.executeUpdate;

@Slf4j
public class BasicCrudIntegrationTest {

    private static boolean isPostgresTestDisabled;
    private static boolean isMySQLTestDisabled;
    private static boolean isMariaDBTestDisabled;
    private static boolean isCockroachDBTestDisabled;
    private static boolean isOracleTestEnabled;
    private static boolean isSqlServerTestEnabled;
    private static boolean isDb2TestEnabled;
    private static String tablePrefix = "";

    @BeforeAll
    public static void setup() {
        isPostgresTestDisabled = !Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
        isMySQLTestDisabled = Boolean.parseBoolean(System.getProperty("disableMySQLTests", "false"));
        isMariaDBTestDisabled = Boolean.parseBoolean(System.getProperty("disableMariaDBTests", "false"));
        isCockroachDBTestDisabled = Boolean.parseBoolean(System.getProperty("disableCockroachDBTests", "false"));
        isOracleTestEnabled = Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
        isSqlServerTestEnabled = Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
        isDb2TestEnabled = Boolean.parseBoolean(System.getProperty("enableDb2Tests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_mysql_mariadb_oracle_sqlserver_connections.csv")
    public void crudTestSuccessful(String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException {
        // Skip PostgreSQL tests if disabled
        if (url.toLowerCase().contains("postgresql") && isPostgresTestDisabled) {
            Assumptions.assumeFalse(true, "Skipping Postgres tests");
            tablePrefix = "postgres_";
        }
        
        // Skip MySQL tests if disabled
        if (url.toLowerCase().contains("mysql") && isMySQLTestDisabled) {
            Assumptions.assumeFalse(true, "Skipping MySQL tests");
            tablePrefix = "mysql_";
        }

        // Skip MariaDB tests if disabled
        if (url.toLowerCase().contains("mariadb") && isMariaDBTestDisabled) {
            Assumptions.assumeFalse(true, "Skipping MariaDB tests");
            tablePrefix = "mariadb_";
        }

        // Skip Oracle tests if not enabled
        if (url.toLowerCase().contains("oracle") && !isOracleTestEnabled) {
            Assumptions.assumeFalse(true, "Skipping Oracle tests - not enabled");
            tablePrefix = "oracle_";
        }

        // Skip SQL Server tests if not enabled
        if (url.toLowerCase().contains("sqlserver") && !isSqlServerTestEnabled) {
            Assumptions.assumeFalse(true, "Skipping SQL Server tests - not enabled");
            tablePrefix = "sqlserver_";
        }

        // Skip DB2 tests if not enabled
        if (url.toLowerCase().contains("db2") && !isDb2TestEnabled) {
            Assumptions.assumeFalse(true, "Skipping DB2 tests - not enabled");
            tablePrefix = "db2_";
        }

        // Skip CockroachDB tests if disabled  
        if (url.toLowerCase().contains("26257") && isCockroachDBTestDisabled) {
            Assumptions.assumeFalse(true, "Skipping CockroachDB tests");
            tablePrefix = "cockroachdb_";
        }

        ConnectionResult connResult = TestDBUtils.createConnection(url, user, pwd, isXA);
        Connection conn = connResult.getConnection();

        // For non-XA connections, set autocommit to false for explicit transaction control
        if (!isXA) {
            conn.setAutoCommit(false);
        }

        // Set schema for DB2 connections to avoid "object not found" errors
        if (url.toLowerCase().contains("db2")) {
            try (java.sql.Statement schemaStmt = conn.createStatement()) {
                schemaStmt.execute("SET SCHEMA DB2INST1");
            }
        }

        System.out.println("Testing for url -> " + url);

        // Use qualified table names for DB2
        String tableName = tablePrefix + "basic_crud_test";
        if (url.toLowerCase().contains("db2")) {
            tableName = "DB2INST1." + tableName;
        }

        try {
            executeUpdate(conn, "drop table " + tableName);
            connResult.commit();
        } catch (Exception e) {
            //Does not matter - table might not exist
            try {
                connResult.rollback();
            } catch (Exception ex) {
                // Ignore rollback errors
            }
        }
        
        // Start new transaction for next operation
        connResult.startXATransactionIfNeeded();

        executeUpdate(conn, "create table " + tableName + "(" +
                "id INT NOT NULL," +
                "title VARCHAR(50) NOT NULL" +
                ")");
        connResult.commit();
        
        // Start new transaction for next operation
        connResult.startXATransactionIfNeeded();

        executeUpdate(conn, " insert into " + tableName + " (id, title) values (1, 'TITLE_1')");
        connResult.commit();
        
        // Start new transaction for next operation
        connResult.startXATransactionIfNeeded();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from " + tableName + " where id = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        int id = resultSet.getInt(1);
        String title = resultSet.getString(2);
        Assert.assertEquals(1, id);
        Assert.assertEquals("TITLE_1", title);

        executeUpdate(conn, "update " + tableName + " set title='TITLE_1_UPDATED'");
        connResult.commit();
        
        // Start new transaction for next operation
        connResult.startXATransactionIfNeeded();

        ResultSet resultSetUpdated = psSelect.executeQuery();
        resultSetUpdated.next();
        int idUpdated = resultSetUpdated.getInt(1);
        String titleUpdated = resultSetUpdated.getString(2);
        Assert.assertEquals(1, idUpdated);
        Assert.assertEquals("TITLE_1_UPDATED", titleUpdated);

        executeUpdate(conn, " delete from " + tableName + " where id=1 and title='TITLE_1_UPDATED'");
        connResult.commit();
        
        // Start new transaction for next operation
        connResult.startXATransactionIfNeeded();

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        Assert.assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        
        // Clean up - drop the test table
        try {
            executeUpdate(conn, "drop table " + tableName);
            connResult.commit();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
        
        connResult.close();
    }

}
