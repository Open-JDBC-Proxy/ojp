package org.openjproxy.xa.pool.commons;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.commons.housekeeping.HousekeepingConfig;
import org.openjproxy.xa.pool.commons.housekeeping.HousekeepingListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.SQLException;

/**
 * Apache Commons Pool 2 factory for creating and managing {@link XABackendSession} instances.
 * <p>
 * This factory handles the complete lifecycle of pooled XA sessions:
 * </p>
 * <ul>
 *   <li><strong>makeObject</strong> - Creates new XAConnection and wraps it in BackendSessionImpl</li>
 *   <li><strong>validateObject</strong> - Checks session health via {@link XABackendSession#isHealthy()}</li>
 *   <li><strong>activateObject</strong> - Called when borrowing from pool (no-op for XA)</li>
 *   <li><strong>passivateObject</strong> - Called when returning to pool, invokes {@link XABackendSession#reset()}</li>
 *   <li><strong>destroyObject</strong> - Closes the session permanently</li>
 * </ul>
 *
 * <h3>Reset on Passivate:</h3>
 * <p>
 * When a session is returned to the pool, {@code passivateObject} calls {@link XABackendSession#reset()}
 * to clean up session state. If reset() fails, the object is marked as invalid and will be destroyed.
 * </p>
 *
 * <p><strong>Critical:</strong> This factory is only used for sessions that have completed their
 * XA transaction (COMMITTED or ROLLEDBACK). Sessions in PREPARED state are NOT returned to the
 * pool and therefore never reach passivateObject.</p>
 */
public class BackendSessionFactory implements PooledObjectFactory<XABackendSession> {
    private static final Logger log = LoggerFactory.getLogger(BackendSessionFactory.class);

    private final XADataSource xaDataSource;
    private final Integer defaultTransactionIsolation;
    private final HousekeepingConfig housekeepingConfig;
    private final HousekeepingListener housekeepingListener;

    /**
     * Creates a new backend session factory.
     *
     * @param xaDataSource the XA data source to create connections from
     */
    public BackendSessionFactory(XADataSource xaDataSource) {
        this(xaDataSource, null, null, null);
    }

    /**
     * Creates a new backend session factory with transaction isolation reset support.
     *
     * @param xaDataSource the XA data source to create connections from
     * @param defaultTransactionIsolation the default transaction isolation level to reset connections to, or null to not reset
     */
    public BackendSessionFactory(XADataSource xaDataSource, Integer defaultTransactionIsolation) {
        this(xaDataSource, defaultTransactionIsolation, null, null);
    }

    /**
     * Creates a new backend session factory with housekeeping support.
     *
     * @param xaDataSource the XA data source to create connections from
     * @param defaultTransactionIsolation the default transaction isolation level to reset connections to, or null to not reset
     * @param housekeepingConfig the housekeeping configuration, or null to disable housekeeping
     * @param housekeepingListener the listener for housekeeping events, or null if housekeeping is disabled
     */
    public BackendSessionFactory(XADataSource xaDataSource, Integer defaultTransactionIsolation,
                                   HousekeepingConfig housekeepingConfig, HousekeepingListener housekeepingListener) {
        if (xaDataSource == null) {
            throw new IllegalArgumentException("xaDataSource cannot be null");
        }
        this.xaDataSource = xaDataSource;
        this.defaultTransactionIsolation = defaultTransactionIsolation;
        this.housekeepingConfig = housekeepingConfig;
        this.housekeepingListener = housekeepingListener;
    }

    @Override
    public PooledObject<XABackendSession> makeObject() throws Exception {
        log.debug("Creating new backend session");

        try {
            // Create XAConnection from the vendor XADataSource
            XAConnection xaConnection = xaDataSource.getXAConnection();

            // Wrap in our XABackendSession implementation
            BackendSessionImpl session = new BackendSessionImpl(xaConnection, defaultTransactionIsolation);

            // Open the session (obtains Connection and XAResource)
            session.open();

            log.info("Backend session created successfully");

            return new DefaultPooledObject<>(session);

        } catch (SQLException e) {
            log.error("Failed to create backend session", e);
            throw e;
        }
    }

    @Override
    public void destroyObject(PooledObject<XABackendSession> p) throws Exception {
        XABackendSession session = p.getObject();

        log.debug("Destroying backend session");

        try {
            session.close();
            log.info("Backend session destroyed");
        } catch (Exception e) {
            log.error("Error destroying backend session", e);
            throw e;
        }
    }

    @Override
    public boolean validateObject(PooledObject<XABackendSession> p) {
        XABackendSession session = p.getObject();

        // Check basic health
        boolean isHealthy = session.isHealthy();

        if (!isHealthy) {
            log.warn("Backend session validation failed - health check failed");
            return false;
        }

        // Check max lifetime if enabled
        if (housekeepingConfig != null && housekeepingConfig.getMaxLifetimeMs() > 0) {
            if (session instanceof BackendSessionImpl) {
                BackendSessionImpl impl = (BackendSessionImpl) session;

                boolean isExpired = impl.isExpired(
                    housekeepingConfig.getMaxLifetimeMs(),
                    housekeepingConfig.getIdleBeforeRecycleMs()
                );

                if (isExpired) {
                    long ageMs = impl.getAge() / 1_000_000L;
                    log.info("Backend session expired after {}ms (max: {}ms, idle requirement: {}ms)",
                        ageMs, housekeepingConfig.getMaxLifetimeMs(), housekeepingConfig.getIdleBeforeRecycleMs());

                    // Notify listener
                    if (housekeepingListener != null) {
                        housekeepingListener.onConnectionExpired(session, ageMs);
                    }

                    return false; // Pool will destroy this session
                }
            }
        }

        return true;
    }

    @Override
    public void activateObject(PooledObject<XABackendSession> p) throws Exception {
        // No activation needed for XA sessions
        // The session is already open and ready to use
        log.debug("Backend session activated (no-op)");
    }

    @Override
    public void passivateObject(PooledObject<XABackendSession> p) throws Exception {
        XABackendSession session = p.getObject();

        log.debug("Passivating backend session (resetting state)");

        try {
            // Reset session to clean state before returning to pool
            // This is ONLY called after transaction completion (COMMITTED/ROLLEDBACK)
            session.reset();

            log.debug("Backend session passivated successfully");

        } catch (Exception e) {
            log.error("Failed to reset session during passivation", e);
            // Mark object as invalid - pool will destroy it instead of reusing
            throw e;
        }
    }
}
