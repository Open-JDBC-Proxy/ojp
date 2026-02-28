package org.openjproxy.xa.pool.commons.metrics;

import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global holder for the OpenTelemetry instance used by XA pool metrics.
 * <p>
 * This class provides a centralized way to access the OpenTelemetry instance
 * that is configured in the ojp-server. The instance is set once during server
 * initialization and can be retrieved by the pool metrics factory.
 * </p>
 * 
 * <p>Thread-safe: This class uses volatile to ensure visibility across threads.</p>
 */
public class OpenTelemetryHolder {
    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryHolder.class);
    
    private static volatile OpenTelemetry instance;
    
    /**
     * Sets the global OpenTelemetry instance.
     * This should be called once during server initialization.
     *
     * @param openTelemetry the OpenTelemetry instance to use for metrics
     */
    public static void setInstance(OpenTelemetry openTelemetry) {
        if (instance != null && openTelemetry != null) {
            log.warn("OpenTelemetry instance is being replaced. This may indicate multiple initializations.");
        }
        instance = openTelemetry;
        if (openTelemetry != null) {
            log.info("OpenTelemetry instance registered for XA pool metrics");
        }
    }
    
    /**
     * Gets the global OpenTelemetry instance.
     *
     * @return the OpenTelemetry instance, or null if not set
     */
    public static OpenTelemetry getInstance() {
        OpenTelemetry current = instance;
        if (current == null) {
            log.warn("OpenTelemetry instance requested but not yet registered. Pool metrics will not be available.");
        } else {
            log.debug("OpenTelemetry instance retrieved for pool metrics");
        }
        return current;
    }
    
    /**
     * Clears the global OpenTelemetry instance.
     * This is mainly for testing purposes.
     */
    public static void clear() {
        instance = null;
    }
}
