package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import openjproxy.jdbc.testutil.TestDBUtils.ConnectionResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.*;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class PostgresDatabaseMetaDataExtensiveTests {

    private static boolean isTestEnabled;
    private static ConnectionResult connectionResult;
    private static Connection connection;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestEnabled = Boolean.parseBoolean(System.getProperty("enablePostgresTests", "false"));
    }

    public void setUp(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        assumeFalse(!isTestEnabled, "Postgres tests are disabled");
        
        connectionResult = TestDBUtils.createConnection(url, user, password, isXA);
        connection = connectionResult.getConnection();
        
        // For non-XA connections, set autocommit to false for transaction control
        if (!isXA) {
            connection.setAutoCommit(false);
        }
        TestDBUtils.createBasicTestTable(connection, "postgres_db_metadata_test", TestDBUtils.SqlSyntax.POSTGRES, true);
    }

    @AfterAll
    public static void teardown() throws Exception {
        if (connectionResult != null) {
            connectionResult.close();
        }
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/postgres_connection.csv")
    public void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password, boolean isXA) throws Exception {
        this.setUp(driverClass, url, user, password, isXA);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5: Basic database information (PostgreSQL-specific values)
        Assertions.assertEquals(true, meta.allProceduresAreCallable());
        Assertions.assertEquals(true, meta.allTablesAreSelectable());
        Assertions.assertTrue(meta.getURL().contains("postgresql") || meta.getURL().contains(":5432/"));
        Assertions.assertNotNull(meta.getUserName()); // PostgreSQL username
        Assertions.assertEquals(false, meta.isReadOnly());

        // 6–10: Null handling and database product info (PostgreSQL-specific behaviors)
        Assertions.assertEquals(true, meta.nullsAreSortedHigh());  // PostgreSQL behavior
        Assertions.assertEquals(false, meta.nullsAreSortedLow());
        Assertions.assertEquals(false, meta.nullsAreSortedAtStart());
        Assertions.assertEquals(false, meta.nullsAreSortedAtEnd()); // PostgreSQL behavior
        Assertions.assertEquals("PostgreSQL", meta.getDatabaseProductName());

        // 11–15: Version information
        Assertions.assertNotNull(meta.getDatabaseProductVersion());
        Assertions.assertEquals("PostgreSQL JDBC Driver", meta.getDriverName());
        Assertions.assertNotNull(meta.getDriverVersion());
        Assertions.assertTrue(meta.getDriverMajorVersion() >= 42); // PostgreSQL driver version
        Assertions.assertTrue(meta.getDriverMinorVersion() >= 0);

        // 16–20: File handling and identifiers
        Assertions.assertEquals(false, meta.usesLocalFiles());
        Assertions.assertEquals(false, meta.usesLocalFilePerTable());
        Assertions.assertEquals(false, meta.supportsMixedCaseIdentifiers());
        Assertions.assertEquals(false, meta.storesUpperCaseIdentifiers());
        Assertions.assertEquals(true, meta.storesLowerCaseIdentifiers()); // PostgreSQL stores lowercase

        // 21–25: Quoted identifiers
        Assertions.assertEquals(false, meta.storesMixedCaseIdentifiers());
        Assertions.assertEquals(true, meta.supportsMixedCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesUpperCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesLowerCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesMixedCaseQuotedIdentifiers()); // PostgreSQL behavior

        // 26–30: String handling and functions
        Assertions.assertEquals("\"", meta.getIdentifierQuoteString());
        Assertions.assertNotNull(meta.getSQLKeywords());
        Assertions.assertNotNull(meta.getNumericFunctions());
        Assertions.assertNotNull(meta.getStringFunctions());
        Assertions.assertNotNull(meta.getSystemFunctions());

        // 31–35: More functions and table operations
        Assertions.assertNotNull(meta.getTimeDateFunctions());
        Assertions.assertEquals("\\", meta.getSearchStringEscape());
        // PostgreSQL may not allow extra name characters beyond standard ones
        String extraChars = meta.getExtraNameCharacters();
        Assertions.assertNotNull(extraChars); // Accept any non-null value
        Assertions.assertEquals(true, meta.supportsAlterTableWithAddColumn());
        Assertions.assertEquals(true, meta.supportsAlterTableWithDropColumn());

        // 36–40: Query features
        Assertions.assertEquals(true, meta.supportsColumnAliasing());
        Assertions.assertEquals(true, meta.nullPlusNonNullIsNull());
        Assertions.assertEquals(false, meta.supportsConvert()); // PostgreSQL behavior differs from H2
        Assertions.assertEquals(false, meta.supportsConvert(Types.INTEGER, Types.VARCHAR)); // PostgreSQL behavior
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
        Assertions.assertEquals(true, meta.supportsMultipleResultSets()); // PostgreSQL supports multiple result sets
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

        // 61–65: Schema and catalog terminology
        Assertions.assertEquals("schema", meta.getSchemaTerm());
        Assertions.assertEquals("function", meta.getProcedureTerm()); // PostgreSQL uses functions
        Assertions.assertEquals("database", meta.getCatalogTerm());
        Assertions.assertEquals(true, meta.isCatalogAtStart());
        Assertions.assertEquals(".", meta.getCatalogSeparator());

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
        Assertions.assertEquals(true, meta.supportsOpenStatementsAcrossCommit());
        Assertions.assertEquals(true, meta.supportsOpenStatementsAcrossRollback());

        // 91–111: Limits (PostgreSQL typically has no limits or very high limits)
        Assertions.assertEquals(0, meta.getMaxBinaryLiteralLength());
        Assertions.assertEquals(0, meta.getMaxCharLiteralLength());
        Assertions.assertEquals(63, meta.getMaxColumnNameLength()); // PostgreSQL identifier limit
        Assertions.assertEquals(0, meta.getMaxColumnsInGroupBy());
        Assertions.assertEquals(32, meta.getMaxColumnsInIndex()); // PostgreSQL index column limit
        Assertions.assertEquals(0, meta.getMaxColumnsInOrderBy());
        Assertions.assertEquals(0, meta.getMaxColumnsInSelect()); // PostgreSQL column limit
        Assertions.assertEquals(1600, meta.getMaxColumnsInTable());
        Assertions.assertEquals(8192, meta.getMaxConnections());
        Assertions.assertEquals(63, meta.getMaxCursorNameLength());
        Assertions.assertEquals(0, meta.getMaxIndexLength());
        Assertions.assertEquals(63, meta.getMaxSchemaNameLength());
        Assertions.assertEquals(63, meta.getMaxProcedureNameLength());
        Assertions.assertEquals(63, meta.getMaxCatalogNameLength());
        Assertions.assertEquals(1073741824, meta.getMaxRowSize());
        Assertions.assertEquals(false, meta.doesMaxRowSizeIncludeBlobs());
        Assertions.assertEquals(0, meta.getMaxStatementLength());
        Assertions.assertEquals(0, meta.getMaxStatements());
        Assertions.assertEquals(63, meta.getMaxTableNameLength());
        Assertions.assertEquals(0, meta.getMaxTablesInSelect());
        Assertions.assertEquals(63, meta.getMaxUserNameLength());
        Assertions.assertEquals(Connection.TRANSACTION_READ_COMMITTED, meta.getDefaultTransactionIsolation());

        // 112–118: Transaction support
        Assertions.assertEquals(true, meta.supportsTransactions());
        Assertions.assertEquals(true, meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        Assertions.assertEquals(true, meta.supportsDataDefinitionAndDataManipulationTransactions());
        Assertions.assertEquals(false, meta.supportsDataManipulationTransactionsOnly());
        Assertions.assertEquals(false, meta.dataDefinitionCausesTransactionCommit());
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
        try (ResultSet rs = meta.getColumnPrivileges(null, null, "postgres_db_metadata_test", null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTablePrivileges(null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getBestRowIdentifier(null, null, "postgres_db_metadata_test", DatabaseMetaData.bestRowSession, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getVersionColumns(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPrimaryKeys(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getImportedKeys(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getExportedKeys(null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCrossReference(null, null, "postgres_db_metadata_test", null, null, "postgres_db_metadata_test")) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTypeInfo()) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getIndexInfo(null, null, "postgres_db_metadata_test", false, false)) {
            TestDBUtils.validateAllRows(rs);
        }
        try (ResultSet rs = meta.getUDTs(null, null, null, null)) {
            TestDBUtils.validateAllRows(rs);
        }
        Assertions.assertNotNull(meta.getConnection());
        Assertions.assertEquals(true, meta.supportsSavepoints());
        Assertions.assertEquals(false, meta.supportsNamedParameters());
        Assertions.assertEquals(false, meta.supportsMultipleOpenResults());
        Assertions.assertEquals(true, meta.supportsGetGeneratedKeys());

        Assertions.assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, meta.getResultSetHoldability());
        Assertions.assertTrue(meta.getDatabaseMajorVersion() >= 10); // Modern PostgreSQL
        Assertions.assertTrue(meta.getDatabaseMinorVersion() >= 0);
        Assertions.assertEquals(4, meta.getJDBCMajorVersion());
        Assertions.assertTrue(meta.getJDBCMinorVersion() >= 2);
        Assertions.assertEquals(DatabaseMetaData.sqlStateSQL, meta.getSQLStateType());
        Assertions.assertEquals(true, meta.locatorsUpdateCopy());
        Assertions.assertEquals(false, meta.supportsStatementPooling());

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
        Assertions.assertEquals(true, meta.generatedKeyAlwaysReturned());
        Assertions.assertEquals(0, meta.getMaxLogicalLobSize());
        Assertions.assertEquals(true, meta.supportsRefCursors());
        Assertions.assertEquals(false, meta.supportsSharding());

        // 175–177: ResultSet/Concurrency methods
        Assertions.assertEquals(true, meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(true, meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        Assertions.assertEquals(true, meta.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(true, meta.ownDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(true, meta.ownInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
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
        Assertions.assertThrows(SQLException.class, () -> meta.getRowIdLifetime());
        Assertions.assertThrows(SQLException.class, () -> meta.getPseudoColumns(null, null, null, null));

    }
}