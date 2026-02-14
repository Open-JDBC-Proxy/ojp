# Open J Proxy Multinode Configuration Guide

## Overview

Open J Proxy supports multinode deployment for high availability, load distribution, and fault tolerance. The multinode functionality allows JDBC clients to connect to multiple OJP servers simultaneously, providing automatic failover and load balancing capabilities.

## Features

- **Multinode URL Support**: Connect to multiple OJP servers with a single JDBC URL
- **Load-Aware Server Selection**: Automatically directs new connections to the least-loaded server
- **Session Stickiness**: Ensures session-bound operations stay on the same server
- **Automatic Failover**: Seamlessly handles server failures with retry logic
- **Server Health Monitoring**: Tracks server health and attempts recovery
- **Backward Compatibility**: Single-node configurations continue to work unchanged

## JDBC URL Format

### Single Node (Existing Format)
```
jdbc:ojp[host:port]_actual_jdbc_url
```

### Multinode Format
```
jdbc:ojp[host1:port1,host2:port2,host3:port3]_actual_jdbc_url
```

### Examples

#### PostgreSQL with Three OJP Servers
```java
String url = "jdbc:ojp[192.168.1.10:1059,192.168.1.11:1059,192.168.1.12:1059]_postgresql://localhost:5432/mydb";
Connection conn = DriverManager.getConnection(url, "user", "password");
```

#### MySQL with Two OJP Servers
```java
String url = "jdbc:ojp[server1.example.com:1059,server2.example.com:1059]_mysql://localhost:3306/testdb";
Connection conn = DriverManager.getConnection(url, "user", "password");
```

#### Oracle with Mixed Ports
```java
String url = "jdbc:ojp[db-proxy1:1059,db-proxy2:1060]_oracle:thin:@localhost:1521/XEPDB1";
Connection conn = DriverManager.getConnection(url, "user", "password");
```

## Configuration

### Client-Side Configuration

The multinode functionality works with the `ojp.properties` configuration file (or environment-specific files like `ojp-dev.properties`, `ojp-staging.properties`, `ojp-prod.properties`). Additional multinode-specific properties can be configured:

```properties
# Standard connection pool configuration (applied to each server)
ojp.connection.pool.maximumPoolSize=25
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.idleTimeout=300000
ojp.connection.pool.maxLifetime=900000
ojp.connection.pool.connectionTimeout=15000

# Multinode configuration (configurable via properties)
ojp.multinode.retryAttempts=-1        # -1 for infinite retry, or positive number
ojp.multinode.retryDelayMs=5000       # milliseconds between retry attempts

# Load-aware server selection
ojp.loadaware.selection.enabled=true  # Enable load-aware selection (default: true)
                                       # When enabled, new connections go to the server with fewest active connections
                                       # When disabled, uses legacy round-robin distribution
```

**For environment-specific configuration** (development, staging, production), see:
- **[OJP JDBC Configuration](../configuration/ojp-jdbc-configuration.md)** - Environment-specific properties file guide
- **[Example Configuration Files](../configuration/)** - ojp-dev.properties, ojp-staging.properties, ojp-prod.properties

### Server-Side Configuration

Each OJP server in a multinode setup should be configured identically with the same database connection settings. The servers will automatically coordinate connection pool sizes based on the number of active servers in the cluster.

**Regular Connection Pools**: Pool sizes (`maximumPoolSize`, `minimumIdle`) are divided among healthy servers. When a server fails, remaining servers increase their pool sizes to maintain total capacity.

**XA Backend Session Pools**: XA backend session pool sizes (`ojp.xa.connection.pool.maxTotal`, `ojp.xa.connection.pool.minIdle`) are also divided among healthy servers, with dynamic rebalancing on server failure or recovery.

#### XA Multinode Example

With 3 OJP servers and `ojp.xa.connection.pool.maxTotal=30`:
- **Normal operation**: Each server's XA backend pool allows max 10 concurrent sessions
- **One server fails**: Remaining 2 servers increase to max 15 XA backend sessions each
- **Server recovers**: All 3 servers rebalance back to max 10 XA backend sessions each

This automatic coordination prevents exceeding database connection or transaction limits while maintaining fault tolerance.

## How It Works

### Connection Establishment

1. **URL Parsing**: The JDBC driver parses the comma-separated list of server addresses
2. **Initial Connection**: The driver attempts to connect to servers using load-aware selection
3. **Health Tracking**: Each server's health status is monitored continuously
4. **Load Balancing**: New connections are distributed across healthy servers based on their current load

### Session Management

1. **Session Stickiness**: Once a session is established (identified by `SessionInfo.sessionUUID`), all subsequent requests for that session are routed to the same server
2. **Session Tracking**: The client maintains a mapping of session UUIDs to server endpoints
3. **Failover Handling**: If a session's server becomes unhealthy, the system throws a `SQLException` to maintain ACID guarantees rather than silently failing over

### Request Routing

- **Non-Session Requests**: Routed using load-aware selection (default) or round-robin if disabled
- **Session-Bound Requests**: Always routed to the specific server associated with the session
- **Transaction Requests**: Always routed to the session's server to maintain ACID properties

### Load-Aware Server Selection

By default, OJP uses load-aware server selection to automatically balance connections across healthy servers:

1. **Connection Tracking**: The client tracks the number of active connections to each server
2. **Least-Loaded Selection**: New connections are directed to the server with the fewest active connections
3. **Dynamic Balancing**: As connections are opened and closed, the load automatically balances across servers
4. **Tie-Breaking**: When servers have equal load, round-robin is used as a tie-breaker for fairness

**Benefits:**
- Prevents overloading individual servers
- Automatically adapts to varying workloads
- Improves response times during uneven traffic patterns
- Better resource utilization across the cluster

**Configuration:**
- Enabled by default (`ojp.loadaware.selection.enabled=true`)
- Can be disabled to use legacy round-robin distribution
- No code changes required - works transparently with existing applications

### Failure Handling

1. **Server Failure Detection**: Servers are marked unhealthy only for connection-level failures
   - Connection failures (cannot reach the server)
   - Timeout errors (server not responding)
   - gRPC communication errors
   - Database-level errors (e.g., table not found, syntax errors) do NOT mark servers as unhealthy
2. **Automatic Retry**: Failed requests are retried on other healthy servers (for non-session operations)
3. **Recovery Attempts**: Unhealthy servers are periodically tested for recovery
4. **Graceful Degradation**: System continues operating with remaining healthy servers

### Session Stickiness Enforcement

**Important**: Multinode implementation enforces strict session stickiness to maintain ACID transaction guarantees:

- If a transaction or session exists and its bound server becomes unavailable, the system throws a `SQLException`
- This prevents silent failover which could break transactional integrity
- Non-transactional operations can fail over to other healthy servers

## Best Practices

### Server Setup

1. **Minimum 3-Node Production Configuration (Recommended)**: Deploy at least 3 OJP servers in production environments
   - **Reason**: Distributes load impact during failures across remaining nodes
   - **Example**: With `maximumPoolSize=30` and 3 nodes:
     - Normal operation: Each server handles 10 connections
     - One node fails: Remaining 2 servers increase to 15 connections each (50% increase)
   - **Comparison with 2-node setup**:
     - Normal operation: Each server handles 15 connections  
     - One node fails: Remaining server must handle all 30 connections (100% increase)
     - This can overwhelm the remaining server and reduce reliability
   - **Benefits of 3+ nodes**: Lower impact per server during failures, better fault tolerance, smoother load distribution
2. **Identical Configuration**: All OJP servers should have identical database connection settings
3. **Shared Database**: All servers should connect to the same database instance or cluster
4. **Network Reliability**: Ensure reliable network connectivity between clients and all servers
5. **Automatic Pool Coordination**: When multiple OJP servers are configured in a multinode setup:
   - Pool sizes are automatically divided among servers (e.g., with `maximumPoolSize=20` and 2 servers, each gets max 10 connections)
   - When a server becomes unhealthy, remaining servers automatically increase their pool sizes to maintain capacity
   - When an unhealthy server recovers, all servers rebalance back to the divided pool sizes
   - This ensures the global pool limits are respected while maintaining high availability

### Client Configuration

1. **Connection Pool Sizing**: Configure `maximumPoolSize` and `minimumIdle` based on your total capacity needs
   - The OJP servers will automatically divide these values among themselves
   - Example: With `maximumPoolSize=20` and 3 servers, each server maintains max 7 connections (rounded up)
   - If one server fails, the remaining 2 servers each increase to max 10 connections
2. **Health Check Frequency**: Configure appropriate retry delays to balance responsiveness and resource usage
3. **DNS Configuration**: Use DNS names instead of IP addresses when possible for easier maintenance

### Monitoring

1. **Server Health**: Monitor the health status of all OJP servers
2. **Connection Distribution**: Verify that connections are being distributed evenly
3. **Failover Testing**: Regularly test failover scenarios
4. **Performance Metrics**: Monitor response times across all servers

## Deployment Scenarios

### High Availability Setup
```
Application Tier
├── App Instance 1 → OJP[server1:1059,server2:1059]
├── App Instance 2 → OJP[server1:1059,server2:1059]
└── App Instance 3 → OJP[server1:1059,server2:1059]

OJP Proxy Tier
├── OJP Server 1 (Primary)
└── OJP Server 2 (Secondary)

Database Tier
└── PostgreSQL Database
```

### Load Distribution Setup
```
Application Tier
├── App Instance 1 → OJP[proxy1:1059,proxy2:1059,proxy3:1059]
├── App Instance 2 → OJP[proxy1:1059,proxy2:1059,proxy3:1059]
└── App Instance N → OJP[proxy1:1059,proxy2:1059,proxy3:1059]

OJP Proxy Tier
├── OJP Server 1 (Load Balanced)
├── OJP Server 2 (Load Balanced)
└── OJP Server 3 (Load Balanced)

Database Tier
└── MySQL Database Cluster
```

## Troubleshooting

### Common Issues

**Issue**: Client fails to connect to any server
- **Solution**: Verify all server addresses and ports are correct
- **Check**: Ensure at least one OJP server is running and accessible

**Issue**: Uneven load distribution
- **Solution**: Check server health status and network connectivity
- **Verify**: All servers are configured identically

**Issue**: Session-bound operations failing
- **Solution**: This is expected behavior if the session's server is unavailable
- **Action**: Check server logs and restart the failed server

### Debug Logging

Enable debug logging to troubleshoot multinode issues:

```java
// Add to your logging configuration
System.setProperty("org.slf4j.simpleLogger.log.org.openjproxy.grpc.client", "debug");
```

Example log output:
```
[DEBUG] MultinodeConnectionManager - Selected server proxy1.example.com:1059 for request (round-robin)
[WARN]  MultinodeConnectionManager - Connection failed to server proxy2.example.com:1059: UNAVAILABLE
[INFO]  MultinodeConnectionManager - Successfully recovered server proxy2.example.com:1059
```

## Migration from Single Node

Migrating from single-node to multinode is straightforward:

### Before (Single Node)
```java
String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/mydb";
```

### After (Multinode)
```java
String url = "jdbc:ojp[server1:1059,server2:1059]_postgresql://localhost:5432/mydb";
```

No code changes are required - simply update the JDBC URL to include multiple server addresses.

## Current Limitations

1. **Session Server Unavailability**: When a session is bound to a server that becomes unavailable, the operation fails rather than failing over (by design for ACID guarantees)
2. **Manual Server Discovery**: Servers must be explicitly listed in the URL; automatic discovery is not yet supported
3. **Configuration Synchronization**: Servers do not automatically synchronize configuration changes

## Future Enhancements

1. **Dynamic Server Discovery**: Automatic discovery of new servers in the cluster
2. **Advanced Load Balancing**: Support for weighted round-robin and response-time-based strategies
3. **Health Check Endpoints**: Dedicated health check endpoints for monitoring systems
4. **Configuration Synchronization**: Automatic synchronization of configuration changes across servers

## Related Documentation

- [OJP Server Configuration](../configuration/ojp-server-configuration.md)
- [Connection Pool Configuration](../configuration/ojp-jdbc-configuration.md)
- [OJP Components](../OJPComponents.md)
