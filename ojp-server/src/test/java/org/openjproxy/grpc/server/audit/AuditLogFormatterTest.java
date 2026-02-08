package org.openjproxy.grpc.server.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AuditLogFormatter class.
 */
public class AuditLogFormatterTest {

    private final AuditLogFormatter formatter = new AuditLogFormatter();

    @Test
    public void testFormatBasicEvent() {
        Instant timestamp = Instant.parse("2026-01-24T21:25:22.587Z");
        
        AuditEvent event = new AuditEvent.Builder()
            .timestamp(timestamp)
            .eventType(AuditEvent.EventType.CONNECTION)
            .level(AuditEvent.Level.INFO)
            .sessionId("sess-12345")
            .clientIp("192.168.1.100")
            .user("app-user-1")
            .message("Connection established")
            .build();
        
        String formatted = formatter.format(event);
        
        assertTrue(formatted.contains("[2026-01-24T21:25:22.587Z]"));
        assertTrue(formatted.contains("[INFO]"));
        assertTrue(formatted.contains("[CONNECTION]"));
        assertTrue(formatted.contains("[sess-12345]"));
        assertTrue(formatted.contains("[192.168.1.100]"));
        assertTrue(formatted.contains("[app-user-1]"));
        assertTrue(formatted.contains("Connection established"));
    }

    @Test
    public void testFormatEventWithMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("database", "postgresql");
        metadata.put("host", "db-server-1");
        metadata.put("port", 5432);
        
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .sessionId("sess-12345")
            .message("Connection established")
            .metadata(metadata)
            .build();
        
        String formatted = formatter.format(event);
        
        assertTrue(formatted.contains("Connection established"));
        assertTrue(formatted.contains("\"database\":\"postgresql\""));
        assertTrue(formatted.contains("\"host\":\"db-server-1\""));
        assertTrue(formatted.contains("\"port\":5432"));
    }

    @Test
    public void testFormatQueryEvent() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sql", "SELECT * FROM users WHERE id = ?");
        metadata.put("executionTimeMs", 45);
        metadata.put("rowCount", 1);
        
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.QUERY)
            .level(AuditEvent.Level.INFO)
            .sessionId("sess-12345")
            .clientIp("192.168.1.100")
            .user("app-user-1")
            .message("Query executed")
            .metadata(metadata)
            .build();
        
        String formatted = formatter.format(event);
        
        assertTrue(formatted.contains("[QUERY]"));
        assertTrue(formatted.contains("Query executed"));
        assertTrue(formatted.contains("\"sql\":"));
        assertTrue(formatted.contains("\"executionTimeMs\":45"));
        assertTrue(formatted.contains("\"rowCount\":1"));
    }

    @Test
    public void testFormatAuthFailureEvent() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reason", "invalid_credentials");
        metadata.put("attempts", 3);
        
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.AUTH)
            .level(AuditEvent.Level.WARN)
            .sessionId("sess-67890")
            .clientIp("10.0.0.50")
            .user("unknown")
            .message("Authentication failed")
            .metadata(metadata)
            .build();
        
        String formatted = formatter.format(event);
        
        assertTrue(formatted.contains("[WARN]"));
        assertTrue(formatted.contains("[AUTH]"));
        assertTrue(formatted.contains("Authentication failed"));
        assertTrue(formatted.contains("\"reason\":\"invalid_credentials\""));
        assertTrue(formatted.contains("\"attempts\":3"));
    }

    @Test
    public void testFormatEventWithNullSessionId() {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .sessionId(null)
            .message("Test")
            .build();
        
        String formatted = formatter.format(event);
        assertTrue(formatted.contains("[unknown]")); // Should use 'unknown' for null sessionId
    }

    @Test
    public void testFormatEventWithEmptyMetadata() {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .message("Connection established")
            .build();
        
        String formatted = formatter.format(event);
        
        assertTrue(formatted.contains("Connection established"));
        // Should not have trailing metadata JSON
        assertFalse(formatted.endsWith(" - {}"));
    }

    @Test
    public void testJsonEscaping() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("message", "Test \"quoted\" text with\nnewline and\ttab");
        
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .message("Test")
            .metadata(metadata)
            .build();
        
        String formatted = formatter.format(event);
        
        assertTrue(formatted.contains("\\\"quoted\\\""));
        assertTrue(formatted.contains("\\n"));
        assertTrue(formatted.contains("\\t"));
    }

    @Test
    public void testMetadataWithBooleanValues() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("success", true);
        metadata.put("failure", false);
        
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .message("Test")
            .metadata(metadata)
            .build();
        
        String formatted = formatter.format(event);
        
        assertTrue(formatted.contains("\"success\":true"));
        assertTrue(formatted.contains("\"failure\":false"));
    }

    @Test
    public void testMetadataWithNullValue() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nullValue", null);
        
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .message("Test")
            .metadata(metadata)
            .build();
        
        String formatted = formatter.format(event);
        
        assertTrue(formatted.contains("\"nullValue\":null"));
    }
}
