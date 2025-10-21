package org.openjproxy.grpc.server.pool;

import com.atomikos.icatch.config.UserTransactionService;
import com.atomikos.icatch.config.UserTransactionServiceImp;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of Atomikos transaction manager.
 * Initializes and shuts down the UserTransactionService and UserTransactionManager.
 */
@Slf4j
public class AtomikosLifecycle {
    
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static UserTransactionService userTransactionService;
    
    /**
     * Initializes Atomikos transaction manager if not already initialized.
     * This should be called before creating any Atomikos datasources.
     * 
     * @param loggingEnabled Whether to enable Atomikos logging
     * @param logDir Directory for transaction logs (null to use default)
     */
    public static synchronized void initialize(boolean loggingEnabled, String logDir) {
        if (initialized.get()) {
            log.debug("Atomikos already initialized, skipping");
            return;
        }
        
        try {
            log.info("Initializing Atomikos transaction manager (logging={}, logDir={})", loggingEnabled, logDir);
            
            // Configure Atomikos properties
            Properties atomikosProperties = new Properties();
            
            // Set log directory
            if (logDir != null && !logDir.isEmpty()) {
                atomikosProperties.setProperty("com.atomikos.icatch.log_base_dir", logDir);
            } else {
                // Use a default directory in the working directory
                atomikosProperties.setProperty("com.atomikos.icatch.log_base_dir", "./atomikos-logs");
            }
            
            // Configure logging level
            if (!loggingEnabled) {
                // Minimize logging by setting to WARN level
                atomikosProperties.setProperty("com.atomikos.icatch.console_log_level", "WARN");
                atomikosProperties.setProperty("com.atomikos.icatch.output_dir", logDir != null ? logDir : "./atomikos-logs");
                atomikosProperties.setProperty("com.atomikos.icatch.enable_logging", "false");
            } else {
                atomikosProperties.setProperty("com.atomikos.icatch.console_log_level", "INFO");
                atomikosProperties.setProperty("com.atomikos.icatch.enable_logging", "true");
            }
            
            // Set service name
            atomikosProperties.setProperty("com.atomikos.icatch.service", "ojp-atomikos-tm");
            
            // Set transaction timeout (default 30 seconds)
            atomikosProperties.setProperty("com.atomikos.icatch.default_jta_timeout", "30000");
            
            // Initialize UserTransactionService
            userTransactionService = new UserTransactionServiceImp(atomikosProperties);
            userTransactionService.init();
            
            initialized.set(true);
            log.info("Atomikos transaction manager initialized successfully");
            
        } catch (Exception e) {
            log.error("Failed to initialize Atomikos transaction manager", e);
            throw new RuntimeException("Failed to initialize Atomikos", e);
        }
    }
    
    /**
     * Shuts down Atomikos transaction manager.
     * This should be called during server shutdown.
     */
    public static synchronized void shutdown() {
        if (!initialized.get()) {
            log.debug("Atomikos not initialized, nothing to shutdown");
            return;
        }
        
        try {
            log.info("Shutting down Atomikos transaction manager");
            
            if (userTransactionService != null) {
                userTransactionService.shutdown(true);
                userTransactionService = null;
            }
            
            initialized.set(false);
            log.info("Atomikos transaction manager shutdown successfully");
            
        } catch (Exception e) {
            log.error("Error shutting down Atomikos transaction manager", e);
            // Don't throw exception during shutdown
        }
    }
    
    /**
     * Returns whether Atomikos is initialized.
     */
    public static boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * Gets the UserTransactionService instance.
     * 
     * @return UserTransactionService or null if not initialized
     */
    public static UserTransactionService getUserTransactionService() {
        return userTransactionService;
    }
}
