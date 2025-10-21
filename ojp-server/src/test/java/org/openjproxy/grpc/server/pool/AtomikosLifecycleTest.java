package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AtomikosLifecycle - validates transaction manager lifecycle management.
 */
public class AtomikosLifecycleTest {

    @AfterEach
    public void cleanup() {
        // Ensure Atomikos is shutdown after each test
        if (AtomikosLifecycle.isInitialized()) {
            AtomikosLifecycle.shutdown();
        }
    }

    @Test
    public void testInitializeAndShutdown() {
        // Initially should not be initialized
        assertFalse(AtomikosLifecycle.isInitialized());
        
        // Initialize
        AtomikosLifecycle.initialize(false, "./test-atomikos-logs");
        
        // Should be initialized now
        assertTrue(AtomikosLifecycle.isInitialized());
        assertNotNull(AtomikosLifecycle.getUserTransactionService());
        
        // Shutdown
        AtomikosLifecycle.shutdown();
        
        // Should not be initialized anymore
        assertFalse(AtomikosLifecycle.isInitialized());
        assertNull(AtomikosLifecycle.getUserTransactionService());
    }

    @Test
    public void testMultipleInitializeCallsAreIdempotent() {
        assertFalse(AtomikosLifecycle.isInitialized());
        
        // First initialize
        AtomikosLifecycle.initialize(false, "./test-atomikos-logs");
        assertTrue(AtomikosLifecycle.isInitialized());
        
        // Second initialize should be no-op
        AtomikosLifecycle.initialize(false, "./test-atomikos-logs");
        assertTrue(AtomikosLifecycle.isInitialized());
        
        // Cleanup
        AtomikosLifecycle.shutdown();
        assertFalse(AtomikosLifecycle.isInitialized());
    }

    @Test
    public void testShutdownWhenNotInitialized() {
        assertFalse(AtomikosLifecycle.isInitialized());
        
        // Shutdown should not throw exception even if not initialized
        assertDoesNotThrow(() -> AtomikosLifecycle.shutdown());
        
        assertFalse(AtomikosLifecycle.isInitialized());
    }

    @Test
    public void testInitializeWithLoggingEnabled() {
        assertFalse(AtomikosLifecycle.isInitialized());
        
        // Initialize with logging enabled
        AtomikosLifecycle.initialize(true, "./test-atomikos-logs-enabled");
        
        assertTrue(AtomikosLifecycle.isInitialized());
        assertNotNull(AtomikosLifecycle.getUserTransactionService());
        
        // Cleanup
        AtomikosLifecycle.shutdown();
    }

    @Test
    public void testInitializeWithNullLogDir() {
        assertFalse(AtomikosLifecycle.isInitialized());
        
        // Initialize with null log directory (should use default)
        AtomikosLifecycle.initialize(false, null);
        
        assertTrue(AtomikosLifecycle.isInitialized());
        
        // Cleanup
        AtomikosLifecycle.shutdown();
    }
}
