package org.openjproxy.grpc.server.sql;

import lombok.Data;
import org.apache.calcite.rel.type.RelDataType;

import java.util.List;

/**
 * Metadata for a database table.
 * Contains column information and the corresponding Calcite RelDataType.
 */
@Data
public class TableMetadata {
    private final String tableName;
    private final List<ColumnMetadata> columns;
    private final RelDataType relDataType;

    /**
     * Creates table metadata.
     *
     * @param tableName Name of the table
     * @param columns List of column metadata
     * @param relDataType Calcite type representation for the table
     */
    public TableMetadata(String tableName, List<ColumnMetadata> columns, RelDataType relDataType) {
        this.tableName = tableName;
        this.columns = columns;
        this.relDataType = relDataType;
    }
}
