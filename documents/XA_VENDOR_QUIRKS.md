# OJP XA Vendor Quirks and Configuration Guide

## Overview

This document details vendor-specific XA behaviors, configuration requirements, and known limitations for databases supported by OJP's distributed transaction implementation.

---

## PostgreSQL

### XA Implementation

PostgreSQL implements XA transactions using `PREPARE TRANSACTION`:
```sql
-- XA start creates a transaction
BEGIN;

-- XA prepare durably records the transaction
PREPARE TRANSACTION 'xid_12345';

-- XA commit completes the prepared transaction
COMMIT PREPARED 'xid_12345';

-- XA rollback aborts the prepared transaction
ROLLBACK PREPARED 'xid_12345';
```

### Configuration Requirements

**1. Enable prepared transactions in postgresql.conf:**
```ini
max_prepared_transactions = 100  # Must be > 0, recommend >= max_connections
```

**Default:** `0` (prepared transactions disabled)

**Restart required:** Yes

**2. Grant PREPARE privilege (optional, if using restricted roles):**
```sql
GRANT ALL PRIVILEGES ON DATABASE mydb TO myuser;
```

### XA Driver Class

```properties
xa.datasource.className=org.postgresql.xa.PGXADataSource
```

### Recovery Behavior

**How it works:**
- Prepared transactions are stored in PostgreSQL's transaction log (WAL)
- `XAResource.recover()` queries `pg_prepared_xacts` system view
- Transactions survive server restart

**Query prepared transactions:**
```sql
SELECT * FROM pg_prepared_xacts;
```

**Manual cleanup (if needed):**
```sql
-- List prepared transactions
SELECT gid, prepared, owner, database FROM pg_prepared_xacts;

-- Manually commit or rollback
COMMIT PREPARED 'xid_12345';
ROLLBACK PREPARED 'xid_12345';
```

### Known Limitations

1. **Transaction ID format:**
   - PostgreSQL's transaction ID must fit in 200 characters
   - OJP formats Xid as: `formatId_globalTxId_branchQualifier`
   - Very long Xids may be rejected

2. **Idle prepared transactions:**
   - Prepared transactions hold locks and consume resources
   - Old prepared transactions can cause VACUUM issues
   - Monitor and clean up abandoned prepared transactions

3. **No heuristic decisions:**
   - PostgreSQL does not support heuristic commit/rollback
   - Always use transaction manager recovery

### Testing Checklist

- [ ] Verify `max_prepared_transactions` is configured
- [ ] Test 2PC across two PostgreSQL databases
- [ ] Test crash recovery (kill OJP server during prepare)
- [ ] Verify `recover()` returns prepared Xids after restart
- [ ] Test prepared transaction cleanup

### Recommended Configuration

```properties
# PostgreSQL XA DataSource
xa.datasource.className=org.postgresql.xa.PGXADataSource
xa.url=jdbc:postgresql://localhost:5432/mydb
xa.username=postgres
xa.password=secret
xa.maxPoolSize=20
xa.minIdle=5

# PostgreSQL-specific properties
xa.prepareThreshold=5
xa.binaryTransfer=true
xa.reWriteBatchedInserts=true
```

---

## Oracle

### XA Implementation

Oracle has native XA support via Oracle XA libraries and JDBC driver.

### Configuration Requirements

**1. Grant XA privileges:**
```sql
GRANT SELECT ON sys.dba_pending_transactions TO myuser;
GRANT SELECT ON sys.pending_trans$ TO myuser;
GRANT SELECT ON sys.dba_2pc_pending TO myuser;
GRANT EXECUTE ON sys.dbms_xa TO myuser;
GRANT FORCE ANY TRANSACTION TO myuser;  -- For recovery
```

**2. Configure shared servers (if using):**
```sql
-- Oracle shared servers can cause issues with XA
-- Recommend dedicated connections for XA
ALTER SYSTEM SET shared_servers = 0;  -- Use dedicated servers
```

### XA Driver Classes

**Oracle 19c+ (ojdbc11.jar, ojdbc10.jar):**
```properties
xa.datasource.className=oracle.jdbc.xa.client.OracleXADataSource
```

**Oracle 12c (ojdbc8.jar):**
```properties
xa.datasource.className=oracle.jdbc.xa.client.OracleXADataSource
```

### Recovery Behavior

**How it works:**
- Oracle stores prepared transactions in `dba_2pc_pending` view
- `XAResource.recover()` queries Oracle's transaction log
- Supports heuristic decisions

**Query prepared transactions:**
```sql
SELECT * FROM sys.dba_2pc_pending;
SELECT * FROM sys.dba_pending_transactions;
```

**Manual recovery:**
```sql
-- Force commit
COMMIT FORCE 'local_tran_id';

-- Force rollback
ROLLBACK FORCE 'local_tran_id';
```

### Known Limitations

1. **Shared servers:**
   - XA transactions may not work correctly with shared server architecture
   - Use dedicated connections for XA

2. **RAC (Real Application Clusters):**
   - XA prepare may pin sessions to specific RAC nodes
   - Ensure connection pooling handles RAC failover correctly

3. **Heuristic decisions:**
   - Oracle supports heuristic commit/rollback
   - Must be handled by transaction manager

4. **Transaction branch limits:**
   - Oracle limits: 64 concurrent branches per transaction
   - Exceeding limit causes XAException

### Testing Checklist

- [ ] Grant necessary XA privileges
- [ ] Test 2PC across two Oracle databases
- [ ] Test distributed transaction across Oracle + PostgreSQL
- [ ] Test crash recovery with `recover()`
- [ ] Test RAC failover during prepare phase (if using RAC)
- [ ] Verify heuristic outcomes are logged

### Recommended Configuration

```properties
# Oracle XA DataSource
xa.datasource.className=oracle.jdbc.xa.client.OracleXADataSource
xa.url=jdbc:oracle:thin:@localhost:1521:ORCL
xa.username=system
xa.password=oracle
xa.maxPoolSize=20
xa.minIdle=5

# Oracle-specific properties
oracle.jdbc.implicitStatementCacheSize=25
oracle.jdbc.defaultRowPrefetch=20
```

---

## MySQL / MariaDB

### XA Implementation

MySQL 5.7+ and MariaDB 10.3+ support XA transactions.

### Configuration Requirements

**1. InnoDB storage engine (required):**
```sql
-- Check default storage engine
SHOW VARIABLES LIKE 'default_storage_engine';

-- Set to InnoDB if not already
SET GLOBAL default_storage_engine = 'InnoDB';
```

**2. Enable XA support:**
```sql
-- XA support is enabled by default in MySQL 5.7+
-- Verify with:
SHOW VARIABLES LIKE 'xa%';
```

**3. Binary logging considerations:**
```ini
# In my.cnf
binlog_format=ROW  # Recommended for XA
```

### XA Driver Classes

**MySQL:**
```properties
xa.datasource.className=com.mysql.cj.jdbc.MysqlXADataSource
```

**MariaDB:**
```properties
xa.datasource.className=org.mariadb.jdbc.MariaDbDataSource
```

### Recovery Behavior

**How it works:**
- Prepared transactions stored in InnoDB transaction log
- `XAResource.recover()` queries `INFORMATION_SCHEMA.INNODB_TRX`

**Query prepared transactions:**
```sql
-- MySQL/MariaDB
XA RECOVER;
XA RECOVER CONVERT XID;  -- Show Xid in hex format
```

**Manual cleanup:**
```sql
-- Commit prepared transaction
XA COMMIT 'xid_in_format';

-- Rollback prepared transaction
XA ROLLBACK 'xid_in_format';
```

### Known Limitations

1. **XA and binary logging:**
   - In some MySQL versions, XA PREPARE with binary logging can cause issues
   - Ensure binary logging is properly configured

2. **MyISAM not supported:**
   - XA transactions require InnoDB
   - Tables using MyISAM will not participate in XA

3. **Transaction timeout:**
   - MySQL has `innodb_lock_wait_timeout` (default 50 seconds)
   - Long-running XA transactions may timeout
   - Configure appropriately for your workload

4. **Prepared transaction limits:**
   - No explicit limit, but too many prepared transactions degrade performance
   - Monitor and clean up abandoned prepared transactions

5. **Heuristic decisions:**
   - MySQL does not support heuristic outcomes
   - Failed prepared transactions must be resolved via recover()

### Testing Checklist

- [ ] Verify InnoDB is default storage engine
- [ ] Test 2PC across two MySQL databases
- [ ] Test crash recovery after prepare
- [ ] Verify `XA RECOVER` returns prepared Xids
- [ ] Test timeout behavior with long-running transactions
- [ ] Test with binary logging enabled

### Recommended Configuration

**MySQL:**
```properties
# MySQL XA DataSource
xa.datasource.className=com.mysql.cj.jdbc.MysqlXADataSource
xa.url=jdbc:mysql://localhost:3306/mydb?useSSL=false&allowPublicKeyRetrieval=true
xa.username=root
xa.password=mysql
xa.maxPoolSize=20
xa.minIdle=5

# MySQL-specific properties
xa.cachePrepStmts=true
xa.prepStmtCacheSize=250
xa.prepStmtCacheSqlLimit=2048
```

**MariaDB:**
```properties
# MariaDB XA DataSource
xa.datasource.className=org.mariadb.jdbc.MariaDbDataSource
xa.url=jdbc:mariadb://localhost:3306/mydb
xa.username=root
xa.password=mariadb
xa.maxPoolSize=20
xa.minIdle=5
```

---

## Microsoft SQL Server

### XA Implementation

SQL Server requires MS DTC (Microsoft Distributed Transaction Coordinator) for XA support.

### Configuration Requirements

**1. Enable MS DTC:**

**Windows Server:**
```powershell
# Start MS DTC service
net start msdtc

# Configure MS DTC (Component Services Console)
# - Allow Inbound/Outbound connections
# - Enable XA Transactions
# - Enable SNA LU 6.2 Transactions (optional)
```

**Configuration steps:**
1. Open "Component Services" (comexp.msc)
2. Navigate to: Component Services > Computers > My Computer > Distributed Transaction Coordinator > Local DTC
3. Right-click "Local DTC" → Properties
4. Security tab:
   - ✅ Network DTC Access
   - ✅ Allow Inbound
   - ✅ Allow Outbound
   - ✅ Enable XA Transactions
   - ✅ Enable SNA LU 6.2 Transactions (if needed)
5. Click OK and restart MS DTC service

**2. Install SQL Server XA support:**
```sql
-- Run as administrator
-- Installs XA support stored procedures
EXEC sp_addextendedproc 'xp_sqljdbc_xa_init', 
    'sqljdbc_xa.dll';
```

**Verify installation:**
```sql
SELECT * FROM sys.extended_stored_procedures 
WHERE name LIKE 'xp_sqljdbc%';
```

**3. Grant XA permissions:**
```sql
-- Grant XA permissions to SQL Server service account
EXEC sp_addrolemember 'SqlJDBCXAUser', 'DOMAIN\SQLServerServiceAccount';
```

### XA Driver Class

**SQL Server 2019+ (mssql-jdbc-12.x.jar):**
```properties
xa.datasource.className=com.microsoft.sqlserver.jdbc.SQLServerXADataSource
```

**SQL Server 2017 (mssql-jdbc-9.x.jar or 11.x.jar):**
```properties
xa.datasource.className=com.microsoft.sqlserver.jdbc.SQLServerXADataSource
```

### Recovery Behavior

**How it works:**
- Prepared transactions managed by MS DTC
- `XAResource.recover()` queries MS DTC transaction log
- Supports heuristic decisions

**Query prepared transactions (via MS DTC UI):**
1. Open "Component Services" (comexp.msc)
2. Navigate to: Distributed Transaction Coordinator > Local DTC > Transaction List
3. View in-doubt transactions

**Manual cleanup:**
```sql
-- Query distributed transactions
SELECT * FROM sys.dm_tran_active_transactions 
WHERE transaction_type = 4;  -- Distributed

-- Resolve manually via MS DTC UI if needed
```

### Known Limitations

1. **MS DTC dependency:**
   - XA requires MS DTC service running
   - MS DTC must be configured correctly (network access, XA enabled)
   - Firewall may need ports opened (default: 135, 1024-65535 dynamic)

2. **Cross-server communication:**
   - MS DTC requires RPC to be enabled between servers
   - Network security and firewall configuration critical

3. **Linux SQL Server:**
   - MS DTC not available on Linux
   - XA transactions not supported on SQL Server for Linux

4. **Prepared transaction retention:**
   - In-doubt transactions can cause log growth
   - Monitor and resolve orphaned prepared transactions

5. **Heuristic outcomes:**
   - SQL Server supports heuristic commit/rollback via MS DTC
   - Must be handled by transaction manager

### Testing Checklist

- [ ] Verify MS DTC service is running and configured
- [ ] Install SQL Server XA support (sqljdbc_xa.dll)
- [ ] Grant XA permissions to SQL Server service account
- [ ] Test 2PC across two SQL Server instances
- [ ] Test crash recovery with MS DTC
- [ ] Verify `recover()` returns prepared Xids
- [ ] Test firewall/network connectivity for distributed transactions

### Recommended Configuration

```properties
# SQL Server XA DataSource
xa.datasource.className=com.microsoft.sqlserver.jdbc.SQLServerXADataSource
xa.url=jdbc:sqlserver://localhost:1433;databaseName=mydb;encrypt=true;trustServerCertificate=true
xa.username=sa
xa.password=YourStrong@Passw0rd
xa.maxPoolSize=20
xa.minIdle=5

# SQL Server-specific properties
xa.sendStringParametersAsUnicode=false
xa.selectMethod=cursor
xa.responseBuffering=adaptive
```

**MS DTC Configuration (Windows Registry):**
```reg
[HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\MSDTC\Security]
"NetworkDtcAccess"=dword:00000001
"NetworkDtcAccessInbound"=dword:00000001
"NetworkDtcAccessOutbound"=dword:00000001
"XaTransactions"=dword:00000001
```

---

## DB2

### XA Implementation

IBM DB2 has robust native XA support.

### Configuration Requirements

**1. Enable XA support:**
```sql
-- DB2 XA support is enabled by default
-- Verify with:
db2 get dbm cfg | grep -i xa
```

**2. Grant XA privileges:**
```sql
GRANT DBADM ON DATABASE TO USER myuser;
```

### XA Driver Classes

**DB2 11.5+ (db2jcc4.jar):**
```properties
xa.datasource.className=com.ibm.db2.jcc.DB2XADataSource
```

### Recovery Behavior

**How it works:**
- DB2 stores prepared transactions in transaction log
- `XAResource.recover()` queries DB2's XA transaction manager

**Query prepared transactions:**
```sql
-- List indoubt transactions
SELECT * FROM SYSIBMADM.SNAPHADR_INDOUBT_TXN;
```

**Manual recovery:**
```sql
-- Commit indoubt transaction
COMMIT WORK TRANSACTION 'xid';

-- Rollback indoubt transaction
ROLLBACK WORK TRANSACTION 'xid';
```

### Known Limitations

1. **Transaction log space:**
   - Prepared transactions hold log space
   - Monitor log utilization

2. **Heuristic decisions:**
   - DB2 supports heuristic outcomes
   - Must be handled by transaction manager

### Testing Checklist

- [ ] Verify XA is enabled
- [ ] Test 2PC across two DB2 databases
- [ ] Test crash recovery
- [ ] Verify `recover()` works after restart

### Recommended Configuration

```properties
# DB2 XA DataSource
xa.datasource.className=com.ibm.db2.jcc.DB2XADataSource
xa.url=jdbc:db2://localhost:50000/mydb
xa.username=db2admin
xa.password=db2admin
xa.maxPoolSize=20
xa.minIdle=5
```

---

## H2 Database

### XA Implementation

H2 supports XA transactions but with limitations.

### Configuration Requirements

**No special configuration required.**

### XA Driver Class

```properties
xa.datasource.className=org.h2.jdbcx.JdbcDataSource
```

### Recovery Behavior

**How it works:**
- H2 stores prepared transactions in-memory
- Prepared transactions do NOT survive database restart
- Not recommended for production use

### Known Limitations

1. **No durability:**
   - Prepared transactions lost on restart
   - **Do NOT use H2 XA in production**

2. **Testing only:**
   - Suitable for unit/integration tests
   - Not suitable for production distributed transactions

### Testing Checklist

- [ ] Use H2 for testing only
- [ ] Do NOT rely on H2 XA for production workloads

### Recommended Configuration (Testing Only)

```properties
# H2 XA DataSource (TESTING ONLY)
xa.datasource.className=org.h2.jdbcx.JdbcDataSource
xa.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
xa.username=sa
xa.password=
xa.maxPoolSize=10
xa.minIdle=2
```

---

## Summary Comparison

| Database   | XA Support | Durability | Heuristics | Complexity | Recommended |
|------------|-----------|------------|------------|------------|-------------|
| PostgreSQL | ✅ Excellent | ✅ Yes | ❌ No | Low | ✅ Yes |
| Oracle     | ✅ Excellent | ✅ Yes | ✅ Yes | Medium | ✅ Yes |
| MySQL      | ✅ Good | ✅ Yes | ❌ No | Low | ✅ Yes |
| MariaDB    | ✅ Good | ✅ Yes | ❌ No | Low | ✅ Yes |
| SQL Server | ✅ Excellent | ✅ Yes | ✅ Yes | High | ⚠️ Windows Only |
| DB2        | ✅ Excellent | ✅ Yes | ✅ Yes | Medium | ✅ Yes |
| H2         | ⚠️ Limited | ❌ No | ❌ No | Low | ❌ Testing Only |

---

## General Best Practices

### 1. Monitor Prepared Transactions

All vendors: Monitor and clean up abandoned prepared transactions.

**PostgreSQL:**
```sql
-- Check for old prepared transactions
SELECT age(now(), prepared) AS age, * 
FROM pg_prepared_xacts 
WHERE age(now(), prepared) > interval '1 hour';
```

**Oracle:**
```sql
-- Check for old prepared transactions
SELECT * FROM sys.dba_2pc_pending 
WHERE state = 'prepared';
```

**MySQL:**
```sql
-- Check for old prepared transactions
XA RECOVER;
```

### 2. Configure Timeouts

Set appropriate timeouts at all levels:
- JTA transaction manager timeout
- XA transaction timeout (`setTransactionTimeout()`)
- Database statement timeout

### 3. Test Recovery

**Always test crash scenarios:**
1. Start distributed transaction
2. Execute prepare phase (XA_OK returned)
3. Kill OJP server before commit
4. Restart OJP server
5. Verify transaction manager recovers and completes transaction

### 4. Use Dedicated Connections

For XA workloads:
- Use dedicated connection pools for XA
- Do NOT mix XA and non-XA connections in same pool

### 5. Firewall Configuration

For distributed systems:
- Ensure firewall allows connections between all nodes
- SQL Server requires MS DTC ports open

---

## Troubleshooting

### Problem: "XA not supported"

**Symptom:** `SQLException: XA transactions not supported`

**Possible Causes:**
- PostgreSQL: `max_prepared_transactions = 0`
- MySQL: MyISAM tables instead of InnoDB
- SQL Server: MS DTC not running or not configured
- SQL Server Linux: XA not available

**Solution:**
- Enable XA support per vendor instructions above
- Verify configuration with vendor-specific queries

### Problem: "Prepared transactions accumulating"

**Symptom:** Old prepared transactions not being cleaned up

**Possible Causes:**
- Transaction manager not calling `recover()`
- Network failures preventing completion
- Application crashes before recovery

**Solution:**
- Enable transaction manager recovery
- Manually clean up using vendor-specific commands
- Implement monitoring/alerting for old prepared transactions

### Problem: "MS DTC connection failures"

**Symptom:** `SQLException: MSDTC unavailable`

**Possible Causes:**
- MS DTC service not running
- XA transactions not enabled in MS DTC
- Firewall blocking MS DTC ports
- Network permissions issues

**Solution:**
- Verify MS DTC service running: `net start msdtc`
- Check MS DTC configuration (Component Services)
- Open firewall ports (135, 1024-65535 dynamic)
- Grant network permissions to SQL Server service account

---

## Additional Resources

**PostgreSQL:**
- https://www.postgresql.org/docs/current/sql-prepare-transaction.html

**Oracle:**
- https://docs.oracle.com/en/database/oracle/oracle-database/21/jjdbc/distributed-transactions.html

**MySQL:**
- https://dev.mysql.com/doc/refman/8.0/en/xa.html

**SQL Server:**
- https://learn.microsoft.com/en-us/sql/connect/jdbc/understanding-xa-transactions

**DB2:**
- https://www.ibm.com/docs/en/db2/11.5?topic=applications-programming-xa-transactions

---

## For Support

- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- Documentation: https://github.com/Open-J-Proxy/ojp/blob/main/documents/

