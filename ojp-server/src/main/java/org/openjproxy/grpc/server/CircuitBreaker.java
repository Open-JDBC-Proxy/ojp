package org.openjproxy.grpc.server;

import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
/**
 * Implements a basic circuit breaker that counts failures and return the latest error if a threshold is exceeded.
 */
@Slf4j
public class CircuitBreaker {
    private static class FailureRecord {
        private volatile SQLException lastError;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicLong openUntil = new AtomicLong(0);
        private final int failureThreshold;

        public FailureRecord(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public void recordFailure(SQLException error, long openMs) {
            lastError = error;
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                openUntil.updateAndGet(prev -> Math.max(prev, System.nanoTime() + openMs * 1_000_000L));
            }
        }

        public boolean isOpen() {
            long until = openUntil.get();
            if (failureCount.get() < failureThreshold) {
                return false;
            }
            if (System.nanoTime() > until) {
                return false;
            }
            return true;
        }

        public boolean tryReset() {
            long until = openUntil.get();
            if (System.nanoTime() > until && failureCount.get() >= failureThreshold) {
                return true;
            }
            return false;
        }

        public void reset() {
            failureCount.set(0);
            openUntil.set(0);
            lastError = null;
        }

        public SQLException getLastError() {
            return lastError;
        }

        public int getFailureCount() {
            return failureCount.get();
        }
    }

    private final ConcurrentHashMap<String, FailureRecord> state = new ConcurrentHashMap<>();
    private final long openMs;
    private final int failureThreshold;
    private final String resourceId;
    public CircuitBreaker(long openMs, int failureThreshold, String resourceId) {
        this.openMs = openMs;
        this.failureThreshold = failureThreshold;
        this.resourceId = resourceId;
    }

    /**
     * Call when a statement is received.
     * @param sql The SQL statement (normalized string).
     * @throws java.sql.SQLException if blocked (open).
     */
    public void preCheck(String sql) throws SQLException {
        FailureRecord rec = state.get(sql);
        if (rec == null) {
            return;
        }
        if (rec.isOpen()) {
            throw rec.getLastError();
        }
        rec.tryReset();
    }

    /**
     * Call when a statement succeeds.
     * @param sql The SQL statement.
     */
    public void onSuccess(String sql) {
        FailureRecord rec = state.get(sql);
        if (rec != null) {
            rec.reset();
            state.remove(sql, rec);
        }
    }

    /**
     * Call when a statement fails. If called when already opened will not record the failure,
     * that is intended so it can always be called from catch blocks without checks as per if
     * a catch block might be handling an exception thrown by the circuit breaker itself.
     * @param sql The SQL statement.
     * @param error The exception.
     */
    public void onFailure(String sql, SQLException error) {
        FailureRecord rec = state.computeIfAbsent(sql, s -> new FailureRecord(this.failureThreshold));
        if (!rec.isOpen()) {
            rec.recordFailure(error, openMs);
            if (rec.isOpen()) {
                log.warn("Circuit Breaker TRIP: Resource Id [{}] is now OPEN for query key: {}", this.resourceId, sql);
            }
        }
    }
}
