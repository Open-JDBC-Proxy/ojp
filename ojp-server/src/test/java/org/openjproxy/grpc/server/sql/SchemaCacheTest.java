package org.openjproxy.grpc.server.sql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SchemaCache.
 */
class SchemaCacheTest {
    
    private SchemaCache schemaCache;
    
    @BeforeEach
    void setUp() {
        schemaCache = new SchemaCache();
    }
    
    @Test
    void testInitialStateIsEmpty() {
        SchemaMetadata schema = schemaCache.getSchema(false);
        assertNull(schema, "Initial schema should be null");
        assertEquals(0, schemaCache.getLastRefreshTimestamp(), "Initial refresh timestamp should be 0");
    }
    
    @Test
    void testUpdateSchema() {
        Map<String, TableMetadata> tables = new HashMap<>();
        SchemaMetadata schema = new SchemaMetadata(tables, System.currentTimeMillis(), "catalog", "schema");
        
        schemaCache.updateSchema(schema);
        
        SchemaMetadata retrievedSchema = schemaCache.getSchema(false);
        assertNotNull(retrievedSchema, "Schema should not be null after update");
        assertEquals(schema, retrievedSchema, "Retrieved schema should match the one that was set");
        assertTrue(schemaCache.getLastRefreshTimestamp() > 0, "Refresh timestamp should be set");
    }
    
    @Test
    void testUpdateSchemaWithNull() {
        Map<String, TableMetadata> tables = new HashMap<>();
        SchemaMetadata schema = new SchemaMetadata(tables, System.currentTimeMillis(), "catalog", "schema");
        schemaCache.updateSchema(schema);
        
        // Try to update with null - should be ignored
        schemaCache.updateSchema(null);
        
        SchemaMetadata retrievedSchema = schemaCache.getSchema(false);
        assertNotNull(retrievedSchema, "Schema should still be present after null update attempt");
        assertEquals(schema, retrievedSchema, "Schema should not change when null is passed");
    }
    
    @Test
    void testNeedsRefresh() {
        // Initially with timestamp 0, it WILL need refresh if interval > 0
        // because timeSinceLastRefresh will be very large (current time - 0)
        assertTrue(schemaCache.needsRefresh(1000), "Should need refresh when timestamp is 0");
        
        // Update schema
        Map<String, TableMetadata> tables = new HashMap<>();
        SchemaMetadata schema = new SchemaMetadata(tables, System.currentTimeMillis(), "catalog", "schema");
        schemaCache.updateSchema(schema);
        
        // Should not need refresh immediately
        assertFalse(schemaCache.needsRefresh(1000), "Should not need refresh immediately after update");
        
        // Wait a bit and check again
        try {
            Thread.sleep(100); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Should need refresh with very short interval
        assertTrue(schemaCache.needsRefresh(50), "Should need refresh after interval has passed");
    }
    
    @Test
    void testNeedsRefreshWithZeroInterval() {
        Map<String, TableMetadata> tables = new HashMap<>();
        SchemaMetadata schema = new SchemaMetadata(tables, System.currentTimeMillis(), "catalog", "schema");
        schemaCache.updateSchema(schema);
        
        // Zero interval means refresh is disabled
        assertFalse(schemaCache.needsRefresh(0), "Should not need refresh when interval is 0");
    }
    
    @Test
    void testRefreshLock() {
        // Should be able to acquire lock initially
        assertTrue(schemaCache.tryAcquireRefreshLock(), "Should be able to acquire lock initially");
        assertTrue(schemaCache.isRefreshInProgress(), "Refresh should be in progress");
        
        // Should not be able to acquire lock again
        assertFalse(schemaCache.tryAcquireRefreshLock(), "Should not be able to acquire lock twice");
        
        // Release lock
        schemaCache.releaseRefreshLock();
        assertFalse(schemaCache.isRefreshInProgress(), "Refresh should not be in progress after release");
        
        // Should be able to acquire lock again
        assertTrue(schemaCache.tryAcquireRefreshLock(), "Should be able to acquire lock after release");
        schemaCache.releaseRefreshLock();
    }
    
    @Test
    void testConcurrentLockAcquisition() throws InterruptedException {
        final boolean[] thread1Acquired = {false};
        final boolean[] thread2Acquired = {false};
        
        Thread thread1 = new Thread(() -> {
            thread1Acquired[0] = schemaCache.tryAcquireRefreshLock();
            try {
                Thread.sleep(100);//NOSONAR
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            schemaCache.releaseRefreshLock();
        });
        
        Thread thread2 = new Thread(() -> {
            try {
                // Start a bit later
                Thread.sleep(50); //NOSONAR
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread2Acquired[0] = schemaCache.tryAcquireRefreshLock();
            schemaCache.releaseRefreshLock();
        });
        
        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
        
        assertTrue(thread1Acquired[0], "Thread 1 should have acquired the lock");
        assertFalse(thread2Acquired[0], "Thread 2 should not have acquired the lock while thread 1 held it");
    }
}
