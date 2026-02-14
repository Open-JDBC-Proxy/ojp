package org.openjproxy.grpc.server.sql;

import lombok.Getter;

/**
 * Result of SQL validation operation.
 * This is separate from SqlEnhancementResult to allow validation
 * to occur independently of optimization.
 */
@Getter
public class SqlValidationResult {
    
    private final String sql;
    private final boolean valid;
    private final String errorMessage;
    
    private SqlValidationResult(String sql, boolean valid, String errorMessage) {
        this.sql = sql;
        this.valid = valid;
        this.errorMessage = errorMessage;
    }
    
    /**
     * Creates a successful validation result.
     * 
     * @param sql The validated SQL
     * @return SqlValidationResult
     */
    public static SqlValidationResult success(String sql) {
        return new SqlValidationResult(sql, true, null);
    }
    
    /**
     * Creates a failed validation result.
     * 
     * @param sql The original SQL
     * @param errorMessage The validation error message
     * @return SqlValidationResult
     */
    public static SqlValidationResult failure(String sql, String errorMessage) {
        return new SqlValidationResult(sql, false, errorMessage);
    }
}
