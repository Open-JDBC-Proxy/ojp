# Slow Query Segregation Feature

## Overview

The Slow Query Segregation feature monitors all database operations (`executeQuery` and `executeUpdate`) and classifies them as "slow" or "fast" based on their execution time. It then manages the number of concurrently executing operations in each category to prevent slow operations from blocking the system.

## How It Works

### 1. Operation Monitoring
- Every SQL operation is tracked using a hash of the SQL statement
- Execution times are recorded and averaged using a weighted formula: `new_average = ((stored_average * 4) + new_measurement) / 5`
- This gives 20% weight to the newest measurement, smoothing out outliers

### 2. Slow vs Fast Classification
- An operation is classified as "slow" if its average execution time is **2x or greater** than the overall average execution time
- The overall average is calculated as the average of all individual operation averages
- All other operations are classified as "fast"

### 3. Execution Slot Management
- The total number of concurrent operations is limited by the HikariCP connection pool maximum size
- By default, 20% of slots are allocated to slow operations, 80% to fast operations
- Operations must acquire an appropriate slot before executing

### 4. Slot Borrowing
- If one pool (slow/fast) is idle for a configurable time (default: 10 seconds), the other pool can borrow its slots
- This ensures efficient resource utilization while maintaining segregation
- Borrowed slots are returned to their original pool after use

## Configuration

Add these properties to your server configuration:

```properties
# Enable/disable the feature
ojp.server.slowQuerySegregation.enabled=true

# Percentage of slots for slow operations (0-100)
ojp.server.slowQuerySegregation.slowSlotPercentage=20

# Idle timeout for slot borrowing (milliseconds)
ojp.server.slowQuerySegregation.idleTimeout=10000
```

📖 **[Complete Configuration Example](ojp-server-slow-query-example.properties)** - Full server configuration file with all available slow query segregation settings.

## Benefits

1. **Prevents Resource Starvation**: Fast operations aren't blocked by slow ones
2. **Maintains Throughput**: System remains responsive even under mixed workloads
3. **Adaptive**: Automatically learns which operations are slow based on historical data
4. **Efficient**: Allows slot borrowing when pools are idle
5. **Configurable**: Tune the balance between slow and fast operation slots

## Monitoring

The feature provides status information including:
- Number of tracked operations
- Overall average execution time
- Current slot usage (slow/fast/borrowed)
- Classification of individual operations

## Thread Safety

All components are designed to be thread-safe and can handle concurrent operations without data corruption or deadlocks.

## Backwards Compatibility

The feature is designed to be non-intrusive:
- When disabled, it only performs performance monitoring without slot management
- Existing applications continue to work without modifications
- No changes to client code are required

## Example Scenarios

### Scenario 1: Mixed Workload
- Fast queries: `SELECT * FROM users WHERE id = ?` (avg: 10ms)
- Slow queries: `SELECT * FROM large_table ORDER BY date` (avg: 500ms)
- Overall average: ~255ms
- Slow threshold: 510ms
- Result: Only the complex query is classified as slow

### Scenario 2: Resource Protection
- 10 total connection slots
- 2 slow slots, 8 fast slots
- If 2 slow operations are running, additional slow operations must wait
- Fast operations can still use their 8 slots unimpeded

### Scenario 3: Slot Borrowing
- No slow operations for 10+ seconds (idle)
- Fast pool is at capacity (8/8 used)
- New fast operation can borrow from slow pool
- System maintains high throughput