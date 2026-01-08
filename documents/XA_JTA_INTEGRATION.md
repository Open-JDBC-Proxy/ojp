# OJP XA/JTA Integration Guide

## Overview

This document provides comprehensive guidance for integrating OJP's XA implementation with popular JTA transaction managers including Spring Framework, Atomikos, and Narayana.

**Key Requirement:** You MUST use `OjpXADataSource` (not the regular `DataSource`) for distributed transactions.

---

## Understanding OJP XA Architecture

### XA vs Non-XA Connections

**Non-XA Connection (Regular DataSource):**
```java
// Regular JDBC connection - local transactions only
DataSource ds = new DataSource();
ds.setUrl("jdbc:ojp:...");
Connection conn = ds.getConnection();  // Local transaction
```

**XA Connection (Distributed Transactions):**
```java
// XA connection - distributed transactions
OjpXADataSource xaDs = new OjpXADataSource();
xaDs.setUrl("jdbc:ojp:...");
XAConnection xaConn = xaDs.getXAConnection();
Connection conn = xaConn.getConnection();  // Managed by JTA
XAResource xaRes = xaConn.getXAResource(); // Enrolled in global transaction
```

### Automatic Enlistment

Most JTA transaction managers provide automatic enlistment when using XADataSource:
- **Spring**: Use `JtaTransactionManager` with `OjpXADataSource`
- **Atomikos**: Wrap `OjpXADataSource` in `AtomikosDataSourceBean`
- **Narayana**: Use Narayana's transaction services with `OjpXADataSource`

---

## Spring Framework Integration

### Spring Boot with JTA (Atomikos)

Spring Boot provides excellent support for XA transactions via Atomikos.

**Step 1: Add Dependencies**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jta-atomikos</artifactId>
</dependency>
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.3.2-snapshot</version>
</dependency>
```

**Step 2: Configure XADataSource Beans**

```java
@Configuration
@EnableTransactionManagement
public class JtaConfig {
    
    @Bean(name = "ojpDataSource1")
    @ConfigurationProperties(prefix = "spring.jta.atomikos.datasource.ojp1")
    public AtomikosDataSourceBean ojpDataSource1() {
        AtomikosDataSourceBean ds = new AtomikosDataSourceBean();
        ds.setUniqueResourceName("ojpDB1");
        ds.setXaDataSourceClassName("org.openjproxy.jdbc.xa.OjpXADataSource");
        
        Properties props = new Properties();
        props.setProperty("url", "jdbc:ojp:grpc://localhost:10591?datasource=postgres1");
        props.setProperty("user", "postgres");
        props.setProperty("password", "password");
        ds.setXaProperties(props);
        
        ds.setPoolSize(10);
        ds.setMinPoolSize(5);
        ds.setMaxPoolSize(20);
        
        return ds;
    }
    
    @Bean(name = "ojpDataSource2")
    @ConfigurationProperties(prefix = "spring.jta.atomikos.datasource.ojp2")
    public AtomikosDataSourceBean ojpDataSource2() {
        AtomikosDataSourceBean ds = new AtomikosDataSourceBean();
        ds.setUniqueResourceName("ojpDB2");
        ds.setXaDataSourceClassName("org.openjproxy.jdbc.xa.OjpXADataSource");
        
        Properties props = new Properties();
        props.setProperty("url", "jdbc:ojp:grpc://localhost:10592?datasource=postgres2");
        props.setProperty("user", "postgres");
        props.setProperty("password", "password");
        ds.setXaProperties(props);
        
        ds.setPoolSize(10);
        ds.setMinPoolSize(5);
        ds.setMaxPoolSize(20);
        
        return ds;
    }
    
    @Bean
    public JtaTransactionManager transactionManager() {
        return new JtaTransactionManager();
    }
}
```

**Step 3: Use Transactional Service**

```java
@Service
public class DistributedTransactionService {
    
    @Autowired
    @Qualifier("ojpDataSource1")
    private DataSource dataSource1;
    
    @Autowired
    @Qualifier("ojpDataSource2")
    private DataSource dataSource2;
    
    @Transactional
    public void performDistributedTransaction() {
        // Both operations participate in the same distributed transaction
        try (Connection conn1 = dataSource1.getConnection();
             Connection conn2 = dataSource2.getConnection()) {
            
            // Update database 1
            try (PreparedStatement ps1 = conn1.prepareStatement(
                    "INSERT INTO accounts (id, balance) VALUES (?, ?)")) {
                ps1.setInt(1, 1);
                ps1.setBigDecimal(2, new BigDecimal("1000.00"));
                ps1.executeUpdate();
            }
            
            // Update database 2
            try (PreparedStatement ps2 = conn2.prepareStatement(
                    "INSERT INTO ledger (account_id, amount) VALUES (?, ?)")) {
                ps2.setInt(1, 1);
                ps2.setBigDecimal(2, new BigDecimal("1000.00"));
                ps2.executeUpdate();
            }
            
            // Both commit together via 2PC, or both rollback on exception
        }
    }
}
```

**application.properties:**

```properties
# Atomikos transaction manager configuration
spring.jta.enabled=true
spring.jta.atomikos.properties.log-base-dir=./atomikos-logs
spring.jta.atomikos.properties.checkpoint-interval=10000

# OJP DataSource 1
spring.jta.atomikos.datasource.ojp1.unique-resource-name=ojpDB1
spring.jta.atomikos.datasource.ojp1.max-pool-size=20
spring.jta.atomikos.datasource.ojp1.min-pool-size=5

# OJP DataSource 2
spring.jta.atomikos.datasource.ojp2.unique-resource-name=ojpDB2
spring.jta.atomikos.datasource.ojp2.max-pool-size=20
spring.jta.atomikos.datasource.ojp2.min-pool-size=5
```

### Spring Framework with Narayana

**Step 1: Add Dependencies**

```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-tx</artifactId>
    <version>6.0.0</version>
</dependency>
<dependency>
    <groupId>org.jboss.narayana.jta</groupId>
    <artifactId>narayana-jta</artifactId>
    <version>5.13.0.Final</version>
</dependency>
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.3.2-snapshot</version>
</dependency>
```

**Step 2: Configure Transaction Manager**

```java
@Configuration
@EnableTransactionManagement
public class NarayanaConfig {
    
    @Bean
    public com.arjuna.ats.jta.TransactionManager narayanaTransactionManager() {
        return com.arjuna.ats.jta.TransactionManager.transactionManager();
    }
    
    @Bean
    public javax.transaction.UserTransaction narayanaUserTransaction() {
        return com.arjuna.ats.jta.UserTransaction.userTransaction();
    }
    
    @Bean
    public JtaTransactionManager transactionManager() {
        JtaTransactionManager tm = new JtaTransactionManager();
        tm.setTransactionManager(narayanaTransactionManager());
        tm.setUserTransaction(narayanaUserTransaction());
        return tm;
    }
    
    @Bean
    public DataSource ojpDataSource1() {
        OjpXADataSource xaDs = new OjpXADataSource();
        xaDs.setUrl("jdbc:ojp:grpc://localhost:10591?datasource=postgres1");
        xaDs.setUser("postgres");
        xaDs.setPassword("password");
        
        // Enlist with Narayana
        try {
            XAConnection xaConn = xaDs.getXAConnection();
            javax.transaction.TransactionManager tm = narayanaTransactionManager();
            tm.getTransaction().enlistResource(xaConn.getXAResource());
        } catch (Exception e) {
            throw new RuntimeException("Failed to enlist XAResource", e);
        }
        
        return xaDs;
    }
}
```

---

## Standalone Atomikos Integration

For applications not using Spring, you can integrate Atomikos directly.

**Step 1: Add Dependencies**

```xml
<dependency>
    <groupId>com.atomikos</groupId>
    <artifactId>transactions-jdbc</artifactId>
    <version>5.0.9</version>
</dependency>
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.3.2-snapshot</version>
</dependency>
```

**Step 2: Configure Atomikos XADataSource**

```java
import com.atomikos.jdbc.AtomikosDataSourceBean;

public class AtomikosSetup {
    
    public static AtomikosDataSourceBean createOjpDataSource(
            String resourceName, String url, String user, String password) {
        
        AtomikosDataSourceBean ds = new AtomikosDataSourceBean();
        ds.setUniqueResourceName(resourceName);
        ds.setXaDataSourceClassName("org.openjproxy.jdbc.xa.OjpXADataSource");
        
        Properties props = new Properties();
        props.setProperty("url", url);
        props.setProperty("user", user);
        props.setProperty("password", password);
        ds.setXaProperties(props);
        
        // Atomikos pool configuration
        ds.setPoolSize(10);
        ds.setMinPoolSize(5);
        ds.setMaxPoolSize(20);
        ds.setBorrowConnectionTimeout(30);
        ds.setMaintenanceInterval(60);
        ds.setMaxIdleTime(120);
        
        return ds;
    }
    
    public static void main(String[] args) {
        // Create two XA datasources
        AtomikosDataSourceBean ds1 = createOjpDataSource(
            "ojpDB1",
            "jdbc:ojp:grpc://localhost:10591?datasource=postgres1",
            "postgres",
            "password"
        );
        
        AtomikosDataSourceBean ds2 = createOjpDataSource(
            "ojpDB2",
            "jdbc:ojp:grpc://localhost:10592?datasource=postgres2",
            "postgres",
            "password"
        );
        
        // Use in distributed transaction
        UserTransactionManager utm = new UserTransactionManager();
        UserTransaction ut = new UserTransactionImp();
        
        try {
            ut.begin();
            
            try (Connection conn1 = ds1.getConnection();
                 Connection conn2 = ds2.getConnection()) {
                
                // Execute SQL on both databases
                conn1.createStatement().executeUpdate(
                    "INSERT INTO accounts VALUES (1, 'Alice', 1000)");
                conn2.createStatement().executeUpdate(
                    "INSERT INTO ledger VALUES (1, 1000, NOW())");
            }
            
            ut.commit();  // 2PC: both commit or both rollback
            
        } catch (Exception e) {
            try {
                ut.rollback();
            } catch (SystemException se) {
                se.printStackTrace();
            }
            e.printStackTrace();
        } finally {
            utm.close();
        }
    }
}
```

---

## Standalone Narayana Integration

**Step 1: Add Dependencies**

```xml
<dependency>
    <groupId>org.jboss.narayana.jta</groupId>
    <artifactId>narayana-jta</artifactId>
    <version>5.13.0.Final</version>
</dependency>
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.3.2-snapshot</version>
</dependency>
```

**Step 2: Use Narayana Transaction Manager**

```java
import com.arjuna.ats.jta.TransactionManager;
import com.arjuna.ats.jta.UserTransaction;

public class NarayanaExample {
    
    public static void main(String[] args) {
        // Create OJP XA datasources
        OjpXADataSource xaDs1 = new OjpXADataSource();
        xaDs1.setUrl("jdbc:ojp:grpc://localhost:10591?datasource=postgres1");
        xaDs1.setUser("postgres");
        xaDs1.setPassword("password");
        
        OjpXADataSource xaDs2 = new OjpXADataSource();
        xaDs2.setUrl("jdbc:ojp:grpc://localhost:10592?datasource=postgres2");
        xaDs2.setUser("postgres");
        xaDs2.setPassword("password");
        
        javax.transaction.UserTransaction ut = UserTransaction.userTransaction();
        javax.transaction.TransactionManager tm = TransactionManager.transactionManager();
        
        try {
            ut.begin();
            
            // Get XA connections and enlist resources
            XAConnection xaConn1 = xaDs1.getXAConnection();
            XAConnection xaConn2 = xaDs2.getXAConnection();
            
            tm.getTransaction().enlistResource(xaConn1.getXAResource());
            tm.getTransaction().enlistResource(xaConn2.getXAResource());
            
            // Execute SQL
            Connection conn1 = xaConn1.getConnection();
            Connection conn2 = xaConn2.getConnection();
            
            conn1.createStatement().executeUpdate(
                "INSERT INTO accounts VALUES (1, 'Alice', 1000)");
            conn2.createStatement().executeUpdate(
                "INSERT INTO ledger VALUES (1, 1000, NOW())");
            
            ut.commit();  // 2PC
            
        } catch (Exception e) {
            try {
                ut.rollback();
            } catch (Exception re) {
                re.printStackTrace();
            }
            e.printStackTrace();
        }
    }
}
```

---

## Important Behaviors

### Multiple getConnection() Calls

**Within the same JTA transaction:**

```java
@Transactional
public void multipleConnections() {
    Connection conn1 = dataSource.getConnection();
    Connection conn2 = dataSource.getConnection();
    // Both conn1 and conn2 share the same XA transaction context
    // Both use the same XAResource (no duplicate enlistment)
}
```

**Behavior:**
- ✅ Same XAResource is reused (no duplicate enlistment)
- ✅ Both connections route to the same backend session
- ✅ Closing one connection does NOT affect the other

### Connection.close() Inside Active Transaction

**Transaction managers often use "close early" patterns:**

```java
@Transactional
public void closeEarly() {
    Connection conn = dataSource.getConnection();
    // ... do work ...
    conn.close();  // Logical close only
    
    // Transaction is still active
    // Physical resources released at commit/rollback
}
```

**Behavior:**
- ✅ `conn.close()` performs logical close only
- ✅ Physical backend session remains bound to transaction
- ✅ Resources released when transaction completes (commit/rollback)

### JDBC Method Restrictions in XA Context

**setAutoCommit():**
```java
Connection conn = xaConn.getConnection();
conn.setAutoCommit(false);  // Ignored - XA controls transaction
```
- ✅ Ignored (logged but not enforced)
- ✅ XA protocol controls transaction boundaries

**commit() / rollback():**
```java
Connection conn = xaConn.getConnection();
conn.commit();  // ❌ SQLException thrown
```
- ❌ Throws SQLException
- ✅ Prevents local commits inside global transaction
- ✅ Must use `UserTransaction.commit()` or `@Transactional`

**setTransactionIsolation():**
```java
Connection conn = xaConn.getConnection();
conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
```
- ✅ Forwarded to backend database
- ✅ Applied to physical connection
- ✅ Reset to default after transaction completion

---

## Crash Recovery

### Transaction Manager Recovery

After a crash, the transaction manager must recover prepared transactions:

1. **TM starts recovery scan:**
   - Calls `XAResource.recover(TMSTARTRSCAN)` on all registered resources
   
2. **OJP queries backend databases:**
   - Each OJP server queries its backend DB's transaction log
   - Returns prepared Xids via vendor `XAResource.recover()`
   
3. **TM completes transactions:**
   - For each recovered Xid, TM checks its own log
   - Calls `XAResource.commit()` or `XAResource.rollback()`

### Example: Atomikos Recovery

Atomikos stores its transaction log in `atomikos-logs/`:

```properties
# Atomikos recovery configuration
com.atomikos.icatch.log_base_dir=./atomikos-logs
com.atomikos.icatch.checkpoint_interval=10000
com.atomikos.icatch.enable_logging=true
```

After restart, Atomikos automatically:
1. Reads its transaction log
2. Calls `recover()` on all registered XA resources
3. Completes pending transactions

**No manual intervention required.**

---

## Best Practices

### 1. Connection Pooling

**Do NOT pool XADataSource at the client level:**
```java
// ❌ BAD: Client-side pooling of XADataSource
HikariDataSource hikari = new HikariDataSource();
hikari.setDataSource(xaDataSource);  // Wrong!
```

**Let Atomikos/Narayana handle pooling:**
```java
// ✅ GOOD: Transaction manager handles pooling
AtomikosDataSourceBean atomikosDs = new AtomikosDataSourceBean();
atomikosDs.setXaDataSourceClassName("org.openjproxy.jdbc.xa.OjpXADataSource");
// Atomikos pools XA connections internally
```

**OJP server-side pooling is automatic:**
- Backend sessions are pooled at the OJP server
- No additional pooling needed at client

### 2. Transaction Timeout

Configure timeouts at multiple levels:

**JTA Transaction Manager:**
```java
@Transactional(timeout = 30)  // 30 seconds
public void longRunningTransaction() {
    // ...
}
```

**OJP XAResource:**
```java
XAResource xaRes = xaConn.getXAResource();
xaRes.setTransactionTimeout(30);  // 30 seconds
```

**Backend Database:**
```sql
-- PostgreSQL
SET statement_timeout = 30000;  -- 30 seconds
```

### 3. Error Handling

Always handle XA exceptions properly:

```java
try {
    ut.begin();
    // ... work ...
    ut.commit();
} catch (RollbackException e) {
    // Transaction was rolled back (possibly due to timeout)
    log.error("Transaction rolled back", e);
} catch (HeuristicMixedException e) {
    // Some resources committed, some rolled back (very rare)
    log.error("Heuristic mixed outcome - manual intervention required", e);
} catch (Exception e) {
    try {
        ut.rollback();
    } catch (Exception re) {
        log.error("Rollback failed", re);
    }
    throw e;
}
```

### 4. Monitoring

Monitor XA operations:

```java
// Atomikos statistics
AtomikosDataSourceBean ds = ...;
int activeConnections = ds.poolSize();
int availableConnections = ds.poolAvailableSize();

// OJP metrics (via JMX or custom endpoint)
Map<String, Object> stats = xaDataSource.getStatistics();
log.info("Active branches: {}", stats.get("activeBranches"));
log.info("Prepared count: {}", stats.get("preparedCount"));
```

---

## Troubleshooting

### Problem: "XAResource not enlisted"

**Symptom:** SQL executes but doesn't participate in global transaction.

**Cause:** XAResource not enlisted with transaction manager.

**Solution:**
- Verify using `OjpXADataSource` (not regular `DataSource`)
- Check transaction manager configuration
- Verify `@Transactional` annotation present

### Problem: "Cannot commit/rollback inside JTA transaction"

**Symptom:** `SQLException: Commit not allowed on XA connection`

**Cause:** Application calling `conn.commit()` directly.

**Solution:**
- Remove `conn.commit()` / `conn.rollback()` calls
- Let transaction manager control boundaries
- Use `UserTransaction.commit()` or `@Transactional`

### Problem: "Prepared transactions not recovered"

**Symptom:** In-doubt transactions after crash.

**Cause:** Transaction manager not calling `recover()`.

**Solution:**
- Verify TM recovery is enabled (e.g., Atomikos logs)
- Check TM log directory exists and is writable
- Restart TM to trigger recovery

### Problem: "Pool exhausted"

**Symptom:** `NoSuchElementException` or timeout waiting for connection.

**Cause:** Too many concurrent XA transactions.

**Solution:**
- Increase pool size: `xa.maxPoolSize=50`
- Reduce transaction duration
- Check for connection leaks (unclosed connections)

---

## Summary

**Key Points:**
1. ✅ Always use `OjpXADataSource` for distributed transactions
2. ✅ Let transaction manager (Atomikos/Narayana) handle pooling
3. ✅ Never call `commit()`/`rollback()` on Connection in JTA context
4. ✅ Configure timeouts at all levels (JTA, XA, DB)
5. ✅ Enable recovery in transaction manager
6. ✅ Monitor XA metrics for production systems

**For Support:**
- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- Documentation: https://github.com/Open-J-Proxy/ojp/blob/main/documents/

