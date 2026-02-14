package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for QueryPerformanceMonitor functionality.
 */
class QueryPerformanceMonitorTest {

    private QueryPerformanceMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new QueryPerformanceMonitor();
    }

    @Test
    void testInitialState() {
        assertEquals(0, monitor.getTrackedOperationCount());
        assertEquals(0, monitor.getTotalExecutionCount());
        assertEquals(0.0, monitor.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testRecordSingleExecution() {
        String operationHash = "test-hash-1";
        double executionTime = 100.0;

        monitor.recordExecutionTime(operationHash, executionTime);

        assertEquals(1, monitor.getTrackedOperationCount());
        assertEquals(1, monitor.getTotalExecutionCount());
        assertEquals(100.0, monitor.getOperationAverageTime(operationHash), 0.001);
        assertEquals(100.0, monitor.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testRecordMultipleExecutionsSameOperation() {
        String operationHash = "test-hash-1";

        // First execution: 100ms
        monitor.recordExecutionTime(operationHash, 100.0);
        assertEquals(100.0, monitor.getOperationAverageTime(operationHash), 0.001);

        // Second execution: 200ms
        // new_average = ((100 * 4) + 200) / 5 = 600 / 5 = 120ms
        monitor.recordExecutionTime(operationHash, 200.0);
        assertEquals(120.0, monitor.getOperationAverageTime(operationHash), 0.001);

        // Third execution: 50ms
        // new_average = ((120 * 4) + 50) / 5 = 530 / 5 = 106ms
        monitor.recordExecutionTime(operationHash, 50.0);
        assertEquals(106.0, monitor.getOperationAverageTime(operationHash), 0.001);

        assertEquals(1, monitor.getTrackedOperationCount());
        assertEquals(3, monitor.getTotalExecutionCount());
        assertEquals(106.0, monitor.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testMultipleOperations() {
        String op1 = "operation-1";
        String op2 = "operation-2";

        // Operation 1: 100ms
        monitor.recordExecutionTime(op1, 100.0);

        // Operation 2: 200ms
        monitor.recordExecutionTime(op2, 200.0);

        assertEquals(2, monitor.getTrackedOperationCount());
        assertEquals(2, monitor.getTotalExecutionCount());
        assertEquals(100.0, monitor.getOperationAverageTime(op1), 0.001);
        assertEquals(200.0, monitor.getOperationAverageTime(op2), 0.001);
        assertEquals(150.0, monitor.getOverallAverageExecutionTime(), 0.001); // (100 + 200) / 2
    }

    @Test
    void testSlowOperationClassification() {
        String fastOp = "fast-operation";
        String slowOp = "slow-operation";

        // Create a baseline with fast operations
        monitor.recordExecutionTime(fastOp, 50.0);
        monitor.recordExecutionTime("other-fast", 60.0);

        // Overall average should be (50 + 60) / 2 = 55ms
        assertEquals(55.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // An operation with 50ms average should not be slow (50 < 55 * 2 = 110)
        assertFalse(monitor.isSlowOperation(fastOp));

        // Add a slow operation: 150ms (which is > 55 * 2 = 110)
        monitor.recordExecutionTime(slowOp, 150.0);

        // Overall average should now be (50 + 60 + 150) / 3 = 86.67ms
        assertEquals(86.67, monitor.getOverallAverageExecutionTime(), 0.01);

        // The slow operation should be classified as slow (150 > 86.67 * 2 = 173.33, false)
        // Actually 150 < 173.33, so it shouldn't be slow yet
        assertFalse(monitor.isSlowOperation(slowOp));

        // Add more executions to make it clearly slow
        monitor.recordExecutionTime(slowOp, 300.0); // avg becomes (150*4 + 300)/5 = 180ms

        // Overall average should now be (50 + 60 + 180) / 3 = 96.67ms
        assertEquals(96.67, monitor.getOverallAverageExecutionTime(), 0.01);

        // Now the slow operation should be classified as slow (180 > 96.67 * 2 = 193.33, still false)
        // Let's add one more really slow execution
        monitor.recordExecutionTime(slowOp, 500.0); // avg becomes (180*4 + 500)/5 = 244ms

        // Overall average should now be (50 + 60 + 244) / 3 = 118ms
        assertEquals(118.0, monitor.getOverallAverageExecutionTime(), 0.01);

        // Now the slow operation should be classified as slow (244 > 118 * 2 = 236)
        assertTrue(monitor.isSlowOperation(slowOp));
        assertFalse(monitor.isSlowOperation(fastOp));
    }

    @Test
    void testLowOverallAverageHandling() {
        String operation = "test-op";

        // When overall average is very low, operations should be classified as fast
        monitor.recordExecutionTime(operation, 0.5);

        assertFalse(monitor.isSlowOperation(operation));

        // Even if the operation time is higher than 2x the overall average
        monitor.recordExecutionTime(operation, 2.0); // avg becomes (0.5*4 + 2)/5 = 0.8ms

        assertFalse(monitor.isSlowOperation(operation)); // Still fast because overall avg < 1.0
    }

    @Test
    void testInvalidInputHandling() {
        // Null hash should be handled gracefully
        monitor.recordExecutionTime(null, 100.0);
        assertEquals(0, monitor.getTrackedOperationCount());

        // Negative execution time should be handled gracefully
        monitor.recordExecutionTime("test", -10.0);
        assertEquals(0, monitor.getTrackedOperationCount());

        // Non-existent operation should return 0
        assertEquals(0.0, monitor.getOperationAverageTime("non-existent"), 0.001);
    }

    @Test
    void testClear() {
        monitor.recordExecutionTime("op1", 100.0);
        monitor.recordExecutionTime("op2", 200.0);

        assertEquals(2, monitor.getTrackedOperationCount());
        assertEquals(2, monitor.getTotalExecutionCount());

        monitor.clear();

        assertEquals(0, monitor.getTrackedOperationCount());
        assertEquals(0, monitor.getTotalExecutionCount());
        assertEquals(0.0, monitor.getOverallAverageExecutionTime(), 0.001);
    }
}