# Timezone-Aware Date/Time Handling - Architecture

## Data Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CLIENT SIDE (JDBC Driver)                          │
│                          (Timezone: America/New_York)                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Application Code:                                                           │
│    PreparedStatement ps = conn.prepareStatement("INSERT ... VALUES (?)");   │
│    ps.setDate(1, Date.valueOf("2025-03-29"));  ← User sets date             │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────┐              │
│  │ PreparedStatement.setDate() Implementation                │              │
│  │                                                            │              │
│  │  1. Get client timezone: "America/New_York"               │              │
│  │  2. Convert Date to TemporalData:                         │              │
│  │     - timeMillis: 1743292800000 (UTC epoch)               │              │
│  │     - nanos: 0                                            │              │
│  │     - timezoneId: "America/New_York"                      │              │
│  │  3. Store in Parameter with type DATE                     │              │
│  └──────────────────────────────────────────────────────────┘              │
│                             ↓                                                │
│                    Serialize TemporalData                                    │
│                             ↓                                                │
└─────────────────────────────────────────────────────────────────────────────┘
                              │
                              │ gRPC over network
                              │ (serialized TemporalData)
                              ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│                           SERVER SIDE (OJP Server)                           │
│                             (Timezone: UTC or other)                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────┐              │
│  │ ParameterHandler.addParam() Implementation                │              │
│  │                                                            │              │
│  │  1. Receive TemporalData from client:                     │              │
│  │     - timeMillis: 1743292800000                           │              │
│  │     - nanos: 0                                            │              │
│  │     - timezoneId: "America/New_York"                      │              │
│  │                                                            │              │
│  │  2. Reconstruct Date with client timezone:                │              │
│  │     Date date = new Date(timeMillis);                     │              │
│  │     Calendar cal = Calendar.getInstance(                  │              │
│  │         TimeZone.getTimeZone("America/New_York"));        │              │
│  │                                                            │              │
│  │  3. Set parameter with correct timezone context:          │              │
│  │     ps.setDate(idx, date, cal);                          │              │
│  └──────────────────────────────────────────────────────────┘              │
│                             ↓                                                │
│              Database JDBC Driver (native)                                   │
│                             ↓                                                │
│              Database (stores with correct interpretation)                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Key Components

### TemporalData DTO Structure
```
┌─────────────────────────────────────┐
│         TemporalData                │
├─────────────────────────────────────┤
│ - timeMillis: long                  │  ← UTC milliseconds since epoch
│ - nanos: int                        │  ← Nanosecond precision (for Timestamp)
│ - timezoneId: String                │  ← Client timezone (e.g., "America/New_York")
└─────────────────────────────────────┘
```

### Supported Operations

#### PreparedStatement
- `setDate(int parameterIndex, Date x)`
- `setDate(int parameterIndex, Date x, Calendar cal)`
- `setTime(int parameterIndex, Time x)`
- `setTime(int parameterIndex, Time x, Calendar cal)`
- `setTimestamp(int parameterIndex, Timestamp x)`
- `setTimestamp(int parameterIndex, Timestamp x, Calendar cal)`

#### CallableStatement
All PreparedStatement methods, plus:
- `setDate(String parameterName, Date x)`
- `setDate(String parameterName, Date x, Calendar cal)`
- `setTime(String parameterName, Time x)`
- `setTime(String parameterName, Time x, Calendar cal)`
- `setTimestamp(String parameterName, Timestamp x)`
- `setTimestamp(String parameterName, Timestamp x, Calendar cal)`

## Timezone Resolution Priority

```
1. Calendar parameter (if provided)
   Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
   ps.setDate(1, date, cal);  ← Uses "UTC"
   
2. Default system timezone (if no Calendar)
   ps.setDate(1, date);  ← Uses TimeZone.getDefault().getID()
```

## Benefits Matrix

| Aspect                | Before                          | After                              |
|-----------------------|---------------------------------|------------------------------------|
| Timezone handling     | ❌ Inconsistent                | ✅ Consistent across timezones    |
| Timestamp precision   | ⚠️  Can lose nanos             | ✅ Preserves full nanosecond      |
| Backward compatibility| N/A                             | ✅ Transparent to existing code   |
| Calendar support      | ❌ Lost during serialization   | ✅ Preserved and used on server   |
| Code changes required | N/A                             | ✅ None (automatic)               |

## Example Scenarios

### Scenario 1: Client in New York, Server in London
```
Client (NY, GMT-5):    2025-03-29 12:00:00
  ↓ (converts to TemporalData with timezone "America/New_York")
Server (London, GMT):  Receives and reconstructs with NY timezone
Database:              Stores correctly as 2025-03-29 12:00:00 NY time
```

### Scenario 2: Explicit Timezone via Calendar
```
Client (Tokyo):        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                       ps.setTimestamp(1, ts, cal);
  ↓ (converts to TemporalData with timezone "UTC")
Server (anywhere):     Reconstructs with UTC timezone
Database:              Stores correctly as UTC time
```

### Scenario 3: Timestamp with Nanosecond Precision
```
Client:                Timestamp ts = new Timestamp(1743292800000L);
                       ts.setNanos(123456789);
  ↓ (TemporalData: timeMillis=1743292800000, nanos=123456789)
Server:                Timestamp ts = new Timestamp(temporalData.getTimeMillis());
                       ts.setNanos(temporalData.getNanos());
Database:              Stores with full nanosecond precision
```
