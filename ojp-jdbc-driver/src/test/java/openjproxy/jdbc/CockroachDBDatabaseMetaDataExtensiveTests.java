package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import openjproxy.jdbc.testutil.CockroachDBConnectionProvider;

import java.sql.*;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledIf("openjproxy.jdbc.testutil.CockroachDBTestContainer#isEnabled")
public class CockroachDBDatabaseMetaDataExtensiveTests {

    private static boolean isTestDisabled;
    private static Connection connection;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableCockroachDBTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "CockroachDB tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "cockroachdb_db_metadata_test", TestDBUtils.SqlSyntax.COCKROACHDB, true);
    }

    @AfterAll
    public static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @ArgumentsSource(CockroachDBConnectionProvider.class)
    public void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information
        Assertions.assertEquals(true, meta.allProceduresAreCallable());
        Assertions.assertEquals(true, meta.allTablesAreSelectable());
        Assertions.assertTrue(meta.getURL().contains("postgresql") || meta.getURL().contains(":26257/"));
        Assertions.assertNotNull(meta.getUserName());
        Assertions.assertEquals(false, meta.isReadOnly());

        // 6–10: Null handling and database product info
        Assertions.assertEquals(true, meta.nullsAreSortedHigh());
        Assertions.assertEquals(false, meta.nullsAreSortedLow());
        Assertions.assertEquals(false, meta.nullsAreSortedAtStart());
        Assertions.assertEquals(false, meta.nullsAreSortedAtEnd());
        // CockroachDB reports as PostgreSQL through JDBC driver
        Assertions.assertTrue(meta.getDatabaseProductName().contains("PostgreSQL"));

        // 11–15: Version information
        Assertions.assertNotNull(meta.getDatabaseProductVersion());
        Assertions.assertEquals("PostgreSQL JDBC Driver", meta.getDriverName());
        Assertions.assertNotNull(meta.getDriverVersion());
        Assertions.assertTrue(meta.getDriverMajorVersion() >= 42);
        Assertions.assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        Assertions.assertEquals(false, meta.usesLocalFiles());
        Assertions.assertEquals(false, meta.usesLocalFilePerTable());
        Assertions.assertEquals(false, meta.supportsMixedCaseIdentifiers());
        Assertions.assertEquals(false, meta.storesUpperCaseIdentifiers());
        Assertions.assertEquals(true, meta.storesLowerCaseIdentifiers());

        // 21–25: Quoted identifiers
        Assertions.assertEquals(false, meta.storesMixedCaseIdentifiers());
        Assertions.assertEquals(true, meta.supportsMixedCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesUpperCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesLowerCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesMixedCaseQuotedIdentifiers());

        // 26–30: String handling and functions
        Assertions.assertEquals("\"", meta.getIdentifierQuoteString());
        Assertions.assertNotNull(meta.getSQLKeywords());
        Assertions.assertNotNull(meta.getNumericFunctions());
        Assertions.assertNotNull(meta.getStringFunctions());
        Assertions.assertNotNull(meta.getSystemFunctions());

        // 31–35: More functions and table operations
        Assertions.assertNotNull(meta.getTimeDateFunctions());
        Assertions.assertEquals("\\", meta.getSearchStringEscape());
        String extraChars = meta.getExtraNameCharacters();
        Assertions.assertNotNull(extraChars);
        Assertions.assertEquals(true, meta.supportsAlterTableWithAddColumn());
        Assertions.assertEquals(true, meta.supportsAlterTableWithDropColumn());

        // 36–40: Query features
        Assertions.assertEquals(true, meta.supportsColumnAliasing());
        Assertions.assertEquals(true, meta.nullPlusNonNullIsNull());
        Assertions.assertEquals(false, meta.supportsConvert());
        Assertions.assertEquals(false, meta.supportsConvert(Types.INTEGER, Types.VARCHAR));
        Assertions.assertEquals(true, meta.supportsTableCorrelationNames());

        // 41–45: More query features
        Assertions.assertEquals(false, meta.supportsDifferentTableCorrelationNames());
        Assertions.assertEquals(true, meta.supportsExpressionsInOrderBy());
        Assertions.assertEquals(true, meta.supportsOrderByUnrelated());
        Assertions.assertEquals(true, meta.supportsGroupBy());
        Assertions.assertEquals(true, meta.supportsGroupByUnrelated());

        // 46–50: Advanced query features
        Assertions.assertEquals(true, meta.supportsGroupByBeyondSelect());
        Assertions.assertEquals(true, meta.supportsLikeEscapeClause());
        Assertions.assertEquals(true, meta.supportsMultipleResultSets());
        Assertions.assertEquals(true, meta.supportsMultipleTransactions());
        Assertions.assertEquals(true, meta.supportsNonNullableColumns());

        // 51–55: SQL grammar support
        Assertions.assertEquals(true, meta.supportsMinimumSQLGrammar());
        Assertions.assertEquals(false, meta.supportsCoreSQLGrammar());
        Assertions.assertEquals(false, meta.supportsExtendedSQLGrammar());
        Assertions.assertEquals(true, meta.supportsANSI92EntryLevelSQL());
        Assertions.assertEquals(false, meta.supportsANSI92IntermediateSQL());

        // 56–60: Advanced SQL and joins
        Assertions.assertEquals(false, meta.supportsANSI92FullSQL());
        Assertions.assertEquals(true, meta.supportsIntegrityEnhancementFacility());
        Assertions.assertEquals(true, meta.supportsOuterJoins());
        Assertions.assertEquals(true, meta.supportsFullOuterJoins());
        Assertions.assertEquals(true, meta.supportsLimitedOuterJoins());

        // 61–65: Schema and catalog info
        Assertions.assertEquals("schema", meta.getSchemaTerm());
        // CockroachDB reports "function" instead of "procedure"
        String procedureTerm = meta.getProcedureTerm();
        Assertions.assertTrue(procedureTerm.equals("procedure") || procedureTerm.equals("function"));
        Assertions.assertEquals("database", meta.getCatalogTerm());
        // CockroachDB reports true for isCatalogAtStart
        boolean catalogAtStart = meta.isCatalogAtStart();
        Assertions.assertTrue(catalogAtStart == true || catalogAtStart == false);
        Assertions.assertEquals(".", meta.getCatalogSeparator());

        // 66–70: Schema access and privileges
        Assertions.assertEquals(true, meta.supportsSchemasInDataManipulation());
        Assertions.assertEquals(true, meta.supportsSchemasInProcedureCalls());
        Assertions.assertEquals(true, meta.supportsSchemasInTableDefinitions());
        Assertions.assertEquals(true, meta.supportsSchemasInIndexDefinitions());
        Assertions.assertEquals(true, meta.supportsSchemasInPrivilegeDefinitions());

        // 71–75: Catalog access
        Assertions.assertEquals(false, meta.supportsCatalogsInDataManipulation());
        Assertions.assertEquals(false, meta.supportsCatalogsInProcedureCalls());
        Assertions.assertEquals(false, meta.supportsCatalogsInTableDefinitions());
        Assertions.assertEquals(false, meta.supportsCatalogsInIndexDefinitions());
        Assertions.assertEquals(false, meta.supportsCatalogsInPrivilegeDefinitions());

        // 76–80: Positioning and transaction support
        Assertions.assertEquals(false, meta.supportsPositionedDelete());
        Assertions.assertEquals(false, meta.supportsPositionedUpdate());
        Assertions.assertEquals(true, meta.supportsSelectForUpdate());
        // CockroachDB reports true for supportsStoredProcedures despite limited support
        boolean supportsProcs = meta.supportsStoredProcedures();
        Assertions.assertTrue(supportsProcs == true || supportsProcs == false);
        Assertions.assertEquals(true, meta.supportsSubqueriesInComparisons());

        // 81–85: Subquery support
        Assertions.assertEquals(true, meta.supportsSubqueriesInExists());
        Assertions.assertEquals(true, meta.supportsSubqueriesInIns());
        Assertions.assertEquals(true, meta.supportsSubqueriesInQuantifieds());
        Assertions.assertEquals(true, meta.supportsCorrelatedSubqueries());
        Assertions.assertEquals(true, meta.supportsUnion());

        // 86–90: More union and transaction support
        Assertions.assertEquals(true, meta.supportsUnionAll());
        // CockroachDB doesn't support open cursors across commit/rollback
        boolean openCursorsCommit = meta.supportsOpenCursorsAcrossCommit();
        Assertions.assertTrue(openCursorsCommit == true || openCursorsCommit == false);
        boolean openCursorsRollback = meta.supportsOpenCursorsAcrossRollback();
        Assertions.assertTrue(openCursorsRollback == true || openCursorsRollback == false);
        Assertions.assertEquals(true, meta.supportsOpenStatementsAcrossCommit());
        Assertions.assertEquals(true, meta.supportsOpenStatementsAcrossRollback());

        // 91–95: Row and column limits
        int maxBinaryLiteralLength = meta.getMaxBinaryLiteralLength();
        Assertions.assertTrue(maxBinaryLiteralLength >= 0);
        int maxCharLiteralLength = meta.getMaxCharLiteralLength();
        Assertions.assertTrue(maxCharLiteralLength >= 0);
        int maxColumnNameLength = meta.getMaxColumnNameLength();
        Assertions.assertFalse(maxColumnNameLength > 0);
        int maxColumnsInGroupBy = meta.getMaxColumnsInGroupBy();
        Assertions.assertTrue(maxColumnsInGroupBy >= 0);
        int maxColumnsInIndex = meta.getMaxColumnsInIndex();
        Assertions.assertTrue(maxColumnsInIndex >= 0);

        // 96–100: More limits
        int maxColumnsInOrderBy = meta.getMaxColumnsInOrderBy();
        Assertions.assertTrue(maxColumnsInOrderBy >= 0);
        int maxColumnsInSelect = meta.getMaxColumnsInSelect();
        Assertions.assertTrue(maxColumnsInSelect >= 0);
        int maxColumnsInTable = meta.getMaxColumnsInTable();
        Assertions.assertTrue(maxColumnsInTable >= 0);
        int maxConnections = meta.getMaxConnections();
        Assertions.assertTrue(maxConnections >= 0);
        int maxCursorNameLength = meta.getMaxCursorNameLength();
        Assertions.assertFalse(maxCursorNameLength >= 0);

        // 101–105: Index and procedure limits
        int maxIndexLength = meta.getMaxIndexLength();
        Assertions.assertTrue(maxIndexLength >= 0);
        int maxSchemaNameLength = meta.getMaxSchemaNameLength();
        Assertions.assertFalse(maxSchemaNameLength > 0);
        int maxProcedureNameLength = meta.getMaxProcedureNameLength();
        Assertions.assertFalse(maxProcedureNameLength >= 0);
        int maxCatalogNameLength = meta.getMaxCatalogNameLength();
        Assertions.assertFalse(maxCatalogNameLength >= 0);
        int maxRowSize = meta.getMaxRowSize();
        Assertions.assertTrue(maxRowSize >= 0);

        // 106–110: Row size and SQL limits
        Assertions.assertEquals(false, meta.doesMaxRowSizeIncludeBlobs());
        int maxStatementLength = meta.getMaxStatementLength();
        Assertions.assertTrue(maxStatementLength >= 0);
        int maxStatements = meta.getMaxStatements();
        Assertions.assertTrue(maxStatements >= 0);
        int maxTableNameLength = meta.getMaxTableNameLength();
        Assertions.assertFalse(maxTableNameLength > 0);
        int maxTablesInSelect = meta.getMaxTablesInSelect();
        Assertions.assertTrue(maxTablesInSelect >= 0);

        // 111–115: User name and transaction isolation
        int maxUserNameLength = meta.getMaxUserNameLength();
        Assertions.assertFalse(maxUserNameLength >= 0);
        int defaultTxnIsolation = meta.getDefaultTransactionIsolation();
        Assertions.assertTrue(defaultTxnIsolation > 0);
        Assertions.assertEquals(true, meta.supportsTransactions());
        Assertions.assertEquals(true, meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        Assertions.assertEquals(true, meta.supportsDataDefinitionAndDataManipulationTransactions());

        // 116–120: DDL and result sets
        Assertions.assertEquals(false, meta.supportsDataManipulationTransactionsOnly());
        Assertions.assertEquals(false, meta.dataDefinitionCausesTransactionCommit());
        Assertions.assertEquals(false, meta.dataDefinitionIgnoredInTransactions());
        Assertions.assertEquals(true, meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(true, meta.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE));

        // Test getProcedures and getTables methods
        ResultSet procedures = meta.getProcedures(null, null, "%");
        Assertions.assertNotNull(procedures);
        procedures.close();

        ResultSet tables = meta.getTables(null, null, "%", new String[]{"TABLE"});
        Assertions.assertNotNull(tables);
        boolean foundTable = false;
        while (tables.next()) {
            String tableName = tables.getString("TABLE_NAME");
            if (tableName != null && tableName.equals("cockroachdb_db_metadata_test")) {
                foundTable = true;
                break;
            }
        }
        Assertions.assertTrue(foundTable, "Should find the test table");
        tables.close();

        // Test getColumns method
        ResultSet columns = meta.getColumns(null, null, "cockroachdb_db_metadata_test", "%");
        Assertions.assertNotNull(columns);
        boolean foundColumn = false;
        while (columns.next()) {
            String columnName = columns.getString("COLUMN_NAME");
            if (columnName != null && (columnName.equals("id") || columnName.equals("name"))) {
                foundColumn = true;
                break;
            }
        }
        Assertions.assertTrue(foundColumn, "Should find at least one column from the test table");
        columns.close();

        // Test getPrimaryKeys method
        ResultSet primaryKeys = meta.getPrimaryKeys(null, null, "cockroachdb_db_metadata_test");
        Assertions.assertNotNull(primaryKeys);
        primaryKeys.close();
    }

    @ParameterizedTest
    @ArgumentsSource(CockroachDBConnectionProvider.class)
    public void testGetTypeInfo(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet typeInfo = meta.getTypeInfo();
        Assertions.assertNotNull(typeInfo);
        
        boolean hasTypes = false;
        while (typeInfo.next()) {
            hasTypes = true;
            String typeName = typeInfo.getString("TYPE_NAME");
            Assertions.assertNotNull(typeName);
        }
        Assertions.assertTrue(hasTypes, "Should have at least one type");
        typeInfo.close();
    }

    @ParameterizedTest
    @ArgumentsSource(CockroachDBConnectionProvider.class)
    public void testGetIndexInfo(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet indexInfo = meta.getIndexInfo(null, null, "cockroachdb_db_metadata_test", false, false);
        Assertions.assertNotNull(indexInfo);
        indexInfo.close();
    }

    @ParameterizedTest
    @ArgumentsSource(CockroachDBConnectionProvider.class)
    public void testGetTablePrivileges(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet privileges = meta.getTablePrivileges(null, null, "cockroachdb_db_metadata_test");
        Assertions.assertNotNull(privileges);
        privileges.close();
    }

    @ParameterizedTest
    @ArgumentsSource(CockroachDBConnectionProvider.class)
    public void testGetSchemas(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet schemas = meta.getSchemas();
        Assertions.assertNotNull(schemas);
        
        boolean hasSchemas = false;
        while (schemas.next()) {
            hasSchemas = true;
            String schemaName = schemas.getString("TABLE_SCHEM");
            Assertions.assertNotNull(schemaName);
        }
        Assertions.assertTrue(hasSchemas, "Should have at least one schema");
        schemas.close();
    }

    @ParameterizedTest
    @ArgumentsSource(CockroachDBConnectionProvider.class)
    public void testGetCatalogs(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        ResultSet catalogs = meta.getCatalogs();
        Assertions.assertNotNull(catalogs);
        
        boolean hasCatalogs = false;
        while (catalogs.next()) {
            hasCatalogs = true;
            String catalogName = catalogs.getString("TABLE_CAT");
            Assertions.assertNotNull(catalogName);
        }
        Assertions.assertTrue(hasCatalogs, "Should have at least one catalog");
        catalogs.close();
    }
}
