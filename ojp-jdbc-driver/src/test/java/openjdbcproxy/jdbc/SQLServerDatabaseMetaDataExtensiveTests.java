package openjdbcproxy.jdbc;

import openjdbcproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.*;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class SQLServerDatabaseMetaDataExtensiveTests {

    private static boolean isTestDisabled;
    private static Connection connection;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableSqlServerTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "SQL Server tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "sqlserver_db_metadata_test", TestDBUtils.SqlSyntax.SQLSERVER);
    }

    @AfterAll
    public static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information (SQL Server-specific values)
        Assertions.assertEquals(false, meta.allProceduresAreCallable());
        Assertions.assertEquals(false, meta.allTablesAreSelectable());
        Assertions.assertTrue(meta.getURL().contains("sqlserver") || meta.getURL().contains(":1433"));
        Assertions.assertNotNull(meta.getUserName()); // SQL Server username
        Assertions.assertEquals(false, meta.isReadOnly());

        // 6–10: Null handling and database product info (SQL Server-specific behaviors)
        Assertions.assertEquals(false, meta.nullsAreSortedHigh());  // SQL Server behavior
        Assertions.assertEquals(true, meta.nullsAreSortedLow());   // SQL Server sorts nulls low
        Assertions.assertEquals(false, meta.nullsAreSortedAtStart());
        Assertions.assertEquals(false, meta.nullsAreSortedAtEnd());
        Assertions.assertTrue(meta.getDatabaseProductName().toLowerCase().contains("microsoft"));

        // 11–15: Version information
        Assertions.assertNotNull(meta.getDatabaseProductVersion());
        Assertions.assertTrue(meta.getDriverName().toLowerCase().contains("microsoft"));
        Assertions.assertNotNull(meta.getDriverVersion());
        Assertions.assertTrue(meta.getDriverMajorVersion() >= 12); // SQL Server driver version
        Assertions.assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        Assertions.assertEquals(false, meta.usesLocalFiles());
        Assertions.assertEquals(false, meta.usesLocalFilePerTable());
        Assertions.assertEquals(false, meta.supportsMixedCaseIdentifiers());
        Assertions.assertEquals(false, meta.storesUpperCaseIdentifiers()); // SQL Server doesn't force uppercase
        Assertions.assertEquals(false, meta.storesLowerCaseIdentifiers()); // SQL Server doesn't force lowercase

        // 21–25: Quoted identifiers
        Assertions.assertEquals(true, meta.storesMixedCaseIdentifiers()); // SQL Server preserves case
        Assertions.assertEquals(true, meta.supportsMixedCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesUpperCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesLowerCaseQuotedIdentifiers());
        Assertions.assertEquals(true, meta.storesMixedCaseQuotedIdentifiers()); // SQL Server behavior

        // 26–30: String handling and functions
        Assertions.assertTrue(meta.getIdentifierQuoteString().equals("[") || meta.getIdentifierQuoteString().equals("\""));
        Assertions.assertNotNull(meta.getSQLKeywords());
        Assertions.assertNotNull(meta.getNumericFunctions());
        Assertions.assertNotNull(meta.getStringFunctions());
        Assertions.assertNotNull(meta.getSystemFunctions());

        // 31–35: Date/time functions and search escape characters
        Assertions.assertNotNull(meta.getTimeDateFunctions());
        Assertions.assertEquals("{", meta.getSearchStringEscape()); // SQL Server escape for LIKE
        Assertions.assertNotNull(meta.getExtraNameCharacters());
        Assertions.assertEquals(true, meta.supportsAlterTableWithAddColumn());
        Assertions.assertEquals(true, meta.supportsAlterTableWithDropColumn());

        // 36–40: Column operations and table correlation names
        Assertions.assertEquals(true, meta.supportsColumnAliasing());
        Assertions.assertEquals(false, meta.nullPlusNonNullIsNull());  // SQL Server: NULL + 'text' = NULL
        Assertions.assertEquals(true, meta.supportsConvert());
        Assertions.assertEquals(true, meta.supportsTableCorrelationNames());
        Assertions.assertEquals(true, meta.supportsDifferentTableCorrelationNames());

        // 41–45: Expression handling and ORDER BY
        Assertions.assertEquals(true, meta.supportsExpressionsInOrderBy());
        Assertions.assertEquals(true, meta.supportsOrderByUnrelated());
        Assertions.assertEquals(true, meta.supportsGroupBy());
        Assertions.assertEquals(true, meta.supportsGroupByUnrelated());
        Assertions.assertEquals(true, meta.supportsGroupByBeyondSelect());

        // 46–50: LIKE operations and escape characters
        Assertions.assertEquals(true, meta.supportsLikeEscapeClause());
        Assertions.assertEquals(false, meta.supportsMultipleResultSets()); // Depends on driver implementation
        Assertions.assertEquals(true, meta.supportsMultipleTransactions());
        Assertions.assertEquals(true, meta.supportsNonNullableColumns());
        Assertions.assertEquals(true, meta.supportsMinimumSQLGrammar());

        // 51–55: SQL grammar support levels
        Assertions.assertEquals(true, meta.supportsCoreSQLGrammar());
        Assertions.assertEquals(true, meta.supportsExtendedSQLGrammar());
        Assertions.assertEquals(true, meta.supportsANSI92EntryLevelSQL());
        Assertions.assertEquals(true, meta.supportsANSI92IntermediateSQL());
        Assertions.assertEquals(false, meta.supportsANSI92FullSQL()); // SQL Server doesn't fully support ANSI 92

        // 56–60: Outer joins and schema operations
        Assertions.assertEquals(true, meta.supportsOuterJoins());
        Assertions.assertEquals(true, meta.supportsFullOuterJoins());
        Assertions.assertEquals(true, meta.supportsLimitedOuterJoins());
        Assertions.assertNotNull(meta.getSchemaTerm());
        Assertions.assertNotNull(meta.getProcedureTerm());

        // 61–65: Catalog and cursor operations
        Assertions.assertNotNull(meta.getCatalogTerm());
        Assertions.assertEquals(true, meta.isCatalogAtStart());
        Assertions.assertEquals(".", meta.getCatalogSeparator());
        Assertions.assertEquals(true, meta.supportsSchemasInDataManipulation());
        Assertions.assertEquals(true, meta.supportsSchemasInProcedureCalls());

        // 66–70: Schema and catalog support in various contexts
        Assertions.assertEquals(true, meta.supportsSchemasInTableDefinitions());
        Assertions.assertEquals(true, meta.supportsSchemasInIndexDefinitions());
        Assertions.assertEquals(true, meta.supportsSchemasInPrivilegeDefinitions());
        Assertions.assertEquals(true, meta.supportsCatalogsInDataManipulation());
        Assertions.assertEquals(true, meta.supportsCatalogsInProcedureCalls());

        // 71–75: Catalog support in definitions
        Assertions.assertEquals(true, meta.supportsCatalogsInTableDefinitions());
        Assertions.assertEquals(true, meta.supportsCatalogsInIndexDefinitions());
        Assertions.assertEquals(true, meta.supportsCatalogsInPrivilegeDefinitions());
        Assertions.assertEquals(true, meta.supportsPositionedDelete());
        Assertions.assertEquals(true, meta.supportsPositionedUpdate());

        // 76–80: SELECT FOR UPDATE and stored procedures
        Assertions.assertEquals(false, meta.supportsSelectForUpdate()); // SQL Server doesn't support SELECT FOR UPDATE
        Assertions.assertEquals(true, meta.supportsStoredProcedures());
        Assertions.assertEquals(true, meta.supportsSubqueriesInComparisons());
        Assertions.assertEquals(true, meta.supportsSubqueriesInExists());
        Assertions.assertEquals(true, meta.supportsSubqueriesInIns());

        // 81–85: Subquery support and correlation names
        Assertions.assertEquals(true, meta.supportsSubqueriesInQuantifieds());
        Assertions.assertEquals(true, meta.supportsCorrelatedSubqueries());
        Assertions.assertEquals(true, meta.supportsUnion());
        Assertions.assertEquals(true, meta.supportsUnionAll());
        Assertions.assertEquals(true, meta.supportsOpenCursorsAcrossCommit());

        // 86–90: Cursor and statement persistence
        Assertions.assertEquals(false, meta.supportsOpenCursorsAcrossRollback()); // SQL Server behavior
        Assertions.assertEquals(true, meta.supportsOpenStatementsAcrossCommit());
        Assertions.assertEquals(true, meta.supportsOpenStatementsAcrossRollback());
        Assertions.assertTrue(meta.getMaxBinaryLiteralLength() > 0);
        Assertions.assertTrue(meta.getMaxCharLiteralLength() > 0);

        // 91–95: Maximum lengths and limits
        Assertions.assertTrue(meta.getMaxColumnNameLength() > 0);
        Assertions.assertTrue(meta.getMaxColumnsInGroupBy() >= 0);
        Assertions.assertTrue(meta.getMaxColumnsInIndex() >= 0);
        Assertions.assertTrue(meta.getMaxColumnsInOrderBy() >= 0);
        Assertions.assertTrue(meta.getMaxColumnsInSelect() >= 0);

        // 96–100: More maximum limits
        Assertions.assertTrue(meta.getMaxColumnsInTable() > 0);
        Assertions.assertTrue(meta.getMaxConnections() >= 0);
        Assertions.assertTrue(meta.getMaxCursorNameLength() >= 0);
        Assertions.assertTrue(meta.getMaxIndexLength() >= 0);
        Assertions.assertTrue(meta.getMaxSchemaNameLength() > 0);

        // 101–105: Procedure and statement limits
        Assertions.assertTrue(meta.getMaxProcedureNameLength() > 0);
        Assertions.assertTrue(meta.getMaxCatalogNameLength() > 0);
        Assertions.assertTrue(meta.getMaxRowSize() > 0);
        Assertions.assertEquals(false, meta.doesMaxRowSizeIncludeBlobs()); // SQL Server behavior
        Assertions.assertTrue(meta.getMaxStatementLength() > 0);

        // 106–110: More limits and transaction support
        Assertions.assertTrue(meta.getMaxStatements() >= 0);
        Assertions.assertTrue(meta.getMaxTableNameLength() > 0);
        Assertions.assertTrue(meta.getMaxTablesInSelect() >= 0);
        Assertions.assertTrue(meta.getMaxUserNameLength() > 0);
        Assertions.assertTrue(meta.getDefaultTransactionIsolation() >= 0);

        // 111–115: Transaction and DDL support
        Assertions.assertEquals(true, meta.supportsTransactions());
        Assertions.assertEquals(true, meta.supportsDataDefinitionAndDataManipulationTransactions());
        Assertions.assertEquals(false, meta.supportsDataManipulationTransactionsOnly());
        Assertions.assertEquals(false, meta.dataDefinitionCausesTransactionCommit()); // SQL Server behavior
        Assertions.assertEquals(false, meta.dataDefinitionIgnoredInTransactions());

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testSqlServerSpecificMetadata(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // Test SQL Server-specific metadata features
        Assertions.assertNotNull(meta.getDatabaseProductName());
        Assertions.assertTrue(meta.getDatabaseProductName().toLowerCase().contains("microsoft"));

        // Test SQL Server version information
        String version = meta.getDatabaseProductVersion();
        Assertions.assertNotNull(version);
        
        // Test SQL Server supports various features
        Assertions.assertEquals(true, meta.supportsStoredProcedures());
        Assertions.assertEquals(true, meta.supportsBatchUpdates());
        
        // Test SQL Server identifier handling
        Assertions.assertTrue(meta.getIdentifierQuoteString().equals("[") || 
                             meta.getIdentifierQuoteString().equals("\""));

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testGetTables(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"});
        TestDBUtils.validateAllRows(rs);
        rs.close();

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/sqlserver_connections.csv")
    public void testGetColumns(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet rs = meta.getColumns(null, null, "sqlserver_db_metadata_test", "%");
        TestDBUtils.validateAllRows(rs);
        rs.close();

        // Clean up
        TestDBUtils.cleanupTestTables(connection, "sqlserver_db_metadata_test");
    }
}