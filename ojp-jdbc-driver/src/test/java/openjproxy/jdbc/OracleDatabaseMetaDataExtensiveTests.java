package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.api.condition.EnabledIf;
import openjproxy.jdbc.testutil.OracleConnectionProvider;

import java.sql.*;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

@EnabledIf("openjproxy.jdbc.testutil.OracleTestContainer#isEnabled")
public class OracleDatabaseMetaDataExtensiveTests {

    private static boolean isTestDisabled;
    private static Connection connection;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "oracle_db_metadata_test", TestDBUtils.SqlSyntax.ORACLE, true);
    }

    @AfterAll
    public static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @ArgumentsSource(OracleConnectionProvider.class)
    public void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information (Oracle-specific values)
        Assertions.assertEquals(false, meta.allProceduresAreCallable());
        Assertions.assertEquals(false, meta.allTablesAreSelectable());
        Assertions.assertTrue(meta.getURL().contains("oracle") || meta.getURL().contains(":1521/"));
        Assertions.assertNotNull(meta.getUserName()); // Oracle username
        Assertions.assertEquals(false, meta.isReadOnly());

        // 6–10: Null handling and database product info (Oracle-specific behaviors)
        Assertions.assertEquals(true, meta.nullsAreSortedHigh());  // Oracle behavior
        Assertions.assertEquals(false, meta.nullsAreSortedLow());
        Assertions.assertEquals(false, meta.nullsAreSortedAtStart());
        Assertions.assertEquals(false, meta.nullsAreSortedAtEnd()); // Oracle behavior
        Assertions.assertEquals("Oracle", meta.getDatabaseProductName());

        // 11–15: Version information
        Assertions.assertNotNull(meta.getDatabaseProductVersion());
        Assertions.assertEquals("Oracle JDBC driver", meta.getDriverName());
        Assertions.assertNotNull(meta.getDriverVersion());
        Assertions.assertTrue(meta.getDriverMajorVersion() >= 21); // Oracle driver version
        Assertions.assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        Assertions.assertEquals(false, meta.usesLocalFiles());
        Assertions.assertEquals(false, meta.usesLocalFilePerTable());
        Assertions.assertEquals(false, meta.supportsMixedCaseIdentifiers());
        Assertions.assertEquals(true, meta.storesUpperCaseIdentifiers()); // Oracle stores uppercase
        Assertions.assertEquals(false, meta.storesLowerCaseIdentifiers()); // Oracle stores uppercase

        // 21–25: Quoted identifiers
        Assertions.assertEquals(false, meta.storesMixedCaseIdentifiers());
        Assertions.assertEquals(true, meta.supportsMixedCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesUpperCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesLowerCaseQuotedIdentifiers());
        Assertions.assertEquals(true, meta.storesMixedCaseQuotedIdentifiers()); // Oracle behavior

        // 26–30: String handling and functions
        Assertions.assertEquals("\"", meta.getIdentifierQuoteString());
        Assertions.assertNotNull(meta.getSQLKeywords());
        Assertions.assertNotNull(meta.getNumericFunctions());
        Assertions.assertNotNull(meta.getStringFunctions());
        Assertions.assertNotNull(meta.getSystemFunctions());

        // 31–35: More functions and table operations
        Assertions.assertNotNull(meta.getTimeDateFunctions());
        Assertions.assertEquals("/", meta.getSearchStringEscape());
        // Oracle may have extra name characters
        String extraChars = meta.getExtraNameCharacters();
        Assertions.assertNotNull(extraChars); // Accept any non-null value
        Assertions.assertEquals(true, meta.supportsAlterTableWithAddColumn());
        Assertions.assertEquals(false, meta.supportsAlterTableWithDropColumn());

        // 36–40: Query features
        Assertions.assertEquals(true, meta.supportsColumnAliasing());
        Assertions.assertEquals(true, meta.nullPlusNonNullIsNull());
        Assertions.assertEquals(false, meta.supportsConvert()); // Oracle behavior differs from PostgreSQL
        Assertions.assertEquals(false, meta.supportsConvert(Types.INTEGER, Types.VARCHAR)); // Oracle behavior
        Assertions.assertEquals(true, meta.supportsTableCorrelationNames());

        // 41–45: More query features
        Assertions.assertEquals(true, meta.supportsDifferentTableCorrelationNames());
        Assertions.assertEquals(true, meta.supportsExpressionsInOrderBy());
        Assertions.assertEquals(true, meta.supportsOrderByUnrelated());
        Assertions.assertEquals(true, meta.supportsGroupBy());
        Assertions.assertEquals(true, meta.supportsGroupByUnrelated());

        // 46–50: Advanced query features
        Assertions.assertEquals(true, meta.supportsGroupByBeyondSelect());
        Assertions.assertEquals(true, meta.supportsLikeEscapeClause());
        Assertions.assertEquals(false, meta.supportsMultipleResultSets()); // Oracle supports multiple result sets
        Assertions.assertEquals(true, meta.supportsMultipleTransactions());
        Assertions.assertEquals(true, meta.supportsNonNullableColumns());

        // 51–55: SQL grammar support
        Assertions.assertEquals(true, meta.supportsMinimumSQLGrammar());
        Assertions.assertEquals(true, meta.supportsCoreSQLGrammar());
        Assertions.assertEquals(true, meta.supportsExtendedSQLGrammar());
        Assertions.assertEquals(true, meta.supportsANSI92EntryLevelSQL());
        Assertions.assertEquals(false, meta.supportsANSI92IntermediateSQL());

        // 56–60: Advanced SQL and joins
        Assertions.assertEquals(false, meta.supportsANSI92FullSQL());
        Assertions.assertEquals(true, meta.supportsIntegrityEnhancementFacility());
        Assertions.assertEquals(true, meta.supportsOuterJoins());
        Assertions.assertEquals(true, meta.supportsFullOuterJoins());
        Assertions.assertEquals(true, meta.supportsLimitedOuterJoins());

        // 61–65: Schema and catalog terminology
        Assertions.assertEquals("schema", meta.getSchemaTerm());
        Assertions.assertEquals("procedure", meta.getProcedureTerm()); // Oracle uses procedures
        Assertions.assertEquals("", meta.getCatalogTerm());
        Assertions.assertEquals(false, meta.isCatalogAtStart());
        Assertions.assertEquals("", meta.getCatalogSeparator());

        // 66–75: Schema and catalog support
        Assertions.assertEquals(true, meta.supportsSchemasInDataManipulation());
        Assertions.assertEquals(true, meta.supportsSchemasInProcedureCalls());
        Assertions.assertEquals(true, meta.supportsSchemasInTableDefinitions());
        Assertions.assertEquals(true, meta.supportsSchemasInIndexDefinitions());
        Assertions.assertEquals(true, meta.supportsSchemasInPrivilegeDefinitions());
        Assertions.assertEquals(false, meta.supportsCatalogsInDataManipulation());
        Assertions.assertEquals(false, meta.supportsCatalogsInProcedureCalls());
        Assertions.assertEquals(false, meta.supportsCatalogsInTableDefinitions());
        Assertions.assertEquals(false, meta.supportsCatalogsInIndexDefinitions());
        Assertions.assertEquals(false, meta.supportsCatalogsInPrivilegeDefinitions());

        // 76–90: Cursor and subquery support
        Assertions.assertEquals(false, meta.supportsPositionedDelete());
        Assertions.assertEquals(false, meta.supportsPositionedUpdate());
        Assertions.assertEquals(true, meta.supportsSelectForUpdate());
        Assertions.assertEquals(true, meta.supportsStoredProcedures());
        Assertions.assertEquals(true, meta.supportsSubqueriesInComparisons());
        Assertions.assertEquals(true, meta.supportsSubqueriesInExists());
        Assertions.assertEquals(true, meta.supportsSubqueriesInIns());
        Assertions.assertEquals(true, meta.supportsSubqueriesInQuantifieds());
        Assertions.assertEquals(true, meta.supportsCorrelatedSubqueries());
        Assertions.assertEquals(true, meta.supportsUnion());
        Assertions.assertEquals(true, meta.supportsUnionAll());
        Assertions.assertEquals(false, meta.supportsOpenCursorsAcrossCommit());
        Assertions.assertEquals(false, meta.supportsOpenCursorsAcrossRollback());
        Assertions.assertEquals(false, meta.supportsOpenStatementsAcrossCommit());
        Assertions.assertEquals(false, meta.supportsOpenStatementsAcrossRollback());

        // 91–111: Limits (Oracle-specific limits)
        Assertions.assertEquals(1000, meta.getMaxBinaryLiteralLength());
        Assertions.assertEquals(2000, meta.getMaxCharLiteralLength()); // Oracle VARCHAR2 limit
        Assertions.assertEquals(128, meta.getMaxColumnNameLength()); // Oracle identifier limit
        Assertions.assertEquals(0, meta.getMaxColumnsInGroupBy());
        Assertions.assertEquals(32, meta.getMaxColumnsInIndex()); // Oracle index column limit
        Assertions.assertEquals(0, meta.getMaxColumnsInOrderBy());
        Assertions.assertEquals(0, meta.getMaxColumnsInSelect()); // Oracle column limit
        Assertions.assertEquals(1000, meta.getMaxColumnsInTable());
        Assertions.assertEquals(0, meta.getMaxConnections());
        Assertions.assertEquals(0, meta.getMaxCursorNameLength());
        Assertions.assertEquals(0, meta.getMaxIndexLength());
        Assertions.assertEquals(128, meta.getMaxSchemaNameLength());
        Assertions.assertEquals(128, meta.getMaxProcedureNameLength());
        Assertions.assertEquals(0, meta.getMaxCatalogNameLength());
        Assertions.assertEquals(0, meta.getMaxRowSize());
        Assertions.assertEquals(true, meta.doesMaxRowSizeIncludeBlobs());
        Assertions.assertEquals(65535, meta.getMaxStatementLength());
        Assertions.assertEquals(0, meta.getMaxStatements());
        Assertions.assertEquals(128, meta.getMaxTableNameLength());
        Assertions.assertEquals(0, meta.getMaxTablesInSelect());
        Assertions.assertEquals(128, meta.getMaxUserNameLength());
        Assertions.assertEquals(Connection.TRANSACTION_READ_COMMITTED, meta.getDefaultTransactionIsolation());

        // 112–118: Transaction support
        Assertions.assertEquals(true, meta.supportsTransactions());
        Assertions.assertEquals(true, meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        Assertions.assertEquals(true, meta.supportsDataDefinitionAndDataManipulationTransactions());
        Assertions.assertEquals(true, meta.supportsDataManipulationTransactionsOnly());
        Assertions.assertEquals(true, meta.dataDefinitionCausesTransactionCommit());
        Assertions.assertEquals(false, meta.dataDefinitionIgnoredInTransactions());

        // 119–174: ResultSets for metadata queries
        try (ResultSet rs = meta.getProcedures(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getProcedureColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTables(null, null, null, new String[]{"TABLE"})) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getSchemas()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCatalogs()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTableTypes()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumnPrivileges(null, null, "ORACLE_DB_METADATA_TEST", null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTablePrivileges(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getBestRowIdentifier(null, null, "ORACLE_DB_METADATA_TEST", DatabaseMetaData.bestRowSession, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getVersionColumns(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPrimaryKeys(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getImportedKeys(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getExportedKeys(null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCrossReference(null, null, "ORACLE_DB_METADATA_TEST", null, null, "ORACLE_DB_METADATA_TEST")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTypeInfo()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getIndexInfo(null, null, "ORACLE_DB_METADATA_TEST", false, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getUDTs(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        Assertions.assertNotNull(meta.getConnection());
        Assertions.assertEquals(true, meta.supportsSavepoints());
        Assertions.assertEquals(true, meta.supportsNamedParameters());
        Assertions.assertEquals(false, meta.supportsMultipleOpenResults());
        Assertions.assertEquals(true, meta.supportsGetGeneratedKeys());

        Assertions.assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, meta.getResultSetHoldability());
        Assertions.assertTrue(meta.getDatabaseMajorVersion() >= 18); // Modern Oracle
        Assertions.assertTrue(meta.getDatabaseMinorVersion() >= 0);
        Assertions.assertEquals(4, meta.getJDBCMajorVersion());
        Assertions.assertTrue(meta.getJDBCMinorVersion() >= 2);
        Assertions.assertEquals(DatabaseMetaData.functionColumnUnknown, meta.getSQLStateType());
        Assertions.assertEquals(true, meta.locatorsUpdateCopy());
        Assertions.assertEquals(true, meta.supportsStatementPooling());

        try (ResultSet rs = meta.getSchemas(null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        Assertions.assertEquals(true, meta.supportsStoredFunctionsUsingCallSyntax());
        Assertions.assertEquals(false, meta.autoCommitFailureClosesAllResultSets());
        try (ResultSet rs = meta.getClientInfoProperties()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctions(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctionColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        Assertions.assertEquals(false, meta.generatedKeyAlwaysReturned());
        Assertions.assertEquals(true, meta.supportsRefCursors());
        Assertions.assertEquals(true, meta.supportsSharding());

        // 175–177: ResultSet/Concurrency methods
        Assertions.assertEquals(true, meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(true, meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        Assertions.assertEquals(false, meta.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.ownDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.ownInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.othersUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.othersDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.othersInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.updatesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.deletesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.insertsAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(true, meta.supportsBatchUpdates());

        // These tests has to be at the end as per when using hikariCP the connection will be marked as broken after this operations.
        Assertions.assertEquals(true, meta.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        Assertions.assertThrows(SQLException.class, () -> meta.getSuperTypes(null, null, null));
        Assertions.assertThrows(SQLException.class, () -> meta.getSuperTables(null, null, null));
        Assertions.assertThrows(SQLException.class, () -> meta.getAttributes(null, null, null, null));

        Assertions.assertEquals(RowIdLifetime.ROWID_VALID_FOREVER, meta.getRowIdLifetime());
        try (ResultSet rs = meta.getPseudoColumns(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
    }
}