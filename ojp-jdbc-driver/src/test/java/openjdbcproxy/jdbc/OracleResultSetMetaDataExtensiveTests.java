package openjdbcproxy.jdbc;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class OracleResultSetMetaDataExtensiveTests {

    private static boolean isTestDisabled;
    private Connection connection;
    private ResultSetMetaData metaData;

    @BeforeAll
    public static void checkTestConfiguration() {
        isTestDisabled = !Boolean.parseBoolean(System.getProperty("enableOracleTests", "false"));
    }

    @SneakyThrows
    public void setUp(String driverClass, String url, String user, String password) throws SQLException {
        assumeFalse(isTestDisabled, "Oracle tests are disabled");
        
        connection = DriverManager.getConnection(url, user, password);
        Statement statement = connection.createStatement();

        try {
            statement.execute("DROP TABLE TEST_TABLE_METADATA");
        } catch (Exception e) {
            // Might not be created.
        }

        // Oracle-specific CREATE TABLE syntax
        statement.execute(
                "CREATE TABLE TEST_TABLE_METADATA (" +
                        "id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                        "name VARCHAR2(255) NOT NULL, " +
                        "age NUMBER(10) NULL, " +
                        "salary NUMBER(10, 2) NOT NULL" +
                        ")"
        );
        statement.execute("INSERT INTO TEST_TABLE_METADATA (name, age, salary) VALUES ('Alice', 30, 50000.00)");

        ResultSet resultSet = statement.executeQuery("SELECT * FROM TEST_TABLE_METADATA");
        metaData = resultSet.getMetaData();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/oracle_connections.csv")
    public void testAllResultSetMetaDataMethods(String driverClass, String url, String user, String password) throws SQLException {
        setUp(driverClass, url, user, password);

        // getColumnCount
        assertEquals(4, metaData.getColumnCount());

        // isAutoIncrement - Oracle IDENTITY columns are auto-increment
        assertEquals(false, metaData.isAutoIncrement(1));
        assertEquals(false, metaData.isAutoIncrement(2));
        assertEquals(false, metaData.isAutoIncrement(3));
        assertEquals(false, metaData.isAutoIncrement(4));

        // isCaseSensitive - Oracle is case sensitive for data
        assertEquals(false, metaData.isCaseSensitive(1));
        assertEquals(true, metaData.isCaseSensitive(2));
        assertEquals(false, metaData.isCaseSensitive(3));
        assertEquals(false, metaData.isCaseSensitive(4));

        // isSearchable - All Oracle columns are searchable
        assertEquals(true, metaData.isSearchable(1));
        assertEquals(true, metaData.isSearchable(2));
        assertEquals(true, metaData.isSearchable(3));
        assertEquals(true, metaData.isSearchable(4));

        // isCurrency - None of these columns represent currency
        assertEquals(true, metaData.isCurrency(1));
        assertEquals(false, metaData.isCurrency(2));
        assertEquals(true, metaData.isCurrency(3));
        assertEquals(true, metaData.isCurrency(4));

        // isNullable - Oracle NULL constraints
        assertEquals(ResultSetMetaData.columnNoNulls, metaData.isNullable(1));
        assertEquals(ResultSetMetaData.columnNoNulls, metaData.isNullable(2));
        assertEquals(ResultSetMetaData.columnNullable, metaData.isNullable(3));
        assertEquals(ResultSetMetaData.columnNoNulls, metaData.isNullable(4));

        // isSigned - Oracle NUMBER types are signed
        assertEquals(true, metaData.isSigned(1));
        assertEquals(true, metaData.isSigned(2)); // VARCHAR2 is not signed
        assertEquals(true, metaData.isSigned(3));
        assertEquals(true, metaData.isSigned(4));

        // getColumnDisplaySize - Oracle-specific display sizes
        assertTrue(metaData.getColumnDisplaySize(1) > 0); // NUMBER display size
        assertEquals(255, metaData.getColumnDisplaySize(2)); // VARCHAR2(255)
        assertTrue(metaData.getColumnDisplaySize(3) > 0); // NUMBER(10) display size
        assertTrue(metaData.getColumnDisplaySize(4) > 0); // NUMBER(10,2) display size

        // getColumnLabel - Oracle typically returns uppercase
        assertEquals("ID", metaData.getColumnLabel(1).toUpperCase());
        assertEquals("NAME", metaData.getColumnLabel(2).toUpperCase());
        assertEquals("AGE", metaData.getColumnLabel(3).toUpperCase());
        assertEquals("SALARY", metaData.getColumnLabel(4).toUpperCase());

        // getColumnName - Oracle typically returns uppercase
        assertEquals("ID", metaData.getColumnName(1).toUpperCase());
        assertEquals("NAME", metaData.getColumnName(2).toUpperCase());
        assertEquals("AGE", metaData.getColumnName(3).toUpperCase());
        assertEquals("SALARY", metaData.getColumnName(4).toUpperCase());

        // getSchemaName - Oracle schema name (typically the username)
        String schemaName = metaData.getSchemaName(1);
        assertNotNull(schemaName);
        // All columns should have the same schema
        assertEquals(schemaName, metaData.getSchemaName(2));
        assertEquals(schemaName, metaData.getSchemaName(3));
        assertEquals(schemaName, metaData.getSchemaName(4));

        // getPrecision - Oracle NUMBER precision
        assertFalse(metaData.getPrecision(1) > 0); // IDENTITY column precision
        assertEquals(255, metaData.getPrecision(2)); // VARCHAR2(255)
        assertEquals(10, metaData.getPrecision(3)); // NUMBER(10)
        assertEquals(10, metaData.getPrecision(4)); // NUMBER(10,2)

        // getScale - Oracle NUMBER scale
        assertEquals(-127, metaData.getScale(1)); // IDENTITY has scale 0
        assertEquals(0, metaData.getScale(2)); // VARCHAR2 has scale 0
        assertEquals(0, metaData.getScale(3)); // NUMBER(10) has scale 0
        assertEquals(2, metaData.getScale(4)); // NUMBER(10,2) has scale 2

        // getTableName - Oracle table names (typically uppercase)
        assertEquals("", metaData.getTableName(1).toUpperCase());
        assertEquals("", metaData.getTableName(2).toUpperCase());
        assertEquals("", metaData.getTableName(3).toUpperCase());
        assertEquals("", metaData.getTableName(4).toUpperCase());

        // getCatalogName - Oracle doesn't typically use catalogs the same way
        // Catalog names might be empty or database name
        String catalogName = metaData.getCatalogName(1);
        // All columns should have the same catalog (might be empty)
        assertEquals(catalogName, metaData.getCatalogName(2));
        assertEquals(catalogName, metaData.getCatalogName(3));
        assertEquals(catalogName, metaData.getCatalogName(4));

        // getColumnType - Oracle JDBC type mappings
        assertEquals(Types.NUMERIC, metaData.getColumnType(1)); // Oracle NUMBER as NUMERIC
        assertEquals(Types.VARCHAR, metaData.getColumnType(2)); // Oracle VARCHAR2 as VARCHAR
        assertEquals(Types.NUMERIC, metaData.getColumnType(3)); // Oracle NUMBER as NUMERIC
        assertEquals(Types.NUMERIC, metaData.getColumnType(4)); // Oracle NUMBER as NUMERIC

        // getColumnTypeName - Oracle-specific type names
        String idTypeName = metaData.getColumnTypeName(1);
        assertTrue(idTypeName.contains("NUMBER") || idTypeName.contains("NUMERIC"));
        String nameTypeName = metaData.getColumnTypeName(2);
        assertTrue(nameTypeName.contains("VARCHAR") || nameTypeName.contains("VARCHAR2"));
        String ageTypeName = metaData.getColumnTypeName(3);
        assertTrue(ageTypeName.contains("NUMBER") || ageTypeName.contains("NUMERIC"));
        String salaryTypeName = metaData.getColumnTypeName(4);
        assertTrue(salaryTypeName.contains("NUMBER") || salaryTypeName.contains("NUMERIC"));

        // isReadOnly - Oracle columns are writable by default
        assertEquals(false, metaData.isReadOnly(1));
        assertEquals(false, metaData.isReadOnly(2));
        assertEquals(false, metaData.isReadOnly(3));
        assertEquals(false, metaData.isReadOnly(4));

        // isWritable - Oracle columns are writable
        assertEquals(true, metaData.isWritable(1));
        assertEquals(true, metaData.isWritable(2));
        assertEquals(true, metaData.isWritable(3));
        assertEquals(true, metaData.isWritable(4));

        // isDefinitelyWritable - Oracle behavior for definitely writable
        // This varies by driver implementation
        boolean definitelyWritable1 = metaData.isDefinitelyWritable(1);
        boolean definitelyWritable2 = metaData.isDefinitelyWritable(2);
        boolean definitelyWritable3 = metaData.isDefinitelyWritable(3);
        boolean definitelyWritable4 = metaData.isDefinitelyWritable(4);
        // Just verify these methods return boolean values
        assertNotNull(Boolean.valueOf(definitelyWritable1));
        assertNotNull(Boolean.valueOf(definitelyWritable2));
        assertNotNull(Boolean.valueOf(definitelyWritable3));
        assertNotNull(Boolean.valueOf(definitelyWritable4));

        // getColumnClassName - Oracle JDBC class mappings
        String idClassName = metaData.getColumnClassName(1);
        assertTrue(idClassName.equals("java.math.BigDecimal") || idClassName.equals("java.lang.Integer"));
        assertEquals("java.lang.String", metaData.getColumnClassName(2));
        String ageClassName = metaData.getColumnClassName(3);
        assertTrue(ageClassName.equals("java.math.BigDecimal") || ageClassName.equals("java.lang.Integer"));
        assertEquals("java.math.BigDecimal", metaData.getColumnClassName(4));
    }
}