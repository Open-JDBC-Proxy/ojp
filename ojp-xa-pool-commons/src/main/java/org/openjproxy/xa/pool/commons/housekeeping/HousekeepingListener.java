package org.openjproxy.xa.pool.commons.housekeeping;

/**
 * Listener interface for housekeeping events.
 * <p>
 * Implementations receive callbacks when housekeeping tasks detect issues
 * or perform maintenance operations on the connection pool.
 * </p>
 *
 * <h3>Event Types:</h3>
 * <ul>
 *   <li><strong>Leak Detection:</strong> Connection held too long</li>
 *   <li><strong>Max Lifetime:</strong> Connection expired and recycled</li>
 *   <li><strong>Diagnostics:</strong> Periodic pool state information</li>
 *   <li><strong>Errors:</strong> Housekeeping task failures</li>
 * </ul>
 */
public interface HousekeepingListener {

    /**
     * Called when a connection leak is detected.
     * <p>
     * A leak occurs when a connection is borrowed and held longer than
     * the configured timeout without being returned to the pool.
     * </p>
     *
     * @param connection the leaked connection object
     * @param holdingThread the thread that borrowed the connection (may be null)
     * @param stackTrace the stack trace from when connection was borrowed (may be null if enhanced reporting disabled)
     */
    void onLeakDetected(Object connection, Thread holdingThread, StackTraceElement[] stackTrace);

    /**
     * Called when a connection is expired due to max lifetime enforcement.
     * <p>
     * This occurs during validation when a connection has exceeded its maximum
     * lifetime and has been idle for the required minimum time.
     * </p>
     *
     * @param connection the expired connection object
     * @param ageMs the age of the connection in milliseconds
     */
    void onConnectionExpired(Object connection, long ageMs);

    /**
     * Called when a connection is successfully recycled.
     * <p>
     * This is informational and indicates the connection was removed from
     * the pool and a new one may be created to replace it.
     * </p>
     *
     * @param connection the recycled connection object
     */
    void onConnectionRecycled(Object connection);

    /**
     * Called when a housekeeping task encounters an error.
     * <p>
     * This provides visibility into operational issues with the housekeeping
     * system itself.
     * </p>
     *
     * @param message a description of the error
     * @param cause the exception that caused the error (may be null)
     */
    void onHousekeepingError(String message, Throwable cause);

    /**
     * Called when diagnostics logging is triggered.
     * <p>
     * This provides a snapshot of the pool's current state including
     * active connections, idle connections, and other statistics.
     * </p>
     *
     * @param stateInfo the formatted pool state information
     */
    void onPoolStateLog(String stateInfo);
}
