package openjdbcproxy.jdbc;

import lombok.extern.slf4j.Slf4j;
import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static openjdbcproxy.helpers.SqlHelper.executeUpdate;

@Slf4j
public class BasicCrudIntegrationTest {

    private static boolean isTestDisabled;

    @BeforeAll
    public static void setup() {
        isTestDisabled = Boolean.parseBoolean(System.getProperty("disablePostgresTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_postgres_connections.csv")
    public void crudTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {
        Assumptions.assumeFalse(isTestDisabled, "Skipping Postgres tests");

        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing for url -> " + url);

        try {
            executeUpdate(conn, "drop table test_table");
        } catch (Exception e) {
            //Does not matter
        }

        executeUpdate(conn, "create table test_table(" +
                "id INT NOT NULL," +
                "title VARCHAR(50) NOT NULL" +
                ")");

        executeUpdate(conn, " insert into test_table (id, title) values (1, 'TITLE_1')");

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from test_table where id = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        int id = resultSet.getInt(1);
        String title = resultSet.getString(2);
        Assert.assertEquals(1, id);
        Assert.assertEquals("TITLE_1", title);

        executeUpdate(conn, "update test_table set title='TITLE_1_UPDATED'");

        ResultSet resultSetUpdated = psSelect.executeQuery();
        resultSetUpdated.next();
        int idUpdated = resultSetUpdated.getInt(1);
        String titleUpdated = resultSetUpdated.getString(2);
        Assert.assertEquals(1, idUpdated);
        Assert.assertEquals("TITLE_1_UPDATED", titleUpdated);

        executeUpdate(conn, " delete from test_table where id=1 and title='TITLE_1_UPDATED'");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        Assert.assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

}
