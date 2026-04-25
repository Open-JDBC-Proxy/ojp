package org.openjproxy.grpc.server.sql;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory for creating Calcite Schema objects from SchemaMetadata.
 * Handles conversion between our metadata model and Calcite's schema model.
 */
@Slf4j
public class CalciteSchemaFactory {

    /**
     * Creates a Calcite Schema from schema metadata.
     *
     * @param metadata The schema metadata to convert
     * @return Calcite Schema object
     */
    public Schema createSchema(SchemaMetadata metadata) {
        if (metadata == null) {
            log.debug("No metadata provided, returning empty schema");
            return createEmptySchema();
        }

        return new MetadataBackedSchema(metadata);
    }

    /**
     * Creates an empty schema with no tables.
     *
     * @return Empty Calcite Schema
     */
    public Schema createEmptySchema() {
        return new AbstractSchema() {
            @Override
            protected Map<String, Table> getTableMap() {
                return new HashMap<>();
            }
        };
    }

    /**
     * Schema implementation backed by SchemaMetadata.
     */
    private static class MetadataBackedSchema extends AbstractSchema {
        private final SchemaMetadata metadata;

        public MetadataBackedSchema(SchemaMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        protected Map<String, Table> getTableMap() {
            Map<String, Table> tables = new HashMap<>();

            for (Map.Entry<String, TableMetadata> entry : metadata.getTables().entrySet()) {
                String tableName = entry.getKey();
                TableMetadata tableMetadata = entry.getValue();

                tables.put(tableName, new MetadataBackedTable(tableMetadata));
            }

            return tables;
        }
    }

    /**
     * Table implementation backed by TableMetadata.
     */
    private static class MetadataBackedTable extends AbstractTable {
        private final TableMetadata metadata;

        public MetadataBackedTable(TableMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            // Return the pre-built RelDataType from metadata
            return metadata.getRelDataType();
        }
    }
}
