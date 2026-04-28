package org.openjproxy.grpc.server.sql;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * Result of SQL enhancement operation.
 * Contains the enhanced SQL and metadata about the enhancement process including
 * optimization metrics and applied transformation rules.
 */
@Getter
public class SqlEnhancementResult {

    private final String enhancedSql;
    private final boolean modified;
    private final boolean hasErrors;
    private final String errorMessage;

    // Optimization metadata
    private final boolean optimized;
    private final List<String> appliedRules;
    private final long optimizationTimeMs;

    private SqlEnhancementResult(String enhancedSql, boolean modified, boolean hasErrors, String errorMessage,
                                 boolean optimized, List<String> appliedRules, long optimizationTimeMs) {
        this.enhancedSql = enhancedSql;
        this.modified = modified;
        this.hasErrors = hasErrors;
        this.errorMessage = errorMessage;
        this.optimized = optimized;
        this.appliedRules = appliedRules != null ? appliedRules : Collections.emptyList();
        this.optimizationTimeMs = optimizationTimeMs;
    }

    /**
     * Creates a successful enhancement result.
     *
     * @param enhancedSql The enhanced SQL
     * @param modified Whether the SQL was modified
     * @return SqlEnhancementResult
     */
    public static SqlEnhancementResult success(String enhancedSql, boolean modified) {
        return new SqlEnhancementResult(enhancedSql, modified, false, null, false, null, 0);
    }

    /**
     * Creates a successful enhancement result with optimization metadata.
     * Phase 2: Used when optimization is applied.
     *
     * @param enhancedSql The enhanced/optimized SQL
     * @param modified Whether the SQL was modified
     * @param appliedRules List of rule names that were applied
     * @param optimizationTimeMs Time spent on optimization in milliseconds
     * @return SqlEnhancementResult
     */
    public static SqlEnhancementResult optimized(String enhancedSql, boolean modified,
                                                 List<String> appliedRules, long optimizationTimeMs) {
        return new SqlEnhancementResult(enhancedSql, modified, false, null,
                                       true, appliedRules, optimizationTimeMs);
    }

    /**
     * Creates a pass-through result (original SQL unchanged).
     *
     * @param originalSql The original SQL
     * @return SqlEnhancementResult
     */
    public static SqlEnhancementResult passthrough(String originalSql) {
        return new SqlEnhancementResult(originalSql, false, false, null, false, null, 0);
    }

    /**
     * Creates an error result.
     *
     * @param originalSql The original SQL
     * @param errorMessage The error message
     * @return SqlEnhancementResult
     */
    public static SqlEnhancementResult error(String originalSql, String errorMessage) {
        return new SqlEnhancementResult(originalSql, false, true, errorMessage, false, null, 0);
    }
}
