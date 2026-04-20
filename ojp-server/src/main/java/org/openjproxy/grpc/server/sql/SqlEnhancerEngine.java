package org.openjproxy.grpc.server.sql;

import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL Enhancer Engine that uses Apache Calcite for SQL parsing, validation, and optimization.
 * 
 * Features:
 * - SQL syntax validation and parsing
 * - Query optimization using rule-based transformations
 * - SQL rewriting for improved performance
 * - Comprehensive caching for fast repeated queries
 * - Metrics tracking for monitoring
 */
@Slf4j
public class SqlEnhancerEngine {
    
    private final boolean enabled;
    private final SqlParser.Config parserConfig;
    private final ConcurrentHashMap<String, SqlEnhancementResult> cache;
    private final OjpSqlDialect dialect;
    private final org.apache.calcite.sql.SqlDialect calciteDialect;
    private final OjpSqlDialect targetDialect; // Target dialect for translation
    private final org.apache.calcite.sql.SqlDialect targetCalciteDialect; // Target Calcite dialect
    private final boolean translationEnabled; // Whether automatic translation is enabled
    private final RelationalAlgebraConverter converter;
    private final boolean conversionEnabled;
    
    // Optimization configuration
    private final boolean optimizationEnabled;
    private final OptimizationMode optimizationMode;
    private final OptimizationRuleRegistry ruleRegistry;
    private final List<String> enabledRules;
    
    // Failed optimization tracking - stores original SQL that failed optimization
    private final Set<String> failedOptimizations;
    
    // Async optimization executor
    private final ExecutorService optimizationExecutor;
    
    // Schema management
    private final SchemaCache schemaCache;
    private final SchemaLoader schemaLoader;
    private final javax.sql.DataSource dataSource;
    private final String catalogName;
    private final String schemaName;
    private final long schemaRefreshIntervalMillis;
    
    // Metrics tracking - using AtomicLong for thread-safe updates without synchronization
    private final AtomicLong totalQueriesProcessed = new AtomicLong(0);
    private final AtomicLong totalQueriesValidated = new AtomicLong(0);
    private final AtomicLong totalQueriesOptimized = new AtomicLong(0);
    private final AtomicLong totalOptimizationTimeMs = new AtomicLong(0);
    private final AtomicLong totalQueriesModified = new AtomicLong(0);
    private final AtomicLong totalOptimizationFailures = new AtomicLong(0);
    
    
    /**
     * Creates a new SqlEnhancerEngine with full configuration options including schema refresh and optimization mode.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use (source dialect)
     * @param targetDialectName The target SQL dialect for translation (empty = no translation)
     * @param conversionEnabled Whether to enable SQL-to-RelNode conversion
     * @param optimizationEnabled Whether to enable query optimization
     * @param optimizationMode The optimization execution mode (SYNC, ASYNC, DISABLED)
     * @param enabledRules List of rule names to enable (null = use safe rules)
     * @param schemaCache Optional schema cache for real schema metadata (can be null)
     * @param schemaLoader Optional schema loader for periodic refresh (can be null)
     * @param dataSource Optional data source for schema refresh (can be null)
     * @param catalogName Catalog name for schema refresh (can be null)
     * @param schemaName Schema name for schema refresh (can be null)
     * @param schemaRefreshIntervalHours Hours between schema refreshes (0 = disabled)
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, String targetDialectName, boolean conversionEnabled,
                             boolean optimizationEnabled, OptimizationMode optimizationMode, List<String> enabledRules,
                             SchemaCache schemaCache, SchemaLoader schemaLoader,
                             javax.sql.DataSource dataSource, String catalogName, String schemaName,
                             long schemaRefreshIntervalHours) {
        this.enabled = enabled;
        this.conversionEnabled = conversionEnabled;
        this.optimizationEnabled = optimizationEnabled;
        this.optimizationMode = optimizationMode != null ? optimizationMode : OptimizationMode.SYNC;
        this.cache = new ConcurrentHashMap<>();
        this.failedOptimizations = ConcurrentHashMap.newKeySet();
        this.dialect = OjpSqlDialect.fromString(dialectName);
        this.calciteDialect = dialect.getCalciteDialect();
        
        // Configure target dialect for translation
        if (targetDialectName != null && !targetDialectName.trim().isEmpty()) {
            this.targetDialect = OjpSqlDialect.fromString(targetDialectName);
            this.targetCalciteDialect = targetDialect.getCalciteDialect();
            this.translationEnabled = true;
        } else {
            this.targetDialect = null;
            this.targetCalciteDialect = null;
            this.translationEnabled = false;
        }
        
        this.schemaCache = schemaCache;
        this.schemaLoader = schemaLoader;
        this.dataSource = dataSource;
        this.catalogName = catalogName;
        this.schemaName = schemaName;
        this.schemaRefreshIntervalMillis = schemaRefreshIntervalHours * 60 * 60 * 1000; // Convert hours to milliseconds
        
        // Configure parser with dialect-specific settings
        SqlParser.Config baseConfig = SqlParser.config();
        
        // Configure conformance based on dialect
        SqlConformanceEnum conformance = getConformanceForDialect(this.dialect);
        
        this.parserConfig = baseConfig
            .withConformance(conformance)
            .withCaseSensitive(false); // Most SQL is case-insensitive
        
        // Initialize converter if conversion is enabled
        // Pass SqlDialect for SQL generation, SqlLibrary for operators, and SchemaCache for real schema
        this.converter = conversionEnabled ? 
            new RelationalAlgebraConverter(parserConfig, calciteDialect, dialect.getSqlLibrary(), schemaCache) : null;
        
        // Initialize optimization components
        this.ruleRegistry = new OptimizationRuleRegistry();
        this.enabledRules = enabledRules != null ? enabledRules : 
            Arrays.asList(
                // Basic expression optimization
                "FILTER_REDUCE", "PROJECT_REDUCE", "FILTER_MERGE", "PROJECT_MERGE", "PROJECT_REMOVE",
                // Subquery optimization - removes correlated subqueries by converting to JOINs
                "SUB_QUERY_REMOVE", "SUB_QUERY_REMOVE_FILTER", "SUB_QUERY_REMOVE_JOIN",
                "PROJECT_SUB_QUERY_TO_CORRELATE", "FILTER_SUB_QUERY_TO_CORRELATE", "JOIN_SUB_QUERY_TO_CORRELATE"
            );
        
        // Initialize async executor if needed
        if (optimizationEnabled && this.optimizationMode == OptimizationMode.ASYNC) {
            // Use 2 threads: one for immediate processing and one for queued work
            // This balances responsiveness with resource usage
            this.optimizationExecutor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "sql-optimizer");
                t.setDaemon(true);
                return t;
            });
        } else {
            this.optimizationExecutor = null;
        }
        
        if (enabled) {
            String conversionStatus = conversionEnabled ? " with relational algebra conversion" : "";
            String optimizationStatus = optimizationEnabled ? 
                " and optimization (" + this.optimizationMode + " mode)" : "";
            String schemaStatus = schemaCache != null ? " and real schema support" : "";
            String refreshStatus = (schemaLoader != null && dataSource != null && schemaRefreshIntervalHours > 0) ? 
                " with periodic refresh" : "";
            String translationStatus = translationEnabled ? " and automatic translation to " + targetDialect : "";
            log.info("SQL Enhancer Engine initialized and enabled with dialect: {}{}{}{}{}{}", 
                    dialectName, conversionStatus, optimizationStatus, schemaStatus, refreshStatus, translationStatus);
        } else {
            log.info("SQL Enhancer Engine initialized but disabled");
        }
    }
    
    /**
     * Creates a new SqlEnhancerEngine with full configuration options including schema refresh.
     * Uses SYNC optimization mode by default for backward compatibility.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use (source dialect)
     * @param targetDialectName The target SQL dialect for translation (empty = no translation)
     * @param conversionEnabled Whether to enable SQL-to-RelNode conversion
     * @param optimizationEnabled Whether to enable query optimization
     * @param enabledRules List of rule names to enable (null = use safe rules)
     * @param schemaCache Optional schema cache for real schema metadata (can be null)
     * @param schemaLoader Optional schema loader for periodic refresh (can be null)
     * @param dataSource Optional data source for schema refresh (can be null)
     * @param catalogName Catalog name for schema refresh (can be null)
     * @param schemaName Schema name for schema refresh (can be null)
     * @param schemaRefreshIntervalHours Hours between schema refreshes (0 = disabled)
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, String targetDialectName, boolean conversionEnabled,
                             boolean optimizationEnabled, List<String> enabledRules,
                             SchemaCache schemaCache, SchemaLoader schemaLoader,
                             javax.sql.DataSource dataSource, String catalogName, String schemaName,
                             long schemaRefreshIntervalHours) {
        this(enabled, dialectName, targetDialectName, conversionEnabled, optimizationEnabled, 
             OptimizationMode.SYNC, enabledRules, schemaCache, schemaLoader, dataSource, 
             catalogName, schemaName, schemaRefreshIntervalHours);
    }
    
    /**
     * Creates a new SqlEnhancerEngine with full configuration options (without schema refresh).
     * Uses SYNC optimization mode by default for backward compatibility.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     * @param targetDialectName The target SQL dialect for translation (empty = no translation)
     * @param conversionEnabled Whether to enable SQL-to-RelNode conversion
     * @param optimizationEnabled Whether to enable query optimization
     * @param enabledRules List of rule names to enable (null = use safe rules)
     * @param schemaCache Optional schema cache for real schema metadata (can be null)
     * @param schemaRefreshIntervalHours Hours between schema refreshes (0 = disabled)
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, String targetDialectName, boolean conversionEnabled,
                             boolean optimizationEnabled, List<String> enabledRules,
                             SchemaCache schemaCache, long schemaRefreshIntervalHours) {
        this(enabled, dialectName, targetDialectName, conversionEnabled, optimizationEnabled, 
             OptimizationMode.SYNC, enabledRules, schemaCache, null, null, null, null, schemaRefreshIntervalHours);
    }
    
    /**
     * Creates a new SqlEnhancerEngine with full configuration options (no schema cache).
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     * @param targetDialectName The target SQL dialect for translation (empty = no translation)
     * @param conversionEnabled Whether to enable SQL-to-RelNode conversion
     * @param optimizationEnabled Whether to enable query optimization
     * @param enabledRules List of rule names to enable (null = use safe rules)
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, String targetDialectName, boolean conversionEnabled,
                             boolean optimizationEnabled, List<String> enabledRules) {
        this(enabled, dialectName, targetDialectName, conversionEnabled, optimizationEnabled, enabledRules, null, 0);
    }
    
    /**
     * Creates a new SqlEnhancerEngine with full configuration options (no target dialect).
     * Legacy constructor for backward compatibility.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     * @param conversionEnabled Whether to enable SQL-to-RelNode conversion
     * @param optimizationEnabled Whether to enable query optimization
     * @param enabledRules List of rule names to enable (null = use safe rules)
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, boolean conversionEnabled,
                             boolean optimizationEnabled, List<String> enabledRules) {
        this(enabled, dialectName, "", conversionEnabled, optimizationEnabled, enabledRules, null, 0);
    }
    
    /**
     * Creates a new SqlEnhancerEngine with conversion enabled.
     * Optimization is disabled by default for backward compatibility.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     * @param conversionEnabled Whether to enable SQL-to-RelNode conversion
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName, boolean conversionEnabled) {
        this(enabled, dialectName, conversionEnabled, false, null);
    }
    
    /**
     * Creates a new SqlEnhancerEngine with the given enabled status and dialect.
     * Conversion is disabled by default for backward compatibility.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     * @param dialectName The SQL dialect to use
     */
    public SqlEnhancerEngine(boolean enabled, String dialectName) {
        this(enabled, dialectName, false);
    }
    
    /**
     * Creates a new SqlEnhancerEngine with default GENERIC dialect.
     * 
     * @param enabled Whether the SQL enhancer is enabled
     */
    public SqlEnhancerEngine(boolean enabled) {
        this(enabled, "GENERIC");
    }
    
    /**
     * Get SQL conformance based on dialect.
     */
    private SqlConformanceEnum getConformanceForDialect(OjpSqlDialect dialect) {
        switch (dialect) {
            case POSTGRESQL:
                return SqlConformanceEnum.LENIENT;
            case MYSQL:
                return SqlConformanceEnum.MYSQL_5;
            case ORACLE:
                return SqlConformanceEnum.ORACLE_12;
            case SQL_SERVER:
                return SqlConformanceEnum.SQL_SERVER_2008;
            default:
                return SqlConformanceEnum.DEFAULT;
        }
    }
    
    /**
     * Checks if the SQL enhancer is enabled.
     * 
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Gets the configured SQL dialect.
     * 
     * @return the SQL dialect
     */
    public OjpSqlDialect getDialect() {
        return dialect;
    }
    
    /**
     * Gets cache statistics.
     * 
     * @return String describing cache statistics
     */
    public String getCacheStats() {
        return String.format("Cache size: %d (no max limit, dynamically expands)", cache.size());
    }
    
    /**
     * Gets optimization statistics.
     * 
     * @return String describing optimization statistics
     */
    public String getOptimizationStats() {
        if (!optimizationEnabled) {
            return "Optimization disabled";
        }
        
        long processed = totalQueriesProcessed.get();
        long validated = totalQueriesValidated.get();
        long optimized = totalQueriesOptimized.get();
        long optimizationTime = totalOptimizationTimeMs.get();
        long modified = totalQueriesModified.get();
        long failures = totalOptimizationFailures.get();
        
        long avgOptimizationTime = optimized > 0 ? optimizationTime / optimized : 0;
        
        double optimizationRate = processed > 0 ? 
            (100.0 * optimized / processed) : 0.0;
        
        double modificationRate = optimized > 0 ? 
            (100.0 * modified / optimized) : 0.0;
        
        double failureRate = (optimized + failures) > 0 ?
            (100.0 * failures / (optimized + failures)) : 0.0;
        
        return String.format(
            "Optimization Stats: Processed=%d, Validated=%d, Optimized=%d (%.1f%%), Modified=%d (%.1f%%), Failed=%d (%.1f%%), AvgTime=%dms, Mode=%s",
            processed, validated, optimized, optimizationRate,
            modified, modificationRate, failures, failureRate, avgOptimizationTime, optimizationMode
        );
    }
    
    /**
     * Clears the cache.
     */
    public void clearCache() {
        cache.clear();
        failedOptimizations.clear();
        log.info("SQL enhancer cache cleared");
    }
    
    /**
     * Validates SQL syntax and semantics without optimization.
     * This method always runs synchronously and returns immediately after validation.
     * 
     * @param sql The SQL statement to validate
     * @return SqlValidationResult containing validation status
     */
    public SqlValidationResult validate(String sql) {
        if (!enabled) {
            // If disabled, pass through without validation
            return SqlValidationResult.success(sql);
        }
        
        totalQueriesValidated.incrementAndGet();
        
        try {
            // Parse and validate SQL with dialect-specific configuration
            SqlParser parser = SqlParser.create(sql, parserConfig);
            SqlNode sqlNode = parser.parseQuery();
            
            // Log successful parse
            log.debug("Successfully validated SQL with {} dialect: {}", 
                     dialect, sql.substring(0, Math.min(sql.length(), 100)));
            
            return SqlValidationResult.success(sql);
            
        } catch (SqlParseException e) {
            // Log validation error
            log.debug("SQL validation error with {} dialect: {} for SQL: {}", 
                     dialect, e.getMessage(), sql.substring(0, Math.min(sql.length(), 100)));
            
            return SqlValidationResult.failure(sql, e.getMessage());
        } catch (Exception e) {
            // Catch any unexpected errors
            log.warn("Unexpected error during SQL validation with {} dialect: {}", dialect, e.getMessage(), e);
            
            return SqlValidationResult.failure(sql, e.getMessage());
        }
    }
    
    /**
     * Ensures schema is loaded from the provided DataSource.
     * This is called before enhance() to populate the schema cache on-demand.
     * Thread-safe and idempotent - multiple calls will only load once.
     * 
     * @param dataSource DataSource to load schema from
     * @param catalogName Catalog name (may be null)
     * @param schemaName Schema name (may be null)
     */
    public void ensureSchemaLoaded(javax.sql.DataSource dataSource, String catalogName, String schemaName) {
        // Only load if we have a schema cache and loader
        if (schemaCache == null || schemaLoader == null || dataSource == null) {
            return;
        }
        
        // Check if schema is already loaded
        if (schemaCache.getSchema(false) != null) {
            log.debug("Schema already loaded in cache");
            return;
        }
        
        // Try to acquire lock for loading
        if (!schemaCache.tryAcquireRefreshLock()) {
            log.debug("Schema load already in progress by another thread");
            return;
        }
        
        try {
            // Double-check after acquiring lock
            if (schemaCache.getSchema(false) != null) {
                log.debug("Schema loaded by another thread while waiting for lock");
                return;
            }
            
            log.info("Loading schema metadata from DataSource (catalog: {}, schema: {})", 
                     catalogName, schemaName);
            
            // Load schema synchronously
            try (java.sql.Connection connection = dataSource.getConnection()) {
                SchemaMetadata schema = schemaLoader.loadSchema(connection, catalogName, schemaName);
                schemaCache.updateSchema(schema);
                log.info("Successfully loaded schema with {} tables", schema.getTables().size());
            } catch (java.sql.SQLException e) {
                log.error("Failed to load schema metadata", e);
                // Don't throw - allow query to proceed without schema (will fall back to generic schema)
            }
            
        } finally {
            schemaCache.releaseRefreshLock();
        }
    }
    
    /**
     * Parses, validates, and optionally optimizes SQL.
     * 
     * Validation always happens synchronously. Optimization behavior depends on the optimization mode:
     * - DISABLED: No optimization, validation only
     * - SYNC: Optimization happens synchronously, query blocks until complete
     * - ASYNC: First run returns original SQL and optimizes in background, subsequent runs use cached optimized SQL
     * 
     * @param sql The SQL statement to enhance
     * @return SqlEnhancementResult containing the result
     */
    public SqlEnhancementResult enhance(String sql) {
        if (!enabled) {
            // If disabled, just return the original SQL
            return SqlEnhancementResult.passthrough(sql);
        }
        
        // Check cache first - no synchronization needed for ConcurrentHashMap.get()
        SqlEnhancementResult cached = cache.get(sql);
        if (cached != null) {
            log.debug("Cache hit for SQL (dialect: {}): {}", dialect, sql.substring(0, Math.min(sql.length(), 50)));
            return cached;
        }
        
        // Track metrics
        totalQueriesProcessed.incrementAndGet();
        
        long startTime = System.nanoTime();
        SqlEnhancementResult result;
        
        try {
            // Step 1: Always validate SQL synchronously
            SqlParser parser = SqlParser.create(sql, parserConfig);
            SqlNode sqlNode = parser.parseQuery();
            
            // Log successful parse
            log.debug("Successfully parsed and validated SQL with {} dialect: {}", 
                     dialect, sql.substring(0, Math.min(sql.length(), 100)));
            
            // Step 2: Handle optimization based on mode
            if (conversionEnabled && converter != null && optimizationEnabled) {
                
                if (optimizationMode == OptimizationMode.DISABLED) {
                    // Optimization disabled, return validated original SQL
                    result = SqlEnhancementResult.success(sql, false);
                    
                } else if (optimizationMode == OptimizationMode.SYNC) {
                    // Synchronous optimization - block until complete
                    result = performOptimization(sql);
                    
                } else if (optimizationMode == OptimizationMode.ASYNC) {
                    // Asynchronous optimization - return original SQL immediately and optimize in background
                    result = SqlEnhancementResult.success(sql, false);
                    
                    // Trigger async optimization
                    String sqlToOptimize = sql; // Capture for lambda
                    CompletableFuture.runAsync(() -> {
                        try {
                            log.debug("Starting async optimization for SQL: {}", 
                                     sqlToOptimize.substring(0, Math.min(sqlToOptimize.length(), 50)));
                            SqlEnhancementResult optimizedResult = performOptimization(sqlToOptimize);
                            
                            // Cache the optimized result for future use
                            cache.put(sqlToOptimize, optimizedResult);
                            log.debug("Async optimization complete and cached for SQL: {}", 
                                     sqlToOptimize.substring(0, Math.min(sqlToOptimize.length(), 50)));
                        } catch (Exception e) {
                            log.warn("Async optimization failed: {}", e.getMessage());
                            // Mark as failed so we don't retry
                            failedOptimizations.add(sqlToOptimize);
                        }
                    }, optimizationExecutor);
                } else {
                    // Default fallback
                    result = SqlEnhancementResult.success(sql, false);
                }
            } else {
                // Conversion or optimization not enabled, return validated original SQL
                result = SqlEnhancementResult.success(sql, false);
            }
            
        } catch (SqlParseException e) {
            // Log parse errors with dialect info
            log.info("SQL parse error with {} dialect: {} for SQL: {}",
                     dialect, e.getMessage(), sql.substring(0, Math.min(sql.length(), 100)), e);
            
            // On parse error, return original SQL (pass-through mode)
            result = SqlEnhancementResult.passthrough(sql);
        } catch (Exception e) {
            // Catch any unexpected errors
            log.warn("Unexpected error in SQL enhancer with {} dialect: {}", dialect, e.getMessage(), e);
            
            // Fall back to pass-through mode
            result = SqlEnhancementResult.passthrough(sql);
        }
        
        // Apply automatic dialect translation if enabled
        if (translationEnabled && !result.isHasErrors()) {
            try {
                String sqlToTranslate = result.getEnhancedSql();
                String translatedSql = applyTranslation(sqlToTranslate);
                
                // Check if translation actually changed the SQL
                boolean wasTranslated = !sqlToTranslate.equals(translatedSql);
                if (wasTranslated) {
                    // Create new result with translated SQL, preserving optimization metadata
                    if (result.isOptimized()) {
                        result = SqlEnhancementResult.optimized(translatedSql, true, 
                                                               result.getAppliedRules(), 
                                                               result.getOptimizationTimeMs());
                    } else {
                        result = SqlEnhancementResult.success(translatedSql, true);
                    }
                    log.info("SQL automatically translated from {} to {}: {} chars -> {} chars", 
                            dialect, targetDialect, sqlToTranslate.length(), translatedSql.length());
                }
            } catch (Exception e) {
                log.warn("Automatic translation failed from {} to {}: {}, using untranslated SQL", 
                        dialect, targetDialect, e.getMessage());
                // Keep the untranslated result
            }
        }
        
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1_000_000L;
        
        // Log performance if it took significant time
        if (duration > 50) {
            log.debug("SQL enhancement took {}ms for SQL: {}", duration, sql.substring(0, Math.min(sql.length(), 50)));
        }
        
        // Cache the result - ConcurrentHashMap.put() is thread-safe without explicit synchronization
        // If two threads cache the same SQL simultaneously, the last one wins (acceptable - same result)
        cache.put(sql, result);
        
        // Check if schema refresh is needed (after enhancement to minimize overhead)
        triggerSchemaRefreshIfNeeded();
        
        return result;
    }
    
    /**
     * Applies dialect translation to SQL.
     * Internal method used by automatic translation.
     * 
     * @param sql The SQL to translate
     * @return Translated SQL
     * @throws Exception if translation fails
     */
    private String applyTranslation(String sql) throws Exception {
        // Parse with current dialect
        SqlParser parser = SqlParser.create(sql, parserConfig);
        SqlNode sqlNode = parser.parseQuery();
        
        // Convert to target dialect
        String translated = sqlNode.toSqlString(targetCalciteDialect).getSql();
        
        return translated;
    }
    
    /**
     * Translates SQL from one dialect to another.
     * Phase 3: Dialect translation support.
     * 
     * @param sql The SQL to translate
     * @param targetDialect The target SQL dialect
     * @return Translated SQL or original if translation fails
     */
    public String translateDialect(String sql, OjpSqlDialect targetDialect) {
        if (!enabled) {
            return sql;
        }
        
        try {
            // Parse with current dialect
            SqlParser parser = SqlParser.create(sql, parserConfig);
            SqlNode sqlNode = parser.parseQuery();
            
            // Convert to target dialect
            org.apache.calcite.sql.SqlDialect targetCalciteDialect = targetDialect.getCalciteDialect();
            String translated = sqlNode.toSqlString(targetCalciteDialect).getSql();
            
            log.debug("Translated SQL from {} to {}: {} chars -> {} chars", 
                     this.dialect, targetDialect, sql.length(), translated.length());
            
            return translated;
        } catch (Exception e) {
            log.warn("Failed to translate SQL from {} to {}: {}", 
                    this.dialect, targetDialect, e.getMessage());
            return sql; // Return original on error
        }
    }
    
    /**
     * Performs SQL optimization synchronously.
     * This is an internal helper method that performs the actual optimization work.
     * 
     * @param sql The original SQL to optimize
     * @return SqlEnhancementResult with optimization result
     */
    private SqlEnhancementResult performOptimization(String sql) {
        // Check if this SQL previously failed optimization
        if (failedOptimizations.contains(sql)) {
            log.debug("Skipping optimization for previously failed SQL: {}", 
                     sql.substring(0, Math.min(sql.length(), 50)));
            return SqlEnhancementResult.success(sql, false);
        }
        
        long optimizationStartTime = System.nanoTime();
        
        try {
            // Convert SQL → RelNode
            RelNode relNode = converter.convertToRelNode(sql);
            log.debug("Successfully converted SQL to RelNode");
            
            // Get optimization rules
            List<RelOptRule> rules = ruleRegistry.getRulesByNames(enabledRules);
            log.debug("Applying {} optimization rules", rules.size());
            
            // Apply optimizations
            RelNode optimizedNode = converter.applyOptimizations(relNode, rules);
            
            // Generate SQL from optimized RelNode
            try {
                String optimizedSql = converter.convertToSql(optimizedNode);
                
                long optimizationEndTime = System.nanoTime();
                long optimizationTime = (optimizationEndTime - optimizationStartTime) / 1_000_000L;
                
                // Check if SQL was actually modified
                boolean wasModified = !sql.trim().equalsIgnoreCase(optimizedSql.trim());
                
                // Track metrics
                totalQueriesOptimized.incrementAndGet();
                totalOptimizationTimeMs.addAndGet(optimizationTime);
                if (wasModified) {
                    totalQueriesModified.incrementAndGet();
                }
                
                if (wasModified) {
                    log.info("SQL optimized with {} rules in {}ms. Original length: {}, Optimized length: {}", 
                            rules.size(), optimizationTime, sql.length(), optimizedSql.length());
                    log.debug("Original SQL: {}", sql.substring(0, Math.min(sql.length(), 200)));
                    log.debug("Optimized SQL: {}", optimizedSql.substring(0, Math.min(optimizedSql.length(), 200)));
                }
                
                return SqlEnhancementResult.optimized(optimizedSql, wasModified, 
                                                     enabledRules, optimizationTime);
                
            } catch (RelationalAlgebraConverter.SqlGenerationException e) {
                log.debug("SQL generation failed, marking as failed optimization: {}", e.getMessage());
                long optimizationTime = (System.nanoTime() - optimizationStartTime) / 1_000_000L;
                totalOptimizationFailures.incrementAndGet();
                failedOptimizations.add(sql);
                // Return original SQL with optimization metadata even though generation failed
                return SqlEnhancementResult.optimized(sql, false, enabledRules, optimizationTime);
            }
            
        } catch (RelationalAlgebraConverter.ConversionException | RelationalAlgebraConverter.OptimizationException e) {
            log.debug("Optimization failed, marking as failed: {}", e.getMessage());
            totalOptimizationFailures.incrementAndGet();
            failedOptimizations.add(sql);
            return SqlEnhancementResult.success(sql, false);
        } catch (Exception e) {
            log.warn("Unexpected error during optimization, marking as failed: {}", e.getMessage());
            totalOptimizationFailures.incrementAndGet();
            failedOptimizations.add(sql);
            return SqlEnhancementResult.success(sql, false);
        }
    }
    
    /**
     * Triggers an asynchronous schema refresh if needed.
     * Checks if refresh interval has passed and refresh is not already in progress.
     */
    private void triggerSchemaRefreshIfNeeded() {
        // Only refresh if all required components are available
        if (schemaCache == null || schemaLoader == null || dataSource == null) {
            return;
        }
        
        // Check if refresh is needed and not already in progress
        if (schemaCache.needsRefresh(schemaRefreshIntervalMillis)) {
            if (schemaCache.tryAcquireRefreshLock()) {
                try {
                    log.debug("Triggering async schema refresh");
                    // Trigger async refresh
                    schemaLoader.loadSchemaAsync(dataSource, catalogName, schemaName)
                        .thenAccept(schema -> {
                            schemaCache.updateSchema(schema);
                            log.info("Schema refreshed successfully with {} tables", schema.getTables().size());
                        })
                        .exceptionally(ex -> {
                            log.warn("Schema refresh failed: {}", ex.getMessage());
                            return null;
                        })
                        .whenComplete((result, ex) -> schemaCache.releaseRefreshLock());
                } catch (Exception e) {
                    schemaCache.releaseRefreshLock();
                    log.warn("Failed to start schema refresh: {}", e.getMessage());
                }
            }
        }
    }
    
    /**
     * Shuts down the async optimization executor if it exists.
     * This method should be called when the SQL enhancer is no longer needed
     * to ensure proper cleanup of background threads.
     * 
     * The method will:
     * 1. Initiate an orderly shutdown
     * 2. Wait up to 5 seconds for existing tasks to complete
     * 3. Force shutdown if tasks don't complete in time
     */
    public void shutdown() {
        if (optimizationExecutor != null) {
            log.info("Shutting down SQL optimization executor");
            optimizationExecutor.shutdown();
            try {
                // Wait up to 5 seconds for existing tasks to complete
                if (!optimizationExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("SQL optimization executor did not terminate in time, forcing shutdown");
                    optimizationExecutor.shutdownNow();
                    // Wait a bit more for tasks to respond to interruption
                    if (!optimizationExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        log.error("SQL optimization executor did not terminate after forced shutdown");
                    }
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for SQL optimization executor to terminate");
                optimizationExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
