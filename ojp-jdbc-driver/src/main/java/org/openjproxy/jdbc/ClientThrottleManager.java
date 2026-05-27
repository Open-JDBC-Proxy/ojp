package org.openjproxy.jdbc;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.openjproxy.constants.CommonConstants.DEFAULT_JDBC_CLIENT_THROTTLE_REACTIVE_ERROR_THRESHOLD;
import static org.openjproxy.constants.CommonConstants.DEFAULT_JDBC_CLIENT_THROTTLE_REACTIVE_WINDOW_MILLIS;
import static org.openjproxy.constants.CommonConstants.JDBC_CLIENT_THROTTLE_REACTIVE_ERROR_THRESHOLD_PROPERTY;
import static org.openjproxy.constants.CommonConstants.JDBC_CLIENT_THROTTLE_REACTIVE_WINDOW_MILLIS_PROPERTY;

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

    // Rolling-window burst detection for notifyServerOverload().
    // Halve reactiveLimit only when at least overloadErrorThreshold RESOURCE_EXHAUSTED
    // events occur within overloadWindowNanos. State is touched only in the (cold) error
    // path, never in tryAcquire/release, so the hot path has zero added overhead.
    private final int overloadErrorThreshold;
    private final long overloadWindowNanos;
    private final long[] overloadTimestamps;
    private int overloadCount = 0;
    private final LongSupplier nowNanos;

    public ClientThrottleManager() {
        this(readErrorThreshold(), readWindowMillis(), System::nanoTime);
    }

    /** Package-private constructor for tests: injectable threshold, window and clock. */
    ClientThrottleManager(int errorThreshold, long windowMillis, LongSupplier nowNanos) {
        this.overloadErrorThreshold = Math.max(1, errorThreshold);
        this.overloadWindowNanos = Math.max(0L, windowMillis) * 1_000_000L;
        this.overloadTimestamps = new long[this.overloadErrorThreshold];
        this.nowNanos = nowNanos;
    }

    private static int readErrorThreshold() {
        String raw = System.getProperty(JDBC_CLIENT_THROTTLE_REACTIVE_ERROR_THRESHOLD_PROPERTY);
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_JDBC_CLIENT_THROTTLE_REACTIVE_ERROR_THRESHOLD;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_JDBC_CLIENT_THROTTLE_REACTIVE_ERROR_THRESHOLD;
        }
    }

    private static long readWindowMillis() {
        String raw = System.getProperty(JDBC_CLIENT_THROTTLE_REACTIVE_WINDOW_MILLIS_PROPERTY);
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_JDBC_CLIENT_THROTTLE_REACTIVE_WINDOW_MILLIS;
        }
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_JDBC_CLIENT_THROTTLE_REACTIVE_WINDOW_MILLIS;
        }
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
            } else if (newProactive > lastProactiveLimit) {
                proactiveLimit = lastProactiveLimit + 1;
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
            } else if (newReactive > lastReactiveLimit) {
                reactiveLimit = lastReactiveLimit + 1;
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
            return true;
        }

        int current = inFlight.get();
        if (current >= effectiveLimit) {
            log.debug("Client throttle rejected: inFlight={}, effectiveLimit={}, mode={}", current, effectiveLimit, mode);
            return false;
        }
        // CAS loop: atomically check-and-increment to avoid exceeding the limit due to races
        while (true) {
            int cur = inFlight.get();
            if (cur >= effectiveLimit) {
                log.debug("Client throttle rejected (CAS): inFlight={}, effectiveLimit={}, mode={}", cur, effectiveLimit, mode);
                return false;
            }
            if (inFlight.compareAndSet(cur, cur + 1)) {
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

    private int getEffectiveLimit(ClientThrottleMode mode) {
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
     * Applies a multiplicative decrease to the reactive limit (AIMD: halve on overload), but
     * only when a burst of RESOURCE_EXHAUSTED events has been observed — specifically, at
     * least {@code overloadErrorThreshold} events within {@code overloadWindowNanos}. A single
     * isolated error is treated as transient noise and does not change the limit. When
     * {@code overloadErrorThreshold == 1} the historical "halve on every overload" behaviour
     * is preserved.
     *
     * <p>Example: reactiveLimit = 8 → notifyServerOverload() → reactiveLimit = 4.
     * The next request will be blocked client-side instead of hitting the still-overloaded server.
     * If the reactive limit was uninitialised (MAX_VALUE), it is seeded from half the proactive
     * limit so the client immediately backs off to a reasonable level.</p>
     *
     * <p>Thread safety: the rolling-window bookkeeping is serialised on this instance's
     * monitor. This is only contended on the (rare) RESOURCE_EXHAUSTED path; the hot
     * tryAcquire/release path is unaffected. Reads and writes to {@code reactiveLimit} and
     * {@code lastReactiveLimit} remain volatile.</p>
     */
    public void notifyServerOverload() {
        if (!recordOverloadAndCheckBurst()) {
            log.debug("ClientThrottleManager notifyServerOverload: ignored (below burst threshold)");
            return;
        }
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
        log.debug("ClientThrottleManager notifyServerOverload: reactiveLimit {} -> {} (burst threshold {} reached within {} ms)",
                current, newLimit, overloadErrorThreshold, overloadWindowNanos / 1_000_000L);
    }

    /**
     * Record an overload event and return true if it completes a burst of at least
     * {@code overloadErrorThreshold} events within {@code overloadWindowNanos}. The burst
     * counter is reset on a positive return so the next halving requires a fresh burst.
     */
    private synchronized boolean recordOverloadAndCheckBurst() {
        long now = nowNanos.getAsLong();
        if (overloadErrorThreshold <= 1) {
            // Legacy behaviour: every overload triggers a halving.
            return true;
        }
        int idx = overloadCount % overloadErrorThreshold;
        long oldest = overloadTimestamps[idx];
        overloadTimestamps[idx] = now;
        overloadCount++;
        if (overloadCount < overloadErrorThreshold) {
            return false;
        }
        // oldest is the timestamp of the (errorThreshold-1)-th previous event.
        if ((now - oldest) <= overloadWindowNanos) {
            // Reset: next halving requires a fresh burst.
            overloadCount = 0;
            for (int i = 0; i < overloadTimestamps.length; i++) {
                overloadTimestamps[i] = 0L;
            }
            return true;
        }
        return false;
    }

    public int getProactiveLimit() {
        return proactiveLimit;
    }

    public int getReactiveLimit() {
        return reactiveLimit;
    }
}
