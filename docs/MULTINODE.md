# Multinode Support in Open-J-Proxy

Open-J-Proxy now supports connecting to multiple server instances for high availability and load balancing.

## Overview

The multinode implementation provides:
- **Round-Robin Load Balancing**: New connections are distributed across available servers
- **Session Stickiness**: Once a session is established, all operations for that session go to the same server
- **Automatic Failover**: Connection failures are automatically retried on other servers
- **Health Tracking**: Failed servers are marked unhealthy and given time to recover

## URL Format

To use multinode functionality, specify multiple endpoints in the JDBC URL:

```
jdbc:ojp[host1:port1,host2:port2,host3:port3]_<database_url>
```

### Examples

**PostgreSQL with two servers:**
```
jdbc:ojp[server1.example.com:1059,server2.example.com:1059]_postgresql://localhost:5432/mydb
```

**MySQL with three servers:**
```
jdbc:ojp[192.168.1.10:1059,192.168.1.11:1059,192.168.1.12:1059]_mysql://localhost:3306/testdb
```

**Single server (backward compatible):**
```
jdbc:ojp[localhost:1059]_h2:mem:test
```

## How It Works

### 1. Connection Establishment
When you create a new connection, the driver:
1. Parses the URL to detect multinode endpoints
2. Creates a `MultinodeStatementService` if multiple endpoints are found
3. Uses round-robin to select a server for the new connection
4. Binds the session to the selected server

### 2. Session Stickiness
After a connection is established:
- All subsequent operations for that session are routed to the bound server
- This ensures transaction consistency and state management
- Session binding is maintained until the connection is closed

### 3. Automatic Failover
When a connection error occurs:
- The failed server is marked as unhealthy
- For unbound operations, the request is automatically retried on another server
- Database errors (like constraint violations) do NOT trigger failover
- Configurable retry attempts (default: 2)

### 4. Health Management
Servers marked as unhealthy:
- Are avoided for new connections when possible
- Can recover after a timeout period (default: 30 seconds)
- If all servers are unhealthy, the least recently failed one is used (circuit breaker pattern)

## Configuration

The multinode feature works with default settings, but you can customize behavior by modifying `MultinodeConnectionManager` parameters:

```java
new MultinodeConnectionManager(
    endpoints,
    maxRetries,           // Default: 2
    unhealthyTimeoutMs    // Default: 30000 (30 seconds)
)
```

## Error Handling

### Connection-Level Errors (trigger failover)
- `UNAVAILABLE` - Server is down or unreachable
- `DEADLINE_EXCEEDED` - Request timed out
- `CANCELLED` - Request was cancelled
- Network errors (connection refused, reset, timeout, broken pipe)

### Database Errors (do NOT trigger failover)
- `INVALID_ARGUMENT` - Bad SQL syntax or parameters
- `PERMISSION_DENIED` - Authorization failures
- Constraint violations
- SQL syntax errors

## Thread Safety

All multinode components are thread-safe and can handle concurrent connections and operations safely.

## Backward Compatibility

Single-endpoint URLs continue to work exactly as before:
```
jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb
```

The driver automatically detects whether a URL is multinode or single-node and uses the appropriate implementation.

## Monitoring and Debugging

Enable debug logging to see multinode behavior:

```properties
logging.level.org.openjproxy.jdbc.MultinodeConnectionManager=DEBUG
logging.level.org.openjproxy.grpc.client.MultinodeStatementService=DEBUG
```

Log output includes:
- Server selection decisions
- Session binding information
- Failover attempts
- Health status changes

## Example Usage

```java
// Standard JDBC usage - multinode is transparent
String url = "jdbc:ojp[server1:1059,server2:1059]_postgresql://localhost/mydb";
Properties props = new Properties();
props.put("user", "postgres");
props.put("password", "password");

try (Connection conn = DriverManager.getConnection(url, props)) {
    // Use connection normally
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT * FROM users");
    
    // All operations automatically handled with:
    // - Session stickiness
    // - Automatic failover on connection errors
    // - Health tracking
}
```

## Limitations

- Session-bound operations (within a transaction or using LOBs) cannot failover to another server
- All servers must connect to the same backend database (data consistency is not managed by OJP)
- Server selection is round-robin only (no weighted or smart routing)

## Testing

The implementation includes comprehensive unit tests:
- `MultinodeUrlParserTest` - URL parsing and validation
- `MultinodeConnectionManagerTest` - Server selection, health tracking, error detection
- `MultinodeStatementServiceTest` - Service integration and behavior

Run tests with:
```bash
mvn test -Dtest=Multinode*Test
```
