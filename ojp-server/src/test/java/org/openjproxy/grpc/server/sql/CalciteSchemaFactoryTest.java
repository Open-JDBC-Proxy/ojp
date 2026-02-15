package org.openjproxy.grpc.server.sql;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.lookup.LikePattern;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for CalciteSchemaFactory.
 */
class CalciteSchemaFactoryTest {
    
    private CalciteSchemaFactory schemaFactory;
    private RelDataTypeFactory typeFactory;
    
    @BeforeEach
    void setUp() {
        schemaFactory = new CalciteSchemaFactory();
        typeFactory = new SqlTypeFactoryImpl(org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
    }
    
    @Test
    void testCreateEmptySchema() {
        Schema schema = schemaFactory.createEmptySchema();
        
        assertNotNull(schema, "Schema should not be null");
        Set<String> tableNames = schema.tables().getNames(LikePattern.any());
        assertNotNull(tableNames, "Table names should not be null");
        assertEquals(0, tableNames.size(), "Empty schema should have no tables");
    }
    
    @Test
    void testCreateSchemaFromNull() {
        Schema schema = schemaFactory.createSchema(null);
        
        assertNotNull(schema, "Schema should not be null even with null metadata");
        Set<String> tableNames = schema.tables().getNames(LikePattern.any());
        assertNotNull(tableNames, "Table names should not be null");
        assertEquals(0, tableNames.size(), "Schema from null metadata should have no tables");
    }
    
    @Test
    void testCreateSchemaWithTables() {
        // Create sample schema metadata
        Map<String, TableMetadata> tables = new HashMap<>();
        
        // Create users table
        List<ColumnMetadata> userColumns = new ArrayList<>();
        userColumns.add(new ColumnMetadata("id", java.sql.Types.INTEGER, "INTEGER", false, 10, 0));
        userColumns.add(new ColumnMetadata("name", java.sql.Types.VARCHAR, "VARCHAR", false, 255, 0));
        
        RelDataType userType = buildTableType( userColumns);
        TableMetadata usersTable = new TableMetadata("users", userColumns, userType);
        tables.put("users", usersTable);
        
        // Create orders table
        List<ColumnMetadata> orderColumns = new ArrayList<>();
        orderColumns.add(new ColumnMetadata("order_id", java.sql.Types.INTEGER, "INTEGER", false, 10, 0));
        orderColumns.add(new ColumnMetadata("amount", java.sql.Types.DECIMAL, "DECIMAL", false, 10, 2));
        
        RelDataType orderType = buildTableType( orderColumns);
        TableMetadata ordersTable = new TableMetadata("orders", orderColumns, orderType);
        tables.put("orders", ordersTable);
        
        SchemaMetadata metadata = new SchemaMetadata(tables, System.currentTimeMillis(), null, null);
        
        // Create Calcite schema
        Schema schema = schemaFactory.createSchema(metadata);
        
        assertNotNull(schema, "Schema should not be null");
        Set<String> tableNamesSet = schema.tables().getNames(LikePattern.any());
        assertEquals(2, tableNamesSet.size(), "Schema should have 2 tables");
        assertTrue(tableNamesSet.contains("users"), "Schema should contain users table");
        assertTrue(tableNamesSet.contains("orders"), "Schema should contain orders table");
    }
    
    @Test
    void testTableFromSchema() {
        // Create sample schema metadata
        List<ColumnMetadata> columns = new ArrayList<>();
        columns.add(new ColumnMetadata("id", java.sql.Types.INTEGER, "INTEGER", false, 10, 0));
        columns.add(new ColumnMetadata("name", java.sql.Types.VARCHAR, "VARCHAR", false, 255, 0));
        
        RelDataType tableType = buildTableType( columns);
        TableMetadata tableMetadata = new TableMetadata("test_table", columns, tableType);
        
        Map<String, TableMetadata> tables = new HashMap<>();
        tables.put("test_table", tableMetadata);
        
        SchemaMetadata metadata = new SchemaMetadata(tables, System.currentTimeMillis(), null, null);
        Schema schema = schemaFactory.createSchema(metadata);
        
        // Get table from schema
        Table table = schema.tables().get("test_table");
        assertNotNull(table, "Table should not be null");
        
        // Check table row type
        RelDataType rowType = table.getRowType(typeFactory);
        assertNotNull(rowType, "Row type should not be null");
        assertEquals(2, rowType.getFieldCount(), "Row type should have 2 fields");
    }
    
    @Test
    void testTableRowTypeMatches() {
        // Create sample column metadata
        List<ColumnMetadata> columns = new ArrayList<>();
        columns.add(new ColumnMetadata("id", java.sql.Types.INTEGER, "INTEGER", false, 10, 0));
        columns.add(new ColumnMetadata("name", java.sql.Types.VARCHAR, "VARCHAR", false, 255, 0));
        columns.add(new ColumnMetadata("balance", java.sql.Types.DECIMAL, "DECIMAL", true, 10, 2));
        
        RelDataType originalType = buildTableType( columns);
        TableMetadata tableMetadata = new TableMetadata("test_table", columns, originalType);
        
        Map<String, TableMetadata> tables = new HashMap<>();
        tables.put("test_table", tableMetadata);
        
        SchemaMetadata metadata = new SchemaMetadata(tables, System.currentTimeMillis(), null, null);
        Schema schema = schemaFactory.createSchema(metadata);
        
        Table table = schema.tables().get("test_table");
        assertNotNull(table, "Table should not be null");
        RelDataType retrievedType = table.getRowType(typeFactory);
        
        // Verify the row type has the expected structure
        assertEquals(3, retrievedType.getFieldCount(), "Should have 3 fields");
        assertNotNull(retrievedType.getField("id", false, false), "Should have id field");
        assertNotNull(retrievedType.getField("name", false, false), "Should have name field");
        assertNotNull(retrievedType.getField("balance", false, false), "Should have balance field");
    }
    
    /**
     * Helper method to build a table type from columns (similar to SchemaLoader).
     */
    private RelDataType buildTableType(List<ColumnMetadata> columns) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        
        for (ColumnMetadata column : columns) {
            SqlTypeName typeName = jdbcTypeToSqlTypeName(column.getJdbcType());
            RelDataType colType;
            
            if (typeName == SqlTypeName.DECIMAL) {
                colType = typeFactory.createSqlType(typeName, column.getPrecision(), column.getScale());
            } else if (typeName == SqlTypeName.VARCHAR) {
                colType = typeFactory.createSqlType(typeName, column.getPrecision());
            } else {
                colType = typeFactory.createSqlType(typeName);
            }
            
            colType = typeFactory.createTypeWithNullability(colType, column.isNullable());
            builder.add(column.getColumnName(), colType);
        }
        
        return builder.build();
    }
    
    /**
     * Simplified JDBC to SqlTypeName conversion for testing.
     */
    private SqlTypeName jdbcTypeToSqlTypeName(int jdbcType) {
        return switch (jdbcType) {
            case java.sql.Types.INTEGER -> SqlTypeName.INTEGER;
            case java.sql.Types.DECIMAL -> SqlTypeName.DECIMAL;
            case java.sql.Types.TIMESTAMP -> SqlTypeName.TIMESTAMP;
            case java.sql.Types.VARCHAR -> SqlTypeName.VARCHAR;
            default -> SqlTypeName.VARCHAR;
        };
    }
}
