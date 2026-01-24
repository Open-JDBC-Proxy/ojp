package org.openjproxy.grpc.server.audit;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Formats audit events into structured log entries.
 * Format: [TIMESTAMP] [LEVEL] [EVENT_TYPE] [SESSION_ID] [CLIENT_IP] [USER] - [MESSAGE] - [METADATA_JSON]
 */
public class AuditLogFormatter {
    
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
    
    /**
     * Formats an audit event into a structured log string.
     * 
     * @param event The audit event to format
     * @return Formatted log string
     */
    public String format(AuditEvent event) {
        StringBuilder sb = new StringBuilder();
        
        // [TIMESTAMP]
        sb.append("[").append(TIMESTAMP_FORMATTER.format(event.getTimestamp())).append("]");
        sb.append(" ");
        
        // [LEVEL]
        sb.append("[").append(event.getLevel()).append("]");
        sb.append(" ");
        
        // [EVENT_TYPE]
        sb.append("[").append(event.getEventType()).append("]");
        sb.append(" ");
        
        // [SESSION_ID]
        sb.append("[").append(event.getSessionId() != null ? event.getSessionId() : "unknown").append("]");
        sb.append(" ");
        
        // [CLIENT_IP]
        sb.append("[").append(event.getClientIp()).append("]");
        sb.append(" ");
        
        // [USER]
        sb.append("[").append(event.getUser()).append("]");
        sb.append(" - ");
        
        // [MESSAGE]
        sb.append(event.getMessage());
        
        // [METADATA_JSON]
        Map<String, Object> metadata = event.getMetadata();
        if (metadata != null && !metadata.isEmpty()) {
            sb.append(" - ");
            sb.append(toSimpleJson(metadata));
        }
        
        return sb.toString();
    }
    
    /**
     * Converts a map to a simple JSON-like string representation.
     * This is a lightweight alternative to using a full JSON library.
     */
    private String toSimpleJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                json.append(",");
            }
            first = false;
            
            json.append("\"").append(escapeJson(entry.getKey())).append("\":");
            
            Object value = entry.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof String) {
                json.append("\"").append(escapeJson(value.toString())).append("\"");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                // For other types, convert to string and quote
                json.append("\"").append(escapeJson(value.toString())).append("\"");
            }
        }
        
        json.append("}");
        return json.toString();
    }
    
    /**
     * Escapes special characters for JSON string values.
     */
    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
