# Read/Write Traffic Splitting User Guide

## Table of Contents

- [Overview](#overview)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [Configuration](#configuration)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)
- [Performance Tuning](#performance-tuning)
- [Migration Guide](#migration-guide)

## Overview

Read/Write Traffic Splitting is an advanced feature in OJP that automatically distributes database queries between primary (write-capable) and replica (read-only) database instances based on the SQL operation type. This feature significantly improves:

- **Scalability**: Distribute read load across multiple replicas
- **Performance**: Reduce primary database load by offloading read queries
- **Availability**: Automatic failover to primary when replicas are unavailable
- **Consistency**: Transaction-aware routing ensures data consistency

### Key Features

✅ **Automatic SQL Classification**: Intelligently classifies queries as READ, WRITE, or UNKNOWN using JSqlParser  
✅ **Round-Robin Load Balancing**: Evenly distributes read queries across healthy replicas  
✅ **Health-Aware Failover**: Automatically tries all replicas before falling back to primary  
✅ **Transaction Isolation**: All queries within transactions route to primary database  
✅ **Sticky Sessions**: Prevents stale reads after writes with configurable duration  
✅ **Zero Application Changes**: Works transparently with existing JDBC applications  

### When to Use Read/Write Splitting

**Ideal Scenarios:**
- Applications with heavy read workloads (80%+ SELECT queries)
- Database replication already configured (MySQL replication, PostgreSQL streaming, etc.)
- Need to scale read capacity without upgrading primary database
- Multiple read replicas available for load distribution

**Not Recommended:**
- Write-heavy applications (primarily INSERT/UPDATE/DELETE)
- Single database without replication
- Applications requiring read-after-write consistency with sub-second replication lag
- Real-time analytics requiring immediate data visibility

## Quick Start

### Step 1: Enable Read/Write Splitting

Add read/write configuration to your OJP datasource properties file:

```properties
# Primary database (write-capable)
primary.connection.name=primary
primary.connection.url=jdbc:postgresql://primary-db.example.com:5432/mydb
primary.connection.user=app_user
primary.connection.password=secret123
primary.pool.maxPoolSize=20
primary.pool.minIdle=5

# Enable read/write splitting for this datasource
primary.ojp.readwrite.enabled=true
primary.ojp.readwrite.role=primary
primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
primary.ojp.readwrite.stickySessionSeconds=5
primary.ojp.readwrite.replicaFailoverToPrimary=true

# Replica 1 (read-only)
replica1.connection.name=replica1
replica1.connection.url=jdbc:postgresql://replica1.example.com:5432/mydb
replica1.connection.user=readonly_user
replica1.connection.password=readonly_secret
replica1.pool.maxPoolSize=15
replica1.pool.minIdle=3

# Configure replica1 to reference the primary
replica1.ojp.readwrite.role=replica
replica1.ojp.readwrite.primary=primary

# Replica 2 (read-only, optional)
replica2.connection.name=replica2
replica2.connection.url=jdbc:postgresql://replica2.example.com:5432/mydb
replica2.connection.user=readonly_user
replica2.connection.password=readonly_secret
replica2.pool.maxPoolSize=15
replica2.pool.minIdle=3
replica2.ojp.readwrite.role=replica
replica2.ojp.readwrite.primary=primary
```

### Step 2: Connect to OJP

No application code changes required! Simply connect to OJP using the primary datasource name:

```java
// Standard JDBC connection - routing happens automatically
String url = "jdbc:ojp://localhost:50051/primary";
Connection conn = DriverManager.getConnection(url);

// Read queries automatically route to replicas
ResultSet rs = conn.createStatement()
    .executeQuery("SELECT * FROM users WHERE active = true");

// Write queries automatically route to primary
conn.createStatement()
    .executeUpdate("UPDATE users SET last_login = NOW() WHERE id = 123");
```

### Step 3: Verify Routing (Optional)

Enable OJP logging to see routing decisions:

```properties
# In ojp-server.properties or log4j2.xml
logger.readwrite.name=org.openjproxy.grpc.server.readwrite
logger.readwrite.level=DEBUG
```

You'll see log entries like:
```
[ReadWriteRouter] Routing READ query to replica: replica1
[ReadWriteRouter] Routing WRITE query to primary: primary
[ReadWriteRouter] In-transaction query routing to primary: primary
```

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────┐
│                       JDBC Application                       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                          OJP Server                          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           SqlClassifier (JSqlParser)                 │   │
│  │         Classifies SQL: READ/WRITE/UNKNOWN          │   │
│  └─────────────────────────────────────────────────────┘   │
│                              │                               │
│                              ▼                               │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              ReadWriteRouter                         │   │
│  │  • Transaction-aware routing                        │   │
│  │  • Sticky session support                           │   │
│  │  • Health-aware failover                            │   │
│  └─────────────────────────────────────────────────────┘   │
│                              │                               │
│           ┌──────────────────┴──────────────────┐           │
│           ▼                                      ▼           │
│  ┌─────────────────┐                  ┌──────────────────┐ │
│  │  Primary Pool   │                  │  Replica Pools   │ │
│  │  (HikariCP)     │                  │  (HikariCP)      │ │
│  └─────────────────┘                  └──────────────────┘ │
└─────────────────────────────────────────────────────────────┘
           │                                      │
           ▼                                      ▼
┌──────────────────┐              ┌─────────────────────────┐
│  Primary DB      │              │  Replica1    Replica2   │
│  (Read + Write)  │────────────▶ │  (Read-Only) (Read-Only)│
└──────────────────┘ Replication  └─────────────────────────┘
```

### Routing Decision Flow

1. **SQL Classification**: JSqlParser analyzes SQL statement
   - `SELECT` → READ (route to replica)
   - `INSERT/UPDATE/DELETE` → WRITE (route to primary)
   - `BEGIN/COMMIT/ROLLBACK` → UNKNOWN (route to primary)
   - Parse failures → UNKNOWN (route to primary for safety)

2. **Transaction Check**: Is the session in a transaction?
   - Yes → Route to primary (all queries in transaction must use same datasource)
   - No → Continue to step 3

3. **Sticky Session Check**: Recent write operation?
   - Yes, within sticky duration → Route to primary (prevent stale reads)
   - No, expired → Continue to step 4

4. **Replica Selection**: For READ queries outside transactions
   - Round-robin across healthy replicas
   - Health check each replica (`Connection.isValid()`)
   - If all replicas unhealthy → Fallback to primary

### SQL Classification Examples

**Classified as READ** (route to replicas):
```sql
SELECT * FROM users WHERE id = 123
SELECT COUNT(*) FROM orders WHERE status = 'pending'
WITH recent_orders AS (SELECT ...) SELECT * FROM recent_orders
```

**Classified as WRITE** (route to primary):
```sql
INSERT INTO users (name, email) VALUES ('John', 'john@example.com')
UPDATE orders SET status = 'shipped' WHERE id = 456
DELETE FROM cache WHERE expires_at < NOW()
SELECT * FROM users FOR UPDATE  -- Locks rows, must use primary
```

**Classified as UNKNOWN** (route to primary for safety):
```sql
BEGIN TRANSACTION
COMMIT
CALL update_statistics()
EXEC sp_update_user @id = 123
```

## Configuration

### Primary Database Configuration

```properties
# Datasource identification
<primary-name>.connection.name=<primary-name>
<primary-name>.connection.url=jdbc:<driver>://<host>:<port>/<database>
<primary-name>.connection.user=<username>
<primary-name>.connection.password=<password>

# Connection pool settings (HikariCP)
<primary-name>.pool.maxPoolSize=20
<primary-name>.pool.minIdle=5
<primary-name>.pool.connectionTimeout=30000
<primary-name>.pool.idleTimeout=600000
<primary-name>.pool.maxLifetime=1800000

# Read/write splitting configuration
<primary-name>.ojp.readwrite.enabled=true
<primary-name>.ojp.readwrite.role=primary
<primary-name>.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
<primary-name>.ojp.readwrite.stickySessionSeconds=5
<primary-name>.ojp.readwrite.replicaFailoverToPrimary=true
```

### Replica Database Configuration

```properties
# Datasource identification
<replica-name>.connection.name=<replica-name>
<replica-name>.connection.url=jdbc:<driver>://<host>:<port>/<database>
<replica-name>.connection.user=<readonly-username>
<replica-name>.connection.password=<readonly-password>

# Connection pool settings (typically smaller than primary)
<replica-name>.pool.maxPoolSize=15
<replica-name>.pool.minIdle=3

# Link to primary datasource
<replica-name>.ojp.readwrite.role=replica
<replica-name>.ojp.readwrite.primary=<primary-name>
```

### Configuration Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `ojp.readwrite.enabled` | Yes | `false` | Enable read/write splitting for this datasource |
| `ojp.readwrite.role` | Yes | - | Role: `primary` or `replica` |
| `ojp.readwrite.primary` | Yes (replicas) | - | Name of the primary datasource (replicas only) |
| `ojp.readwrite.replicaSelectionStrategy` | No | `ROUND_ROBIN` | Strategy: `ROUND_ROBIN`, `RANDOM`, `LEAST_CONNECTIONS` (future) |
| `ojp.readwrite.stickySessionSeconds` | No | `5` | Duration in seconds to route reads to primary after writes |
| `ojp.readwrite.replicaFailoverToPrimary` | No | `true` | Fallback to primary when all replicas are unhealthy |

### Multiple Primary Configurations

You can configure read/write splitting for multiple independent datasources:

```properties
# Production database with replicas
prod.ojp.readwrite.enabled=true
prod.ojp.readwrite.role=primary
prod_replica1.ojp.readwrite.role=replica
prod_replica1.ojp.readwrite.primary=prod

# Analytics database with replicas
analytics.ojp.readwrite.enabled=true
analytics.ojp.readwrite.role=primary
analytics_replica1.ojp.readwrite.role=replica
analytics_replica1.ojp.readwrite.primary=analytics
```

## Best Practices

### 1. Use Read-Only Database Users for Replicas

Create dedicated read-only users for replica connections to prevent accidental writes:

**PostgreSQL:**
```sql
CREATE USER readonly_user WITH PASSWORD 'secret';
GRANT CONNECT ON DATABASE mydb TO readonly_user;
GRANT USAGE ON SCHEMA public TO readonly_user;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public 
    GRANT SELECT ON TABLES TO readonly_user;
```

**MySQL:**
```sql
CREATE USER 'readonly_user'@'%' IDENTIFIED BY 'secret';
GRANT SELECT ON mydb.* TO 'readonly_user'@'%';
FLUSH PRIVILEGES;
```

### 2. Configure Appropriate Sticky Session Duration

The sticky session duration should match your replication lag:

- **Sub-second replication**: `stickySessionSeconds=1` or `2`
- **1-3 second replication**: `stickySessionSeconds=5` (default)
- **3-10 second replication**: `stickySessionSeconds=10`
- **Highly delayed replication**: Consider not using read/write splitting

**Measure your replication lag:**

PostgreSQL:
```sql
-- On primary
SELECT pg_current_wal_lsn();

-- On replica
SELECT 
    now() - pg_last_xact_replay_timestamp() AS replication_lag;
```

MySQL:
```sql
SHOW SLAVE STATUS\G
-- Look at Seconds_Behind_Master
```

### 3. Size Connection Pools Appropriately

**Primary Pool:**
- Must handle ALL write traffic + failover read traffic
- Size based on: write workload + 20% buffer

**Replica Pools:**
- Distribute read workload across replicas
- Total replica capacity = read workload / number of replicas

**Example sizing:**
```properties
# Application has 100 concurrent connections
# 80% reads, 20% writes
# 3 replicas

primary.pool.maxPoolSize=30    # 20 writes + 10 buffer
replica1.pool.maxPoolSize=27   # 80 reads / 3 replicas
replica2.pool.maxPoolSize=27
replica3.pool.maxPoolSize=27
```

### 4. Monitor Replica Health

Use database-specific health checks to verify replicas are in sync:

**PostgreSQL:**
```sql
-- Check replication status
SELECT * FROM pg_stat_replication;

-- Check replica lag
SELECT 
    now() - pg_last_xact_replay_timestamp() AS lag
FROM pg_stat_replication;
```

**MySQL:**
```sql
SHOW SLAVE STATUS\G
```

Set up alerts when:
- Replication lag > sticky session duration
- Replica is not replicating
- Replica has errors

### 5. Handle Replica Failures Gracefully

OJP automatically handles replica failures, but you should:

1. **Monitor failover events** via OJP logs
2. **Set up alerts** when replicas are unavailable
3. **Ensure primary can handle** full read+write load temporarily
4. **Have runbooks** for replica recovery

### 6. Test Failover Scenarios

Regularly test your failover configuration:

```bash
# Simulate replica failure
docker stop replica1-container

# Verify traffic routes to primary or other replicas
# Check OJP logs for failover events

# Restore replica
docker start replica1-container

# Verify traffic resumes to replica
```

## Troubleshooting

### Issue: Queries Routing to Wrong Datasource

**Symptoms:**
- Read queries going to primary
- Write queries failing on replicas

**Diagnosis:**
1. Enable debug logging:
```properties
logger.readwrite.name=org.openjproxy.grpc.server.readwrite
logger.readwrite.level=DEBUG
```

2. Check SQL classification:
```
[SqlClassifier] Classifying SQL: SELECT * FROM users
[SqlClassifier] Result: READ
```

3. Check routing decision:
```
[ReadWriteRouter] Routing READ query to replica: replica1
```

**Solutions:**
- If reads going to primary: Check sticky session duration
- If writes failing: Verify replica users are read-only
- If classification wrong: Check for `SELECT FOR UPDATE` or transaction state

### Issue: Stale Data After Writes

**Symptoms:**
- Application writes data but subsequent reads don't see the changes

**Root Cause:**
- Sticky session duration < replication lag

**Solution:**
```properties
# Increase sticky session duration to exceed replication lag
primary.ojp.readwrite.stickySessionSeconds=10
```

Or ensure application uses transactions:
```java
conn.setAutoCommit(false);
// Write
stmt.executeUpdate("INSERT INTO users ...");
// Read - will use same primary datasource
ResultSet rs = stmt.executeQuery("SELECT * FROM users WHERE id = ...");
conn.commit();
```

### Issue: High Load on Primary Database

**Symptoms:**
- Primary database CPU/connections high
- Replicas underutilized

**Diagnosis:**
1. Check replica health:
```sql
-- PostgreSQL
SELECT * FROM pg_stat_replication;

-- MySQL
SHOW SLAVE STATUS\G
```

2. Check OJP routing distribution:
```
[ReadWriteRouter] Stats: Primary=80%, Replica1=10%, Replica2=10%
```

**Solutions:**
- Verify replicas are healthy and in-sync
- Check replica connection pool sizes
- Reduce sticky session duration if appropriate
- Verify application workload is read-heavy

### Issue: Connection Pool Exhaustion

**Symptoms:**
```
java.sql.SQLTransientConnectionException: HikariPool - Connection is not available
```

**Solutions:**
1. Increase pool size:
```properties
primary.pool.maxPoolSize=30
replica1.pool.maxPoolSize=20
```

2. Reduce connection timeout:
```properties
primary.pool.connectionTimeout=10000
```

3. Check for connection leaks in application code

### Issue: Replica Failover Not Working

**Symptoms:**
- Replica down, but queries still attempting to use it
- No automatic failback to primary

**Diagnosis:**
1. Check configuration:
```properties
primary.ojp.readwrite.replicaFailoverToPrimary=true
```

2. Check health check timeout:
```java
// OJP uses Connection.isValid(5) - 5 second timeout
```

**Solutions:**
- Verify `replicaFailoverToPrimary=true` is set
- Check network connectivity to replicas
- Verify primary pool has capacity for failover traffic

## Performance Tuning

### Optimize SQL Classification

**JSqlParser Performance:**
- Classification time: < 0.5ms per query (typical)
- Caching: Parse results are not cached (stateless)
- Impact: Negligible for most applications

**If performance is critical:**
- Profile SQL classification overhead
- Consider connection pool size adjustments
- Ensure hardware has sufficient CPU

### Optimize Replica Selection

**Round-Robin Strategy:**
- Overhead: ~1-2 microseconds per query
- Thread-safe with AtomicInteger
- No locking contention

**Health Checking:**
- Uses `Connection.isValid(5)` - 5 second timeout
- Only checked when connection is borrowed
- Cached by HikariCP for pool lifetime

### Monitor Performance Metrics

Track these key metrics:

1. **Routing Distribution**
   - Percentage of queries to primary vs replicas
   - Goal: 80%+ to replicas for read-heavy workloads

2. **Failover Events**
   - Frequency of replica → primary failovers
   - Goal: < 1% of queries

3. **Sticky Session Activation**
   - Percentage of reads in sticky mode
   - Goal: Match write query percentage

4. **Classification Performance**
   - SQL classification time (p50, p95, p99)
   - Goal: < 1ms at p99

5. **Connection Pool Utilization**
   - Primary pool: target 60-80% utilization
   - Replica pools: target 70-90% utilization

## Migration Guide

### Migrating from Single Datasource

**Step 1: Set up database replication** (outside OJP)

Configure MySQL replication, PostgreSQL streaming replication, or your database's native replication.

**Step 2: Create read-only users**

```sql
-- PostgreSQL
CREATE USER readonly_app WITH PASSWORD 'readonly_secret';
GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly_app;

-- MySQL
CREATE USER 'readonly_app'@'%' IDENTIFIED BY 'readonly_secret';
GRANT SELECT ON mydb.* TO 'readonly_app'@'%';
```

**Step 3: Add replica configuration**

Add replica datasources to your existing configuration:

```properties
# Existing primary configuration (unchanged)
mydb.connection.name=mydb
mydb.connection.url=jdbc:postgresql://primary:5432/mydb
mydb.connection.user=app_user
mydb.connection.password=secret

# NEW: Enable read/write splitting
mydb.ojp.readwrite.enabled=true
mydb.ojp.readwrite.role=primary
mydb.ojp.readwrite.stickySessionSeconds=5

# NEW: Add replica
mydb_replica1.connection.name=mydb_replica1
mydb_replica1.connection.url=jdbc:postgresql://replica1:5432/mydb
mydb_replica1.connection.user=readonly_app
mydb_replica1.connection.password=readonly_secret
mydb_replica1.pool.maxPoolSize=15
mydb_replica1.ojp.readwrite.role=replica
mydb_replica1.ojp.readwrite.primary=mydb
```

**Step 4: Restart OJP server**

```bash
# Graceful restart
kill -SIGTERM <ojp-pid>
java -jar ojp-server.jar
```

**Step 5: Verify routing**

Enable debug logging and verify queries are routing correctly:

```properties
logger.readwrite.level=DEBUG
```

**Step 6: Monitor and tune**

- Monitor replica lag
- Adjust sticky session duration
- Tune connection pool sizes
- Track routing distribution

### Migrating Between Configurations

**Adding replicas:**
1. Configure new replica datasource
2. Restart OJP (hot reload not supported)
3. Verify new replica receives traffic

**Removing replicas:**
1. Stop replica database
2. Remove replica configuration from properties file
3. Restart OJP

**Disabling read/write splitting:**
1. Set `ojp.readwrite.enabled=false`
2. Restart OJP
3. All queries route to primary

### Zero-Downtime Migration

For zero-downtime migration:

1. **Deploy second OJP instance** with read/write configuration
2. **Update DNS/load balancer** to point to new instance
3. **Drain connections** from old instance
4. **Shutdown old instance**

## Database-Specific Examples

### PostgreSQL with Streaming Replication

```properties
# Primary (write-capable)
pg_primary.connection.name=pg_primary
pg_primary.connection.url=jdbc:postgresql://pg-primary.example.com:5432/myapp
pg_primary.connection.user=app_user
pg_primary.connection.password=secret
pg_primary.pool.maxPoolSize=25
pg_primary.ojp.readwrite.enabled=true
pg_primary.ojp.readwrite.role=primary
pg_primary.ojp.readwrite.stickySessionSeconds=3

# Replica 1
pg_replica1.connection.name=pg_replica1
pg_replica1.connection.url=jdbc:postgresql://pg-replica1.example.com:5432/myapp
pg_replica1.connection.user=readonly_user
pg_replica1.connection.password=readonly_secret
pg_replica1.pool.maxPoolSize=20
pg_replica1.ojp.readwrite.role=replica
pg_replica1.ojp.readwrite.primary=pg_primary
```

### MySQL with Master-Slave Replication

```properties
# Master (write-capable)
mysql_master.connection.name=mysql_master
mysql_master.connection.url=jdbc:mysql://mysql-master.example.com:3306/myapp
mysql_master.connection.user=app_user
mysql_master.connection.password=secret
mysql_master.pool.maxPoolSize=25
mysql_master.ojp.readwrite.enabled=true
mysql_master.ojp.readwrite.role=primary

# Slave 1
mysql_slave1.connection.name=mysql_slave1
mysql_slave1.connection.url=jdbc:mysql://mysql-slave1.example.com:3306/myapp
mysql_slave1.connection.user=readonly_user
mysql_slave1.connection.password=readonly_secret
mysql_slave1.pool.maxPoolSize=20
mysql_slave1.ojp.readwrite.role=replica
mysql_slave1.ojp.readwrite.primary=mysql_master
```

### Oracle with Data Guard

```properties
# Primary database
oracle_primary.connection.name=oracle_primary
oracle_primary.connection.url=jdbc:oracle:thin:@//oracle-primary.example.com:1521/PRODDB
oracle_primary.connection.user=app_user
oracle_primary.connection.password=secret
oracle_primary.pool.maxPoolSize=30
oracle_primary.ojp.readwrite.enabled=true
oracle_primary.ojp.readwrite.role=primary

# Standby database (Active Data Guard)
oracle_standby.connection.name=oracle_standby
oracle_standby.connection.url=jdbc:oracle:thin:@//oracle-standby.example.com:1521/PRODDB
oracle_standby.connection.user=readonly_user
oracle_standby.connection.password=readonly_secret
oracle_standby.pool.maxPoolSize=25
oracle_standby.ojp.readwrite.role=replica
oracle_standby.ojp.readwrite.primary=oracle_primary
```

## Advanced Topics

### Custom Replica Selection Strategies

Currently supported: `ROUND_ROBIN`

Future strategies:
- `RANDOM`: Random replica selection
- `LEAST_CONNECTIONS`: Select replica with fewest active connections
- `WEIGHTED`: Weight-based distribution (e.g., 70% to replica1, 30% to replica2)

### SQL Hints for Manual Routing

Future enhancement: SQL comments to override automatic routing:

```sql
-- Force primary
SELECT /* ojp:route=primary */ * FROM users;

-- Force specific replica
SELECT /* ojp:route=replica2 */ * FROM analytics_data;
```

### Integration with Connection Pooling

Read/write splitting integrates seamlessly with OJP's connection pooling:

- Each datasource (primary + replicas) has independent HikariCP pool
- Pool settings configured per datasource
- Health checking integrated with pool lifecycle
- No additional configuration needed

### Monitoring and Observability

Future enhancements for monitoring:

- Prometheus metrics for routing distribution
- OpenTelemetry spans for query routing
- Grafana dashboards for replica health
- Alerts for failover events

## FAQ

**Q: Do I need to change my application code?**  
A: No. Read/write splitting works transparently with existing JDBC applications.

**Q: What happens if all replicas are down?**  
A: All queries route to the primary database automatically (if `replicaFailoverToPrimary=true`).

**Q: Can I use read/write splitting with XA transactions?**  
A: Yes, but all queries in XA transactions route to primary for consistency.

**Q: What if my replication lag varies?**  
A: Use a conservative `stickySessionSeconds` value that exceeds your maximum replication lag.

**Q: Can I disable read/write splitting at runtime?**  
A: Currently requires OJP restart. Set `ojp.readwrite.enabled=false` and restart.

**Q: Does this work with connection pooling?**  
A: Yes, each datasource has its own connection pool (HikariCP).

**Q: Can I route specific queries to specific replicas?**  
A: Not currently. Round-robin is the only supported strategy. Manual routing hints are planned for future releases.

**Q: What happens during database failover (primary becomes replica)?**  
A: You must update OJP configuration and restart to reflect the new primary/replica topology.

## Related Documentation

- [Configuration Guide](../designs/READ_WRITE_SPLITTING_ANALYSIS.md) - Technical architecture and design
- [Sequence Diagrams](../designs/read-write-splitting-sequence-diagram.md) - Visual flow diagrams
- [Configuration Templates](../designs/read-write-splitting-configuration-templates.md) - Database-specific examples
- [Integration Tests](../../ojp-server/src/test/java/org/openjproxy/grpc/server/readwrite/SESSION_4_1_INTEGRATION_TESTS.md) - Test coverage documentation

## Support

For issues, questions, or feature requests:
- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- Documentation: https://github.com/Open-J-Proxy/ojp/tree/main/documents

---

**Last Updated**: 2026-04-11  
**Version**: 1.0 (Initial Release)  
**Status**: Production Ready (State Tracking Infrastructure Complete)
