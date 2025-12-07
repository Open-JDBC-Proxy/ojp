package openjproxy.jdbc;

import openjproxy.jdbc.testutil.TestDBUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.*;


public class H2DatabaseMetaDataExtensiveTests {

    private static boolean isH2TestEnabled;
    private static Connection connection;

    @BeforeAll
    public static void checkH2TestsEnabled() {
        isH2TestEnabled = Boolean.parseBoolean(System.getProperty("enableH2Tests", "true"));
        Assumptions.assumeTrue(isH2TestEnabled, "H2 tests are not enabled");
    }

    public void setUp(String driverClass, String url, String user, String password) throws Exception {
        connection = DriverManager.getConnection(url, user, password);
        TestDBUtils.createBasicTestTable(connection, "h2_db_metadata_test", TestDBUtils.SqlSyntax.H2, true);
    }

    @AfterAll
    public static void teardown() throws Exception {
        TestDBUtils.closeQuietly(connection);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/h2_connection.csv")
    public void allDatabaseMetaDataMethodsShouldWorkAndBeAsserted(String driverClass, String url, String user, String password) throws Exception {
        this.setUp(driverClass, url, user, password);
        DatabaseMetaData meta = connection.getMetaData();

        // 1–5
        Assertions.assertEquals(true, meta.allProceduresAreCallable());
        Assertions.assertEquals(true, meta.allTablesAreSelectable());
        Assertions.assertEquals("jdbc:h2:~/test", meta.getURL());
        Assertions.assertEquals(user.toUpperCase(), meta.getUserName()); // random: H2 username can be "SA" or empty, set as needed
        Assertions.assertEquals(false, meta.isReadOnly());

        // 6–10
        Assertions.assertEquals(false, meta.nullsAreSortedHigh());      // random
        Assertions.assertEquals(true, meta.nullsAreSortedLow());        // random
        Assertions.assertEquals(false, meta.nullsAreSortedAtStart());   // random
        Assertions.assertEquals(false, meta.nullsAreSortedAtEnd());      // random
        Assertions.assertEquals("H2", meta.getDatabaseProductName());

        // 11–15
        Assertions.assertNotNull(meta.getDatabaseProductVersion()); // random: version string e.g. "2.1.214 (2022-07-29)"
        Assertions.assertEquals("H2 JDBC Driver", meta.getDriverName());
        Assertions.assertNotNull(meta.getDriverVersion()); // random: version string
        Assertions.assertEquals(2, meta.getDriverMajorVersion()); // random: check your H2 version
        Assertions.assertEquals(3, meta.getDriverMinorVersion()); // random

        // 16–20
        Assertions.assertEquals(true, meta.usesLocalFiles());
        Assertions.assertEquals(false, meta.usesLocalFilePerTable());
        Assertions.assertEquals(false, meta.supportsMixedCaseIdentifiers());
        Assertions.assertEquals(true, meta.storesUpperCaseIdentifiers());
        Assertions.assertEquals(false, meta.storesLowerCaseIdentifiers());

        // 21–25
        Assertions.assertEquals(false, meta.storesMixedCaseIdentifiers());
        Assertions.assertEquals(true, meta.supportsMixedCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesUpperCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesLowerCaseQuotedIdentifiers());
        Assertions.assertEquals(false, meta.storesMixedCaseQuotedIdentifiers());

        // 26–30
        Assertions.assertEquals("\"", meta.getIdentifierQuoteString());
        Assertions.assertNotNull(meta.getSQLKeywords()); // random: String like "LIMIT,MINUS,..." etc
        Assertions.assertNotNull(meta.getNumericFunctions()); // random: String containing function names
        Assertions.assertNotNull(meta.getStringFunctions()); // random
        Assertions.assertNotNull(meta.getSystemFunctions()); // random

        // 31–35
        Assertions.assertNotNull(meta.getTimeDateFunctions()); // random
        Assertions.assertEquals("\\", meta.getSearchStringEscape());
        Assertions.assertEquals("", meta.getExtraNameCharacters());
        Assertions.assertEquals(true, meta.supportsAlterTableWithAddColumn());
        Assertions.assertEquals(true, meta.supportsAlterTableWithDropColumn());

        // 36–40
        Assertions.assertEquals(true, meta.supportsColumnAliasing());
        Assertions.assertEquals(true, meta.nullPlusNonNullIsNull());
        Assertions.assertEquals(true, meta.supportsConvert());
        Assertions.assertEquals(true, meta.supportsConvert(Types.INTEGER, Types.VARCHAR));
        Assertions.assertEquals(true, meta.supportsTableCorrelationNames());

        // 41–45
        Assertions.assertEquals(false, meta.supportsDifferentTableCorrelationNames());
        Assertions.assertEquals(true, meta.supportsExpressionsInOrderBy());
        Assertions.assertEquals(true, meta.supportsOrderByUnrelated());
        Assertions.assertEquals(true, meta.supportsGroupBy());
        Assertions.assertEquals(true, meta.supportsGroupByUnrelated());

        // 46–50
        Assertions.assertEquals(true, meta.supportsGroupByBeyondSelect());
        Assertions.assertEquals(true, meta.supportsLikeEscapeClause());
        Assertions.assertEquals(false, meta.supportsMultipleResultSets());
        Assertions.assertEquals(true, meta.supportsMultipleTransactions());
        Assertions.assertEquals(true, meta.supportsNonNullableColumns());

        // 51–55
        Assertions.assertEquals(true, meta.supportsMinimumSQLGrammar());
        Assertions.assertEquals(true, meta.supportsCoreSQLGrammar());
        Assertions.assertEquals(false, meta.supportsExtendedSQLGrammar());
        Assertions.assertEquals(true, meta.supportsANSI92EntryLevelSQL());
        Assertions.assertEquals(false, meta.supportsANSI92IntermediateSQL());

        // 56–60
        Assertions.assertEquals(false, meta.supportsANSI92FullSQL());
        Assertions.assertEquals(true, meta.supportsIntegrityEnhancementFacility());
        Assertions.assertEquals(true, meta.supportsOuterJoins());
        Assertions.assertEquals(false, meta.supportsFullOuterJoins());
        Assertions.assertEquals(true, meta.supportsLimitedOuterJoins());

        // 61–65
        Assertions.assertEquals("schema", meta.getSchemaTerm());
        Assertions.assertEquals("procedure", meta.getProcedureTerm());
        Assertions.assertEquals("catalog", meta.getCatalogTerm());
        Assertions.assertEquals(true, meta.isCatalogAtStart());
        Assertions.assertEquals(".", meta.getCatalogSeparator());

        // 66–75
        Assertions.assertEquals(true, meta.supportsSchemasInDataManipulation());
        Assertions.assertEquals(true, meta.supportsSchemasInProcedureCalls());
        Assertions.assertEquals(true, meta.supportsSchemasInTableDefinitions());
        Assertions.assertEquals(true, meta.supportsSchemasInIndexDefinitions());
        Assertions.assertEquals(true, meta.supportsSchemasInPrivilegeDefinitions());
        Assertions.assertEquals(true, meta.supportsCatalogsInDataManipulation());
        Assertions.assertEquals(false, meta.supportsCatalogsInProcedureCalls());
        Assertions.assertEquals(true, meta.supportsCatalogsInTableDefinitions());
        Assertions.assertEquals(true, meta.supportsCatalogsInIndexDefinitions());
        Assertions.assertEquals(true, meta.supportsCatalogsInPrivilegeDefinitions());

        // 76–90
        Assertions.assertEquals(false, meta.supportsPositionedDelete());
        Assertions.assertEquals(false, meta.supportsPositionedUpdate());
        Assertions.assertEquals(true, meta.supportsSelectForUpdate());
        Assertions.assertEquals(false, meta.supportsStoredProcedures());
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

        // 91–111: Random numeric values (replace with actual as needed)
        Assertions.assertEquals(0, meta.getMaxBinaryLiteralLength());
        Assertions.assertEquals(0, meta.getMaxCharLiteralLength());
        Assertions.assertEquals(0, meta.getMaxColumnNameLength());
        Assertions.assertEquals(0, meta.getMaxColumnsInGroupBy());
        Assertions.assertEquals(0, meta.getMaxColumnsInIndex());
        Assertions.assertEquals(0, meta.getMaxColumnsInOrderBy());
        Assertions.assertEquals(0, meta.getMaxColumnsInSelect());
        Assertions.assertEquals(0, meta.getMaxColumnsInTable());
        Assertions.assertEquals(0, meta.getMaxConnections());
        Assertions.assertEquals(0, meta.getMaxCursorNameLength());
        Assertions.assertEquals(0, meta.getMaxIndexLength());
        Assertions.assertEquals(0, meta.getMaxSchemaNameLength());
        Assertions.assertEquals(0, meta.getMaxProcedureNameLength());
        Assertions.assertEquals(0, meta.getMaxCatalogNameLength());
        Assertions.assertEquals(0, meta.getMaxRowSize());
        Assertions.assertEquals(false, meta.doesMaxRowSizeIncludeBlobs());
        Assertions.assertEquals(0, meta.getMaxStatementLength());
        Assertions.assertEquals(0, meta.getMaxStatements());
        Assertions.assertEquals(0, meta.getMaxTableNameLength());
        Assertions.assertEquals(0, meta.getMaxTablesInSelect());
        Assertions.assertEquals(0, meta.getMaxUserNameLength());
        Assertions.assertEquals(Connection.TRANSACTION_READ_COMMITTED, meta.getDefaultTransactionIsolation());

        // 112–118
        Assertions.assertEquals(true, meta.supportsTransactions());
        Assertions.assertEquals(true, meta.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        Assertions.assertEquals(false, meta.supportsDataDefinitionAndDataManipulationTransactions());
        Assertions.assertEquals(true, meta.supportsDataManipulationTransactionsOnly());
        Assertions.assertEquals(true, meta.dataDefinitionCausesTransactionCommit());
        Assertions.assertEquals(false, meta.dataDefinitionIgnoredInTransactions());

        // 119–174: ResultSets, Connection, and more
        try (ResultSet rs = meta.getProcedures(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getProcedureColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTables(null, null, null, new String[]{"TABLE"})) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getSchemas()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCatalogs()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTableTypes()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getColumnPrivileges(null, null, "TEST_TABLE", null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTablePrivileges(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getBestRowIdentifier(null, null, "TEST_TABLE", DatabaseMetaData.bestRowSession, false)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getVersionColumns(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPrimaryKeys(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getImportedKeys(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getExportedKeys(null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getCrossReference(null, null, "TEST_TABLE", null, null, "TEST_TABLE")) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getTypeInfo()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getIndexInfo(null, null, "TEST_TABLE", false, false)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getUDTs(null, null, null, null)) {
            validateAllRows(rs);
        }
        Assertions.assertNotNull(meta.getConnection());
        Assertions.assertEquals(true, meta.supportsSavepoints());
        Assertions.assertEquals(false, meta.supportsNamedParameters());
        Assertions.assertEquals(false, meta.supportsMultipleOpenResults());
        Assertions.assertEquals(true, meta.supportsGetGeneratedKeys());
        try (ResultSet rs = meta.getSuperTypes(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getSuperTables(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getAttributes(null, null, null, null)) {
            validateAllRows(rs);
        }
        Assertions.assertEquals(false, meta.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        Assertions.assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, meta.getResultSetHoldability());
        Assertions.assertEquals(2, meta.getDatabaseMajorVersion());
        Assertions.assertEquals(3, meta.getDatabaseMinorVersion());
        Assertions.assertEquals(4, meta.getJDBCMajorVersion());
        Assertions.assertEquals(3, meta.getJDBCMinorVersion());
        Assertions.assertEquals(DatabaseMetaData.sqlStateSQL, meta.getSQLStateType());
        Assertions.assertEquals(false, meta.locatorsUpdateCopy());
        Assertions.assertEquals(false, meta.supportsStatementPooling());
        Assertions.assertNotNull(meta.getRowIdLifetime());
        try (ResultSet rs = meta.getSchemas(null, null)) {
            validateAllRows(rs);
        }
        Assertions.assertEquals(true, meta.supportsStoredFunctionsUsingCallSyntax());
        Assertions.assertEquals(false, meta.autoCommitFailureClosesAllResultSets());
        try (ResultSet rs = meta.getClientInfoProperties()) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctions(null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getFunctionColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        try (ResultSet rs = meta.getPseudoColumns(null, null, null, null)) {
            validateAllRows(rs);
        }
        Assertions.assertEquals(true, meta.generatedKeyAlwaysReturned());
        Assertions.assertEquals(0, meta.getMaxLogicalLobSize());
        Assertions.assertEquals(false, meta.supportsRefCursors());
        Assertions.assertEquals(false, meta.supportsSharding());

        // 175–177: ResultSet/Concurrency methods
        Assertions.assertEquals(true, meta.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(true, meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        Assertions.assertEquals(true, meta.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.ownDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.ownInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.othersUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.othersDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.othersInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.updatesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.deletesAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(false, meta.insertsAreDetected(ResultSet.TYPE_FORWARD_ONLY));
        Assertions.assertEquals(true, meta.supportsBatchUpdates());
    }

    private void validateAllRows(ResultSet rs) throws SQLException {
        TestDBUtils.validateAllRows(rs);
    }
}
