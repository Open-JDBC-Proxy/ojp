package org.openjproxy.interceptor;

/**
 * Defines the type of request being processed.
 * 
 * <p>Interceptors can filter themselves based on request type to only handle
 * relevant operations.</p>
 */
public enum RequestType {
    /** SQL query execution (SELECT) */
    QUERY,
    
    /** SQL update execution (INSERT, UPDATE, DELETE) */
    UPDATE,
    
    /** Batch operation */
    BATCH,
    
    /** Stored procedure call */
    CALLABLE,
    
    /** Transaction management (commit, rollback) */
    TRANSACTION,
    
    /** XA distributed transaction operation */
    XA_OPERATION,
    
    /** Connection management */
    CONNECTION,
    
    /** Result set fetch operation */
    RESULT_SET_FETCH,
    
    /** LOB (Large Object) operation */
    LOB_OPERATION
}
