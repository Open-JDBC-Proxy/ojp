package org.openjproxy.jdbc.metrics;

import lombok.extern.slf4j.Slf4j;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMX-backed {@link ClientThrottleMetrics}. Registers one MBean per
 * {@code connHash} under {@code org.openjproxy:type=ClientThrottle,connHash=<hash>}.
 *
 * <p>Counters are {@link AtomicLong}s; gauges are read live through the
 * supplied {@link ClientThrottleStateProvider}. MBean registration is
 * best-effort: failure to register is logged at WARN and does not throw, so a
 * locked-down host SecurityManager cannot break JDBC functionality.</p>
 *
 * <p>See ADR-010.</p>
 */
@Slf4j
public final class JmxClientThrottleMetrics implements ClientThrottleMetrics, ClientThrottleMetricsMXBean {

    static final String DOMAIN = "org.openjproxy";

    private final String connHash;
    private final ClientThrottleStateProvider stateProvider;
    private final ObjectName objectName;
    private final boolean registered;

    private final AtomicLong acquired = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong serverOverload = new AtomicLong();
    private final AtomicLong limitIncrease = new AtomicLong();
    private final AtomicLong limitDecrease = new AtomicLong();

    public JmxClientThrottleMetrics(String connHash, ClientThrottleStateProvider stateProvider) {
        this.connHash = connHash;
        this.stateProvider = stateProvider;
        this.objectName = buildObjectName(connHash);
        this.registered = register();
    }

    private static ObjectName buildObjectName(String connHash) {
        try {
            // ObjectName values are quoted to tolerate any characters the connHash may legally contain.
            return new ObjectName(DOMAIN + ":type=ClientThrottle,connHash=" + ObjectName.quote(connHash));
        } catch (MalformedObjectNameException e) {
            log.warn("Could not build JMX ObjectName for connHash={}: {}", connHash, e.getMessage());
            return null;
        }
    }

    private boolean register() {
        if (objectName == null) {
            return false;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            server.registerMBean(this, objectName);
            return true;
        } catch (InstanceAlreadyExistsException e) {
            // Another driver instance in the same JVM already registered this hash — that's fine.
            log.debug("JMX MBean already registered for {}", objectName);
            return false;
        } catch (Exception e) {
            log.warn("Could not register JMX MBean for connHash={}: {}", connHash, e.getMessage());
            return false;
        }
    }

    @Override
    public void recordAcquired() {
        acquired.incrementAndGet();
    }

    @Override
    public void recordRejected() {
        rejected.incrementAndGet();
    }

    @Override
    public void recordServerOverload() {
        serverOverload.incrementAndGet();
    }

    @Override
    public void recordLimitChange(LimitChangeDirection direction) {
        if (direction == LimitChangeDirection.INCREASE) {
            limitIncrease.incrementAndGet();
        } else {
            limitDecrease.incrementAndGet();
        }
    }

    @Override
    public void close() {
        if (!registered || objectName == null) {
            return;
        }
        try {
            ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
        } catch (InstanceNotFoundException e) {
            // Already gone — fine.
        } catch (Exception e) {
            log.warn("Could not unregister JMX MBean {}: {}", objectName, e.getMessage());
        }
    }

    // ---- ClientThrottleMetricsMXBean ----

    @Override
    public String getConnHash() {
        return connHash;
    }

    @Override
    public String getMode() {
        return stateProvider.getMode();
    }

    @Override
    public int getInFlight() {
        return stateProvider.getInFlight();
    }

    @Override
    public int getProactiveLimit() {
        return stateProvider.getProactiveLimit();
    }

    @Override
    public int getReactiveLimit() {
        return stateProvider.getReactiveLimit();
    }

    @Override
    public int getEffectiveLimit() {
        return stateProvider.getEffectiveLimit();
    }

    @Override
    public long getRejectedTotal() {
        return rejected.get();
    }

    @Override
    public long getAcquiredTotal() {
        return acquired.get();
    }

    @Override
    public long getServerOverloadEventsTotal() {
        return serverOverload.get();
    }

    @Override
    public long getLimitIncreaseTotal() {
        return limitIncrease.get();
    }

    @Override
    public long getLimitDecreaseTotal() {
        return limitDecrease.get();
    }
}
