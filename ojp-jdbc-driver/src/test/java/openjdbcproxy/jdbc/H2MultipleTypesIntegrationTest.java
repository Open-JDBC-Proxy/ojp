package openjdbcproxy.jdbc;

import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;


import static openjdbcproxy.helpers.SqlHelper.executeUpdate;

public class H2MultipleTypesIntegrationTest {

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    public void typesCoverageTestSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, ParseException {
        Connection conn = DriverManager.getConnection(url, user, pwd);

        System.out.println("Testing for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, TestDBUtils.SqlSyntax.H2);

        java.sql.PreparedStatement psInsert = conn.prepareStatement(
                "insert into test_table (val_int, val_varchar, val_double_precision, val_bigint, val_tinyint, " +
                        "val_smallint, val_boolean, val_decimal, val_float, val_byte, val_binary, val_date, val_time, " +
                        "val_timestamp) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        psInsert.setInt(1, 1);
        psInsert.setString(2, "TITLE_1");
        psInsert.setDouble(3, 2.2222d);
        psInsert.setLong(4, 33333333333333l);
        psInsert.setInt(5, 127);
        psInsert.setInt(6, 32767);
        psInsert.setBoolean(7, true);
        psInsert.setBigDecimal(8, new BigDecimal(10));
        psInsert.setFloat(9, 20.20f);
        psInsert.setByte(10, (byte) 1);
        psInsert.setBytes(11, "AAAA".getBytes());
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
        psInsert.setDate(12, new Date(sdf.parse("29/03/2025").getTime()));
        SimpleDateFormat sdfTime = new SimpleDateFormat("hh:mm:ss");
        psInsert.setTime(13, new Time(sdfTime.parse("11:12:13").getTime()));
        SimpleDateFormat sdfTimestamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        psInsert.setTimestamp(14, new Timestamp(sdfTimestamp.parse("30/03/2025 21:22:23").getTime()));
        psInsert.executeUpdate();

        java.sql.PreparedStatement psSelect = conn.prepareStatement("select * from test_table where val_int = ?");
        psSelect.setInt(1, 1);
        ResultSet resultSet = psSelect.executeQuery();
        resultSet.next();
        Assert.assertEquals(1, resultSet.getInt(1));
        Assert.assertEquals("TITLE_1", resultSet.getString(2));
        Assert.assertEquals("2.2222", ""+resultSet.getDouble(3));
        Assert.assertEquals(33333333333333L, resultSet.getLong(4));
        Assert.assertEquals(127, resultSet.getInt(5));
        Assert.assertEquals(32767, resultSet.getInt(6));
        Assert.assertEquals(true, resultSet.getBoolean(7));
        Assert.assertEquals(new BigDecimal(10), resultSet.getBigDecimal(8));
        Assert.assertEquals(20.20f+"", ""+resultSet.getFloat(9));
        Assert.assertEquals((byte) 1, resultSet.getByte(10));
        Assert.assertEquals("AAAA", new String(resultSet.getBytes(11)));
        Assert.assertEquals("29/03/2025", sdf.format(resultSet.getDate(12)));
        Assert.assertEquals("11:12:13", sdfTime.format(resultSet.getTime(13)));
        Assert.assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp(14)));

        Assert.assertEquals(1, resultSet.getInt("val_int"));
        Assert.assertEquals("TITLE_1", resultSet.getString("val_varchar"));
        Assert.assertEquals("2.2222", ""+resultSet.getDouble("val_double_precision"));
        Assert.assertEquals(33333333333333L, resultSet.getLong("val_bigint"));
        Assert.assertEquals(127, resultSet.getInt("val_tinyint"));
        Assert.assertEquals(32767, resultSet.getInt("val_smallint"));
        Assert.assertEquals(new BigDecimal(10), resultSet.getBigDecimal("val_decimal"));
        Assert.assertEquals(20.20f+"", ""+resultSet.getFloat("val_float"));
        Assert.assertEquals(true, resultSet.getBoolean("val_boolean"));
        Assert.assertEquals((byte) 1, resultSet.getByte("val_byte"));
        Assert.assertEquals("AAAA", new String(resultSet.getBytes("val_binary")));
        Assert.assertEquals("29/03/2025", sdf.format(resultSet.getDate("val_date")));
        Assert.assertEquals("11:12:13", sdfTime.format(resultSet.getTime("val_time")));
        Assert.assertEquals("30/03/2025 21:22:23", sdfTimestamp.format(resultSet.getTimestamp("val_timestamp")));

        executeUpdate(conn, "delete from test_table where val_int=1");

        ResultSet resultSetAfterDeletion = psSelect.executeQuery();
        Assert.assertFalse(resultSetAfterDeletion.next());

        resultSet.close();
        psSelect.close();
        conn.close();
    }

}
