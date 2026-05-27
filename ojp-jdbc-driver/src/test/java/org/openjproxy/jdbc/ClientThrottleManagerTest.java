package org.openjproxy.jdbc;

import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the rolling-window burst detection in {@link ClientThrottleManager#notifyServerOverload()}.
 * The hot path (tryAcquire/release) is intentionally not exercised here — these tests focus on
 * the cold error-handling path that was changed.
 */
class ClientThrottleManagerTest {

    private static SessionInfo sessionInfo(int maxAdmission, int observedPeak, int clientCount) {
        return SessionInfo.newBuilder()
                .setMaxAdmission(maxAdmission)
                .setObservedPeak(observedPeak)
                .setClientCount(clientCount)
                .setClusterHealth("")
                .build();
    }

    private static ClientThrottleManager seed(int reactiveLimit) {
        return seedWith(3, 60_000L, reactiveLimit);
    }

    private static ClientThrottleManager seedWith(int threshold, long windowMillis, int reactiveLimit) {
        AtomicLong clock = new AtomicLong(0);
        ClientThrottleManager m = new ClientThrottleManager(threshold, windowMillis, clock::get);
        // Seed reactiveLimit through a SessionInfo. observedPeak directly drives reactiveLimit.
        m.updateFromSessionInfo(sessionInfo(reactiveLimit * 2, reactiveLimit, 1));
        // updateFromSessionInfo applies the 10% safety margin; recompute the actual seeded value.
        assertTrue(m.getReactiveLimit() > 0);
        return m;
    }

    @Test
    void firstOverloadDoesNotChangeReactiveLimitWhenThresholdIsThree() {
        AtomicLong clock = new AtomicLong(0);
        ClientThrottleManager m = new ClientThrottleManager(3, 60_000L, clock::get);
        m.updateFromSessionInfo(sessionInfo(20, 10, 1));
        int before = m.getReactiveLimit();

        m.notifyServerOverload();

        assertEquals(before, m.getReactiveLimit(), "single overload below threshold must not change limit");
    }

    @Test
    void secondOverloadDoesNotChangeReactiveLimitWhenThresholdIsThree() {
        AtomicLong clock = new AtomicLong(0);
        ClientThrottleManager m = new ClientThrottleManager(3, 60_000L, clock::get);
        m.updateFromSessionInfo(sessionInfo(20, 10, 1));
        int before = m.getReactiveLimit();

        m.notifyServerOverload();
        clock.addAndGet(1_000_000L); // +1ms
        m.notifyServerOverload();

        assertEquals(before, m.getReactiveLimit(), "two overloads below threshold must not change limit");
    }

    @Test
    void thirdOverloadWithinWindowHalvesReactiveLimit() {
        AtomicLong clock = new AtomicLong(0);
        ClientThrottleManager m = new ClientThrottleManager(3, 60_000L, clock::get);
        m.updateFromSessionInfo(sessionInfo(20, 10, 1));
        int before = m.getReactiveLimit();

        m.notifyServerOverload();
        clock.addAndGet(1_000_000L);
        m.notifyServerOverload();
        clock.addAndGet(1_000_000L);
        m.notifyServerOverload();

        assertEquals(Math.max(1, before / 2), m.getReactiveLimit(),
                "third overload within the window must halve the reactive limit");
    }

    @Test
    void burstSpanningLongerThanWindowDoesNotHalve() {
        AtomicLong clock = new AtomicLong(0);
        long windowMs = 50L;
        ClientThrottleManager m = new ClientThrottleManager(3, windowMs, clock::get);
        m.updateFromSessionInfo(sessionInfo(20, 10, 1));
        int before = m.getReactiveLimit();

        m.notifyServerOverload();
        // advance well beyond the window between events so the 3-in-window check fails
        clock.addAndGet(100L * 1_000_000L);
        m.notifyServerOverload();
        clock.addAndGet(100L * 1_000_000L);
        m.notifyServerOverload();

        assertEquals(before, m.getReactiveLimit(),
                "overloads spread beyond the window must not halve the reactive limit");
    }

    @Test
    void thresholdOfOnePreservesLegacyImmediateHalving() {
        AtomicLong clock = new AtomicLong(0);
        ClientThrottleManager m = new ClientThrottleManager(1, 60_000L, clock::get);
        m.updateFromSessionInfo(sessionInfo(20, 10, 1));
        int before = m.getReactiveLimit();

        m.notifyServerOverload();

        assertEquals(Math.max(1, before / 2), m.getReactiveLimit(),
                "errorThreshold=1 must preserve halve-on-every-overload behaviour");
    }

    @Test
    void burstResetsAfterHalvingSoNextHalvingRequiresFreshBurst() {
        AtomicLong clock = new AtomicLong(0);
        ClientThrottleManager m = new ClientThrottleManager(3, 60_000L, clock::get);
        m.updateFromSessionInfo(sessionInfo(80, 40, 1));
        int before = m.getReactiveLimit();

        // First burst halves
        m.notifyServerOverload();
        m.notifyServerOverload();
        m.notifyServerOverload();
        int afterFirstBurst = m.getReactiveLimit();
        assertEquals(Math.max(1, before / 2), afterFirstBurst);

        // Two more overloads should NOT halve again — counter was reset.
        m.notifyServerOverload();
        m.notifyServerOverload();
        assertEquals(afterFirstBurst, m.getReactiveLimit(),
                "after a halving the burst counter must reset");

        // Third overload completes a fresh burst and triggers another halving.
        m.notifyServerOverload();
        assertEquals(Math.max(1, afterFirstBurst / 2), m.getReactiveLimit());
    }

    @Test
    void uninitialisedReactiveLimitIsSeededFromProactiveWhenBurstFires() {
        AtomicLong clock = new AtomicLong(0);
        ClientThrottleManager m = new ClientThrottleManager(1, 60_000L, clock::get);
        // No SessionInfo applied → reactiveLimit is MAX_VALUE; proactiveLimit also MAX_VALUE → newLimit=1.
        m.notifyServerOverload();
        assertEquals(1, m.getReactiveLimit());
    }
}
