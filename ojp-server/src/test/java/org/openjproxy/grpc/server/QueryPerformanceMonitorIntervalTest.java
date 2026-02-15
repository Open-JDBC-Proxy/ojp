package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for QueryPerformanceMonitor interval-based global average update functionality.
 */
class QueryPerformanceMonitorIntervalTest {
    private static final int THIRTY = 30;
    private static final int TWENTY = 20;
    private static final int FORTY = 40;

    private static final long START_TIME = 1000L;
    private static final long TIME_ADVANCE_SMALL = 30L;

    private static final long TIME_ADVANCE_LARGE = 60L;
    private static final double DELTA = 0.001;
    private static final double DOUBLE_HUNDRED = 100.0;
    private static final double DOUBLE_TWO_HUNDRED = 200.0;
    private static final double DOUBLE_THREE_HUNDRED = 300.0;

    private static final double DOUBLE_FOUR = 4.0;
    private static final double DOUBLE_FIVE = 5.0;
    private static final double DOUBLE_ONE_HUNDRED_FIFTY = 150.0;

    private static final double DOUBLE_THREE_HUNDRED_FIFTY = 350.0;
    private static final double DOUBLE_TWO_HUNDRED_FIFTY = 250.0;
    private static final double DOUBLE_ONE_HUNDRED_TWENTY = 120.0;


    private MockTimeProvider mockTimeProvider;
    private QueryPerformanceMonitor monitor;

    // Mock time provider for testing
    private static class MockTimeProvider implements TimeProvider {
        private long currentTimeSeconds = START_TIME; // Start at some arbitrary time

        public void advanceTime(long seconds) {
            currentTimeSeconds += seconds;
        }


        @Override
        public long currentTimeSeconds() {
            return currentTimeSeconds;
        }
    }

    @BeforeEach
    void setUp() {
        mockTimeProvider = new MockTimeProvider();
    }

    @Test
    void testDefaultBehaviorAlwaysUpdate() {
        // Test always-update behavior (interval = 0) - note: 300 seconds is the actual default
        monitor = new QueryPerformanceMonitor(0L, mockTimeProvider);

        // Record first operation
        monitor.recordExecutionTime("op1", DOUBLE_HUNDRED);
        assertEquals(DOUBLE_HUNDRED, monitor.getOverallAverageExecutionTime(), DELTA);

        // Advance time significantly
        mockTimeProvider.advanceTime(START_TIME);

        // Record another operation on same hash - should still update
        monitor.recordExecutionTime("op1", DOUBLE_TWO_HUNDRED);
        assertEquals(DOUBLE_ONE_HUNDRED_TWENTY, monitor.getOverallAverageExecutionTime(), DELTA); // (100*4 + 200)/5 = 120
    }

    @Test
    void testIntervalBasedUpdateWithinInterval() {
        // Set 60-second update interval
        monitor = new QueryPerformanceMonitor(TIME_ADVANCE_LARGE, mockTimeProvider);

        // Record first operation - should update global average immediately
        monitor.recordExecutionTime("op1", DOUBLE_HUNDRED);
        assertEquals(DOUBLE_HUNDRED, monitor.getOverallAverageExecutionTime(), DELTA);

        // Advance time by THIRTY seconds (within interval)
        mockTimeProvider.advanceTime(THIRTY);

        // Record same operation again - should NOT update global average
        monitor.recordExecutionTime("op1", DOUBLE_TWO_HUNDRED);
        assertEquals(DOUBLE_HUNDRED, monitor.getOverallAverageExecutionTime(), DELTA); // Should remain 100

        // Advance time past interval (total 70 seconds)
        mockTimeProvider.advanceTime(FORTY);

        // Record same operation again - should now update global average
        monitor.recordExecutionTime("op1", DOUBLE_THREE_HUNDRED);
        double expectedAvg = ((((DOUBLE_HUNDRED * DOUBLE_FOUR) + DOUBLE_TWO_HUNDRED) / DOUBLE_FIVE) * DOUBLE_FOUR + DOUBLE_THREE_HUNDRED) / DOUBLE_FIVE; // 1FORTYms
        assertEquals(expectedAvg, monitor.getOverallAverageExecutionTime(), DELTA);
    }

    @Test
    void testNewUniqueQueryImmediateUpdate() {
        // Set 60-second update interval
        monitor = new QueryPerformanceMonitor(TIME_ADVANCE_LARGE, mockTimeProvider);

        // Record first operation
        monitor.recordExecutionTime("op1", DOUBLE_HUNDRED);
        assertEquals(DOUBLE_HUNDRED, monitor.getOverallAverageExecutionTime(), DELTA);

        // Advance time by only 10 seconds (well within interval)
        mockTimeProvider.advanceTime(10);

        // Record a NEW operation - should update global average immediately despite interval
        monitor.recordExecutionTime("op2", DOUBLE_TWO_HUNDRED);
        assertEquals(DOUBLE_ONE_HUNDRED_FIFTY, monitor.getOverallAverageExecutionTime(), DELTA); // (100 + 200) / 2

        // Advance time by another 10 seconds (still within interval for next update)
        mockTimeProvider.advanceTime(10);

        // Record another NEW operation - should update immediately again
        monitor.recordExecutionTime("op3", DOUBLE_THREE_HUNDRED);
        assertEquals(DOUBLE_TWO_HUNDRED, monitor.getOverallAverageExecutionTime(), DELTA); // (100 + 200 + 300) / 3
    }

    @Test
    void testNewUniqueQueryResetsInterval() {
        // Set 60-second update interval
        monitor = new QueryPerformanceMonitor(60L, mockTimeProvider);

        // Record first operation
        monitor.recordExecutionTime("op1", 100.0);
        assertEquals(100.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Advance time by 50 seconds
        mockTimeProvider.advanceTime(50);

        // Record a NEW operation - should update immediately and reset interval timer
        monitor.recordExecutionTime("op2", 200.0);
        assertEquals(150.0, monitor.getOverallAverageExecutionTime(), 0.001);

        // Advance time by 50 seconds from the new update
        mockTimeProvider.advanceTime(50);

        // Record existing operation - should NOT update (within new interval)
        monitor.recordExecutionTime("op1", 150.0);
        assertEquals(150.0, monitor.getOverallAverageExecutionTime(), 0.001); // Should remain unchanged

        // Advance time by another 20 seconds (total 70 seconds from last update)
        mockTimeProvider.advanceTime(20);

        // Record existing operation - should now update
        monitor.recordExecutionTime("op1", 400.0);
        // Calculate op1 average step by step:
        // Initial: 100ms
        // After 150ms: ((100 * 4) + 150) / 5 = 110ms
        // After 400ms: ((110 * 4) + 400) / 5 = 168ms
        double expectedGlobalAvg = (168.0 + 200.0) / 2.0; // (168 + 200) / 2 = 184ms
        assertEquals(expectedGlobalAvg, monitor.getOverallAverageExecutionTime(), 0.001);
    }

    @Test
    void testMultipleOperationsIntervalRespected() {
        // Set THIRTY-second update interval
        monitor = new QueryPerformanceMonitor(TIME_ADVANCE_SMALL, mockTimeProvider);

        // Record multiple operations initially
        monitor.recordExecutionTime("op1", DOUBLE_HUNDRED);
        monitor.recordExecutionTime("op2", DOUBLE_TWO_HUNDRED); // New operation, triggers update
        monitor.recordExecutionTime("op3", DOUBLE_THREE_HUNDRED); // New operation, triggers update
        assertEquals(DOUBLE_TWO_HUNDRED, monitor.getOverallAverageExecutionTime(), DELTA); // (100 + 200 + 300) / 3

        // Advance time by 20 seconds (within interval)
        mockTimeProvider.advanceTime(TWENTY);

        // Update existing operations - should NOT trigger global average update
        monitor.recordExecutionTime("op1", DOUBLE_ONE_HUNDRED_FIFTY); // avg becomes 130ms
        monitor.recordExecutionTime("op2", DOUBLE_TWO_HUNDRED_FIFTY); // avg becomes 220ms
        monitor.recordExecutionTime("op3", DOUBLE_THREE_HUNDRED_FIFTY); // avg becomes 320ms

        // Global average should remain unchanged
        assertEquals(DOUBLE_TWO_HUNDRED, monitor.getOverallAverageExecutionTime(), DELTA);

        // Advance time past interval
        mockTimeProvider.advanceTime(TWENTY); // Total FORTY seconds

        // Next update should trigger global average recalculation
        monitor.recordExecutionTime("op1", 170.0);
        // Calculate step by step:
        // op1: 100 -> 110 (after 1FIFTYms) -> 122 (after 170ms)
        // op2: 200 -> 210 (after 2FIFTYms)
        // op3: 300 -> 310 (after 3FIFTYms)
        // Expected global average: (122 + 210 + 310) / 3 = 214.0
        assertEquals(214.0, monitor.getOverallAverageExecutionTime(), DELTA);
    }

    @Test
    void testClearResetsInterval() {
        monitor = new QueryPerformanceMonitor(TIME_ADVANCE_LARGE, mockTimeProvider);

        // Record some operations
        monitor.recordExecutionTime("op1", DOUBLE_HUNDRED);
        monitor.recordExecutionTime("op2", DOUBLE_TWO_HUNDRED);
        assertEquals(DOUBLE_ONE_HUNDRED_FIFTY, monitor.getOverallAverageExecutionTime(), DELTA);

        // Advance time
        mockTimeProvider.advanceTime(THIRTY);

        // Clear should reset everything including interval timer
        monitor.clear();
        assertEquals(0.0, monitor.getOverallAverageExecutionTime(), DELTA);
        assertEquals(0, monitor.getTrackedOperationCount());

        // Record operation immediately after clear - should update global average
        monitor.recordExecutionTime("op1", DOUBLE_THREE_HUNDRED);
        assertEquals(DOUBLE_THREE_HUNDRED, monitor.getOverallAverageExecutionTime(), DELTA);
    }

    @Test
    void testZeroIntervalAlwaysUpdateBehavior() {
        monitor = new QueryPerformanceMonitor(0L, mockTimeProvider);

        // Record operation
        monitor.recordExecutionTime("op1", DOUBLE_HUNDRED);
        assertEquals(DOUBLE_HUNDRED, monitor.getOverallAverageExecutionTime(), DELTA);

        // Advance time significantly
        mockTimeProvider.advanceTime(1000);

        // Any update should trigger global average update
        monitor.recordExecutionTime("op1", DOUBLE_TWO_HUNDRED);
        assertEquals(120.0, monitor.getOverallAverageExecutionTime(), DELTA);

        // Another update should also trigger global average update
        monitor.recordExecutionTime("op1", DOUBLE_THREE_HUNDRED);
        assertEquals(156.0, monitor.getOverallAverageExecutionTime(), DELTA); // ((120*4 + 300)/5)
    }

    @Test
    void testInitialOperationAlwaysUpdates() {
        monitor = new QueryPerformanceMonitor(TIME_ADVANCE_LARGE, mockTimeProvider);

        // First operation should always update global average regardless of interval
        monitor.recordExecutionTime("op1", DOUBLE_HUNDRED);
        assertEquals(DOUBLE_HUNDRED, monitor.getOverallAverageExecutionTime(), DELTA);
        assertEquals(1, monitor.getTrackedOperationCount());
    }
}