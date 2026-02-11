# Read/Write Splitting Configuration Template

This file provides configuration templates for implementing read/write splitting in OJP.

## Template 1: Single Primary with Two Read Replicas (PostgreSQL)

```properties
# ============================================================================
# Primary Database Configuration
# ============================================================================
# This is the main write database where all INSERT, UPDATE, DELETE, and DDL
# operations will be routed. It also serves as the fallback for reads when
# replicas are unavailable.

primary.ojp.connection.pool.maximumPoolSize=50
primary.ojp.connection.pool.minimumIdle=10
primary.ojp.connection.pool.connectionTimeout=30000
primary.ojp.connection.pool.idleTimeout=600000
primary.ojp.connection.pool.maxLifetime=1800000

# Read/Write splitting configuration for primary
primary.ojp.readwrite.enabled=true
primary.ojp.readwrite.role=primary

# Replica selection strategy: ROUND_ROBIN | RANDOM | LEAST_CONNECTIONS
primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN

# Sticky session: After a write, continue reading from primary for N seconds
# This ensures read-your-writes consistency
primary.ojp.readwrite.stickySessionSeconds=5

# Failover: Automatically use primary if replicas are unavailable
primary.ojp.readwrite.replicaFailoverToPrimary=true

# ============================================================================
# Read Replica 1 Configuration
# ============================================================================
replica1.ojp.connection.pool.maximumPoolSize=30
replica1.ojp.connection.pool.minimumIdle=5
replica1.ojp.connection.pool.connectionTimeout=30000
replica1.ojp.connection.pool.idleTimeout=600000
replica1.ojp.connection.pool.maxLifetime=1800000

# Replica-specific settings
replica1.ojp.readwrite.role=replica
replica1.ojp.readwrite.primary=primary

# Replica database URL (different from primary)
replica1.ojp.connection.url=jdbc:postgresql://replica1.example.com:5432/mydb

# ============================================================================
# Read Replica 2 Configuration
# ============================================================================
replica2.ojp.connection.pool.maximumPoolSize=30
replica2.ojp.connection.pool.minimumIdle=5
replica2.ojp.connection.pool.connectionTimeout=30000
replica2.ojp.connection.pool.idleTimeout=600000
replica2.ojp.connection.pool.maxLifetime=1800000

# Replica-specific settings
replica2.ojp.readwrite.role=replica
replica2.ojp.readwrite.primary=primary

# Replica database URL
replica2.ojp.connection.url=jdbc:postgresql://replica2.example.com:5432/mydb
```

**Application JDBC URL (unchanged):**
```java
String url = "jdbc:ojp[localhost:1059(primary)]_postgresql://primary.example.com:5432/mydb";
Connection conn = DriverManager.getConnection(url, "username", "password");
```

---

## Template 2: MySQL Primary with Three Replicas

```properties
# ============================================================================
# Primary Database - MySQL
# ============================================================================
mysql_primary.ojp.connection.pool.maximumPoolSize=100
mysql_primary.ojp.connection.pool.minimumIdle=20
mysql_primary.ojp.connection.pool.connectionTimeout=30000
mysql_primary.ojp.connection.pool.idleTimeout=600000

# Read/Write splitting
mysql_primary.ojp.readwrite.enabled=true
mysql_primary.ojp.readwrite.role=primary
mysql_primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
mysql_primary.ojp.readwrite.stickySessionSeconds=10

# ============================================================================
# Read Replicas - MySQL (3 replicas for high-read workload)
# ============================================================================

# Replica 1
mysql_replica1.ojp.connection.pool.maximumPoolSize=40
mysql_replica1.ojp.connection.pool.minimumIdle=8
mysql_replica1.ojp.readwrite.role=replica
mysql_replica1.ojp.readwrite.primary=mysql_primary
mysql_replica1.ojp.connection.url=jdbc:mysql://mysql-replica1.example.com:3306/mydb

# Replica 2
mysql_replica2.ojp.connection.pool.maximumPoolSize=40
mysql_replica2.ojp.connection.pool.minimumIdle=8
mysql_replica2.ojp.readwrite.role=replica
mysql_replica2.ojp.readwrite.primary=mysql_primary
mysql_replica2.ojp.connection.url=jdbc:mysql://mysql-replica2.example.com:3306/mydb

# Replica 3
mysql_replica3.ojp.connection.pool.maximumPoolSize=40
mysql_replica3.ojp.connection.pool.minimumIdle=8
mysql_replica3.ojp.readwrite.role=replica
mysql_replica3.ojp.readwrite.primary=mysql_primary
mysql_replica3.ojp.connection.url=jdbc:mysql://mysql-replica3.example.com:3306/mydb
```

**Application JDBC URL:**
```java
String url = "jdbc:ojp[localhost:1059(mysql_primary)]_mysql://mysql-primary.example.com:3306/mydb";
```

---

## Template 3: Environment-Specific Configuration

### Development Environment (ojp-dev.properties)

```properties
# Development: No replicas, single database for simplicity
dev_db.ojp.connection.pool.maximumPoolSize=10
dev_db.ojp.connection.pool.minimumIdle=2

# Read/Write splitting disabled in dev
dev_db.ojp.readwrite.enabled=false
```

### Staging Environment (ojp-staging.properties)

```properties
# Staging: Primary + 1 replica
staging_primary.ojp.connection.pool.maximumPoolSize=30
staging_primary.ojp.connection.pool.minimumIdle=5
staging_primary.ojp.readwrite.enabled=true
staging_primary.ojp.readwrite.role=primary
staging_primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
staging_primary.ojp.readwrite.stickySessionSeconds=5

staging_replica1.ojp.connection.pool.maximumPoolSize=20
staging_replica1.ojp.connection.pool.minimumIdle=3
staging_replica1.ojp.readwrite.role=replica
staging_replica1.ojp.readwrite.primary=staging_primary
staging_replica1.ojp.connection.url=jdbc:postgresql://staging-replica.example.com:5432/mydb
```

### Production Environment (ojp-prod.properties)

```properties
# Production: Primary + 4 replicas for high availability
prod_primary.ojp.connection.pool.maximumPoolSize=100
prod_primary.ojp.connection.pool.minimumIdle=20
prod_primary.ojp.readwrite.enabled=true
prod_primary.ojp.readwrite.role=primary
prod_primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
prod_primary.ojp.readwrite.stickySessionSeconds=10
prod_primary.ojp.readwrite.replicaFailoverToPrimary=true

# Replica 1 - US East
prod_replica1.ojp.connection.pool.maximumPoolSize=40
prod_replica1.ojp.connection.pool.minimumIdle=10
prod_replica1.ojp.readwrite.role=replica
prod_replica1.ojp.readwrite.primary=prod_primary
prod_replica1.ojp.connection.url=jdbc:postgresql://prod-replica1-us-east.example.com:5432/mydb

# Replica 2 - US East (same region for redundancy)
prod_replica2.ojp.connection.pool.maximumPoolSize=40
prod_replica2.ojp.connection.pool.minimumIdle=10
prod_replica2.ojp.readwrite.role=replica
prod_replica2.ojp.readwrite.primary=prod_primary
prod_replica2.ojp.connection.url=jdbc:postgresql://prod-replica2-us-east.example.com:5432/mydb

# Replica 3 - US West
prod_replica3.ojp.connection.pool.maximumPoolSize=40
prod_replica3.ojp.connection.pool.minimumIdle=10
prod_replica3.ojp.readwrite.role=replica
prod_replica3.ojp.readwrite.primary=prod_primary
prod_replica3.ojp.connection.url=jdbc:postgresql://prod-replica3-us-west.example.com:5432/mydb

# Replica 4 - EU
prod_replica4.ojp.connection.pool.maximumPoolSize=40
prod_replica4.ojp.connection.pool.minimumIdle=10
prod_replica4.ojp.readwrite.role=replica
prod_replica4.ojp.readwrite.primary=prod_primary
prod_replica4.ojp.connection.url=jdbc:postgresql://prod-replica4-eu.example.com:5432/mydb
```

**Set environment variable to select configuration:**
```bash
export OJP_ENVIRONMENT=prod
# or
java -Dojp.environment=prod -jar myapp.jar
```

---

## Template 4: Mixed Workload - Separate Pools for Different Uses

```properties
# ============================================================================
# Primary for transactional workload
# ============================================================================
transactional_primary.ojp.connection.pool.maximumPoolSize=80
transactional_primary.ojp.connection.pool.minimumIdle=15
transactional_primary.ojp.readwrite.enabled=true
transactional_primary.ojp.readwrite.role=primary
transactional_primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
transactional_primary.ojp.readwrite.stickySessionSeconds=5

transactional_replica1.ojp.connection.pool.maximumPoolSize=30
transactional_replica1.ojp.connection.pool.minimumIdle=5
transactional_replica1.ojp.readwrite.role=replica
transactional_replica1.ojp.readwrite.primary=transactional_primary
transactional_replica1.ojp.connection.url=jdbc:postgresql://replica1.example.com:5432/transactional_db

# ============================================================================
# Separate pool for reporting/analytics (read-heavy, long queries)
# ============================================================================
reporting_primary.ojp.connection.pool.maximumPoolSize=20
reporting_primary.ojp.connection.pool.minimumIdle=5
reporting_primary.ojp.connection.pool.connectionTimeout=60000
reporting_primary.ojp.readwrite.enabled=true
reporting_primary.ojp.readwrite.role=primary
reporting_primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
reporting_primary.ojp.readwrite.stickySessionSeconds=0  # No sticky for reports

# Dedicated replica for reporting (larger pool, longer timeouts)
reporting_replica1.ojp.connection.pool.maximumPoolSize=50
reporting_replica1.ojp.connection.pool.minimumIdle=10
reporting_replica1.ojp.connection.pool.connectionTimeout=60000
reporting_replica1.ojp.connection.pool.idleTimeout=900000
reporting_replica1.ojp.readwrite.role=replica
reporting_replica1.ojp.readwrite.primary=reporting_primary
reporting_replica1.ojp.connection.url=jdbc:postgresql://reporting-replica.example.com:5432/transactional_db
```

**Application uses different datasources for different purposes:**
```java
// Transactional workload
String transactionalUrl = "jdbc:ojp[localhost:1059(transactional_primary)]_postgresql://primary.example.com:5432/transactional_db";

// Reporting workload
String reportingUrl = "jdbc:ojp[localhost:1059(reporting_primary)]_postgresql://primary.example.com:5432/transactional_db";
```

---

## Template 5: Oracle Primary with Read Replicas

```properties
# ============================================================================
# Oracle Primary (Data Guard setup)
# ============================================================================
oracle_primary.ojp.connection.pool.maximumPoolSize=60
oracle_primary.ojp.connection.pool.minimumIdle=12
oracle_primary.ojp.connection.pool.connectionTimeout=30000
oracle_primary.ojp.readwrite.enabled=true
oracle_primary.ojp.readwrite.role=primary
oracle_primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
oracle_primary.ojp.readwrite.stickySessionSeconds=8

# Oracle Active Data Guard Standby 1 (read-only)
oracle_replica1.ojp.connection.pool.maximumPoolSize=40
oracle_replica1.ojp.connection.pool.minimumIdle=8
oracle_replica1.ojp.readwrite.role=replica
oracle_replica1.ojp.readwrite.primary=oracle_primary
oracle_replica1.ojp.connection.url=jdbc:oracle:thin:@replica1.example.com:1521/ORCL

# Oracle Active Data Guard Standby 2 (read-only)
oracle_replica2.ojp.connection.pool.maximumPoolSize=40
oracle_replica2.ojp.connection.pool.minimumIdle=8
oracle_replica2.ojp.readwrite.role=replica
oracle_replica2.ojp.readwrite.primary=oracle_primary
oracle_replica2.ojp.connection.url=jdbc:oracle:thin:@replica2.example.com:1521/ORCL
```

**Application JDBC URL:**
```java
String url = "jdbc:ojp[localhost:1059(oracle_primary)]_oracle:thin:@primary.example.com:1521/ORCL";
```

---

## Template 6: SQL Server with Always On Availability Groups

```properties
# ============================================================================
# SQL Server Primary (Always On Primary Replica)
# ============================================================================
sqlserver_primary.ojp.connection.pool.maximumPoolSize=70
sqlserver_primary.ojp.connection.pool.minimumIdle=14
sqlserver_primary.ojp.connection.pool.connectionTimeout=30000
sqlserver_primary.ojp.readwrite.enabled=true
sqlserver_primary.ojp.readwrite.role=primary
sqlserver_primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
sqlserver_primary.ojp.readwrite.stickySessionSeconds=7

# SQL Server Secondary Replica 1 (read-only)
sqlserver_replica1.ojp.connection.pool.maximumPoolSize=35
sqlserver_replica1.ojp.connection.pool.minimumIdle=7
sqlserver_replica1.ojp.readwrite.role=replica
sqlserver_replica1.ojp.readwrite.primary=sqlserver_primary
sqlserver_replica1.ojp.connection.url=jdbc:sqlserver://secondary1.example.com:1433;databaseName=MyDatabase;applicationIntent=ReadOnly

# SQL Server Secondary Replica 2 (read-only)
sqlserver_replica2.ojp.connection.pool.maximumPoolSize=35
sqlserver_replica2.ojp.connection.pool.minimumIdle=7
sqlserver_replica2.ojp.readwrite.role=replica
sqlserver_replica2.ojp.readwrite.primary=sqlserver_primary
sqlserver_replica2.ojp.connection.url=jdbc:sqlserver://secondary2.example.com:1433;databaseName=MyDatabase;applicationIntent=ReadOnly
```

**Application JDBC URL:**
```java
String url = "jdbc:ojp[localhost:1059(sqlserver_primary)]_sqlserver://primary.example.com:1433;databaseName=MyDatabase";
```

---

## Configuration Property Reference

### Core Read/Write Splitting Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `{datasource}.ojp.readwrite.enabled` | boolean | false | Enable read/write splitting for this datasource |
| `{datasource}.ojp.readwrite.role` | string | - | Datasource role: `primary` or `replica` |
| `{datasource}.ojp.readwrite.primary` | string | - | For replicas: name of the primary datasource |
| `{datasource}.ojp.readwrite.replicaSelectionStrategy` | string | ROUND_ROBIN | Strategy for selecting replicas: `ROUND_ROBIN`, `RANDOM`, `LEAST_CONNECTIONS` |
| `{datasource}.ojp.readwrite.stickySessionSeconds` | integer | 0 | Duration (seconds) to route reads to primary after a write (0 = disabled) |
| `{datasource}.ojp.readwrite.replicaFailoverToPrimary` | boolean | true | Automatically use primary if replicas are unavailable |

### Replica-Specific Properties

| Property | Type | Description |
|----------|------|-------------|
| `{replica}.ojp.connection.url` | string | Full JDBC URL for the replica database (different from primary URL) |

### Standard Connection Pool Properties (apply to both primary and replicas)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `{datasource}.ojp.connection.pool.maximumPoolSize` | integer | 10 | Maximum number of connections in the pool |
| `{datasource}.ojp.connection.pool.minimumIdle` | integer | 2 | Minimum number of idle connections |
| `{datasource}.ojp.connection.pool.connectionTimeout` | long | 30000 | Maximum time (ms) to wait for connection |
| `{datasource}.ojp.connection.pool.idleTimeout` | long | 600000 | Maximum idle time (ms) before connection is retired |
| `{datasource}.ojp.connection.pool.maxLifetime` | long | 1800000 | Maximum lifetime (ms) of a connection in the pool |

---

## Best Practices

### 1. Pool Sizing

- **Primary Pool**: Size based on write workload + fallback reads
- **Replica Pools**: Size based on read distribution (total reads / number of replicas)
- **Example**: If you have 100 connections for reads and 2 replicas, size each replica pool to 50

### 2. Sticky Session Duration

- **Short duration (3-5s)**: Good for most applications, balances consistency and read distribution
- **Long duration (10-15s)**: Use for applications with tight consistency requirements
- **Zero (disabled)**: Use for applications that can tolerate eventual consistency

### 3. Replica Selection Strategy

- **ROUND_ROBIN**: Simple, fair distribution - recommended for most cases
- **RANDOM**: Good for avoiding patterns, similar to round-robin
- **LEAST_CONNECTIONS**: Use when query complexity varies significantly

### 4. Failover Configuration

- Always enable `replicaFailoverToPrimary=true` unless you want to explicitly fail when replicas are down
- Consider implementing circuit breaker patterns for unhealthy replicas

### 5. Monitoring

- Track read/write ratio metrics
- Monitor replica lag (future enhancement)
- Alert on replica failures
- Track sticky session hit rate

---

## Migration Checklist

When implementing read/write splitting:

- [ ] Verify database replication is working correctly
- [ ] Test application with read/write splitting disabled first
- [ ] Start with primary only, add replicas incrementally
- [ ] Enable sticky session initially (conservative approach)
- [ ] Monitor read distribution across replicas
- [ ] Test failover scenarios (replica down)
- [ ] Verify transaction handling (all operations on primary)
- [ ] Test read-your-writes scenarios
- [ ] Monitor for any consistency issues
- [ ] Gradually increase replica pool sizes
- [ ] Reduce sticky session duration if possible

---

## Troubleshooting

### Issue: Stale reads even with sticky session

**Solution**: Increase `stickySessionSeconds` or check replication lag

### Issue: All reads going to primary

**Possible causes**:
- Application using transactions for all operations
- Sticky session duration too long
- SQL classification incorrectly marking reads as writes
- Replicas are down

**Debug**: Enable debug logging for `ReadWriteRouter`

### Issue: Replica connection failures

**Solution**: 
- Verify replica URLs are correct
- Check replica credentials
- Ensure `replicaFailoverToPrimary=true`

---

## Example Complete Configuration

See the templates above for complete working examples for various database systems and deployment scenarios.
