package org.openjproxy.xa.pool.commons.housekeeping;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.openjproxy.xa.pool.XABackendSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic task that logs pool diagnostics information.
 * <p>
 * This task provides visibility into the pool state by logging metrics such as:
 * <ul>
 *   <li>Active sessions (currently borrowed)</li>
 *   <li>Idle sessions (available in pool)</li>
 *   <li>Waiting threads (blocked on borrow)</li>
 *   <li>Total created/destroyed sessions</li>
 *   <li>Configuration limits</li>
 * </ul>
 * </p>
 */
public class DiagnosticsTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticsTask.class);

    private final GenericObjectPool<XABackendSession> pool;
    private final HousekeepingListener listener;

    /**
     * Creates a new diagnostics task.
     *
     * @param pool the pool to monitor
     * @param listener the listener to notify with diagnostics
     */
    public DiagnosticsTask(GenericObjectPool<XABackendSession> pool, HousekeepingListener listener) {
        this.pool = pool;
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            // Collect pool statistics
            int active = pool.getNumActive();
            int idle = pool.getNumIdle();
            int waiters = pool.getNumWaiters();
            int maxTotal = pool.getMaxTotal();
            int minIdle = pool.getMinIdle();
            int maxIdle = pool.getMaxIdle();
            long created = pool.getCreatedCount();
            long destroyed = pool.getDestroyedCount();
            long borrowed = pool.getBorrowedCount();
            long returned = pool.getReturnedCount();

            // Calculate derived metrics
            int total = active + idle;
            double utilizationPercent = maxTotal > 0 ? (active * 100.0 / maxTotal) : 0.0;

            // Create diagnostics message
            String diagnostics = String.format(
                "Pool State: active=%d, idle=%d, waiters=%d, total=%d/%d (%.1f%% utilized), " +
                "minIdle=%d, maxIdle=%d, lifetime: created=%d, destroyed=%d, borrowed=%d, returned=%d",
                active, idle, waiters, total, maxTotal, utilizationPercent,
                minIdle, maxIdle, created, destroyed, borrowed, returned
            );

            // Notify listener
            if (listener != null) {
                listener.onPoolStateLog(diagnostics);
            }

            // Log diagnostics
            log.debug("[XA-POOL-DIAGNOSTICS] {}", diagnostics);

        } catch (Exception e) {
            log.error("[XA-POOL-DIAGNOSTICS] Error collecting pool diagnostics", e);
            if (listener != null) {
                listener.onHousekeepingError("Error collecting pool diagnostics: " + e.getMessage(), e);
            }
        }
    }
}
