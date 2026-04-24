package org.openjproxy.grpc.server;

/**
 * Enum representing the role of a connection in read/write splitting scenarios.
 * <p>
 * In a read/write splitting configuration, a session can maintain two types of connections:
 * <ul>
 *   <li>{@link #PRIMARY} - Connection to the primary (write-enabled) database</li>
 *   <li>{@link #REPLICA} - Connection to a read-only replica database</li>
 *   <li>{@link #NONE} - No connection has been allocated yet</li>
 * </ul>
 * </p>
 * <p>
 * The active connection role determines which physical connection is used for query execution.
 * Sessions can switch between roles based on the type of SQL operation (read vs. write),
 * transaction state, and sticky session policies.
 * </p>
 * 
 * @author OpenJProxy
 * @since 0.5.0
 */
public enum ConnectionRole {
    /**
     * No connection has been allocated to the session yet.
     * This is the initial state before any database operations.
     */
    NONE,
    
    /**
     * The connection is to the primary (write-enabled) database.
     * All write operations (INSERT, UPDATE, DELETE) and reads during
     * transactions or sticky sessions use this connection.
     */
    PRIMARY,
    
    /**
     * The connection is to a read-only replica database.
     * SELECT queries outside of transactions (when not in sticky mode)
     * can use this connection to offload read traffic from the primary.
     */
    REPLICA
}
