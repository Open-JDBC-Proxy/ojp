package org.openjproxy.grpc.server.audit;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents an audit event in the OJP server.
 * Audit events track security-related activities such as connections, queries, and authentication.
 */
public class AuditEvent {
    
    /**
     * Types of audit events that can be logged.
     */
    public enum EventType {
        /** Connection established event */
        CONNECTION,
        /** Query execution event */
        QUERY,
        /** Authentication event */
        AUTH
    }
    
    /**
     * Log levels for audit events.
     */
    public enum Level {
        INFO,
        WARN,
        ERROR
    }
    
    private final Instant timestamp;
    private final Level level;
    private final EventType eventType;
    private final String sessionId;
    private final String clientIp;
    private final String user;
    private final String message;
    private final Map<String, Object> metadata;
    
    private AuditEvent(Builder builder) {
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.level = builder.level;
        this.eventType = builder.eventType;
        this.sessionId = builder.sessionId;
        this.clientIp = builder.clientIp;
        this.user = builder.user;
        this.message = builder.message;
        this.metadata = new HashMap<>(builder.metadata);
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public Level getLevel() {
        return level;
    }
    
    public EventType getEventType() {
        return eventType;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public String getClientIp() {
        return clientIp;
    }
    
    public String getUser() {
        return user;
    }
    
    public String getMessage() {
        return message;
    }
    
    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }
    
    /**
     * Builder for creating AuditEvent instances.
     */
    public static class Builder {
        private Instant timestamp;
        private Level level = Level.INFO;
        private EventType eventType;
        private String sessionId;
        private String clientIp = "unknown";
        private String user = "unknown";
        private String message;
        private Map<String, Object> metadata = new HashMap<>();
        
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder level(Level level) {
            this.level = level;
            return this;
        }
        
        public Builder eventType(EventType eventType) {
            this.eventType = eventType;
            return this;
        }
        
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }
        
        public Builder clientIp(String clientIp) {
            if (clientIp != null && !clientIp.isEmpty()) {
                this.clientIp = clientIp;
            }
            return this;
        }
        
        public Builder user(String user) {
            if (user != null && !user.isEmpty()) {
                this.user = user;
            }
            return this;
        }
        
        public Builder message(String message) {
            this.message = message;
            return this;
        }
        
        public Builder metadata(Map<String, Object> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }
        
        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public AuditEvent build() {
            if (eventType == null) {
                throw new IllegalStateException("Event type must be specified");
            }
            if (message == null || message.isEmpty()) {
                throw new IllegalStateException("Message must be specified");
            }
            return new AuditEvent(this);
        }
    }
}
