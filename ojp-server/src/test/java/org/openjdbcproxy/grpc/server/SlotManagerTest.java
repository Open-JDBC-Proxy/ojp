package org.openjdbcproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SlotManager functionality.
 */
public class SlotManagerTest {

    private SlotManager slotManager;

    @BeforeEach
    public void setUp() {
        // 10 total slots, 20% slow (2 slots), 80% fast (8 slots), 10 second idle timeout
        // Long timeout prevents borrowing in basic slot limit tests
        slotManager = new SlotManager(10, 20, 10000);
    }

    @Test
    public void testInitialization() {
        assertEquals(10, slotManager.getTotalSlots());
        assertEquals(2, slotManager.getSlowSlots());
        assertEquals(8, slotManager.getFastSlots());
        assertEquals(10000, slotManager.getIdleTimeoutMs());
        assertTrue(slotManager.isEnabled());
        assertEquals(0, slotManager.getActiveSlowOperations());
        assertEquals(0, slotManager.getActiveFastOperations());
    }

    @Test
    public void testInvalidConfigurationHandling() {
        // Test invalid total slots
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(0, 20, 100));
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(-1, 20, 100));

        // Test invalid percentage
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(10, -1, 100));
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(10, 101, 100));

        // Test invalid timeout
        assertThrows(IllegalArgumentException.class, () -> new SlotManager(10, 20, -1));
    }

    @Test
    public void testSlowSlotAcquisitionAndRelease() throws InterruptedException {
        // Acquire a slow slot
        assertTrue(slotManager.acquireSlowSlot(1000));
        assertEquals(1, slotManager.getActiveSlowOperations());

        // Acquire another slow slot
        assertTrue(slotManager.acquireSlowSlot(1000));
        assertEquals(2, slotManager.getActiveSlowOperations());

        // Try to acquire a third slow slot (should fail as we only have 2)
        assertFalse(slotManager.acquireSlowSlot(100));
        assertEquals(2, slotManager.getActiveSlowOperations());

        // Release one slow slot
        slotManager.releaseSlowSlot();
        assertEquals(1, slotManager.getActiveSlowOperations());

        // Should be able to acquire again
        assertTrue(slotManager.acquireSlowSlot(1000));
        assertEquals(2, slotManager.getActiveSlowOperations());

        // Release all
        slotManager.releaseSlowSlot();
        slotManager.releaseSlowSlot();
        assertEquals(0, slotManager.getActiveSlowOperations());
    }

    @Test
    public void testFastSlotAcquisitionAndRelease() throws InterruptedException {
        // Acquire fast slots up to the limit (8)
        for (int i = 0; i < 8; i++) {
            assertTrue(slotManager.acquireFastSlot(1000));
            assertEquals(i + 1, slotManager.getActiveFastOperations());
        }

        // Try to acquire one more (should fail)
        assertFalse(slotManager.acquireFastSlot(100));
        assertEquals(8, slotManager.getActiveFastOperations());

        // Release one and try again
        slotManager.releaseFastSlot();
        assertEquals(7, slotManager.getActiveFastOperations());

        assertTrue(slotManager.acquireFastSlot(1000));
        assertEquals(8, slotManager.getActiveFastOperations());

        // Release all
        for (int i = 0; i < 8; i++) {
            slotManager.releaseFastSlot();
        }
        assertEquals(0, slotManager.getActiveFastOperations());
    }

    @Test
    public void testSlotBorrowingFastToSlow() throws InterruptedException {
        // Create a SlotManager with short idle timeout for borrowing tests
        SlotManager borrowingManager = new SlotManager(10, 20, 100);
        
        // Fill up slow slots
        assertTrue(borrowingManager.acquireSlowSlot(1000));
        assertTrue(borrowingManager.acquireSlowSlot(1000));
        assertEquals(2, borrowingManager.getActiveSlowOperations());

        // Try to acquire another slow slot immediately (should fail - no idle time yet)
        assertFalse(borrowingManager.acquireSlowSlot(100));

        // Wait for idle timeout and try again (should still fail because fast slots aren't idle)
        Thread.sleep(150);
        assertFalse(borrowingManager.acquireSlowSlot(100));

        // Release slow slots for cleanup
        borrowingManager.releaseSlowSlot();
        borrowingManager.releaseSlowSlot();
    }

    @Test
    public void testSlotBorrowingSlowToFast() throws InterruptedException {
        // Create a SlotManager with short idle timeout for borrowing tests
        SlotManager borrowingManager = new SlotManager(10, 20, 100);
        
        // Fill up fast slots
        for (int i = 0; i < 8; i++) {
            assertTrue(borrowingManager.acquireFastSlot(1000));
        }
        assertEquals(8, borrowingManager.getActiveFastOperations());

        // Try to acquire another fast slot immediately (should fail - no idle time yet)
        assertFalse(borrowingManager.acquireFastSlot(100));

        // Wait for idle timeout and try again (should still fail because slow slots aren't idle)
        Thread.sleep(150);
        assertFalse(borrowingManager.acquireFastSlot(100));

        // Release fast slots for cleanup
        for (int i = 0; i < 8; i++) {
            borrowingManager.releaseFastSlot();
        }
    }

    @Test
    public void testDisabledSlotManager() throws InterruptedException {
        SlotManager disabledManager = new SlotManager(5, 20, 100);
        disabledManager.setEnabled(false);

        assertFalse(disabledManager.isEnabled());

        // When disabled, all acquisitions should succeed
        for (int i = 0; i < 20; i++) { // Try to acquire more than the limit
            assertTrue(disabledManager.acquireSlowSlot(100));
            assertTrue(disabledManager.acquireFastSlot(100));
        }

        // Releasing should also work (but be no-ops)
        for (int i = 0; i < 20; i++) {
            disabledManager.releaseSlowSlot();
            disabledManager.releaseFastSlot();
        }
    }

    @Test
    public void testEdgeCaseConfigurations() {
        // Test 100% slow slots
        SlotManager allSlowManager = new SlotManager(10, 100, 100);
        assertEquals(10, allSlowManager.getSlowSlots());
        assertEquals(0, allSlowManager.getFastSlots());

        // Test 0% slow slots (minimum 1 is enforced)
        SlotManager noSlowManager = new SlotManager(10, 0, 100);
        assertEquals(1, noSlowManager.getSlowSlots());
        assertEquals(9, noSlowManager.getFastSlots());

        // Test single slot
        SlotManager singleSlotManager = new SlotManager(1, 50, 100);
        assertEquals(1, singleSlotManager.getSlowSlots());
        assertEquals(0, singleSlotManager.getFastSlots());
    }

    @Test
    public void testStatusString() {
        String status = slotManager.getStatus();
        assertNotNull(status);
        assertTrue(status.contains("SlotManager"));
        assertTrue(status.contains("total=10"));
        assertTrue(status.contains("slow=0/2"));
        assertTrue(status.contains("fast=0/8"));
        assertTrue(status.contains("enabled=true"));
    }

    @Test
    public void testConcurrentSlotAcquisition() throws InterruptedException {
        final int numThreads = 5;
        final boolean[] results = new boolean[numThreads];
        Thread[] threads = new Thread[numThreads];

        // Create threads that try to acquire slow slots concurrently
        for (int i = 0; i < numThreads; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    results[threadIndex] = slotManager.acquireSlowSlot(1000);
                } catch (InterruptedException e) {
                    results[threadIndex] = false;
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Count successful acquisitions
        int successCount = 0;
        for (boolean result : results) {
            if (result) successCount++;
        }

        // Should only have 2 successful acquisitions (our slow slot limit)
        assertEquals(2, successCount);
        assertEquals(2, slotManager.getActiveSlowOperations());

        // Clean up
        slotManager.releaseSlowSlot();
        slotManager.releaseSlowSlot();
    }
}