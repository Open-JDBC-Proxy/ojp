package org.openjproxy.jdbc.metrics;

import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.Test;
import org.openjproxy.jdbc.ClientThrottleManager;
import org.openjproxy.jdbc.ClientThrottleMode;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link ClientThrottleManager} invokes the attached
 * {@link ClientThrottleMetrics} on each relevant event.
 */
class ClientThrottleManagerMetricsIntegrationTest {

    private static final class CountingMetrics implements ClientThrottleMetrics {
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger overload = new AtomicInteger();
        final AtomicInteger increase = new AtomicInteger();
        final AtomicInteger decrease = new AtomicInteger();

        @Override public void recordAcquired() { acquired.incrementAndGet(); }
        @Override public void recordRejected() { rejected.incrementAndGet(); }
        @Override public void recordServerOverload() { overload.incrementAndGet(); }
        @Override public void recordLimitChange(LimitChangeDirection direction) {
            if (direction == LimitChangeDirection.INCREASE) {
                increase.incrementAndGet();
            } else {
                decrease.incrementAndGet();
            }
        }
        @Override public void close() { /* no-op */ }
    }

    @Test
    void shouldRecordAcquiredAndRejectedAndOverload() {
        ClientThrottleManager manager = new ClientThrottleManager();
        CountingMetrics metrics = new CountingMetrics();
        manager.setMetrics(metrics);

        // Seed limit to 2 via SessionInfo. ClientThrottleManager applies a 0.9 safety margin
        // after ceiling division: ceil(30/10) = 3; (int)(3 * 0.9) = 2.
        manager.updateFromSessionInfo(SessionInfo.newBuilder()
                .setConnHash("h")
                .setMaxAdmission(30)
                .setClientCount(10)
                .build());

        // 2 acquires succeed, 3rd is rejected.
        assertEquals(true, manager.tryAcquire(ClientThrottleMode.PROACTIVE, false));
        assertEquals(true, manager.tryAcquire(ClientThrottleMode.PROACTIVE, false));
        assertEquals(false, manager.tryAcquire(ClientThrottleMode.PROACTIVE, false));

        assertEquals(2, metrics.acquired.get());
        assertEquals(1, metrics.rejected.get());

        // Server overload halves the reactive limit and records the event + a DECREASE.
        manager.notifyServerOverload();
        assertEquals(1, metrics.overload.get());
        // Total decreases: 1 from initial proactive seed (newProactive < MAX_VALUE) + 1 from overload.
        assertEquals(2, metrics.decrease.get());
    }

    @Test
    void shouldNotRecordWhenModeOff() {
        ClientThrottleManager manager = new ClientThrottleManager();
        CountingMetrics metrics = new CountingMetrics();
        manager.setMetrics(metrics);

        manager.tryAcquire(ClientThrottleMode.OFF, false);
        manager.tryAcquire(ClientThrottleMode.OFF, false);
        assertEquals(0, metrics.acquired.get());
        assertEquals(0, metrics.rejected.get());
    }
}
