package org.openjproxy.grpc.server.sql;

/**
 * Optimization execution mode for SQL enhancer.
 * Controls when and how optimization is performed on the first run.
 */
public enum OptimizationMode {
    /**
     * Optimization is disabled - validation only.
     * Fastest mode, useful for catching SQL errors early.
     */
    DISABLED,

    /**
     * Optimization runs synchronously on first execution.
     * Query execution blocks until optimization completes.
     * Recommended for predictable performance.
     */
    SYNC,

    /**
     * Optimization runs asynchronously on first execution.
     * Query executes with original SQL while optimization happens in background.
     * Subsequent executions use optimized SQL from cache.
     * Recommended for minimal query latency impact.
     */
    ASYNC;

    /**
     * Parse mode from string, case-insensitive.
     * Defaults to SYNC if not found.
     */
    public static OptimizationMode fromString(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return SYNC;
        }

        try {
            return valueOf(mode.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return SYNC;
        }
    }
}
