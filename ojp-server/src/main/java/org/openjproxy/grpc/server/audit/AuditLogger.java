package org.openjproxy.grpc.server.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core audit logging implementation with asynchronous logging support.
 * This class provides minimal performance impact by using a dedicated thread
 * for writing audit events to the log file.
 */
public class AuditLogger {
    
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");
    private static final Logger logger = LoggerFactory.getLogger(AuditLogger.class);
    
    private final AuditConfiguration configuration;
    private final AuditLogFormatter formatter;
    private final BlockingQueue<AuditEvent> eventQueue;
    private final ExecutorService executorService;
    private final AtomicBoolean running;
    
    private static final int QUEUE_CAPACITY = 10000;
    
    /**
     * Creates a new AuditLogger with the specified configuration.
     * 
     * @param configuration Audit configuration settings
     */
    public AuditLogger(AuditConfiguration configuration) {
        this.configuration = configuration;
        this.formatter = new AuditLogFormatter();
        this.eventQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.running = new AtomicBoolean(false);
        
        if (configuration.isEnabled()) {
            this.executorService = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "audit-logger-thread");
                t.setDaemon(true);
                return t;
            });
            start();
            logger.info("Audit logging initialized: {}", configuration);
            logAuditSystemStatus();
        } else {
            this.executorService = null;
            logger.info("Audit logging is disabled");
        }
    }
    
    /**
     * Starts the async audit logging thread.
     */
    private void start() {
        if (running.compareAndSet(false, true)) {
            executorService.submit(this::processEvents);
        }
    }
    
    /**
     * Processes audit events from the queue and writes them to the log.
     */
    private void processEvents() {
        logger.debug("Audit logger thread started");
        
        while (running.get() || !eventQueue.isEmpty()) {
            try {
                AuditEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    writeEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Audit logger thread interrupted", e);
                break;
            } catch (Exception e) {
                logger.error("Error processing audit event", e);
            }
        }
        
        logger.debug("Audit logger thread stopped");
    }
    
    /**
     * Writes an audit event to the log.
     */
    private void writeEvent(AuditEvent event) {
        try {
            String formattedMessage = formatter.format(event);
            
            switch (event.getLevel()) {
                case INFO:
                    auditLog.info(formattedMessage);
                    break;
                case WARN:
                    auditLog.warn(formattedMessage);
                    break;
                case ERROR:
                    auditLog.error(formattedMessage);
                    break;
                default:
                    auditLog.info(formattedMessage);
            }
        } catch (Exception e) {
            logger.error("Failed to write audit event", e);
        }
    }
    
    /**
     * Logs an audit event if the appropriate category is enabled.
     * 
     * @param event The audit event to log
     */
    public void log(AuditEvent event) {
        if (!configuration.isEnabled()) {
            return;
        }
        
        // Check if this event type should be logged
        boolean shouldLog = false;
        switch (event.getEventType()) {
            case CONNECTION:
                shouldLog = configuration.isLogConnections();
                break;
            case QUERY:
                shouldLog = configuration.isLogQueries();
                break;
            case AUTH:
                shouldLog = configuration.isLogAuth();
                break;
        }
        
        if (!shouldLog) {
            return;
        }
        
        // Try to add to queue, drop if queue is full (non-blocking)
        if (!eventQueue.offer(event)) {
            logger.warn("Audit event queue full, dropping event: {}", event.getEventType());
        }
    }
    
    /**
     * Shuts down the audit logger gracefully.
     */
    public void shutdown() {
        if (!configuration.isEnabled() || executorService == null) {
            return;
        }
        
        logger.info("Shutting down audit logger...");
        running.set(false);
        
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
        
        logger.info("Audit logger shut down");
    }
    
    /**
     * Logs the audit system status on startup.
     */
    private void logAuditSystemStatus() {
        logger.info("Audit System Configuration:");
        logger.info("  Audit Log Path: {}", configuration.getLogPath());
        logger.info("  Log Connections: {}", configuration.isLogConnections());
        logger.info("  Log Queries: {}", configuration.isLogQueries());
        logger.info("  Log Auth: {}", configuration.isLogAuth());
        
        if (configuration.isLogQueries()) {
            logger.warn("=============================================================================");
            logger.warn("WARNING: Audit query logging is ENABLED.");
            logger.warn("This will significantly impact performance.");
            logger.warn("Only use in non-production environments or for debugging purposes.");
            logger.warn("=============================================================================");
        }
    }
    
    /**
     * Returns the audit configuration.
     */
    public AuditConfiguration getConfiguration() {
        return configuration;
    }
}
