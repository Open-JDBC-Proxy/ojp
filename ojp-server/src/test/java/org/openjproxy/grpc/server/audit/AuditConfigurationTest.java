package org.openjproxy.grpc.server.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AuditConfiguration class.
 */
public class AuditConfigurationTest {

    @Test
    public void testDefaultConfiguration() {
        AuditConfiguration config = new AuditConfiguration(
            false, "logs/ojp-audit.log", true, false, true);
        
        assertFalse(config.isEnabled());
        assertEquals("logs/ojp-audit.log", config.getLogPath());
        assertFalse(config.isLogConnections()); // Returns false when audit is disabled
        assertFalse(config.isLogQueries()); // Returns false when audit is disabled
        assertFalse(config.isLogAuth()); // Returns false when audit is disabled
    }

    @Test
    public void testEnabledConfiguration() {
        AuditConfiguration config = new AuditConfiguration(
            true, "/var/log/ojp/audit.log", true, true, true);
        
        assertTrue(config.isEnabled());
        assertEquals("/var/log/ojp/audit.log", config.getLogPath());
        assertTrue(config.isLogConnections());
        assertTrue(config.isLogQueries());
        assertTrue(config.isLogAuth());
    }

    @Test
    public void testPartiallyEnabledConfiguration() {
        AuditConfiguration config = new AuditConfiguration(
            true, "logs/audit.log", true, false, false);
        
        assertTrue(config.isEnabled());
        assertTrue(config.isLogConnections());
        assertFalse(config.isLogQueries());
        assertFalse(config.isLogAuth());
    }

    @Test
    public void testToString() {
        AuditConfiguration config = new AuditConfiguration(
            true, "logs/audit.log", true, false, true);
        
        String result = config.toString();
        assertTrue(result.contains("enabled=true"));
        assertTrue(result.contains("logPath='logs/audit.log'"));
        assertTrue(result.contains("logConnections=true"));
        assertTrue(result.contains("logQueries=false"));
        assertTrue(result.contains("logAuth=true"));
    }
}
