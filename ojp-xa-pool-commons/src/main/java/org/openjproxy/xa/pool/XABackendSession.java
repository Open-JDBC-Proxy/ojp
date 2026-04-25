package org.openjproxy.xa.pool;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.SQLException;

/**
 * Represents a poolable backend database session that supports XA transactions.
 *
 * <p>This interface defines the lifecycle contract for objects pooled by Apache Commons Pool 2.
 * Implementations wrap an {@link XAConnection} and provide methods for health checking,
 * reset, and resource cleanup.</p>
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@code open()} - Called once when session is created</li>
 *   <li>{@code isHealthy()} - Called before borrowing from pool</li>
 *   <li>Session is used for XA operations</li>
 *   <li>{@code reset()} - Called when returning to pool (only if transaction complete)</li>
 *   <li>{@code close()} - Called when session is evicted from pool</li>
 * </ol>
 *
 * <p><strong>Important:</strong> {@code reset()} must NEVER be called on a session in
 * PREPARED state. Sessions in PREPARED state must be pinned until commit/rollback.</p>
 */
public interface XABackendSession extends AutoCloseable {

    /**
     * Opens and initializes the backend session.
     *
     * <p>This method is called once when the session is first created by the pool.
     * It should establish the physical connection and perform any initialization.</p>
     *
     * @throws SQLException if the session cannot be opened
     */
    void open() throws SQLException;

    /**
     * Closes and releases all resources associated with this session.
     *
     * <p>This method is called when the session is evicted from the pool or
     * during pool shutdown. It must:</p>
     * <ul>
     *   <li>Close the underlying XAConnection</li>
     *   <li>Release any other resources (statements, result sets)</li>
     *   <li>Be idempotent (safe to call multiple times)</li>
     * </ul>
     *
     * @throws Exception if an error occurs during closing
     */
    @Override
    void close() throws Exception;

    /**
     * Checks if the session is healthy and can be used.
     *
     * <p>This method is called before borrowing the session from the pool.
     * It should verify that:</p>
     * <ul>
     *   <li>The underlying connection is still valid</li>
     *   <li>The session can communicate with the database</li>
     *   <li>No unrecoverable errors have occurred</li>
     * </ul>
     *
     * <p>If this method returns false, the session will be invalidated and
     * destroyed by the pool.</p>
     *
     * @return true if the session is healthy and usable
     */
    boolean isHealthy();

    /**
     * Resets the session to a clean state for reuse by another transaction.
     *
     * <p>This method is called when returning the session to the pool AFTER
     * the transaction has completed (COMMITTED or ROLLEDBACK state only).
     * It must:</p>
     * <ul>
     *   <li>Rollback any uncommitted local transactions</li>
     *   <li>Reset session variables to defaults</li>
     *   <li>Clear temporary tables or artifacts</li>
     *   <li>Drain any pending result sets or statements</li>
     * </ul>
     *
     * <p><strong>CRITICAL INVARIANT:</strong> This method must NEVER be called
     * on a session that has a transaction in PREPARED state. Prepared transactions
     * must remain pinned until completion.</p>
     *
     * <p>If reset fails, the implementation should throw an exception, which will
     * cause the pool to invalidate and destroy the session rather than returning
     * it to the pool.</p>
     *
     * @throws SQLException if reset fails
     */
    void reset() throws SQLException;

    /**
     * Sanitizes the XA session after transaction completion.
     * <p>
     * This method prepares the session for the next transaction by refreshing
     * the logical connection to reset XA state. It is called after commit/rollback
     * while the session remains bound to the OJP Session (not returned to pool).
     * </p>
     * <p>
     * This is different from {@link #reset()} which is called when returning
     * the session to the pool. Sanitization happens BETWEEN transactions on the
     * same OJP Session, while reset happens AFTER the OJP Session terminates.
     * </p>
     * <p>
     * The method must:
     * <ul>
     *   <li>Close the current logical connection (which may be in ENDED XA state)</li>
     *   <li>Obtain a fresh logical connection from the XAConnection</li>
     *   <li>Reset the XA state to IDLE for the next transaction</li>
     * </ul>
     * </p>
     * <p>
     * <strong>CRITICAL:</strong> This method must ONLY be called after transaction
     * completion (COMMITTED or ROLLEDBACK state), never while in PREPARED state.
     * </p>
     *
     * @throws SQLException if sanitization fails
     */
    void sanitizeAfterTransaction() throws SQLException;

    /**
     * Gets the underlying XAConnection for this session.
     *
     * @return the XAConnection
     */
    XAConnection getXAConnection();

    /**
     * Gets the XAResource for performing XA operations.
     *
     * @return the XAResource
     */
    XAResource getXAResource();

    /**
     * Gets the standard JDBC Connection for executing SQL statements.
     *
     * @return the Connection
     */
    java.sql.Connection getConnection();

    /**
     * Gets a unique identifier for this session (for logging and diagnostics).
     *
     * @return a unique session identifier
     */
    String getSessionId();
}
