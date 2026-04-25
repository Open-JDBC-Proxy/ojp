package org.openjproxy.grpc.server;

/**
 * Specifies the type of database connection to allocate.
 * Used by SessionConnectionHelper to determine whether to create
 * a connection to the primary database or a read replica.
 */
public enum ConnectionType {
    /**
     * Connection to the primary database.
     * Used for write operations (INSERT, UPDATE, DELETE), transactions,
     * and reads within sticky session windows.
     */
    PRIMARY,
    
    /**
     * Connection to a read replica database.
     * Used for SELECT queries when read/write splitting is enabled
     * and conditions allow (not in transaction, not in sticky window).
     */
    READ_REPLICA
}
