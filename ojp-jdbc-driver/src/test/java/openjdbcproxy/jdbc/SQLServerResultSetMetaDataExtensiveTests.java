package openjdbcproxy.jdbc;

import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.*;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SQL Server-specific ResultSetMetaData integration tests.
 * Tests SQL Server-specific metadata functionality for result sets.
 */
public class SQLServerResultSetMetaDataExtensiveTests {

    private static boolean isTestDisabled;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerResultSetMetaDataBasics(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server ResultSetMetaData basics for url -> " + url);

        TestDBUtils.createMultiTypeTestTable(conn, "sqlserver_rsmd_test", TestDBUtils.SqlSyntax.SQLSERVER);

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_rsmd_test");
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        // Test basic metadata properties
        int columnCount = rsmd.getColumnCount();
        Assert.assertTrue("Column count should be > 0", columnCount > 0);

        // Test each column's metadata
        for (int i = 1; i <= columnCount; i++) {
            String columnName = rsmd.getColumnName(i);
            Assert.assertNotNull("Column name should not be null", columnName);
            Assert.assertTrue("Column name should not be empty", columnName.length() > 0);

            String columnTypeName = rsmd.getColumnTypeName(i);
            Assert.assertNotNull("Column type name should not be null", columnTypeName);

            int columnType = rsmd.getColumnType(i);
            Assert.assertTrue("Column type should be valid SQL type", columnType != 0);

            String columnClassName = rsmd.getColumnClassName(i);
            Assert.assertNotNull("Column class name should not be null", columnClassName);

            int precision = rsmd.getPrecision(i);
            Assert.assertTrue("Precision should be >= 0", precision >= 0);

            int scale = rsmd.getScale(i);
            Assert.assertTrue("Scale should be >= 0", scale >= 0);
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_rsmd_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerSpecificDataTypes(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server specific data types metadata for url -> " + url);

        // Create table with SQL Server-specific types
        TestDBUtils.createSqlServerSpecificTestTable(conn, "sqlserver_specific_rsmd_test");

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_specific_rsmd_test");
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        int columnCount = rsmd.getColumnCount();
        
        // Check specific SQL Server types
        for (int i = 1; i <= columnCount; i++) {
            String columnName = rsmd.getColumnName(i);
            String columnTypeName = rsmd.getColumnTypeName(i);
            int columnType = rsmd.getColumnType(i);

            switch (columnName.toLowerCase()) {
                case "id":
                    Assert.assertEquals("int", columnTypeName.toLowerCase());
                    Assert.assertEquals(Types.INTEGER, columnType);
                    break;
                case "ntext_col":
                    Assert.assertTrue("NTEXT type should be recognized", 
                        columnTypeName.toLowerCase().contains("ntext") || 
                        columnTypeName.toLowerCase().contains("nvarchar"));
                    break;
                case "money_col":
                    Assert.assertTrue("MONEY type should be recognized", 
                        columnTypeName.toLowerCase().contains("money"));
                    break;
                case "uniqueidentifier_col":
                    Assert.assertTrue("UNIQUEIDENTIFIER type should be recognized", 
                        columnTypeName.toLowerCase().contains("uniqueidentifier"));
                    break;
                case "datetimeoffset_col":
                    Assert.assertTrue("DATETIMEOFFSET type should be recognized", 
                        columnTypeName.toLowerCase().contains("datetimeoffset"));
                    break;
                case "datetime2_col":
                    Assert.assertTrue("DATETIME2 type should be recognized", 
                        columnTypeName.toLowerCase().contains("datetime2"));
                    break;
            }
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_specific_rsmd_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerColumnProperties(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server column properties for url -> " + url);

        // Create a table with specific column properties
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_column_props_test', 'U') IS NOT NULL DROP TABLE sqlserver_column_props_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_column_props_test (" +
                "id INT IDENTITY(1,1) PRIMARY KEY, " +
                "required_col NVARCHAR(50) NOT NULL, " +
                "optional_col NVARCHAR(100) NULL, " +
                "readonly_col AS (id * 2), " +  // Computed column
                "decimal_col DECIMAL(10,2) NOT NULL, " +
                "max_col NVARCHAR(MAX))");

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_column_props_test");
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        int columnCount = rsmd.getColumnCount();
        
        for (int i = 1; i <= columnCount; i++) {
            String columnName = rsmd.getColumnName(i);
            
            switch (columnName.toLowerCase()) {
                case "id":
                    Assert.assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(i));
                    Assert.assertTrue("ID should be auto increment", rsmd.isAutoIncrement(i));
                    break;
                case "required_col":
                    Assert.assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(i));
                    Assert.assertFalse("Required column should not be auto increment", rsmd.isAutoIncrement(i));
                    break;
                case "optional_col":
                    Assert.assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(i));
                    break;
                case "readonly_col":
                    Assert.assertTrue("Computed column should be read-only", rsmd.isReadOnly(i));
                    break;
                case "decimal_col":
                    Assert.assertEquals(10, rsmd.getPrecision(i));
                    Assert.assertEquals(2, rsmd.getScale(i));
                    break;
                case "max_col":
                    // SQL Server MAX columns have very large precision
                    Assert.assertTrue("MAX column should have large precision", rsmd.getPrecision(i) > 1000);
                    break;
            }
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_column_props_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerTableAndSchemaInfo(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server table and schema info for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_table_info_test", TestDBUtils.SqlSyntax.SQLSERVER);

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_table_info_test");
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        int columnCount = rsmd.getColumnCount();
        
        for (int i = 1; i <= columnCount; i++) {
            // Test catalog and schema information
            String catalogName = rsmd.getCatalogName(i);
            String schemaName = rsmd.getSchemaName(i);
            String tableName = rsmd.getTableName(i);

            // SQL Server should provide table information
            Assert.assertNotNull("Table name should not be null", tableName);
            if (tableName.length() > 0) {
                Assert.assertEquals("sqlserver_table_info_test", tableName.toLowerCase());
            }

            // Schema name should be available (might be 'dbo' or user schema)
            if (schemaName != null && schemaName.length() > 0) {
                Assert.assertTrue("Schema name should be valid", schemaName.length() > 0);
            }

            // Test display size
            int displaySize = rsmd.getColumnDisplaySize(i);
            Assert.assertTrue("Display size should be > 0", displaySize > 0);

            // Test searchable property
            boolean isSearchable = rsmd.isSearchable(i);
            // Most columns should be searchable
            Assert.assertTrue("Column should be searchable", isSearchable);
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_table_info_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerAliasedColumns(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server aliased columns for url -> " + url);

        TestDBUtils.createBasicTestTable(conn, "sqlserver_alias_test", TestDBUtils.SqlSyntax.SQLSERVER);

        PreparedStatement ps = conn.prepareStatement(
                "SELECT id AS identifier, name AS full_name, " +
                "UPPER(name) AS upper_name, " +
                "LEN(name) AS name_length " +
                "FROM sqlserver_alias_test"
        );
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        Assert.assertEquals(4, rsmd.getColumnCount());

        // Test aliased column names
        Assert.assertEquals("identifier", rsmd.getColumnLabel(1));
        Assert.assertEquals("full_name", rsmd.getColumnLabel(2));
        Assert.assertEquals("upper_name", rsmd.getColumnLabel(3));
        Assert.assertEquals("name_length", rsmd.getColumnLabel(4));

        // Test original column names (where applicable)
        Assert.assertEquals("id", rsmd.getColumnName(1));
        Assert.assertEquals("name", rsmd.getColumnName(2));
        // Computed columns might not have original names

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_alias_test");
        conn.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerNullabilityAndUpdatability(String driverClass, String url, String user, String pwd) throws SQLException {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        Connection conn = DriverManager.getConnection(url, user, pwd);
        System.out.println("Testing SQL Server nullability and updatability for url -> " + url);

        // Create table with various constraints
        try {
            TestDBUtils.executeUpdate(conn, "IF OBJECT_ID('sqlserver_constraints_test', 'U') IS NOT NULL DROP TABLE sqlserver_constraints_test");
        } catch (Exception e) {
            // Ignore
        }

        TestDBUtils.executeUpdate(conn, "CREATE TABLE sqlserver_constraints_test (" +
                "id INT IDENTITY(1,1) PRIMARY KEY, " +
                "not_null_col NVARCHAR(50) NOT NULL, " +
                "nullable_col NVARCHAR(50) NULL, " +
                "computed_col AS (id + 100), " +
                "default_col NVARCHAR(50) DEFAULT 'default_value')");

        PreparedStatement ps = conn.prepareStatement("SELECT * FROM sqlserver_constraints_test");
        ResultSet rs = ps.executeQuery();
        ResultSetMetaData rsmd = rs.getMetaData();

        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
            String columnName = rsmd.getColumnName(i);
            
            switch (columnName.toLowerCase()) {
                case "id":
                    Assert.assertEquals("Primary key should not allow nulls", 
                        ResultSetMetaData.columnNoNulls, rsmd.isNullable(i));
                    Assert.assertTrue("Identity column should be auto-increment", 
                        rsmd.isAutoIncrement(i));
                    break;
                case "not_null_col":
                    Assert.assertEquals("NOT NULL column should not allow nulls", 
                        ResultSetMetaData.columnNoNulls, rsmd.isNullable(i));
                    break;
                case "nullable_col":
                    Assert.assertEquals("Nullable column should allow nulls", 
                        ResultSetMetaData.columnNullable, rsmd.isNullable(i));
                    break;
                case "computed_col":
                    Assert.assertTrue("Computed column should be read-only", 
                        rsmd.isReadOnly(i));
                    break;
                case "default_col":
                    Assert.assertEquals("Column with default should allow nulls", 
                        ResultSetMetaData.columnNullable, rsmd.isNullable(i));
                    break;
            }
        }

        rs.close();
        ps.close();
        TestDBUtils.cleanupTestTables(conn, "sqlserver_constraints_test");
        conn.close();
    }
}