package org.openjproxy.interceptor;

import com.openjproxy.grpc.SessionInfo;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;

/**
 * Context object that flows through the interceptor chain, containing all
 * information about the current request and its execution state.
 * 
 * <p>The context is mutable and can be modified by interceptors. Changes
 * made to the context will be visible to subsequent interceptors in the chain.</p>
 */
public interface RequestContext {
    
    /**
     * Returns the type of request being processed.
     * 
     * @return the request type
     */
    RequestType getRequestType();
    
    /**
     * Returns the current lifecycle phase.
     * 
     * @return the current phase
     */
    LifecyclePhase getCurrentPhase();
    
    /**
     * Sets the current lifecycle phase (internal use).
     * 
     * @param phase the phase to set
     */
    void setCurrentPhase(LifecyclePhase phase);
    
    /**
     * Returns the original SQL statement.
     * 
     * @return the original SQL
     */
    String getOriginalSql();
    
    /**
     * Returns the current SQL statement (may have been transformed by interceptors).
     * 
     * @return the current SQL
     */
    String getCurrentSql();
    
    /**
     * Sets the SQL statement (allows transformation).
     * 
     * @param sql the SQL to set
     */
    void setCurrentSql(String sql);
    
    /**
     * Returns the SQL statement hash for tracking.
     * 
     * @return the SQL hash
     */
    String getSqlHash();
    
    /**
     * Returns the session information.
     * 
     * @return the session info
     */
    SessionInfo getSessionInfo();
    
    /**
     * Returns the connection hash identifying the datasource.
     * 
     * @return the connection hash
     */
    String getConnectionHash();
    
    /**
     * Returns request parameters (if applicable).
     * 
     * @return optional map of parameters
     */
    Optional<Map<String, Object>> getParameters();
    
    /**
     * Returns the database connection (available during and after EXECUTION phase).
     * 
     * @return optional connection
     */
    Optional<Connection> getConnection();
    
    /**
     * Sets the database connection.
     * 
     * @param connection the connection to set
     */
    void setConnection(Connection connection);
    
    /**
     * Returns the execution result (available during POST_EXECUTION phase).
     * 
     * @return optional result
     */
    Optional<Object> getResult();
    
    /**
     * Sets the execution result.
     * 
     * @param result the result to set
     */
    void setResult(Object result);
    
    /**
     * Returns the result set (for queries, available during POST_EXECUTION phase).
     * 
     * @return optional result set
     */
    Optional<ResultSet> getResultSet();
    
    /**
     * Returns the exception if one occurred (available during EXCEPTION_HANDLING phase).
     * 
     * @return optional exception
     */
    Optional<Exception> getException();
    
    /**
     * Sets the exception (allows transformation).
     * 
     * @param exception the exception to set
     */
    void setException(Exception exception);
    
    /**
     * Returns execution start time in milliseconds.
     * 
     * @return the start time
     */
    long getStartTimeMillis();
    
    /**
     * Returns execution end time in milliseconds (available after execution).
     * 
     * @return optional end time
     */
    Optional<Long> getEndTimeMillis();
    
    /**
     * Sets the execution end time.
     * 
     * @param endTimeMillis the end time to set
     */
    void setEndTimeMillis(long endTimeMillis);
    
    /**
     * Gets a custom attribute by key.
     * Interceptors can use this to pass information between each other.
     * 
     * @param key the attribute key
     * @return the attribute value, or null if not found
     */
    Object getAttribute(String key);
    
    /**
     * Sets a custom attribute.
     * 
     * @param key the attribute key
     * @param value the attribute value
     */
    void setAttribute(String key, Object value);
    
    /**
     * Checks if the request has been short-circuited.
     * 
     * @return true if short-circuited, false otherwise
     */
    boolean isShortCircuited();
    
    /**
     * Marks the request as short-circuited (stops the chain).
     * 
     * @param shortCircuited true to short-circuit, false otherwise
     */
    void setShortCircuited(boolean shortCircuited);
    
    /**
     * Returns metadata about the target datasource.
     * 
     * @return the datasource metadata
     */
    DataSourceMetadata getDataSourceMetadata();
}
