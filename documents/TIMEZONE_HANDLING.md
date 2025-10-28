# Timezone-Aware Date/Time Handling in OJP

## Overview

This document describes the timezone-aware date/time handling implementation in OJP (Open J Proxy), which ensures consistent behavior when the JDBC driver and OJP server are running in different timezones.

## Problem Statement

When the OJP JDBC driver runs in a different timezone than the OJP server, date and time values can be interpreted incorrectly, leading to data inconsistencies. For example:
- A date set in timezone A might be interpreted differently in timezone B
- Timestamp precision can be lost during serialization
- Calendar-based operations might produce unexpected results

## Solution

The solution uses a new DTO class `TemporalData` that stores temporal information as:
1. **timeMillis**: Time in milliseconds since epoch (UTC) - ensures consistent absolute time across timezones
2. **nanos**: Nanoseconds component for Timestamp precision
3. **timezoneId**: The timezone ID of the client (e.g., "America/New_York", "UTC")

## Implementation Details

### TemporalData DTO

Located in: `ojp-grpc-commons/src/main/java/org/openjproxy/grpc/dto/TemporalData.java`

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemporalData implements Serializable {
    private long timeMillis;      // Time in milliseconds since epoch (UTC)
    private int nanos;            // Nanoseconds component (for Timestamp precision)
    private String timezoneId;    // Timezone ID (e.g., "America/New_York")
}
```

### Client-Side Changes

#### PreparedStatement
- Modified `setDate()`, `setTime()`, and `setTimestamp()` methods
- Converts Date/Time/Timestamp to TemporalData before sending to server
- Captures the client's timezone information

#### CallableStatement
- Similar modifications to PreparedStatement
- Supports both parameter index and parameter name variants
- Handles Calendar-based methods to extract timezone information

### Server-Side Changes

#### ParameterHandler
Located in: `ojp-server/src/main/java/org/openjproxy/grpc/server/statement/ParameterHandler.java`

- Receives TemporalData from the client
- Reconstructs Date/Time/Timestamp using the client's timezone
- Uses Calendar to properly interpret the timezone when setting parameters on the underlying JDBC PreparedStatement

## Usage Examples

### Basic Usage (Default Timezone)

```java
// Client side (in any timezone)
PreparedStatement ps = connection.prepareStatement("INSERT INTO events (event_date) VALUES (?)");
Date eventDate = Date.valueOf("2025-03-29");
ps.setDate(1, eventDate);
ps.executeUpdate();

// The date is stored with the client's timezone information
// Server reconstructs it correctly regardless of its own timezone
```

### Calendar-Based Usage (Explicit Timezone)

```java
// Client side - specify explicit timezone
PreparedStatement ps = connection.prepareStatement("INSERT INTO events (event_time) VALUES (?)");
Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));
Timestamp timestamp = Timestamp.valueOf("2025-03-29 14:30:00");
ps.setTimestamp(1, timestamp, cal);
ps.executeUpdate();

// The timestamp is stored with Los Angeles timezone information
// Server uses this timezone when setting the parameter
```

### Timestamp with Nanosecond Precision

```java
// Client side
Timestamp ts = new Timestamp(System.currentTimeMillis());
ts.setNanos(123456789);  // Set nanosecond precision
ps.setTimestamp(1, ts);

// TemporalData preserves both milliseconds and nanoseconds
// Server reconstructs the exact timestamp with full precision
```

## Testing

Comprehensive tests are available in:
- `ojp-grpc-commons/src/test/java/org/openjproxy/grpc/dto/TemporalDataTest.java`

Tests cover:
- Serialization and deserialization of TemporalData
- Conversion from/to Date, Time, and Timestamp
- Different timezone scenarios
- Nanosecond precision for Timestamps
- Null value handling

## Benefits

1. **Timezone Consistency**: Date/time values are correctly interpreted regardless of client/server timezone differences
2. **Precision Preservation**: Timestamp nanosecond precision is maintained through serialization
3. **Backward Compatibility**: The changes are transparent to existing JDBC code
4. **Flexibility**: Supports both default timezone and explicit timezone (via Calendar) operations

## Migration Notes

- Existing code using `setDate()`, `setTime()`, and `setTimestamp()` will automatically use the new timezone-aware implementation
- No changes are required to application code
- The client's timezone is automatically detected and used
- When using Calendar-based methods, the Calendar's timezone is preserved

## Technical Details

### Serialization
- TemporalData is serialized using Java's standard serialization mechanism
- Compact representation: long (8 bytes) + int (4 bytes) + String (timezone ID)
- Efficient for network transmission via gRPC

### Server-Side Reconstruction
```java
// Server reconstructs the temporal value with client's timezone
TemporalData temporalData = (TemporalData) param.getValues().get(0);
Date date = new Date(temporalData.getTimeMillis());
if (temporalData.getTimezoneId() != null) {
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(temporalData.getTimezoneId()));
    ps.setDate(idx, date, cal);
} else {
    ps.setDate(idx, date);
}
```

## Future Enhancements

Potential future improvements:
- Support for Java 8+ java.time.* types (LocalDate, LocalDateTime, ZonedDateTime)
- Configurable timezone handling strategies
- Enhanced timezone conversion utilities
- Performance optimizations for high-volume scenarios
