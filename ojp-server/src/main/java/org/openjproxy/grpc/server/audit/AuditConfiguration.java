package org.openjproxy.grpc.server.audit;

/**
 * Configuration holder for audit logging settings.
 * This class encapsulates all audit-related configuration options.
 */
public class AuditConfiguration {
    
    private final boolean enabled;
    private final String logPath;
    private final boolean logConnections;
    private final boolean logQueries;
    private final boolean logAuth;
    
    public AuditConfiguration(boolean enabled, String logPath, boolean logConnections, 
                             boolean logQueries, boolean logAuth) {
        this.enabled = enabled;
        this.logPath = logPath;
        this.logConnections = logConnections;
        this.logQueries = logQueries;
        this.logAuth = logAuth;
    }
    
    /**
     * Returns whether audit logging is enabled globally.
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Returns the path to the audit log file.
     */
    public String getLogPath() {
        return logPath;
    }
    
    /**
     * Returns whether connection events should be logged.
     */
    public boolean isLogConnections() {
        return enabled && logConnections;
    }
    
    /**
     * Returns whether query events should be logged.
     */
    public boolean isLogQueries() {
        return enabled && logQueries;
    }
    
    /**
     * Returns whether authentication events should be logged.
     */
    public boolean isLogAuth() {
        return enabled && logAuth;
    }
    
    @Override
    public String toString() {
        return "AuditConfiguration{" +
                "enabled=" + enabled +
                ", logPath='" + logPath + '\'' +
                ", logConnections=" + logConnections +
                ", logQueries=" + logQueries +
                ", logAuth=" + logAuth +
                '}';
    }
}
