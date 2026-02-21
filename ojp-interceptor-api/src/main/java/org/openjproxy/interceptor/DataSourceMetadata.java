package org.openjproxy.interceptor;

import java.util.Map;

/**
 * Metadata about the target datasource.
 */
public interface DataSourceMetadata {
    
    /**
     * Returns the connection hash identifying this datasource.
     * 
     * @return the connection hash
     */
    String getConnectionHash();
    
    /**
     * Returns the database type (e.g., "postgresql", "mysql", "oracle").
     * 
     * @return the database type
     */
    String getDatabaseType();
    
    /**
     * Returns the JDBC URL of the datasource.
     * 
     * @return the JDBC URL
     */
    String getUrl();
    
    /**
     * Returns whether XA transactions are enabled for this datasource.
     * 
     * @return true if XA is enabled, false otherwise
     */
    boolean isXAEnabled();
    
    /**
     * Returns statistics about the connection pool.
     * 
     * @return map of statistics (e.g., activeConnections, idleConnections)
     */
    Map<String, Object> getPoolStatistics();
}
