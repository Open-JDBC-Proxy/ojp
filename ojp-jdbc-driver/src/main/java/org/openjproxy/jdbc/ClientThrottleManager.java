package org.openjproxy.jdbc;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.jdbc.metrics.ClientThrottleMetrics;
import org.openjproxy.jdbc.metrics.NoOpClientThrottleMetrics;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-connHash, per-node client-side throttle.
 * Prevents this JVM from sending more concurrent requests to one OJP node than its fair share.
 *
 * Mechanism: fail-fast AtomicInteger counter (no blocking, no semaphore).
 * Limit updates are a single volatile write; AIMD increase capped at +1 per SessionInfo update.
 */
@Slf4j
public class ClientThrottleManager {

    // 10% safety headroom applied after ceiling division.
    // Ceiling division can slightly over-allocate: e.g. 20 server slots / 3 clients
    // → ceil(20/3) = 7 per client; 3 × 7 = 21 would exceed real capacity by 1.
    // Multiplying by 0.9 brings the per-client budget down by one slot (floor: 6),
    // absorbing one stale clientCount value before all clients burst at the same moment.
    private static final double THROTTLE_SAFETY_MARGIN = 0.9;

    private final AtomicInteger inFlight = new AtomicInteger(0);
    private volatile int proactiveLimit = Integer.MAX_VALUE;
    private volatile int reactiveLimit = Integer.MAX_VALUE;
    private volatile int lastProactiveLimit = Integer.MAX_VALUE;
    private volatile int lastReactiveLimit = Integer.MAX_VALUE;
    private volatile ClientThrottleMetrics metrics = NoOpClientThrottleMetrics.INSTANCE;

    /**
     * Attach a metrics sink. Idempotent for the No-op default; for a real
     * sink, the caller (typically {@link Connection}) is responsible for
     * lifecycle (close on connection-pool eviction is not required because
     * the manager lives for the JVM lifetime in {@code THROTTLE_MANAGERS}).
     */
    public void setMetrics(ClientThrottleMetrics metrics) {
        this.metrics = metrics == null ? NoOpClientThrottleMetrics.INSTANCE : metrics;
    }

    public ClientThrottleMetrics getMetrics() {
        return metrics;
    }

    /**
     * Update limits from a fresh SessionInfo.
     * AIMD: decrease immediately; increase capped at currentLimit + 1.
     *
     * <p>Only connect responses carry throttle data (maxAdmission &gt; 0).
     * executeUpdate/executeQuery responses use a minimal SessionInfo without throttle fields
     * (maxAdmission = 0). When maxAdmission is zero, there is nothing to update, so this
     * method returns immediately, preserving any reactive limit already set by
     * {@link #notifyServerOverload()}.</p>
     */
    public void updateFromSessionInfo(SessionInfo sessionInfo) {
        if (sessionInfo.getMaxAdmission() <= 0) {
            // No throttle data in this SessionInfo (e.g., executeUpdate/executeQuery response).
            // Skip to preserve any reactive limit set by notifyServerOverload().
            return;
        }
        int clientCount = Math.max(1, sessionInfo.getClientCount());
        int maxAdmission = sessionInfo.getMaxAdmission();
        int observedPeak = sessionInfo.getObservedPeak();

        int numOjpServers = countUpServers(sessionInfo.getClusterHealth());

        if (maxAdmission > 0) {
            int rawProactive = (int) Math.min(Integer.MAX_VALUE,
                    (long) Math.ceil((double) maxAdmission / clientCount) * numOjpServers);
            int newProactive = (int) (rawProactive * THROTTLE_SAFETY_MARGIN);
            if (newProactive < 1) {
                newProactive = 1;
            }
            if (newProactive < lastProactiveLimit) {
                proactiveLimit = newProactive;
                metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.DECREASE);
            } else if (newProactive > lastProactiveLimit) {
                proactiveLimit = lastProactiveLimit + 1;
                metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.INCREASE);
            }
            lastProactiveLimit = proactiveLimit;
        }

        if (observedPeak > 0 && maxAdmission > 0) {
            int rawReactive = (int) Math.min(Integer.MAX_VALUE,
                    (long) Math.ceil((double) observedPeak / clientCount) * numOjpServers);
            int newReactive = (int) (rawReactive * THROTTLE_SAFETY_MARGIN);
            if (newReactive < 1) {
                newReactive = 1;
            }
            if (newReactive < lastReactiveLimit) {
                reactiveLimit = newReactive;
                metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.DECREASE);
            } else if (newReactive > lastReactiveLimit) {
                reactiveLimit = lastReactiveLimit + 1;
                metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.INCREASE);
            }
            lastReactiveLimit = reactiveLimit;
        } else {
            reactiveLimit = Integer.MAX_VALUE;
            lastReactiveLimit = Integer.MAX_VALUE;
        }

        log.debug("ClientThrottleManager updated: proactiveLimit={}, reactiveLimit={}, clientCount={}, maxAdmission={}, observedPeak={}, numServers={}",
                proactiveLimit, reactiveLimit, clientCount, maxAdmission, observedPeak, numOjpServers);
    }

    /**
     * Count UP servers from clusterHealth string "host1:port1(UP);host2:port2(DOWN);..."
     * Returns 1 if clusterHealth is empty/null.
     */
    private int countUpServers(String clusterHealth) {
        if (clusterHealth == null || clusterHealth.isEmpty()) {
            return 1;
        }
        int count = 0;
        for (String entry : clusterHealth.split(";")) {
            if (entry.contains("(UP)")) {
                count++;
            }
        }
        return count > 0 ? count : 1;
    }

    /**
     * Attempt to acquire a throttle slot.
     *
     * @param mode the configured throttle mode
     * @param inTransaction whether this connection is currently in a transaction (autoCommit=false)
     * @return true if the request should proceed, false if it should be rejected
     */
    public boolean tryAcquire(ClientThrottleMode mode, boolean inTransaction) {
        if (mode == ClientThrottleMode.OFF) {
            return true;
        }
        if (inTransaction) {
            return true;
        }

        int effectiveLimit = getEffectiveLimit(mode);
        if (effectiveLimit == Integer.MAX_VALUE) {
            metrics.recordAcquired();
            return true;
        }

        int current = inFlight.get();
        if (current >= effectiveLimit) {
            log.debug("Client throttle rejected: inFlight={}, effectiveLimit={}, mode={}", current, effectiveLimit, mode);
            metrics.recordRejected();
            return false;
        }
        // CAS loop: atomically check-and-increment to avoid exceeding the limit due to races
        while (true) {
            int cur = inFlight.get();
            if (cur >= effectiveLimit) {
                log.debug("Client throttle rejected (CAS): inFlight={}, effectiveLimit={}, mode={}", cur, effectiveLimit, mode);
                metrics.recordRejected();
                return false;
            }
            if (inFlight.compareAndSet(cur, cur + 1)) {
                metrics.recordAcquired();
                return true;
            }
        }
    }

    /**
     * Release a previously acquired slot. Must be called after tryAcquire returned true.
     */
    public void release(ClientThrottleMode mode, boolean inTransaction) {
        if (mode == ClientThrottleMode.OFF || inTransaction) {
            return;
        }
        // Atomically clamp to 0 to handle any race in concurrent releases
        inFlight.updateAndGet(v -> Math.max(0, v - 1));
    }

    int getEffectiveLimit(ClientThrottleMode mode) {
        switch (mode) {
            case PROACTIVE: return proactiveLimit;
            case REACTIVE: return reactiveLimit;
            case COMBINED:
                int pl = proactiveLimit;
                int rl = reactiveLimit;
                return Math.min(pl, rl);
            default: return Integer.MAX_VALUE;
        }
    }

    public int getInFlight() {
        return inFlight.get();
    }

    /**
     * Called when the server rejects a request with RESOURCE_EXHAUSTED (slot admission timeout).
     * Applies a multiplicative decrease to the reactive limit (AIMD: halve on overload).
     *
     * <p>Example: reactiveLimit = 8 → notifyServerOverload() → reactiveLimit = 4.
     * The next request will be blocked client-side instead of hitting the still-overloaded server.
     * If the reactive limit was uninitialised (MAX_VALUE), it is seeded from half the proactive
     * limit so the client immediately backs off to a reasonable level.</p>
     *
     * <p>Thread safety: reads and writes to {@code reactiveLimit} and {@code lastReactiveLimit}
     * are individually atomic (volatile), but the read-compute-write sequence is not atomic.
     * This is intentional: concurrent calls both halve the limit, producing a more aggressive
     * decrease that is desirable when multiple threads observe the same overload. This matches
     * the eventually-consistent AIMD design used throughout this class.</p>
     */
    public void notifyServerOverload() {
        int current = reactiveLimit;
        int newLimit;
        if (current == Integer.MAX_VALUE) {
            // Reactive limit was uninitialised — seed from half the proactive limit as a starting point.
            int pl = proactiveLimit;
            newLimit = pl == Integer.MAX_VALUE ? 1 : Math.max(1, pl / 2);
        } else {
            newLimit = Math.max(1, current / 2);
        }
        reactiveLimit = newLimit;
        lastReactiveLimit = newLimit;
        metrics.recordServerOverload();
        if (current != newLimit) {
            metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.DECREASE);
        }
        log.debug("ClientThrottleManager notifyServerOverload: reactiveLimit {} -> {}", current, newLimit);
    }

    public int getProactiveLimit() {
        return proactiveLimit;
    }

    public int getReactiveLimit() {
        return reactiveLimit;
    }
}
