package openjproxy.jdbc;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.CsvFileSource;
import openjproxy.jdbc.testutil.PostgresConnectionWithRecordCountsProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static openjproxy.helpers.SqlHelper.executeUpdate;

public class ReadMultipleBlocksOfDataIntegrationTest {

    private static boolean isPostgresTestEnabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isPostgresTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    // H2 tests - run by default
    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections_with_record_counts.csv")
    public void multiplePagesOfRowsResultSetSuccessful(int totalRecords, String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException {
        // Skip Postgres connections in this test - they're tested separately using TestContainers
        // See multiplePagesOfRowsResultSetSuccessfulPostgres() method below
        if (url.contains("postgresql")) {
            return;
        }
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing retrieving " + totalRecords + " records from url -> " + url);

        try {
            executeUpdate(conn, "drop table read_blocks_test_multi");
        } catch (Exception e) {
            //Does not matter
        }
        
        // Create table for H2
        String createTableSql = "create table read_blocks_test_multi(" +
                "id INT NOT NULL, " +
                "title VARCHAR(50) NOT NULL)";
        executeUpdate(conn, createTableSql);

        for (int i = 0; i < totalRecords; i++) {
            executeUpdate(conn,
                    "insert into read_blocks_test_multi (id, title) values (" + i + ", 'TITLE_" + i + "')"
            );
        }

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from read_blocks_test_multi order by id");
        ResultSet resultSet = psSelect.executeQuery();

        for (int i = 0; i < totalRecords; i++) {
            resultSet.next();
            int id = resultSet.getInt(1);
            String title = resultSet.getString(2);
            Assert.assertEquals(i, id);
            Assert.assertEquals("TITLE_" + i, title);
        }

        executeUpdate(conn, "delete from read_blocks_test_multi");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        Assert.assertFalse(resultSetAfterDeletion.next());

        conn.close();
    }

    // PostgreSQL tests using TestContainers - only run when enabled
    @ParameterizedTest
    @ArgumentsSource(PostgresConnectionWithRecordCountsProvider.class)
    public void multiplePagesOfRowsResultSetSuccessfulPostgres(int totalRecords, String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException {
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing retrieving " + totalRecords + " records from PostgreSQL TestContainer url -> " + url);

        try {
            executeUpdate(conn, "drop table read_blocks_test_multi");
        } catch (Exception e) {
            //Does not matter
        }
        
        // Create table for PostgreSQL
        String createTableSql = "create table read_blocks_test_multi(" +
                "id INT NOT NULL, " +
                "title VARCHAR(50) NOT NULL)";
        executeUpdate(conn, createTableSql);

        for (int i = 0; i < totalRecords; i++) {
            executeUpdate(conn,
                    "insert into read_blocks_test_multi (id, title) values (" + i + ", 'TITLE_" + i + "')"
            );
        }

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from read_blocks_test_multi order by id");
        ResultSet resultSet = psSelect.executeQuery();

        for (int i = 0; i < totalRecords; i++) {
            resultSet.next();
            int id = resultSet.getInt(1);
            String title = resultSet.getString(2);
            Assert.assertEquals(i, id);
            Assert.assertEquals("TITLE_" + i, title);
        }

        executeUpdate(conn, "delete from read_blocks_test_multi");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        Assert.assertFalse(resultSetAfterDeletion.next());

        conn.close();
    }
}

