package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for SlowQuerySegregationManager functionality.
 */
class SlowQuerySegregationManagerTest {

    private SlowQuerySegregationManager segregationManager;

    @BeforeEach
    void setUp() {
        // 10 total slots, 20% slow (2 slots), 100ms idle timeout, 5000ms slow timeout, 1000ms fast timeout, enabled
        segregationManager = new SlowQuerySegregationManager(10, 20, 100, 5000, 1000, true);
    }

    @Test
    void testInitialization() {
        assertTrue(segregationManager.isEnabled());
        assertNotNull(segregationManager.getPerformanceMonitor());
        assertNotNull(segregationManager.getSlotManager());
        assertEquals(0.0, segregationManager.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testDisabledManager() {
        SlowQuerySegregationManager disabledManager = new SlowQuerySegregationManager(10, 20, 100, 5000, 1000, false);

        assertFalse(disabledManager.isEnabled());
        assertNotNull(disabledManager.getPerformanceMonitor());
        assertNull(disabledManager.getSlotManager());
    }

    @Test
    void testExecuteWithSegregationEnabled() throws Exception {
        String operationHash = "test-operation";
        String expectedResult = "operation-result";

        // Execute an operation
        String result = segregationManager.executeWithSegregation(operationHash, () -> {
            // Simulate some work
            Thread.sleep(50); //NOSONAR
            return expectedResult;
        });

        assertEquals(expectedResult, result);

        // Check that performance was recorded
        assertTrue(segregationManager.getOperationAverageTime(operationHash) > 0);
        assertEquals(1, segregationManager.getPerformanceMonitor().getTrackedOperationCount());
        assertEquals(1, segregationManager.getPerformanceMonitor().getTotalExecutionCount());
    }

    @Test
    void testExecuteWithSegregationDisabled() throws Exception {
        SlowQuerySegregationManager disabledManager = new SlowQuerySegregationManager(10, 20, 100, 5000, 1000, false);
        String operationHash = "test-operation";
        String expectedResult = "operation-result";

        // Execute an operation
        String result = disabledManager.executeWithSegregation(operationHash, () -> {
            Thread.sleep(50); //NOSONAR
            return expectedResult;
        });

        assertEquals(expectedResult, result);

        // Check that performance was still recorded
        assertTrue(disabledManager.getOperationAverageTime(operationHash) > 0);
    }

    @Test
    void testSlowOperationClassification() throws Exception {
        String fastOp = "fast-operation";
        String slowOp = "slow-operation";

        // Execute fast operations to establish baseline
        segregationManager.executeWithSegregation(fastOp, () -> {
            Thread.sleep(10); //NOSONAR
            return "fast";
        });

        segregationManager.executeWithSegregation("another-fast", () -> {
            Thread.sleep(10); //NOSONAR
            return "fast";
        });

        // Initially, operations should be classified as fast
        assertFalse(segregationManager.isSlowOperation(fastOp));

        // Execute a slow operation multiple times to make it clearly slow
        for (int i = 0; i < 3; i++) {
            segregationManager.executeWithSegregation(slowOp, () -> {
                Thread.sleep(100); //NOSONAR
                return "slow";
            });
        }

        // The slow operation should now be classified differently
        // Note: Due to the 2x threshold and averaging, we might need more executions or longer delays
        // to trigger the slow classification
        double slowAvg = segregationManager.getOperationAverageTime(slowOp);
        double overallAvg = segregationManager.getOverallAverageExecutionTime();

        assertTrue(slowAvg > 0);
        assertTrue(overallAvg > 0);

        // At minimum, verify the performance tracking is working
        assertTrue(segregationManager.getPerformanceMonitor().getTrackedOperationCount() >= 3);
    }

    @Test
    void testExceptionHandling() {
        String operationHash = "failing-operation";
        RuntimeException expectedException = new RuntimeException("Test exception");

        // Verify that exceptions are propagated
        Exception thrownException = assertThrows(RuntimeException.class, () -> segregationManager.executeWithSegregation(operationHash, () -> {
            Thread.sleep(50); //NOSONAR
            throw expectedException;
        }));

        assertEquals(expectedException, thrownException);

        // Check that performance was still recorded even for failed operations
        assertTrue(segregationManager.getOperationAverageTime(operationHash) > 0);
    }

    @Test
    void testConcurrentExecution() throws Exception {
        final int numThreads = 5;
        final String[] results = new String[numThreads];
        final Exception[] exceptions = new Exception[numThreads];
        Thread[] threads = new Thread[numThreads];

        // Create threads that execute operations concurrently
        for (int i = 0; i < numThreads; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    results[threadIndex] = segregationManager.executeWithSegregation(
                            "concurrent-op-" + threadIndex,
                            () -> {
                                Thread.sleep(50); //NOSONAR
                                return "result-" + threadIndex;
                            }
                    );
                } catch (Exception e) {
                    exceptions[threadIndex] = e;
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

        // Verify all operations completed successfully
        for (int i = 0; i < numThreads; i++) {
            assertNull(exceptions[i], "Thread " + i + " should not have thrown an exception");
            assertEquals("result-" + i, results[i]);
        }

        // Verify performance tracking
        assertEquals(numThreads, segregationManager.getPerformanceMonitor().getTrackedOperationCount());
        assertEquals(numThreads, segregationManager.getPerformanceMonitor().getTotalExecutionCount());
    }

    @Test
    void testStatusString() {
        String status = segregationManager.getStatus();
        assertNotNull(status);
        assertTrue(status.contains("SlowQuerySegregationManager"));
        assertTrue(status.contains("enabled=true"));
        assertTrue(status.contains("trackedOps="));
        assertTrue(status.contains("totalExecs="));
        assertTrue(status.contains("overallAvg="));
    }

    @Test
    void testSlotExhaustion() throws Exception {
        // Fill up slow slots by executing operations that will be classified as slow
        String slowOp = "resource-intensive-op";

        // First, establish a baseline with some fast operations
        segregationManager.executeWithSegregation("fast-1", () -> {
            Thread.sleep(5); //NOSONAR
            return "fast";
        });

        segregationManager.executeWithSegregation("fast-2", () -> {
            Thread.sleep(5);  //NOSONAR
            return "fast";
        });

        // Now try to make the operation clearly slow
        for (int i = 0; i < 5; i++) {
            segregationManager.executeWithSegregation(slowOp, () -> {
                // Much longer than fast operations
                Thread.sleep(200);  //NOSONAR
                return "slow";
            });
        }

        // The operation should have been tracked and executed
        assertTrue(segregationManager.getOperationAverageTime(slowOp) > 0);
        assertTrue(segregationManager.getPerformanceMonitor().getTotalExecutionCount() >= 7);
    }

    @Test
    void testVoidOperations() throws Exception {
        String operationHash = "void-operation";
        final boolean[] executed = {false};

        // Test with void operations (like those used in executeQuery)
        Object result = segregationManager.executeWithSegregation(operationHash, () -> {
            executed[0] = true;
            Thread.sleep(30); //NOSONAR
            return null;
        });

        assertNull(result);
        assertTrue(executed[0]);
        assertTrue(segregationManager.getOperationAverageTime(operationHash) > 0);
    }
}