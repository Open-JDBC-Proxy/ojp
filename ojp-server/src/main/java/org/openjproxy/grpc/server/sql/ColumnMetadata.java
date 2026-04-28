package org.openjproxy.grpc.server.sql;

import lombok.Data;

/**
 * Metadata for a single database column.
 * Contains type information, nullability, and precision/scale for numeric types.
 */
@Data
public class ColumnMetadata {
    private final String columnName;
    private final int jdbcType;
    private final String typeName;
    private final boolean nullable;
    private final int precision;
    private final int scale;

    /**
     * Creates column metadata.
     *
     * @param columnName Name of the column
     * @param jdbcType JDBC type constant from java.sql.Types
     * @param typeName Database-specific type name
     * @param nullable Whether the column allows NULL values
     * @param precision Column precision (for numeric/character types)
     * @param scale Column scale (for numeric types)
     */
    public ColumnMetadata(String columnName, int jdbcType, String typeName,
                         boolean nullable, int precision, int scale) {
        this.columnName = columnName;
        this.jdbcType = jdbcType;
        this.typeName = typeName;
        this.nullable = nullable;
        this.precision = precision;
        this.scale = scale;
    }
}
