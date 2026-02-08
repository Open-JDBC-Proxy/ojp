package org.openjproxy.grpc.server.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AuditEvent class.
 */
public class AuditEventTest {

    @Test
    public void testBuildBasicEvent() {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .level(AuditEvent.Level.INFO)
            .sessionId("sess-12345")
            .clientIp("192.168.1.100")
            .user("test-user")
            .message("Connection established")
            .build();
        
        assertEquals(AuditEvent.EventType.CONNECTION, event.getEventType());
        assertEquals(AuditEvent.Level.INFO, event.getLevel());
        assertEquals("sess-12345", event.getSessionId());
        assertEquals("192.168.1.100", event.getClientIp());
        assertEquals("test-user", event.getUser());
        assertEquals("Connection established", event.getMessage());
        assertNotNull(event.getTimestamp());
    }

    @Test
    public void testBuildEventWithMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("database", "postgresql");
        metadata.put("port", 5432);
        
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .message("Connection established")
            .metadata(metadata)
            .build();
        
        Map<String, Object> eventMetadata = event.getMetadata();
        assertEquals("postgresql", eventMetadata.get("database"));
        assertEquals(5432, eventMetadata.get("port"));
    }

    @Test
    public void testAddMetadata() {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.QUERY)
            .message("Query executed")
            .addMetadata("sql", "SELECT * FROM users")
            .addMetadata("executionTimeMs", 45L)
            .build();
        
        Map<String, Object> metadata = event.getMetadata();
        assertEquals("SELECT * FROM users", metadata.get("sql"));
        assertEquals(45L, metadata.get("executionTimeMs"));
    }

    @Test
    public void testDefaultValues() {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.AUTH)
            .message("Authentication attempt")
            .build();
        
        assertEquals(AuditEvent.Level.INFO, event.getLevel());
        assertEquals("unknown", event.getClientIp());
        assertEquals("unknown", event.getUser());
        assertTrue(event.getMetadata().isEmpty());
    }

    @Test
    public void testCustomTimestamp() {
        Instant timestamp = Instant.parse("2026-01-24T21:25:22.587Z");
        
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .timestamp(timestamp)
            .message("Test event")
            .build();
        
        assertEquals(timestamp, event.getTimestamp());
    }

    @Test
    public void testEmptyClientIpUsesDefault() {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .clientIp("")
            .message("Test")
            .build();
        
        assertEquals("unknown", event.getClientIp());
    }

    @Test
    public void testNullClientIpUsesDefault() {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .clientIp(null)
            .message("Test")
            .build();
        
        assertEquals("unknown", event.getClientIp());
    }

    @Test
    public void testMissingEventTypeThrowsException() {
        assertThrows(IllegalStateException.class, () -> {
            new AuditEvent.Builder()
                .message("Test")
                .build();
        });
    }

    @Test
    public void testMissingMessageThrowsException() {
        assertThrows(IllegalStateException.class, () -> {
            new AuditEvent.Builder()
                .eventType(AuditEvent.EventType.CONNECTION)
                .build();
        });
    }

    @Test
    public void testEmptyMessageThrowsException() {
        assertThrows(IllegalStateException.class, () -> {
            new AuditEvent.Builder()
                .eventType(AuditEvent.EventType.CONNECTION)
                .message("")
                .build();
        });
    }

    @Test
    public void testMetadataIsDefensiveCopy() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEvent.EventType.CONNECTION)
            .message("Test")
            .metadata(metadata)
            .build();
        
        // Modify original map
        metadata.put("key2", "value2");
        
        // Event metadata should not be affected
        Map<String, Object> eventMetadata = event.getMetadata();
        assertTrue(eventMetadata.containsKey("key1"));
        assertFalse(eventMetadata.containsKey("key2"));
    }
}
