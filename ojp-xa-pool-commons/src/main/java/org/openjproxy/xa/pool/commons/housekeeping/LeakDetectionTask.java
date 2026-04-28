package org.openjproxy.xa.pool.commons.housekeeping;

import org.openjproxy.xa.pool.XABackendSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Task that periodically checks for leaked connections.
 * <p>
 * This task examines all currently borrowed sessions and identifies any
 * that have been held longer than the configured timeout without being
 * returned to the pool.
 * </p>
 */
public class LeakDetectionTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LeakDetectionTask.class);

    private final Map<XABackendSession, BorrowInfo> borrowedSessions;
    private final long leakTimeoutNanos;
    private final HousekeepingListener listener;

    /**
     * Creates a leak detection task.
     *
     * @param borrowedSessions map of currently borrowed sessions and their borrow info
     * @param leakTimeoutNanos the timeout in nanoseconds after which a held connection is considered leaked
     * @param listener the listener to notify when leaks are detected
     */
    public LeakDetectionTask(
            Map<XABackendSession, BorrowInfo> borrowedSessions,
            long leakTimeoutNanos,
            HousekeepingListener listener) {
        this.borrowedSessions = borrowedSessions;
        this.leakTimeoutNanos = leakTimeoutNanos;
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            long now = System.nanoTime();
            int leakCount = 0;

            for (Map.Entry<XABackendSession, BorrowInfo> entry : borrowedSessions.entrySet()) {
                XABackendSession session = entry.getKey();
                BorrowInfo info = entry.getValue();

                long heldDuration = now - info.getBorrowTime();
                if (heldDuration > leakTimeoutNanos) {
                    leakCount++;

                    // Notify listener
                    if (listener != null) {
                        listener.onLeakDetected(
                            session,
                            info.getThread(),
                            info.getStackTrace()
                        );
                    }
                }
            }

            if (leakCount > 0) {
                log.debug("Leak detection scan completed: {} leak(s) detected out of {} borrowed sessions",
                    leakCount, borrowedSessions.size());
            }

        } catch (Exception e) {
            log.error("Error during leak detection scan", e);
            if (listener != null) {
                listener.onHousekeepingError("Leak detection scan failed", e);
            }
        }
    }
}
