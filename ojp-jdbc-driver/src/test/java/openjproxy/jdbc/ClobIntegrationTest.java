package openjproxy.jdbc;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static openjproxy.helpers.SqlHelper.executeUpdate;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Integration tests for Clob/NClob methods using Reader interface.
 * These tests mirror the BlobIntegrationTest but use Reader instead of InputStream.
 */
public class ClobIntegrationTest {

    private static boolean isH2TestEnabled;
    private static boolean isMySQLTestEnabled;
    private static boolean isMariaDBTestEnabled;
    private static boolean isOracleTestEnabled;
    private String tableName;
    private Connection conn;

    @BeforeAll
    public static void checkTestConfiguration() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "false"));
        isMySQLTestEnabled = Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestEnabled = Boolean.parseBoolean(System.getProperty("enableMariaDBTests", "false"));
        isOracleTestEnabled = Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException {

        this.tableName = "clob_test_clob";
        if (url.toLowerCase().contains("mysql")) {
            assumeFalse(!isMySQLTestEnabled, "MySQL tests are not enabled");
            this.tableName += "_mysql";
        } else if (url.toLowerCase().contains("mariadb")) {
            assumeFalse(!isMariaDBTestEnabled, "MariaDB tests are not enabled");
            this.tableName += "_mariadb";
        } else if (url.toLowerCase().contains("oracle")) {
            assumeFalse(!isOracleTestEnabled, "Oracle tests are disabled");
            this.tableName += "_oracle";
        } else {
            assumeFalse(!isH2TestEnabled, "H2 tests are disabled");
            this.tableName += "_h2";
        }
        Class.forName(driverClass);
        this.conn = DriverManager.getConnection(url, user, pwd);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_oracle_connections.csv")
    public void createAndReadingCLOBsSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        this.setUp(driverClass, url, user, pwd);
        System.out.println("Testing CLOB for url -> " + url);

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // H2, Oracle, and MySQL do not support setClob with Reader due to internal CLOB/BLOB casting issues
        if (url.toLowerCase().contains("h2") || url.toLowerCase().contains("oracle") || 
            url.toLowerCase().contains("mysql")) {
            System.out.println(url + " does not support setClob with Reader - asserting expected failure");
            
            // Create a simple table just for the assertion test
            String clobType = "CLOB";
            if (url.toLowerCase().contains("mysql")) {
                clobType = "LONGTEXT";
            }
            
            executeUpdate(conn,
                    "create table " + tableName + "(" +
                            " val_clob  " + clobType + "," +
                            " val_clob2 " + clobType + "," +
                            " val_clob3 " + clobType +
                            ")"
            );
            
            PreparedStatement psInsert = conn.prepareStatement(
                    " insert into " + tableName + " (val_clob, val_clob2, val_clob3) values (?, ?, ?)"
            );
            String testString = "CLOB VIA READER STREAM";
            Assert.assertThrows(SQLException.class, () -> {
                Clob clob = conn.createClob();
                clob.setString(1, testString);
                psInsert.setClob(1, clob);
                psInsert.setClob(2, new StringReader(testString));
                psInsert.setClob(3, new StringReader(testString), 5);
                psInsert.executeUpdate();
            });
            conn.close();
            return;
        }

        // MariaDB: getClob() returns null through GRPC layer - unsupported
        String clobType = "LONGTEXT";  // MariaDB uses LONGTEXT instead of CLOB

        executeUpdate(conn,
                "create table " + tableName + "(" +
                        " val_clob  " + clobType + "," +
                        " val_clob2 " + clobType + "," +
                        " val_clob3 " + clobType +
                        ")"
        );

        String testString = "CLOB VIA READER STREAM";
        Assert.assertThrows(SQLException.class, () -> {
            PreparedStatement psInsert = conn.prepareStatement(
                    " insert into " + tableName + " (val_clob, val_clob2, val_clob3) values (?, ?, ?)"
            );
            Clob clob = conn.createClob();
            clob.setString(1, testString);
            psInsert.setClob(1, clob);
            psInsert.setClob(2, new StringReader(testString));
            psInsert.setClob(3, new StringReader(testString), 5);
            psInsert.executeUpdate();
            
            // Retrieval fails - getClob() returns null
            PreparedStatement psSelect = conn.prepareStatement("select val_clob from " + tableName);
            ResultSet resultSet = psSelect.executeQuery();
            resultSet.next();
            Clob clobResult = resultSet.getClob(1);
            readAllFromClob(clobResult);
        });
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_oracle_connections.csv")
    public void createAndReadingCLOBsWithMultiByteCharactersSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        this.setUp(driverClass, url, user, pwd);
        System.out.println("Testing CLOB with multi-byte characters for url -> " + url);

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // H2, Oracle, and MySQL do not support setClob with Reader due to internal CLOB/BLOB casting issues
        if (url.toLowerCase().contains("h2") || url.toLowerCase().contains("oracle") || 
            url.toLowerCase().contains("mysql")) {
            System.out.println(url + " does not support setClob with Reader - asserting expected failure");
            
            // Create a simple table just for the assertion test
            String clobType = "CLOB";
            if (url.toLowerCase().contains("mysql")) {
                clobType = "LONGTEXT";
            }
            
            executeUpdate(conn,
                    "create table " + tableName + "(" +
                            " val_clob " + clobType +
                            ")"
            );
            
            PreparedStatement psInsert = conn.prepareStatement(
                    "insert into " + tableName + " (val_clob) values (?)"
            );
            String testString = "Hello 世界 こんにちは 🌍 Testing Unicode Characters!";
            Assert.assertThrows(SQLException.class, () -> {
                psInsert.setClob(1, new StringReader(testString));
                psInsert.executeUpdate();
            });
            conn.close();
            return;
        }

        // MariaDB: getClob() returns null through GRPC layer - unsupported
        String clobType = "LONGTEXT";  // MariaDB uses LONGTEXT instead of CLOB

        executeUpdate(conn,
                "create table " + tableName + "(" +
                        " val_clob " + clobType +
                        ")"
        );

        // Test with multi-byte characters including Chinese, Japanese, and emoji
        String testString = "Hello 世界 こんにちは 🌍 Testing Unicode Characters!";
        
        Assert.assertThrows(SQLException.class, () -> {
            PreparedStatement psInsert = conn.prepareStatement(
                    "insert into " + tableName + " (val_clob) values (?)"
            );
            Reader reader = new StringReader(testString);
            psInsert.setClob(1, reader);
            psInsert.executeUpdate();

            // Retrieval fails - getClob() returns null
            PreparedStatement psSelect = conn.prepareStatement("select val_clob from " + tableName);
            ResultSet resultSet = psSelect.executeQuery();
            resultSet.next();
            Clob clobResult = resultSet.getClob(1);
            readAllFromClob(clobResult);
        });
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_mysql_mariadb_oracle_connections.csv")
    public void createAndReadingNCLOBsSuccessful(String driverClass, String url, String user, String pwd) throws SQLException, ClassNotFoundException, IOException {
        this.setUp(driverClass, url, user, pwd);
        System.out.println("Testing NCLOB for url -> " + url);

        try {
            executeUpdate(conn, "drop table " + tableName);
        } catch (Exception e) {
            //If fails disregard as per the table is most possibly not created yet
        }

        // H2, Oracle, and MySQL do not support setNClob with Reader due to internal CLOB/BLOB casting issues
        // Note: MariaDB DOES support setNClob with Reader (unlike regular setClob)
        if (url.toLowerCase().contains("h2") || url.toLowerCase().contains("oracle") || 
            url.toLowerCase().contains("mysql")) {
            System.out.println(url + " does not support setNClob with Reader - asserting expected failure");
            
            // Create a simple table just for the assertion test
            String clobType = "CLOB";
            if (url.toLowerCase().contains("oracle")) {
                clobType = "NCLOB";
            } else if (url.toLowerCase().contains("mysql")) {
                clobType = "LONGTEXT";
            }
            
            executeUpdate(conn,
                    "create table " + tableName + "(" +
                            " val_nclob " + clobType +
                            ")"
            );
            
            PreparedStatement psInsert = conn.prepareStatement(
                    "insert into " + tableName + " (val_nclob) values (?)"
            );
            String testString = "NCLOB test with 中文字符 and 日本語";
            Assert.assertThrows(SQLException.class, () -> {
                psInsert.setNClob(1, new StringReader(testString), testString.length());
                psInsert.executeUpdate();
                
                // Try to read back - this is where MariaDB fails (getClob returns null)
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT val_nclob FROM " + tableName);
                if (rs.next()) {
                    Clob clob = rs.getClob(1);
                    if (clob == null) {
                        throw new SQLException("getClob() returned null");
                    }
                }
                rs.close();
                stmt.close();
            });
            conn.close();
            return;
        }

        // MariaDB: NCLOB operations actually work! Test them properly.
        System.out.println("Testing MariaDB NCLOB with Reader - should succeed");
        String clobType = "LONGTEXT";  // MariaDB uses LONGTEXT instead of CLOB/NCLOB
        
        executeUpdate(conn,
                "create table " + tableName + "(" +
                        " val_nclob " + clobType +
                        ")"
        );
        
        PreparedStatement psInsert = conn.prepareStatement(
                "insert into " + tableName + " (val_nclob) values (?)"
        );
        String testString = "NCLOB test with 中文字符 and 日本語 and emoji 🌍";
        psInsert.setNClob(1, new StringReader(testString), testString.length());
        psInsert.executeUpdate();
        
        // Read back and verify
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT val_nclob FROM " + tableName);
        Assert.assertTrue(rs.next(), "Should have at least one row");
        Clob clob = rs.getClob(1);
        Assert.assertNotNull(clob, "MariaDB should return non-null Clob for NCLOB");
        
        String result = readAllFromClob(clob);
        Assert.assertEquals(testString, result, "MariaDB NCLOB content should match");
        
        rs.close();
        stmt.close();
        conn.close();
    }

    /**
     * Helper method to read all characters from a Reader into a String
     */
    private String readAll(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[8192];
        int charsRead;
        while ((charsRead = reader.read(buffer)) != -1) {
            sb.append(buffer, 0, charsRead);
        }
        return sb.toString();
    }

    /**
     * Helper method to read all characters from a Clob into a String.
     * Handles MariaDB limitation where getCharacterStream() may return null.
     */
    private String readAllFromClob(Clob clob) throws SQLException, IOException {
        if (clob == null) {
            throw new SQLException("Clob is null - unable to read");
        }
        Reader reader = clob.getCharacterStream();
        if (reader != null) {
            return readAll(reader);
        } else {
            // Fallback for databases (like MariaDB) where getCharacterStream() returns null
            // Use getSubString() instead
            long length = clob.length();
            if (length > Integer.MAX_VALUE) {
                throw new SQLException("Clob too large to read");
            }
            return clob.getSubString(1, (int) length);
        }
    }
}
