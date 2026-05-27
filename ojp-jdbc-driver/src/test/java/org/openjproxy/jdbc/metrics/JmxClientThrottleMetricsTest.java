package org.openjproxy.jdbc.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmxClientThrottleMetricsTest {

    private JmxClientThrottleMetrics metrics;

    private static final class FixedState implements ClientThrottleStateProvider {
        @Override public String getMode() { return "COMBINED"; }
        @Override public int getInFlight() { return 3; }
        @Override public int getProactiveLimit() { return 10; }
        @Override public int getReactiveLimit() { return 8; }
        @Override public int getEffectiveLimit() { return 8; }
    }

    @AfterEach
    void tearDown() {
        if (metrics != null) {
            metrics.close();
        }
    }

    @Test
    void shouldRegisterMBeanAndExposeAttributesWhenCreated() throws Exception {
        metrics = new JmxClientThrottleMetrics("hash-A", new FixedState());

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(JmxClientThrottleMetrics.DOMAIN
                + ":type=ClientThrottle,connHash=" + ObjectName.quote("hash-A"));
        assertTrue(server.isRegistered(name));

        assertEquals("hash-A", server.getAttribute(name, "ConnHash"));
        assertEquals("COMBINED", server.getAttribute(name, "Mode"));
        assertEquals(3, server.getAttribute(name, "InFlight"));
        assertEquals(10, server.getAttribute(name, "ProactiveLimit"));
        assertEquals(8, server.getAttribute(name, "ReactiveLimit"));
        assertEquals(8, server.getAttribute(name, "EffectiveLimit"));
    }

    @Test
    void shouldIncrementCountersWhenEventsRecorded() throws Exception {
        metrics = new JmxClientThrottleMetrics("hash-B", new FixedState());

        metrics.recordAcquired();
        metrics.recordAcquired();
        metrics.recordRejected();
        metrics.recordServerOverload();
        metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.INCREASE);
        metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.DECREASE);
        metrics.recordLimitChange(ClientThrottleMetrics.LimitChangeDirection.DECREASE);

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(JmxClientThrottleMetrics.DOMAIN
                + ":type=ClientThrottle,connHash=" + ObjectName.quote("hash-B"));

        assertEquals(2L, server.getAttribute(name, "AcquiredTotal"));
        assertEquals(1L, server.getAttribute(name, "RejectedTotal"));
        assertEquals(1L, server.getAttribute(name, "ServerOverloadEventsTotal"));
        assertEquals(1L, server.getAttribute(name, "LimitIncreaseTotal"));
        assertEquals(2L, server.getAttribute(name, "LimitDecreaseTotal"));
    }

    @Test
    void shouldUnregisterMBeanWhenClosed() throws Exception {
        metrics = new JmxClientThrottleMetrics("hash-C", new FixedState());
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(JmxClientThrottleMetrics.DOMAIN
                + ":type=ClientThrottle,connHash=" + ObjectName.quote("hash-C"));
        assertTrue(server.isRegistered(name));

        metrics.close();
        assertFalse(server.isRegistered(name));

        // Idempotent: a second close must not throw.
        metrics.close();
        metrics = null; // prevent tearDown double-close
    }

    @Test
    void shouldNotThrowWhenSameConnHashRegisteredTwice() {
        metrics = new JmxClientThrottleMetrics("hash-D", new FixedState());
        // Second registration of the same connHash is tolerated.
        JmxClientThrottleMetrics dup = new JmxClientThrottleMetrics("hash-D", new FixedState());
        assertNotNull(dup);
        // Recording on the duplicate must still be safe.
        dup.recordAcquired();
        dup.close();
    }
}
