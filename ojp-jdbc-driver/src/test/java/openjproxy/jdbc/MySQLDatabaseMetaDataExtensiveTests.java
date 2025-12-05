package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import openjproxy.jdbc.testutil.MySQLConnectionProvider;
import org.junit.Assert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledIf("openjproxy.jdbc.testutil.MySQLTestContainer#isEnabled")
public class MySQLDatabaseMetaDataExtensiveTests {

    private static boolean isMySQLTestDisabled;
    private static boolean isMariaDBTestDisabled;
    private static Connection connection;

    @BeforeAll
    public static void checkTestConfiguration() {
        isMySQLTestDisabled = !Boolean.parseBoolean(System.getProperty("enableMySQLTests", "false"));
        isMariaDBTestDisabled = Boolean.parseBoolean(System.getProperty("disableMariaDBTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isMySQLTestDisabled, "MySQL tests are disabled");
        assumeFalse(isMariaDBTestDisabled, "MariaDB tests are disabled");
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "mysql_db_metadata_test", TestDBUtils.SqlSyntax.MYSQL, true);
    }

    @AfterAll
    public static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testBasicDatabaseMetaDataProperties(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Basic database properties
        Assert.assertNotNull(meta.getDatabaseProductName());
        if (url.toLowerCase().contains("mysql"))
            Assert.assertTrue(meta.getDatabaseProductName().toLowerCase().contains("mysql"));
        else
            Assert.assertTrue(meta.getDatabaseProductName().toLowerCase().contains("mariadb"));

        Assert.assertNotNull(meta.getDatabaseProductVersion());
        Assert.assertNotNull(meta.getDriverName());
        Assert.assertNotNull(meta.getDriverVersion());
        Assert.assertTrue(meta.getDriverMajorVersion() > 0);

        // URL and user info
        Assert.assertNotNull(meta.getURL());
        if (url.toLowerCase().contains("mysql"))
            Assert.assertTrue(meta.getURL().contains("mysql"));
        else
            Assert.assertTrue(meta.getURL().contains("mariadb"));

        Assert.assertNotNull(meta.getUserName());
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testSupportFeatures(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // MySQL typically supports these features
        Assert.assertTrue(meta.supportsTransactions());
        if (url.toLowerCase().contains("mysql"))
            Assert.assertFalse(meta.supportsDataDefinitionAndDataManipulationTransactions());
        else
            Assert.assertTrue(meta.supportsDataDefinitionAndDataManipulationTransactions());
        Assert.assertFalse(meta.supportsDataManipulationTransactionsOnly());
        Assert.assertTrue(meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        Assert.assertTrue(meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));

        // MySQL supports various SQL features
        Assert.assertTrue(meta.supportsAlterTableWithAddColumn());
        Assert.assertTrue(meta.supportsAlterTableWithDropColumn());
        Assert.assertTrue(meta.supportsUnion());
        Assert.assertTrue(meta.supportsUnionAll());
        if (url.toLowerCase().contains("mysql"))
            Assert.assertFalse(meta.supportsOrderByUnrelated());
        else
            Assert.assertTrue(meta.supportsOrderByUnrelated());
        Assert.assertTrue(meta.supportsGroupBy());
        Assert.assertTrue(meta.supportsGroupByUnrelated());
        Assert.assertTrue(meta.supportsLikeEscapeClause());
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testIdentifierProperties(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // MySQL identifier properties
        Assert.assertNotNull(meta.getIdentifierQuoteString());
        Assert.assertEquals("`", meta.getIdentifierQuoteString());
        Assert.assertNotNull(meta.getSQLKeywords());
        Assert.assertNotNull(meta.getExtraNameCharacters());

        // Case sensitivity - MySQL varies by platform
        // Just test that these methods don't throw exceptions
        meta.supportsMixedCaseIdentifiers();
        meta.storesUpperCaseIdentifiers();
        meta.storesLowerCaseIdentifiers();
        meta.storesMixedCaseIdentifiers();
        meta.supportsMixedCaseQuotedIdentifiers();
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testTransactionSupport(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Transaction isolation levels
        Assert.assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        Assert.assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_UNCOMMITTED));
        Assert.assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ));
        Assert.assertTrue(meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE));

        // Default transaction isolation
        int defaultIsolation = meta.getDefaultTransactionIsolation();
        Assert.assertTrue(defaultIsolation >= Connection.TRANSACTION_NONE && 
                   defaultIsolation <= Connection.TRANSACTION_SERIALIZABLE);
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testFunctionSupport(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Function lists should not be null
        Assert.assertNotNull(meta.getNumericFunctions());
        Assert.assertNotNull(meta.getStringFunctions());
        Assert.assertNotNull(meta.getSystemFunctions());
        Assert.assertNotNull(meta.getTimeDateFunctions());

        // MySQL should support common functions
        String numericFunctions = meta.getNumericFunctions().toUpperCase();
        String stringFunctions = meta.getStringFunctions().toUpperCase();
        String timeDateFunctions = meta.getTimeDateFunctions().toUpperCase();

        // Verify some common MySQL functions are listed
        Assert.assertTrue(numericFunctions.contains("ABS") || numericFunctions.contains("ROUND"));
        Assert.assertTrue(stringFunctions.contains("CONCAT") || stringFunctions.contains("LENGTH"));
        Assert.assertTrue(timeDateFunctions.contains("NOW") || timeDateFunctions.contains("CURDATE"));
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testResultSetSupport(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // ResultSet type support
        Assert.assertTrue(meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        // MySQL may or may not support scrollable result sets depending on configuration
        meta.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE);
        meta.supportsResultSetType(ResultSet.TYPE_SCROLL_SENSITIVE);

        // ResultSet concurrency support
        Assert.assertTrue(meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);

        // ResultSet holdability
        Assert.assertTrue(meta.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT) ||
                   meta.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT));
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testGetTables(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test getTables method
        ResultSet tables = meta.getTables(null, null, "%", new String[]{"TABLE"});
        Assert.assertNotNull(tables);
        
        boolean foundTestTable = false;
        while (tables.next()) {
            String tableName = tables.getString("TABLE_NAME");
            if ("mysql_db_metadata_test".equals(tableName)) {
                foundTestTable = true;
                Assert.assertEquals("TABLE", tables.getString("TABLE_TYPE"));
                break;
            }
        }
        Assert.assertTrue("Should find the test table we created", foundTestTable);
        tables.close();
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testGetColumns(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test getColumns method
        ResultSet columns = meta.getColumns(null, null, "mysql_db_metadata_test", "%");
        Assert.assertNotNull(columns);
        
        boolean foundIdColumn = false;
        boolean foundNameColumn = false;
        while (columns.next()) {
            String columnName = columns.getString("COLUMN_NAME");
            if ("id".equals(columnName)) {
                foundIdColumn = true;
                Assert.assertEquals("INT", columns.getString("TYPE_NAME").toUpperCase());
            } else if ("name".equals(columnName)) {
                foundNameColumn = true;
                Assert.assertEquals("VARCHAR", columns.getString("TYPE_NAME").toUpperCase());
            }
        }
        Assert.assertTrue("Should find id column", foundIdColumn);
        Assert.assertTrue("Should find name column", foundNameColumn);
        columns.close();
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testGetPrimaryKeys(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test getPrimaryKeys method
        ResultSet primaryKeys = meta.getPrimaryKeys(null, null, "mysql_db_metadata_test");
        Assert.assertNotNull(primaryKeys);
        
        boolean foundPrimaryKey = false;
        while (primaryKeys.next()) {
            String columnName = primaryKeys.getString("COLUMN_NAME");
            if ("id".equals(columnName)) {
                foundPrimaryKey = true;
                Assert.assertEquals("mysql_db_metadata_test", primaryKeys.getString("TABLE_NAME"));
                Assert.assertEquals((short) 1, primaryKeys.getShort("KEY_SEQ"));
                break;
            }
        }
        Assert.assertTrue("Should find primary key on id column", foundPrimaryKey);
        primaryKeys.close();
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testGetTypeInfo(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test getTypeInfo method
        ResultSet typeInfo = meta.getTypeInfo();
        Assert.assertNotNull(typeInfo);
        
        boolean foundIntType = false;
        boolean foundVarcharType = false;
        while (typeInfo.next()) {
            String typeName = typeInfo.getString("TYPE_NAME").toUpperCase();
            if (typeName.contains("INT")) {
                foundIntType = true;
            } else if (typeName.contains("VARCHAR")) {
                foundVarcharType = true;
            }
        }
        Assert.assertTrue("Should find integer type", foundIntType);
        Assert.assertTrue("Should find varchar type", foundVarcharType);
        typeInfo.close();
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testMySQLSpecificMetaData(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // MySQL specific features
        Assert.assertTrue(meta.supportsSubqueriesInComparisons());
        Assert.assertTrue(meta.supportsSubqueriesInExists());
        Assert.assertTrue(meta.supportsSubqueriesInIns());
        Assert.assertTrue(meta.supportsCorrelatedSubqueries());
        
        // Batch updates
        Assert.assertTrue(meta.supportsBatchUpdates());
        
        // Savepoints
        Assert.assertTrue(meta.supportsSavepoints());
        
        // Get/Set autocommit
        Assert.assertTrue(meta.supportsGetGeneratedKeys());
        
        // Named parameters in callable statements
        meta.supportsNamedParameters(); // May return false, which is fine
        
        // Multiple open results
        meta.supportsMultipleOpenResults(); // Depends on MySQL version/config
    }

    @ParameterizedTest
    @ArgumentsSource(MySQLConnectionProvider.class)
    public void testLimitsAndSizes(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test various limits - these should return reasonable values or 0 if unlimited
        Assert.assertTrue(meta.getMaxBinaryLiteralLength() >= 0);
        Assert.assertTrue(meta.getMaxCharLiteralLength() >= 0);
        Assert.assertTrue(meta.getMaxColumnNameLength() >= 0);
        Assert.assertTrue(meta.getMaxColumnsInGroupBy() >= 0);
        Assert.assertTrue(meta.getMaxColumnsInIndex() >= 0);
        Assert.assertTrue(meta.getMaxColumnsInOrderBy() >= 0);
        Assert.assertTrue(meta.getMaxColumnsInSelect() >= 0);
        Assert.assertTrue(meta.getMaxColumnsInTable() >= 0);
        Assert.assertTrue(meta.getMaxConnections() >= 0);
        Assert.assertTrue(meta.getMaxCursorNameLength() >= 0);
        Assert.assertTrue(meta.getMaxIndexLength() >= 0);
        Assert.assertTrue(meta.getMaxSchemaNameLength() >= 0);
        Assert.assertTrue(meta.getMaxProcedureNameLength() >= 0);
        Assert.assertTrue(meta.getMaxCatalogNameLength() >= 0);
        Assert.assertTrue(meta.getMaxRowSize() >= 0);
        Assert.assertTrue(meta.getMaxStatementLength() >= 0);
        Assert.assertTrue(meta.getMaxStatements() >= 0);
        Assert.assertTrue(meta.getMaxTableNameLength() >= 0);
        Assert.assertTrue(meta.getMaxTablesInSelect() >= 0);
        Assert.assertTrue(meta.getMaxUserNameLength() >= 0);
    }
}