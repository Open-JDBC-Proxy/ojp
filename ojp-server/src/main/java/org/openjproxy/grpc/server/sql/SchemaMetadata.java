package org.openjproxy.grpc.server.sql;

import lombok.Data;

import java.util.Map;

/**
 * Complete schema metadata for a database.
 * Contains all table definitions and metadata about when it was loaded.
 */
@Data
public class SchemaMetadata {
    private final Map<String, TableMetadata> tables;
    private final long loadTimestamp;
    private final String catalogName;
    private final String schemaName;

    /**
     * Creates schema metadata.
     *
     * @param tables Map of table name to table metadata
     * @param loadTimestamp When this schema was loaded (milliseconds since epoch)
     * @param catalogName Database catalog name (may be null)
     * @param schemaName Database schema name (may be null)
     */
    public SchemaMetadata(Map<String, TableMetadata> tables, long loadTimestamp,
                         String catalogName, String schemaName) {
        this.tables = tables;
        this.loadTimestamp = loadTimestamp;
        this.catalogName = catalogName;
        this.schemaName = schemaName;
    }

    /**
     * Gets a table by name (case-insensitive lookup).
     *
     * @param tableName The table name to look up
     * @return TableMetadata or null if not found
     */
    public TableMetadata getTable(String tableName) {
        // Try exact match first
        TableMetadata table = tables.get(tableName);
        if (table != null) {
            return table;
        }

        // Try case-insensitive match
        for (Map.Entry<String, TableMetadata> entry : tables.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(tableName)) {
                return entry.getValue();
            }
        }

        return null;
    }
}
