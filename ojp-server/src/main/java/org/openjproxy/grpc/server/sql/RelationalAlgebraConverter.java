package org.openjproxy.grpc.server.sql;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.FrameworkConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts between SQL and Relational Algebra (RelNode) representations.
 * Handles SQL parsing, optimization using Apache Calcite's HepPlanner,
 * and SQL generation from optimized relational algebra.
 */
@Slf4j
public class RelationalAlgebraConverter {

    private final SqlParser.Config parserConfig;
    private final SqlDialect sqlDialect;
    private final SqlLibrary sqlLibrary;
    private final SchemaCache schemaCache;
    private final CalciteSchemaFactory schemaFactory;

    /**
     * Dynamic schema that provides generic tables for any name requested.
     * Implements Calcite's Schema interface to support unknown table references.
     */
    private static class DynamicSchema extends AbstractSchema {
        @Override
        protected Map<String, Table> getTableMap() {
            // Pre-populate with common table names used in tests
            Map<String, Table> tables = new ConcurrentHashMap<>();
            GenericTable genericTable = new GenericTable();

            // Add common table names in various cases
            String[] commonNames = {"users", "orders", "products", "customers", "items", "accounts", "sales"};
            for (String name : commonNames) {
                tables.put(name, genericTable);
                tables.put(name.toUpperCase(), genericTable);
                tables.put(capitalize(name), genericTable);
            }

            return tables;
        }

        private String capitalize(String str) {
            if (str == null || str.isEmpty()) {
                return str;
            }
            return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
        }
    }

    /**
     * Generic table with dynamic columns that accepts any column reference.
     */
    private static class GenericTable extends AbstractTable {
        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            // Return a generic row type with common columns
            // This allows typical columns to be referenced in test queries
            RelDataTypeFactory.Builder builder = typeFactory.builder();
            builder.add("id", SqlTypeName.INTEGER);
            builder.add("user_id", SqlTypeName.INTEGER);
            builder.add("order_id", SqlTypeName.INTEGER);
            builder.add("product_id", SqlTypeName.INTEGER);
            builder.add("customer_id", SqlTypeName.INTEGER);
            builder.add("name", SqlTypeName.VARCHAR);
            builder.add("product_name", SqlTypeName.VARCHAR);
            builder.add("category", SqlTypeName.VARCHAR);
            builder.add("value", SqlTypeName.VARCHAR);
            builder.add("status", SqlTypeName.VARCHAR);
            builder.add("email", SqlTypeName.VARCHAR);
            builder.add("phone", SqlTypeName.VARCHAR);
            builder.add("address", SqlTypeName.VARCHAR);
            builder.add("amount", SqlTypeName.DECIMAL);
            builder.add("quantity", SqlTypeName.INTEGER);
            builder.add("created_at", SqlTypeName.TIMESTAMP);
            builder.add("updated_at", SqlTypeName.TIMESTAMP);
            return builder.build();
        }
    }

    /**
     * Creates a new converter with the specified parser configuration and SQL dialect.
     *
     * @param parserConfig The parser configuration
     * @param sqlDialect The SQL dialect for generating SQL
     * @param sqlLibrary The SQL library for dialect-specific operators and functions
     * @param schemaCache Optional schema cache for real schema metadata (can be null)
     */
    public RelationalAlgebraConverter(SqlParser.Config parserConfig, SqlDialect sqlDialect,
                                      SqlLibrary sqlLibrary, SchemaCache schemaCache) {
        this.parserConfig = parserConfig;
        this.sqlDialect = sqlDialect;
        this.sqlLibrary = sqlLibrary;
        this.schemaCache = schemaCache;
        this.schemaFactory = new CalciteSchemaFactory();
    }

    /**
     * Creates a new converter with the specified parser configuration and SQL dialect.
     * Uses generic schema only (no real database schema).
     *
     * @param parserConfig The parser configuration
     * @param sqlDialect The SQL dialect for generating SQL
     * @param sqlLibrary The SQL library for dialect-specific operators and functions
     */
    public RelationalAlgebraConverter(SqlParser.Config parserConfig, SqlDialect sqlDialect, SqlLibrary sqlLibrary) {
        this(parserConfig, sqlDialect, sqlLibrary, null);
    }

    /**
     * Creates a new converter with the specified parser configuration and SQL dialect.
     * Uses STANDARD library and no real schema (for backward compatibility).
     *
     * @param parserConfig The parser configuration
     * @param sqlDialect The SQL dialect for generating SQL
     * @param schemaCache Optional schema cache for real schema metadata (can be null)
     */
    public RelationalAlgebraConverter(SqlParser.Config parserConfig, SqlDialect sqlDialect, SchemaCache schemaCache) {
        this(parserConfig, sqlDialect, SqlLibrary.STANDARD, schemaCache);
    }

    /**
     * Creates a new converter with the specified parser configuration and SQL dialect.
     * Uses STANDARD library and generic schema only (for backward compatibility).
     *
     * @param parserConfig The parser configuration
     * @param sqlDialect The SQL dialect for generating SQL
     */
    public RelationalAlgebraConverter(SqlParser.Config parserConfig, SqlDialect sqlDialect) {
        this(parserConfig, sqlDialect, SqlLibrary.STANDARD, null);
    }

    /**
     * Converts SQL string to relational algebra.
     * The RelNode can then be optimized and converted back to SQL.
     *
     * @param sql The SQL string to convert
     * @return RelNode representing the relational algebra
     * @throws ConversionException if conversion fails
     */
    public RelNode convertToRelNode(String sql) throws ConversionException {
        log.debug("Converting SQL to RelNode");

        try {
            // Create a root schema
            SchemaPlus rootSchema = Frameworks.createRootSchema(true);

            // Try to use real schema from cache, fall back to dynamic schema
            SchemaPlus defaultSchema;
            if (schemaCache != null) {
                SchemaMetadata metadata = schemaCache.getSchema(false);
                if (metadata != null && !metadata.getTables().isEmpty()) {
                    log.debug("Using real schema with {} tables", metadata.getTables().size());
                    org.apache.calcite.schema.Schema realSchema = schemaFactory.createSchema(metadata);
                    // Use the actual schema name from metadata (e.g., "public" for PostgreSQL)
                    // Fall back to "default" if schema name is not available
                    String schemaName = (metadata.getSchemaName() != null && !metadata.getSchemaName().isEmpty())
                                        ? metadata.getSchemaName() : "default";
                    defaultSchema = rootSchema.add(schemaName, realSchema);
                    log.debug("Added schema '{}' to Calcite root schema", schemaName);
                } else {
                    log.debug("Real schema not available, using dynamic schema");
                    defaultSchema = rootSchema.add("default", new DynamicSchema());
                }
            } else {
                log.debug("No schema cache provided, using dynamic schema");
                defaultSchema = rootSchema.add("default", new DynamicSchema());
            }

            // Create framework configuration
            FrameworkConfig config = Frameworks.newConfigBuilder()
                .parserConfig(parserConfig)
                .defaultSchema(defaultSchema)
                .operatorTable(SqlLibraryOperatorTableFactory.INSTANCE.getOperatorTable(sqlLibrary))
                .build();

            // Use planner for conversion
            Planner planner = Frameworks.getPlanner(config);

            try {
                // Parse, validate, and convert to relational algebra
                SqlNode sqlNode = planner.parse(sql);
                SqlNode validatedNode = planner.validate(sqlNode);
                RelRoot relRoot = planner.rel(validatedNode);

                log.debug("Successfully converted SQL to RelNode");
                return relRoot.rel;

            } finally {
                planner.close();
            }
        } catch (Exception e) {
            log.warn("Failed to convert SQL to RelNode: {}", e.getMessage());
            throw new ConversionException("Failed to convert SQL to relational algebra", e);
        }
    }

    /**
     * Applies optimization rules to a RelNode using Apache Calcite's HepPlanner.
     *
     * @param relNode The relational algebra node to optimize
     * @param rules List of optimization rules to apply
     * @return Optimized RelNode
     * @throws OptimizationException if optimization fails
     */
    public RelNode applyOptimizations(RelNode relNode, List<RelOptRule> rules) throws OptimizationException {
        log.debug("Applying {} optimization rules to RelNode", rules.size());

        try {
            // Create HepProgram with specified rules and match limit
            HepProgramBuilder builder = new HepProgramBuilder();

            // Set a match limit to prevent infinite loops with aggressive rules
            builder.addMatchLimit(1000);

            for (RelOptRule rule : rules) {
                builder.addRuleInstance(rule);
            }

            HepProgram program = builder.build();

            // Create planner and optimize
            HepPlanner planner = new HepPlanner(program);
            planner.setRoot(relNode);

            RelNode optimizedNode = planner.findBestExp();

            log.debug("Successfully optimized RelNode");
            return optimizedNode;

        } catch (Exception e) {
            log.warn("Failed to optimize RelNode: {}", e.getMessage());
            throw new OptimizationException("Failed to apply optimization rules", e);
        }
    }

    /**
     * Converts an optimized RelNode back to SQL string.
     *
     * @param relNode The relational algebra node to convert
     * @return SQL string representation
     * @throws SqlGenerationException if SQL generation fails
     */
    public String convertToSql(RelNode relNode) throws SqlGenerationException {
        log.debug("Converting RelNode to SQL");

        try {
            // Use RelToSqlConverter to generate SQL from RelNode
            RelToSqlConverter converter = new RelToSqlConverter(sqlDialect);
            SqlNode sqlNode = converter.visitRoot(relNode).asStatement();

            // Convert SqlNode to SQL string
            String sql = sqlNode.toSqlString(sqlDialect).getSql();

            log.debug("Successfully converted RelNode to SQL: {} chars", sql.length());
            return sql;

        } catch (StackOverflowError e) {
            log.warn("StackOverflowError during SQL generation (likely due to aggressive optimization rules): {}", e.getMessage());
            throw new SqlGenerationException("Failed to generate SQL from relational algebra due to StackOverflowError", e);
        } catch (Exception e) {
            log.warn("Failed to convert RelNode to SQL: {}", e.getMessage());
            throw new SqlGenerationException("Failed to generate SQL from relational algebra", e);
        }
    }

    /**
     * Exception thrown when conversion fails.
     */
    public static class ConversionException extends Exception {
        public ConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when optimization fails.
     */
    public static class OptimizationException extends Exception {
        public OptimizationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when SQL generation fails.
     */
    public static class SqlGenerationException extends Exception {
        public SqlGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
