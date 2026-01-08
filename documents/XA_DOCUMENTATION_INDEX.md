# OJP XA (Distributed Transactions) Documentation

This directory contains comprehensive documentation for OJP's XA distributed transaction implementation.

---

## Quick Start

**New to OJP XA?** Start here:

1. Read the **[Executive Summary](XA_REVIEW_EXECUTIVE_SUMMARY.md)** for an overview
2. Follow the **[JTA Integration Guide](XA_JTA_INTEGRATION.md)** to integrate with Spring/Atomikos/Narayana
3. Configure your database using the **[Vendor Quirks Guide](XA_VENDOR_QUIRKS.md)**

---

## Documentation Files

### 1. [XA Review Executive Summary](XA_REVIEW_EXECUTIVE_SUMMARY.md)
**Audience:** Technical Leaders, Architects, Decision Makers  
**Purpose:** High-level assessment of XA implementation  
**Status:** ✅ Production Ready

**Contents:**
- Overall assessment and key strengths
- Review findings (excellent areas, minor improvements)
- Production readiness checklist
- Compliance with XA specification
- Next steps and recommendations

**Read this first if you need to:**
- Understand if OJP XA is production-ready
- Get executive-level summary of architecture
- Understand risks and confidence level

---

### 2. [XA/JTA Integration Guide](XA_JTA_INTEGRATION.md)
**Audience:** Developers, DevOps Engineers  
**Purpose:** Step-by-step integration with JTA transaction managers  
**Length:** ~650 lines

**Contents:**
- **Spring Framework Integration:**
  - Spring Boot + Atomikos
  - Spring Framework + Narayana
  - Configuration examples
- **Standalone Integration:**
  - Atomikos without Spring
  - Narayana without Spring
- **Important Behaviors:**
  - Multiple getConnection() calls
  - Connection.close() in active transactions
  - JDBC method restrictions
- **Crash Recovery:**
  - How recovery works
  - Atomikos recovery example
- **Best Practices:**
  - Connection pooling
  - Transaction timeouts
  - Error handling
  - Monitoring
- **Troubleshooting:**
  - XAResource not enlisted
  - Cannot commit/rollback in JTA transaction
  - Prepared transactions not recovered
  - Pool exhausted

**Read this if you need to:**
- Integrate OJP with Spring/Atomikos/Narayana
- Understand XA connection lifecycle
- Configure distributed transactions
- Troubleshoot XA integration issues

---

### 3. [XA Vendor Quirks and Configuration Guide](XA_VENDOR_QUIRKS.md)
**Audience:** Database Administrators, DevOps Engineers, Developers  
**Purpose:** Database-specific XA configuration and gotchas  
**Length:** ~650 lines

**Vendor Coverage:**
- **PostgreSQL** - max_prepared_transactions, PREPARE TRANSACTION
- **Oracle** - XA privileges, shared servers, RAC
- **MySQL / MariaDB** - InnoDB requirement, XA RECOVER
- **Microsoft SQL Server** - MS DTC configuration (Windows only)
- **IBM DB2** - Transaction log management
- **H2 Database** - Testing only (no durability)

**Each vendor section includes:**
- XA implementation details
- Configuration requirements
- XA driver class
- Recovery behavior
- Known limitations
- Testing checklist
- Recommended configuration
- Example configurations

**Read this if you need to:**
- Configure XA for a specific database
- Understand vendor-specific limitations
- Troubleshoot vendor-specific XA issues
- Set up crash recovery
- Prepare for production deployment

---

### 4. [XA Implementation Review](XA_IMPLEMENTATION_REVIEW.md)
**Audience:** Senior Engineers, Architects, Security Reviewers  
**Purpose:** Comprehensive technical review of XA implementation  
**Length:** ~900 lines

**Contents:**
- **Section 1:** Contract and Boundaries
  - Ownership validation (driver vs server)
  - Dual-channel architecture
  - RM identity (rmId)
- **Section 2:** JDBC Driver Stubs
  - XADataSource/XAConnection/XAResource
  - isSameRM() implementation
  - Virtual JDBC semantics
- **Section 3:** Server RM Identity and Routing
  - SQL routing rules
  - XA association enforcement
- **Section 4:** Server-Side XA State Machine
  - Lifecycle modeling
  - XA flags handling
  - Association constraints
  - Prepare boundary behavior
  - Completion idempotency
- **Section 5:** Recovery Requirements
  - Durable store (backend-delegated)
  - recover() behavior
  - Crash scenarios
- **Section 6:** Commons Pool 2 Integration
  - Pool lifecycle (makeObject, activateObject, passivateObject, etc.)
  - Reset logic
  - Pool configuration
- **Section 7:** JDBC State & Transaction Isolation
  - Isolation reset logic
  - Test coverage
- **Section 8:** Vendor XA Quirks (Summary)
- **Section 9:** Concurrency, Threading, Ordering
- **Section 10:** Observability & Diagnostics
  - Correlation IDs
  - Metrics
  - Test suite requirements

**Read this if you need to:**
- Understand XA architecture in depth
- Review implementation for correctness
- Validate XA specification compliance
- Assess security of XA implementation
- Prepare for code review
- Contribute to XA implementation

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    OJP JDBC Driver                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │OjpXADataSource│→│OjpXAConnection│→│OjpXAResource │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
│         ↓                  ↓                  ↓             │
│    Stubs forward all operations to OJP Server via gRPC    │
└─────────────────────────────────────────────────────────────┘
                            ↓
                      gRPC Channel
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                       OJP Server                            │
│  ┌────────────────────────────────────────────────────┐    │
│  │         XATransactionRegistry                      │    │
│  │  ┌───────────────────────────────────────┐        │    │
│  │  │  TxContext (Xid → State + Session)    │        │    │
│  │  │  - ACTIVE → ENDED → PREPARED →        │        │    │
│  │  │    COMMITTED / ROLLEDBACK              │        │    │
│  │  └───────────────────────────────────────┘        │    │
│  └────────────────────────────────────────────────────┘    │
│                        ↓                                    │
│  ┌────────────────────────────────────────────────────┐    │
│  │    Commons Pool 2 (XABackendSession Pool)         │    │
│  │  - makeObject: Create vendor XAConnection         │    │
│  │  - passivateObject: Reset state (isolation, etc.) │    │
│  │  - validateObject: Health check                   │    │
│  │  - destroyObject: Close resources                 │    │
│  └────────────────────────────────────────────────────┘    │
│                        ↓                                    │
│  ┌────────────────────────────────────────────────────┐    │
│  │        Vendor XADataSource                         │    │
│  │  (PostgreSQL / Oracle / MySQL / SQL Server / DB2) │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                            ↓
                   Backend Database
                   (Transaction Log)
```

---

## Common Use Cases

### Use Case 1: Distributed Transaction Across Two Databases

```java
@Transactional
public void transferMoney(long fromAccount, long toAccount, BigDecimal amount) {
    // Deduct from source database
    try (Connection conn1 = dataSource1.getConnection()) {
        conn1.createStatement().executeUpdate(
            "UPDATE accounts SET balance = balance - " + amount + " WHERE id = " + fromAccount);
    }
    
    // Credit to target database
    try (Connection conn2 = dataSource2.getConnection()) {
        conn2.createStatement().executeUpdate(
            "UPDATE accounts SET balance = balance + " + amount + " WHERE id = " + toAccount);
    }
    
    // Both commit together via 2PC, or both rollback on exception
}
```

### Use Case 2: Crash Recovery

1. Application starts XA transaction
2. Application calls prepare() - returns XA_OK
3. **Application crashes before commit()**
4. Transaction manager restarts and calls recover()
5. OJP queries backend databases for prepared Xids
6. Transaction manager completes the transaction (commit or rollback)

### Use Case 3: Multiple Connections in Same Transaction

```java
@Transactional
public void complexOperation() {
    Connection conn1 = dataSource.getConnection();
    // Do work...
    conn1.close();  // Logical close
    
    Connection conn2 = dataSource.getConnection();
    // More work...
    conn2.close();  // Logical close
    
    // Physical resources released at transaction completion
}
```

---

## FAQ

### Q: Do I need XA for single-database transactions?
**A:** No. Use regular JDBC connections for single-database transactions. XA is only needed for distributed transactions across multiple databases or other transactional resources.

### Q: Which transaction manager should I use?
**A:** 
- **Spring Boot:** Use built-in Atomikos support
- **Standalone Java:** Atomikos or Narayana
- **JBoss/WildFly:** Narayana (built-in)
- **Production:** Atomikos (most mature) or Narayana (Red Hat supported)

### Q: Does OJP pool XA connections?
**A:** 
- **Client side:** No (correct - let transaction manager handle pooling)
- **OJP server side:** Yes (backend sessions are pooled automatically)

### Q: How do I monitor XA transactions?
**A:**
- Use transaction manager metrics (Atomikos statistics)
- Monitor backend database prepared transactions
- Future: OJP will expose XA metrics via JMX/Prometheus

### Q: What happens if OJP server crashes during prepare?
**A:** 
- Prepared transactions are durably stored in backend database
- After restart, transaction manager calls recover()
- OJP queries backend databases and returns prepared Xids
- Transaction manager completes the transactions
- **No data loss**

### Q: Can I use H2 database for XA in production?
**A:** **NO**. H2 does not persist prepared transactions. Use PostgreSQL, Oracle, MySQL, SQL Server, or DB2 for production.

### Q: Does SQL Server on Linux support XA?
**A:** **NO**. SQL Server XA requires MS DTC, which is Windows-only. Use PostgreSQL or another database for Linux.

---

## Getting Help

**Issues:** https://github.com/Open-J-Proxy/ojp/issues  
**Discussions:** https://github.com/Open-J-Proxy/ojp/discussions  
**Main Docs:** https://github.com/Open-J-Proxy/ojp/blob/main/README.md

---

## Contributing

Found an issue or have a suggestion? Please:
1. Check existing documentation
2. Search GitHub issues
3. Open a new issue with details
4. Or submit a pull request

---

## License

Apache License 2.0 - See LICENSE file

---

**Last Updated:** 2026-01-08  
**OJP Version:** 0.3.2-snapshot  
**Status:** ✅ Production Ready

