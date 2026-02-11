# Read/Write Traffic Splitting in OJP - Documentation Index

This directory contains comprehensive analysis and design documentation for implementing read/write traffic splitting in Open J Proxy (OJP).

## 📋 Overview

Read/write splitting is a database architecture pattern where write operations and transactions are directed to a primary database, while read-only operations are distributed across one or more read replicas. This improves scalability, performance, and resource utilization.

## 📚 Documentation Structure

### 1. [READ_WRITE_SPLITTING_ANALYSIS.md](./READ_WRITE_SPLITTING_ANALYSIS.md)

**Main analysis document** - Comprehensive analysis of implementation approaches and technical design.

**Contents:**
- Current OJP architecture overview
- Requirements and goals
- 4 different implementation approaches with pros/cons
- Recommended approach (SQL Parsing and Automatic Routing)
- Detailed technical design with code examples
- Implementation challenges and solutions
- Migration strategy with timeline
- Future enhancements

**Key Sections:**
- Executive Summary
- Architecture comparison
- Component design (ReadWriteRouter, SqlClassifier, ReplicaSelector)
- Integration points
- 8-10 week implementation timeline

**Start here** if you want to understand the full scope and rationale behind the implementation.

---

### 2. [read-write-splitting-sequence-diagram.md](./read-write-splitting-sequence-diagram.md)

**Visual diagrams** - Sequence diagrams illustrating how read/write splitting works in various scenarios.

**Diagrams included:**
1. Simple read query routing to replica
2. Write query routing to primary with sticky session
3. Transaction pinning to primary
4. Replica failover to primary
5. SELECT FOR UPDATE detection as write
6. Router decision flow diagram

**Use this** to understand the runtime behavior and flow of requests through the system.

---

### 3. [read-write-splitting-configuration-templates.md](./read-write-splitting-configuration-templates.md)

**Configuration examples** - Ready-to-use configuration templates for various database setups.

**Templates included:**
1. Single primary with two read replicas (PostgreSQL)
2. MySQL primary with three replicas
3. Environment-specific configuration (dev/staging/prod)
4. Mixed workload with separate pools
5. Oracle with Active Data Guard
6. SQL Server with Always On Availability Groups

**Additional content:**
- Configuration property reference
- Best practices
- Migration checklist
- Troubleshooting guide

**Use this** when you're ready to configure read/write splitting for your deployment.

---

## 🎯 Quick Start

### For Architects and Decision Makers

1. Read the **Executive Summary** in [READ_WRITE_SPLITTING_ANALYSIS.md](./READ_WRITE_SPLITTING_ANALYSIS.md)
2. Review the **Implementation Approaches** section to understand the options
3. Check the **Timeline** in the Migration Strategy section

### For Developers

1. Review the **Technical Design** section in [READ_WRITE_SPLITTING_ANALYSIS.md](./READ_WRITE_SPLITTING_ANALYSIS.md)
2. Study the **Sequence Diagrams** in [read-write-splitting-sequence-diagram.md](./read-write-splitting-sequence-diagram.md)
3. Look at the **Code Examples** for component interfaces and implementations

### For DevOps/Platform Engineers

1. Start with [read-write-splitting-configuration-templates.md](./read-write-splitting-configuration-templates.md)
2. Choose a template matching your database setup
3. Follow the **Migration Checklist** when deploying

---

## 🏗️ Implementation Status

**Current Status**: ✅ Analysis Complete - Implementation Pending

**Deliverables:**
- ✅ Comprehensive architecture analysis
- ✅ Technical design with component specifications
- ✅ Sequence diagrams for key scenarios
- ✅ Configuration templates for all major databases
- ✅ Migration strategy and timeline
- ⏳ Implementation (not started)

**Estimated Timeline**: 8-10 weeks for full implementation

---

## 🔑 Key Design Decisions

### Recommended Approach: SQL Parsing and Automatic Routing

**Why this approach?**
- ✅ **Transparent**: No application code changes required
- ✅ **Automatic**: Reads go to replicas, writes to primary
- ✅ **Transaction-aware**: All ops in a transaction use primary
- ✅ **Safe**: Unknown queries default to primary
- ✅ **Backward compatible**: Opt-in feature, existing configs work

**Core Components:**
1. **SqlClassifier**: Determines if SQL is read or write
2. **ReadWriteRouter**: Selects datasource (primary or replica)
3. **ReplicaSelector**: Chooses which replica for read operations
4. **SessionContext**: Tracks transaction state and sticky sessions

---

## 📊 Architecture Comparison

| Approach | Transparency | Complexity | Performance | Recommended |
|----------|--------------|------------|-------------|-------------|
| URL-Based Explicit | ❌ Low | ✅ Low | ✅ High | ❌ No |
| SQL Parsing (Auto) | ✅ High | 🔶 Medium | ✅ High | ✅ **Yes** |
| Hint-Based | 🔶 Medium | 🔶 Medium | ✅ High | 🔶 Enhancement |
| Connection Property | 🔶 Medium | ✅ Low | ✅ High | 🔶 Enhancement |

**Winner**: SQL Parsing and Automatic Routing (Approach 2)

---

## 🔧 Configuration Example

**Simple setup with 1 primary + 2 replicas:**

```properties
# Primary
primary.ojp.connection.pool.maximumPoolSize=50
primary.ojp.readwrite.enabled=true
primary.ojp.readwrite.role=primary
primary.ojp.readwrite.replicaSelectionStrategy=ROUND_ROBIN
primary.ojp.readwrite.stickySessionSeconds=5

# Replica 1
replica1.ojp.connection.pool.maximumPoolSize=30
replica1.ojp.readwrite.role=replica
replica1.ojp.readwrite.primary=primary
replica1.ojp.connection.url=jdbc:postgresql://replica1.example.com:5432/mydb

# Replica 2
replica2.ojp.connection.pool.maximumPoolSize=30
replica2.ojp.readwrite.role=replica
replica2.ojp.readwrite.primary=primary
replica2.ojp.connection.url=jdbc:postgresql://replica2.example.com:5432/mydb
```

**Application code (unchanged):**

```java
// No code changes needed!
String url = "jdbc:ojp[localhost:1059(primary)]_postgresql://primary.example.com:5432/mydb";
Connection conn = DriverManager.getConnection(url, "user", "password");

// Reads automatically routed to replicas
ResultSet rs = stmt.executeQuery("SELECT * FROM users");

// Writes automatically routed to primary
stmt.executeUpdate("UPDATE users SET email = 'new@example.com'");
```

---

## 🚀 Implementation Phases

### Phase 1: Foundation (1 week)
- ✅ Design and documentation (complete)
- ✅ Architecture diagrams (complete)
- ✅ Configuration schema (complete)

### Phase 2: Core Implementation (2-3 weeks)
- ⏳ Implement SqlClassifier
- ⏳ Implement ReadWriteRouter
- ⏳ Implement ReplicaSelector
- ⏳ Add ReadWriteDataSourceRegistry
- ⏳ Unit tests

### Phase 3: Integration (2 weeks)
- ⏳ Modify ConnectAction
- ⏳ Modify StatementServiceImpl
- ⏳ Transaction boundary detection
- ⏳ Configuration parsing
- ⏳ Integration tests

### Phase 4: Configuration & Documentation (1 week)
- ⏳ Property definitions
- ⏳ Validation and error handling
- ⏳ User documentation
- ⏳ Migration guide
- ⏳ Performance benchmarks

### Phase 5: Advanced Features (2-3 weeks)
- ⏳ Hint-based routing override
- ⏳ Connection.setReadOnly() support
- ⏳ Replica health monitoring
- ⏳ Metrics and observability

**Total**: 8-10 weeks

---

## 🎓 Key Concepts

### Transaction Handling
- All operations within a transaction use the **primary datasource**
- Ensures consistency and ACID properties
- Transaction detected via `setAutoCommit(false)` or explicit `BEGIN`

### Sticky Session
- After a write, subsequent reads use **primary for N seconds**
- Provides "read-your-writes" consistency
- Configurable duration (default: 5 seconds)

### Replica Selection
- **Round-robin**: Fair distribution (default)
- **Random**: Avoid patterns
- **Least connections**: Balance load (future)

### Failover
- If replica unavailable → automatic fallback to **primary**
- Circuit breaker prevents repeated failures
- Health checks on connection pools

---

## 📖 Related OJP Documentation

- [OJP Components](../OJPComponents.md)
- [Connection Pool Configuration](../configuration/ojp-jdbc-configuration.md)
- [OJP Server Configuration](../configuration/ojp-server-configuration.md)
- [Multinode Configuration](../multinode/README.md)
- [Slow Query Segregation](./SLOW_QUERY_SEGREGATION.md)

---

## 🤝 Contributing

This is an analysis and design document. Implementation contributions should follow the design outlined in these documents. Key considerations:

1. **Follow the recommended approach** (SQL Parsing and Automatic Routing)
2. **Maintain backward compatibility** - feature must be opt-in
3. **Add comprehensive tests** for SQL classification and routing logic
4. **Document configuration** with clear examples
5. **Consider edge cases** mentioned in the Implementation Challenges section

---

## 📝 Questions or Feedback?

For questions about the design or implementation:
1. Review the [READ_WRITE_SPLITTING_ANALYSIS.md](./READ_WRITE_SPLITTING_ANALYSIS.md) document
2. Check the [Troubleshooting section](./read-write-splitting-configuration-templates.md#troubleshooting) in configuration templates
3. Open a GitHub issue with the `enhancement` label

---

## 📅 Document Version

- **Created**: February 2026
- **Status**: Analysis Complete
- **Next Review**: After Phase 2 implementation

---

## 📜 License

This documentation is part of the OJP project and is licensed under the Apache License 2.0.
