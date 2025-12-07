package openjproxy.jdbc;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static openjproxy.helpers.SqlHelper.executeUpdate;

public class ReadMultipleBlocksOfDataIntegrationTest {

    private static boolean isH2TestDisabled;
    private static boolean isPostgresTestDisabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isH2TestDisabled = Boolean.parseBoolean(System.getProperty("disableH2Tests", "false"));
        isPostgresTestDisabled = Boolean.parseBoolean(System.getProperty("disablePostgresTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections_with_record_counts.csv")
    public void multiplePagesOfRowsResultSetSuccessful(int totalRecords, String driverClass, String url, String user, String pwd, boolean isXA) throws SQLException, ClassNotFoundException {
        if (isH2TestDisabled && url.toLowerCase().contains(":h2:")) {
            return;
        }
        if (isPostgresTestDisabled && url.contains("postgresql")) {
            return;
        }
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing retrieving " + totalRecords + " records from url -> " + url);

        try {
            executeUpdate(conn, "drop table read_blocks_test_multi");
        } catch (Exception e) {
            //Does not matter
        }
        
        // Create table for H2/PostgreSQL
        String createTableSql = "create table read_blocks_test_multi(" +
                "id INT NOT NULL, " +
                "title VARCHAR(50) NOT NULL)";
        executeUpdate(conn, createTableSql);

        for (int i = 0; i < totalRecords; i++) { //TODO make this test parameterized with multiple parameters
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
