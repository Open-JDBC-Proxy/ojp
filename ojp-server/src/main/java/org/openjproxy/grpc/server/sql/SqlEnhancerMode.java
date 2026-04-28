package org.openjproxy.grpc.server.sql;

/**
 * SQL Enhancer operation mode.
 * Defines what operations the SQL enhancer performs on queries.
 */
public enum SqlEnhancerMode {
    /**
     * Only validate SQL syntax and semantics, don't modify queries.
     * Fastest mode, useful for catching SQL errors early.
     */
    VALIDATE(false, false),

    /**
     * Validate and optimize queries using rule-based transformations.
     * Recommended mode for production use.
     */
    OPTIMIZE(true, true),

    /**
     * Validate, optimize, and support dialect translation.
     * Most comprehensive mode but with higher overhead.
     */
    TRANSLATE(true, true),

    /**
     * Validate and extract metadata for analysis without modifying SQL.
     * Useful for query pattern analysis and monitoring.
     */
    ANALYZE(true, false);

    private final boolean conversionEnabled;
    private final boolean optimizationEnabled;

    SqlEnhancerMode(boolean conversionEnabled, boolean optimizationEnabled) {
        this.conversionEnabled = conversionEnabled;
        this.optimizationEnabled = optimizationEnabled;
    }

    /**
     * Whether SQL-to-RelNode conversion should be enabled for this mode.
     */
    public boolean isConversionEnabled() {
        return conversionEnabled;
    }

    /**
     * Whether query optimization should be enabled for this mode.
     */
    public boolean isOptimizationEnabled() {
        return optimizationEnabled;
    }

    /**
     * Parse mode from string, case-insensitive.
     * Defaults to VALIDATE if not found.
     */
    public static SqlEnhancerMode fromString(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return VALIDATE;
        }

        try {
            return valueOf(mode.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return VALIDATE;
        }
    }
}
