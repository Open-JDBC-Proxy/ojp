package org.openjproxy.grpc.server.sql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SchemaLoader with mocked JDBC.
 */
class SchemaLoaderTest {
    
    private SchemaLoader schemaLoader;
    
    @BeforeEach
    void setUp() {
        schemaLoader = new SchemaLoader();
    }
    
    @Test
    void testJdbcTypeToSqlTypeName() throws Exception {
        // Test type conversion by loading a schema with mocked connection
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet tablesRs = mock(ResultSet.class);
        ResultSet columnsRs = mock(ResultSet.class);
        
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(null, null, null, new String[]{"TABLE"})).thenReturn(tablesRs);
        
        // Setup table result set
        when(tablesRs.next()).thenReturn(true, false); // One table
        when(tablesRs.getString("TABLE_NAME")).thenReturn("test_table");
        
        // Setup columns result set
        when(metaData.getColumns(null, null, "test_table", null)).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, true, false); // Two columns
        when(columnsRs.getString("COLUMN_NAME")).thenReturn("id", "name");
        when(columnsRs.getInt("DATA_TYPE")).thenReturn(Types.INTEGER, Types.VARCHAR);
        when(columnsRs.getString("TYPE_NAME")).thenReturn("INTEGER", "VARCHAR");
        when(columnsRs.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNoNulls, DatabaseMetaData.columnNullable);
        when(columnsRs.getInt("COLUMN_SIZE")).thenReturn(10, 255);
        when(columnsRs.getInt("DECIMAL_DIGITS")).thenReturn(0, 0);
        when(columnsRs.wasNull()).thenReturn(false);
        
        SchemaMetadata schema = schemaLoader.loadSchema(connection, null, null);
        
        assertNotNull(schema, "Schema should not be null");
        assertEquals(1, schema.getTables().size(), "Should have 1 table");
        
        TableMetadata table = schema.getTable("test_table");
        assertNotNull(table, "Table should exist");
        assertEquals(2, table.getColumns().size(), "Should have 2 columns");
        
        ColumnMetadata idColumn = table.getColumns().getFirst();
        assertEquals("id", idColumn.getColumnName());
        assertEquals(Types.INTEGER, idColumn.getJdbcType());
        assertFalse(idColumn.isNullable());
        
        ColumnMetadata nameColumn = table.getColumns().get(1);
        assertEquals("name", nameColumn.getColumnName());
        assertEquals(Types.VARCHAR, nameColumn.getJdbcType());
        assertTrue(nameColumn.isNullable());
    }
    
    @Test
    void testEmptyDatabase() throws SQLException {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet tablesRs = mock(ResultSet.class);
        
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(null, null, null, new String[]{"TABLE"})).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(false); // No tables
        
        SchemaMetadata schema = schemaLoader.loadSchema(connection, null, null);
        
        assertNotNull(schema, "Schema should not be null even when empty");
        assertNotNull(schema.getTables(), "Tables map should not be null");
        assertEquals(0, schema.getTables().size(), "Should have no tables");
    }
    
    @Test
    void testCaseInsensitiveLookup() throws SQLException {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet tablesRs = mock(ResultSet.class);
        ResultSet columnsRs = mock(ResultSet.class);
        
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(null, null, null, new String[]{"TABLE"})).thenReturn(tablesRs);
        when(tablesRs.next()).thenReturn(true, false);
        when(tablesRs.getString("TABLE_NAME")).thenReturn("USERS");
        
        when(metaData.getColumns(null, null, "USERS", null)).thenReturn(columnsRs);
        when(columnsRs.next()).thenReturn(true, false);
        when(columnsRs.getString("COLUMN_NAME")).thenReturn("id");
        when(columnsRs.getInt("DATA_TYPE")).thenReturn(Types.INTEGER);
        when(columnsRs.getString("TYPE_NAME")).thenReturn("INTEGER");
        when(columnsRs.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNoNulls);
        when(columnsRs.getInt("COLUMN_SIZE")).thenReturn(10);
        when(columnsRs.getInt("DECIMAL_DIGITS")).thenReturn(0);
        when(columnsRs.wasNull()).thenReturn(false);
        
        SchemaMetadata schema = schemaLoader.loadSchema(connection, null, null);
        
        // Test different case variations
        assertNotNull(schema.getTable("USERS"), "Should find 'USERS'");
        assertNotNull(schema.getTable("users"), "Should find 'users' (case-insensitive)");
        assertNotNull(schema.getTable("Users"), "Should find 'Users' (case-insensitive)");
    }
}
