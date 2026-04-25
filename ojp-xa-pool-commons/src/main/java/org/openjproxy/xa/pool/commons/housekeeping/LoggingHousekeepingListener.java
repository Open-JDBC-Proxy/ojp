package org.openjproxy.xa.pool.commons.housekeeping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of HousekeepingListener that logs events using SLF4J.
 * <p>
 * This listener logs all housekeeping events at appropriate levels:
 * <ul>
 *   <li>Leak detection: WARN level with optional stack trace</li>
 *   <li>Connection expiration: INFO level</li>
 *   <li>Connection recycling: INFO level</li>
 *   <li>Errors: ERROR level</li>
 *   <li>Pool state: INFO level</li>
 * </ul>
 * </p>
 */
public class LoggingHousekeepingListener implements HousekeepingListener {
    private static final Logger log = LoggerFactory.getLogger(LoggingHousekeepingListener.class);

    @Override
    public void onLeakDetected(Object connection, Thread holdingThread, StackTraceElement[] stackTrace) {
        String threadInfo = holdingThread != null ? holdingThread.getName() : "unknown";

        if (stackTrace != null && stackTrace.length > 0) {
            String formattedTrace = formatStackTrace(stackTrace);
            log.warn("[LEAK DETECTED] Connection {} held for too long by thread: {}. Acquisition trace:\n{}",
                connection, threadInfo, formattedTrace);
        } else {
            log.warn("[LEAK DETECTED] Connection {} held for too long by thread: {}",
                connection, threadInfo);
        }
    }

    @Override
    public void onConnectionExpired(Object connection, long ageMs) {
        log.info("[MAX LIFETIME] Connection {} expired after {}ms, will be recycled", connection, ageMs);
    }

    @Override
    public void onConnectionRecycled(Object connection) {
        log.info("[RECYCLE] Connection {} successfully recycled", connection);
    }

    @Override
    public void onHousekeepingError(String message, Throwable cause) {
        if (cause != null) {
            log.error("[HOUSEKEEPING ERROR] {}", message, cause);
        } else {
            log.error("[HOUSEKEEPING ERROR] {}", message);
        }
    }

    @Override
    public void onPoolStateLog(String stateInfo) {
        log.info(stateInfo);
    }

    /**
     * Formats a stack trace as a multi-line string.
     *
     * @param stackTrace the stack trace elements
     * @return formatted stack trace string
     */
    private String formatStackTrace(StackTraceElement[] stackTrace) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : stackTrace) {
            sb.append("\tat ").append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
