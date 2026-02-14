package org.openjproxy.grpc.server;

/**
 * Simple manual test to verify the slow query segregation functionality.
 * This can be run to validate the implementation works correctly.
 */
public class SlowQuerySegregationManualTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Testing Slow Query Segregation Feature...");
        
        // Test 1: QueryPerformanceMonitor
        testQueryPerformanceMonitor();
        
        // Test 2: SlotManager
        testSlotManager();
        
        // Test 3: SlowQuerySegregationManager
        testSlowQuerySegregationManager();
        
        System.out.println("All tests passed!");
    }
    
    private static void testQueryPerformanceMonitor() {
        System.out.println("\n--- Testing QueryPerformanceMonitor ---");
        
        QueryPerformanceMonitor monitor = new QueryPerformanceMonitor();
        
        // Test basic functionality
        String operation = "SELECT * FROM users";
        monitor.recordExecutionTime(operation, 100.0);
        
        double avgTime = monitor.getOperationAverageTime(operation);
        System.out.println("Operation average time: " + avgTime + "ms (expected: 100.0)");
        assert Math.abs(avgTime - 100.0) < 0.001 : "Expected 100.0, got " + avgTime;
        
        double overallAvg = monitor.getOverallAverageExecutionTime();
        System.out.println("Overall average time: " + overallAvg + "ms (expected: 100.0)");
        assert Math.abs(overallAvg - 100.0) < 0.001 : "Expected 100.0, got " + overallAvg;
        
        // Test weighted averaging
        monitor.recordExecutionTime(operation, 200.0);
        avgTime = monitor.getOperationAverageTime(operation);
        System.out.println("After second execution: " + avgTime + "ms (expected: 120.0)");
        assert Math.abs(avgTime - 120.0) < 0.001 : "Expected 120.0, got " + avgTime;
        
        // Test slow operation classification
        boolean isSlow = monitor.isSlowOperation(operation);
        System.out.println("Is operation slow: " + isSlow + " (expected: false, since overall avg is 120)");
        assert !isSlow : "Operation should not be slow yet";
        
        System.out.println("QueryPerformanceMonitor tests passed!");
    }
    
    private static void testSlotManager() throws InterruptedException {
        System.out.println("\n--- Testing SlotManager ---");
        
        SlotManager slotManager = new SlotManager(10, 20, 100); // 10 total, 20% slow, 100ms idle
        
        System.out.println("Slot allocation: " + slotManager.getSlowSlots() + " slow, " + slotManager.getFastSlots() + " fast");
        assert slotManager.getSlowSlots() == 2 : "Expected 2 slow slots";
        assert slotManager.getFastSlots() == 8 : "Expected 8 fast slots";
        
        // Test slot acquisition
        boolean acquired = slotManager.acquireSlowSlot(1000);
        System.out.println("Acquired slow slot: " + acquired + " (expected: true)");
        assert acquired : "Should be able to acquire slow slot";
        
        assert slotManager.getActiveSlowOperations() == 1 : "Should have 1 active slow operation";
        
        // Release slot
        slotManager.releaseSlowSlot();
        assert slotManager.getActiveSlowOperations() == 0 : "Should have 0 active slow operations";
        
        // Test fast slots
        acquired = slotManager.acquireFastSlot(1000);
        assert acquired : "Should be able to acquire fast slot";
        assert slotManager.getActiveFastOperations() == 1 : "Should have 1 active fast operation";
        
        slotManager.releaseFastSlot();
        assert slotManager.getActiveFastOperations() == 0 : "Should have 0 active fast operations";
        
        System.out.println("SlotManager tests passed!");
    }
    
    private static void testSlowQuerySegregationManager() throws Exception {
        System.out.println("\n--- Testing SlowQuerySegregationManager ---");
        
        SlowQuerySegregationManager manager = new SlowQuerySegregationManager(10, 20, 100, 5000, 1000, true);
        
        assert manager.isEnabled() : "Manager should be enabled";
        
        // Test operation execution
        String operationHash = "test-operation";
        String result = manager.executeWithSegregation(operationHash, () -> {
            Thread.sleep(50); //NOSONAR
            return "success";
        });
        
        System.out.println("Execution result: " + result + " (expected: success)");
        assert "success".equals(result) : "Expected 'success', got " + result;
        
        // Check that performance was recorded
        double avgTime = manager.getOperationAverageTime(operationHash);
        System.out.println("Recorded average time: " + avgTime + "ms (should be > 40)");
        assert avgTime > 40 : "Average time should be greater than 40ms";
        
        // Test disabled manager
        SlowQuerySegregationManager disabledManager = new SlowQuerySegregationManager(10, 20, 100, 5000, 1000, false);
        assert !disabledManager.isEnabled() : "Manager should be disabled";
        
        result = disabledManager.executeWithSegregation(operationHash, () -> "disabled-success");
        assert "disabled-success".equals(result) : "Disabled manager should still execute operations";
        
        System.out.println("SlowQuerySegregationManager tests passed!");
    }
}