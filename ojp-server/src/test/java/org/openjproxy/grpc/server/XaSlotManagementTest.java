package org.openjproxy.grpc.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for XA slot management via SlowQuerySegregationManager.
 * Tests the replacement of XaTransactionLimiter with SlotManager-based approach.
 */
class XaSlotManagementTest {

    @Test
    void testXaSlotManagementWithSlowQueryDisabled() throws Exception {
        // Simulate XA with slow query segregation disabled
        // totalSlots=5, slowSlotPercentage=0 (all fast), fastSlotTimeout=1000ms
        SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                5,      // totalSlots
                0,      // slowSlotPercentage (0 = all slots are fast)
                0,      // idleTimeout (not relevant)
                0,      // slowSlotTimeout (not relevant)
                1000,   // fastSlotTimeout
                0,      // updateGlobalAvgInterval (0 = no performance monitoring)
                true    // enabled
        );

        // Test that we can acquire and release slots
        manager.executeWithSegregation("test-op-1", () -> {
            // Simulate work
            Thread.sleep(50); //NOSONAR
            return "success";
        });

        // Verify slot manager status
        final SlotManager slotManager = manager.getSlotManager();
        assertNotNull(slotManager);
        assertTrue(slotManager.isEnabled());
        assertEquals(5, slotManager.getTotalSlots());
        // Note: SlotManager ensures min 1 slow slot, so with 5 total we get 1 slow + 4 fast
        assertEquals(4, slotManager.getFastSlots());
        assertEquals(1, slotManager.getSlowSlots());
    }

    @Test
    void testXaSlotManagementWithSlowQueryEnabled() throws Exception {
        // Simulate XA with slow query segregation enabled
        // totalSlots=10, slowSlotPercentage=30, so 3 slow + 7 fast
        SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                10,     // totalSlots
                30,     // slowSlotPercentage
                5000,   // idleTimeout
                10000,  // slowSlotTimeout
                5000,   // fastSlotTimeout
                0,      // updateGlobalAvgInterval
                true    // enabled
        );

        // Execute operations
        manager.executeWithSegregation("test-op-1", () -> {
            Thread.sleep(50); //NOSONAR
            return "success";
        });

        // Verify configuration
        final SlotManager slotManager = manager.getSlotManager();
        assertNotNull(slotManager);
        assertTrue(slotManager.isEnabled());
        assertEquals(10, slotManager.getTotalSlots());
        assertEquals(7, slotManager.getFastSlots());
        assertEquals(3, slotManager.getSlowSlots());
    }

    @Test
    void testConcurrentXaOperations() throws InterruptedException {
        int maxSlots = 5;
        SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                maxSlots,
                0,      // All fast slots
                0,
                0,
                5000,   // 5 second timeout
                0,
                true
        );

        int threadCount = 20;
        int iterationsPerThread = 5;

        AtomicInteger successCount;
        AtomicInteger maxConcurrent;
        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            successCount = new AtomicInteger(0);
            maxConcurrent = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready

                        for (int j = 0; j < iterationsPerThread; j++) {
                            manager.executeWithSegregation("test-op-" + threadId, () -> {
                                final SlotManager slotManager = manager.getSlotManager();
                                assertNotNull(slotManager);
                                // Track max concurrent
                                int concurrent = slotManager.getActiveFastOperations();
                                maxConcurrent.updateAndGet(current -> Math.max(current, concurrent));

                                // Simulate some work
                                Thread.sleep(10); //NOSONAR

                                return "success";
                            });
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Some operations may time out, which is expected
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Start all threads
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        // Verify that we never exceeded the limit
        assertTrue(maxConcurrent.get() <= maxSlots,
                "Max concurrent operations should not exceed limit: " + maxConcurrent.get());
        final SlotManager slotManager = manager.getSlotManager();
        assertNotNull(slotManager);

        // Verify all slots are released
        assertEquals(0, slotManager.getActiveFastOperations());

        // Verify successful operations
        assertTrue(successCount.get() > 0, "Should have successful operations");
        System.out.println("Successful operations: " + successCount.get() + " out of " + (threadCount * iterationsPerThread));
    }

    @Test
    void testSlotTimeout() {
        // Create manager with short timeout
        SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                2,      // Only 2 slots
                0,      // All fast
                0,
                0,
                50,     // 50ms timeout (short)
                0,
                true
        );

        // Acquire both slots by holding them
        CountDownLatch holdLatch = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            try {
                manager.executeWithSegregation("holder-1", () -> {
                    holdLatch.await(); // Hold the slot
                    return "success";
                });
            } catch (Exception e) {
                // Ignore
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                manager.executeWithSegregation("holder-2", () -> {
                    holdLatch.await(); // Hold the slot
                    return "success";
                });
            } catch (Exception e) {
                // Ignore
            }
        });

        t1.start();
        t2.start();

        // Give threads time to acquire slots
        try {
            Thread.sleep(100); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Now try to acquire third slot - should time out
        assertThrows(RuntimeException.class, () ->
                manager.executeWithSegregation("should-timeout", () -> "should not execute"));

        // Release the holders
        holdLatch.countDown();

        // Wait for threads to complete
        try {
            t1.join(1000);
            t2.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testSlotManagerDisabledMode() throws Exception {
        // When disabled, all operations should succeed immediately without slot management
        SlowQuerySegregationManager manager = new SlowQuerySegregationManager(
                1, 0, 0, 0, 0, 0, false  // disabled
        );

        // Even though there's only 1 "slot", we can execute multiple operations concurrently
        // because slot management is disabled
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(10);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    manager.executeWithSegregation("test-op", () -> {
                        Thread.sleep(100); //NOSONAR
                        successCount.incrementAndGet();
                        return "success";
                    });
                } catch (Exception e) {
                    // Should not happen
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        assertEquals(10, successCount.get(), "All operations should succeed when disabled");
    }
}
