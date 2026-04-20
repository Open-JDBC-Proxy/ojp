package org.openjproxy.grpc.server.sql;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.sql.type.SqlTypeName;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Loads database schema metadata from JDBC connections.
 * Supports asynchronous loading and multiple database dialects.
 */
@Slf4j
public class SchemaLoader {
    
    private final Executor executor;
    private final long timeoutSeconds;
    private final RelDataTypeFactory typeFactory;
    
    /**
     * Creates a schema loader with default settings.
     */
    public SchemaLoader() {
        this(ForkJoinPool.commonPool(), 30);
    }
    
    /**
     * Creates a schema loader with custom executor and timeout.
     * 
     * @param executor Executor for async operations
     * @param timeoutSeconds Timeout for schema loading operations
     */
    public SchemaLoader(Executor executor, long timeoutSeconds) {
        this.executor = executor;
        this.timeoutSeconds = timeoutSeconds;
        this.typeFactory = new SqlTypeFactoryImpl(org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
    }
    
    /**
     * Asynchronously loads schema metadata from a DataSource with timeout.
     * 
     * @param dataSource The data source to load schema from
     * @param catalogName Catalog name (may be null)
     * @param schemaName Schema name (may be null)
     * @return CompletableFuture containing the schema metadata
     */
    public CompletableFuture<SchemaMetadata> loadSchemaAsync(DataSource dataSource, 
                                                              String catalogName, 
                                                              String schemaName) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return loadSchema(connection, catalogName, schemaName);
            } catch (SQLException e) {
                log.error("Failed to load schema asynchronously", e);
                throw new RuntimeException("Failed to load schema", e);
            }
        }, executor)
        .orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        .exceptionally(ex -> {
            if (ex instanceof java.util.concurrent.TimeoutException) {
                log.warn("Schema loading timed out after {} seconds", timeoutSeconds);
            }
            throw new RuntimeException("Failed to load schema", ex);
        });
    }
    
    /**
     * Synchronously loads schema metadata from a connection.
     * 
     * @param connection Database connection
     * @param catalogName Catalog name (may be null)
     * @param schemaName Schema name (may be null)
     * @return Schema metadata
     * @throws SQLException if database access fails
     */
    public SchemaMetadata loadSchema(Connection connection, String catalogName, String schemaName) 
            throws SQLException {
        log.info("Loading schema metadata for catalog: {}, schema: {}", catalogName, schemaName);
        
        long startTime = System.nanoTime();
        DatabaseMetaData metaData = connection.getMetaData();
        
        // Load all tables
        Map<String, TableMetadata> tables = new HashMap<>();
        
        try (ResultSet tablesRs = metaData.getTables(catalogName, schemaName, null, new String[]{"TABLE"})) {
            while (tablesRs.next()) {
                String tableName = tablesRs.getString("TABLE_NAME");
                
                // Skip system tables
                if (isSystemTable(tableName)) {
                    continue;
                }
                
                try {
                    // Load columns for this table
                    List<ColumnMetadata> columns = loadColumns(metaData, catalogName, schemaName, tableName);
                    
                    if (!columns.isEmpty()) {
                        // Build Calcite type for this table
                        RelDataType relDataType = buildTableType(tableName, columns);
                        
                        TableMetadata tableMetadata = new TableMetadata(tableName, columns, relDataType);
                        tables.put(tableName, tableMetadata);
                        
                        log.debug("Loaded table: {} with {} columns", tableName, columns.size());
                    }
                } catch (SQLException e) {
                    log.warn("Failed to load columns for table: {}, skipping", tableName, e);
                }
            }
        }
        
        long duration = (System.nanoTime() - startTime) / 1_000_000L;
        
        if (tables.isEmpty()) {
            log.warn("No tables found in catalog: {}, schema: {}. Schema may be empty or inaccessible.", catalogName, schemaName);
        } else {
            log.info("Loaded {} tables in {}ms: {}", tables.size(), duration, tables.keySet());
        }
        
        return new SchemaMetadata(tables, System.currentTimeMillis(), catalogName, schemaName);
    }
    
    /**
     * Loads column metadata for a specific table.
     */
    private List<ColumnMetadata> loadColumns(DatabaseMetaData metaData, String catalogName, 
                                            String schemaName, String tableName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        
        try (ResultSet columnsRs = metaData.getColumns(catalogName, schemaName, tableName, null)) {
            while (columnsRs.next()) {
                String columnName = columnsRs.getString("COLUMN_NAME");
                int jdbcType = columnsRs.getInt("DATA_TYPE");
                String typeName = columnsRs.getString("TYPE_NAME");
                int nullable = columnsRs.getInt("NULLABLE");
                int precision = columnsRs.getInt("COLUMN_SIZE");
                int scale = columnsRs.getInt("DECIMAL_DIGITS");
                
                // Handle null scale
                if (columnsRs.wasNull()) {
                    scale = 0;
                }
                
                boolean isNullable = (nullable == DatabaseMetaData.columnNullable);
                
                ColumnMetadata column = new ColumnMetadata(
                    columnName, jdbcType, typeName, isNullable, precision, scale
                );
                
                columns.add(column);
            }
        }
        
        return columns;
    }
    
    /**
     * Builds a Calcite RelDataType from column metadata.
     */
    private RelDataType buildTableType(String tableName, List<ColumnMetadata> columns) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        
        for (ColumnMetadata column : columns) {
            RelDataType colType = jdbcTypeToCalciteType(column);
            builder.add(column.getColumnName(), colType);
        }
        
        return builder.build();
    }
    
    /**
     * Converts JDBC type to Calcite SqlTypeName.
     */
    private RelDataType jdbcTypeToCalciteType(ColumnMetadata column) {
        SqlTypeName typeName = jdbcTypeToSqlTypeName(column.getJdbcType());
        RelDataType type;
        
        // Handle types with precision/scale
        if (typeName == SqlTypeName.DECIMAL) {
            int precision = column.getPrecision() > 0 ? column.getPrecision() : 10;
            int scale = column.getScale();
            type = typeFactory.createSqlType(typeName, precision, scale);
        } else if (typeName == SqlTypeName.VARCHAR || typeName == SqlTypeName.CHAR) {
            int precision = column.getPrecision() > 0 ? column.getPrecision() : 255;
            type = typeFactory.createSqlType(typeName, precision);
        } else if (typeName == SqlTypeName.TIME || typeName == SqlTypeName.TIMESTAMP) {
            int precision = column.getPrecision() > 0 ? column.getPrecision() : 0;
            type = typeFactory.createSqlType(typeName, precision);
        } else {
            type = typeFactory.createSqlType(typeName);
        }
        
        // Handle nullability
        return typeFactory.createTypeWithNullability(type, column.isNullable());
    }
    
    /**
     * Maps JDBC type constants to Calcite SqlTypeName.
     */
    private SqlTypeName jdbcTypeToSqlTypeName(int jdbcType) {
        switch (jdbcType) {
            // Character types
            case Types.CHAR:
                return SqlTypeName.CHAR;
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
                return SqlTypeName.VARCHAR;
            case Types.NCHAR:
                return SqlTypeName.CHAR;
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
                return SqlTypeName.VARCHAR;
            
            // Numeric types
            case Types.BOOLEAN:
            case Types.BIT:
                return SqlTypeName.BOOLEAN;
            case Types.TINYINT:
                return SqlTypeName.TINYINT;
            case Types.SMALLINT:
                return SqlTypeName.SMALLINT;
            case Types.INTEGER:
                return SqlTypeName.INTEGER;
            case Types.BIGINT:
                return SqlTypeName.BIGINT;
            case Types.REAL:
                return SqlTypeName.REAL;
            case Types.FLOAT:
                return SqlTypeName.FLOAT;
            case Types.DOUBLE:
                return SqlTypeName.DOUBLE;
            case Types.DECIMAL:
            case Types.NUMERIC:
                return SqlTypeName.DECIMAL;
            
            // Date/time types
            case Types.DATE:
                return SqlTypeName.DATE;
            case Types.TIME:
                return SqlTypeName.TIME;
            case Types.TIMESTAMP:
                return SqlTypeName.TIMESTAMP;
            
            // Binary types
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return SqlTypeName.VARBINARY;
            
            // LOB types
            case Types.CLOB:
            case Types.NCLOB:
                return SqlTypeName.VARCHAR;
            case Types.BLOB:
                return SqlTypeName.VARBINARY;
            
            // Other types
            case Types.NULL:
                return SqlTypeName.NULL;
            case Types.ARRAY:
                return SqlTypeName.ARRAY;
            
            default:
                log.warn("Unmapped JDBC type: {}, using VARCHAR", jdbcType);
                return SqlTypeName.VARCHAR;
        }
    }
    
    /**
     * Checks if a table name is a system table that should be skipped.
     */
    private boolean isSystemTable(String tableName) {
        if (tableName == null) {
            return true;
        }
        
        String upperName = tableName.toUpperCase();
        
        // Skip common system table patterns
        return upperName.startsWith("SYS_") ||
               upperName.startsWith("SYSTEM_") ||
               upperName.startsWith("INFORMATION_SCHEMA") ||
               upperName.startsWith("PG_") ||
               upperName.startsWith("MYSQL.") ||
               upperName.equals("DUAL");
    }
}
