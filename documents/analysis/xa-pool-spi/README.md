# XA Connection Pool SPI Documentation

## Overview

The XA Connection Pool SPI (Service Provider Interface) provides a pluggable architecture for integrating XA-aware connection pooling implementations into Open-J-Proxy. This enables efficient management of distributed transactions across multiple databases while maintaining zero vendor dependencies at the core level.

## Key Features

- **Zero Vendor Dependencies**: Core SPI has no compile-time dependencies on specific database vendors
- **Pluggable Architecture**: Easy to add new pool providers via Java ServiceLoader
- **Universal Support**: Works with PostgreSQL, SQL Server, DB2, MySQL, MariaDB, Oracle
- **XA Specification Compliant**: Full 2PC (Two-Phase Commit) protocol support
- **Production Ready**: Complete state machine with proper error handling and resource management
- **Housekeeping Features**: Built-in leak detection, max lifetime enforcement, and diagnostics (see [Housekeeping Guide](./XA_POOL_HOUSEKEEPING_README.md))

## Architecture

### Components

1. **XAConnectionPoolProvider SPI** - Interface for pool provider implementations
2. **CommonsPool2XAProvider** - Default universal implementation using Apache Commons Pool 2
3. **XATransactionRegistry** - Manages XA transaction lifecycle and session associations
4. **BackendSession** - Abstraction for poolable XA connections
5. **TxContext** - State machine for individual XA transactions

### Session Lifecycle

```
Client Request
     ↓
connect(isXA=true)
     ↓
Borrow BackendSession from pool ← Pool Provider
     ↓
Bind to OJP Session (long-lived)
     ↓
xaStart(tx1) → Register with XATransactionRegistry
     ↓
Execute SQL
     ↓
xaEnd(tx1) → xaPrepare(tx1) → xaCommit(tx1)
     ↓
Transaction Complete (BackendSession stays bound to session)
     ↓
xaStart(tx2) → Reuse same BackendSession
     ↓
... (multiple transactions) ...
     ↓
Session.terminate()
     ↓
Return BackendSession to pool ← Pool Provider
```

## Documentation Structure

### Core Documentation
- **[Implementation Guide](./IMPLEMENTATION_GUIDE.md)** - How to implement a new XA pool provider
- **[Oracle UCP Example](./ORACLE_UCP_EXAMPLE.md)** - Complete Oracle UCP provider implementation
- **[Configuration Reference](./CONFIGURATION.md)** - Configuration options and defaults
- **[API Reference](./API_REFERENCE.md)** - Complete API documentation
- **[Migration Guide](./MIGRATION_GUIDE.md)** - Migrating from pass-through to pooled XA
- **[Troubleshooting](./TROUBLESHOOTING.md)** - Common issues and solutions

### Transaction Manager Research
- **[Atomikos Transaction Logging Research](./ATOMIKOS_TRANSACTION_LOGGING_RESEARCH.md)** - In-depth analysis of Atomikos tmlog file, transaction states, and recovery mechanisms including failure scenarios

### Housekeeping Features
- **[Housekeeping User Guide](./XA_POOL_HOUSEKEEPING_README.md)** - User-facing guide for leak detection, max lifetime, and diagnostics
- **[Housekeeping Technical Guide](./XA_POOL_HOUSEKEEPING_GUIDE.md)** - Complete technical documentation with architecture diagrams

## Quick Start

### Using the Default Provider (Commons Pool 2)

The default provider works out-of-the-box with all supported databases:

```properties
# Enable XA pooling (default: true)
ojp.xa.connection.pool.enabled=true

# Pool configuration
ojp.xa.connection.pool.maximumPoolSize=10
ojp.xa.minIdle=2
ojp.xa.maxWaitMillis=30000
ojp.xa.idleTimeoutMinutes=10
ojp.xa.maxLifetimeMinutes=30
```

### Database-Specific XADataSource Classes

The provider automatically detects and configures the correct XADataSource:

- **PostgreSQL**: `org.postgresql.xa.PGXADataSource`
- **SQL Server**: `com.microsoft.sqlserver.jdbc.SQLServerXADataSource`
- **DB2**: `com.ibm.db2.jcc.DB2XADataSource`
- **MySQL**: `com.mysql.cj.jdbc.MysqlXADataSource`
- **MariaDB**: `org.mariadb.jdbc.MariaDbDataSource`
- **Oracle**: `oracle.jdbc.xa.client.OracleXADataSource`

### Implementing a Custom Provider

See [Implementation Guide](./IMPLEMENTATION_GUIDE.md) for detailed instructions.

## Performance

XA pooling provides significant performance improvements:

- **50-200ms faster** per transaction (eliminates connection establishment overhead)
- **Connection reuse** across transactions within same session
- **Lazy resource allocation** - connections borrowed only when needed
- **Configurable pool sizing** for optimal resource utilization

## XA Specification Compliance

- ✅ Complete 2PC (Two-Phase Commit) protocol
- ✅ Proper state machine: NONEXISTENT → ACTIVE → ENDED → PREPARED → COMMITTED/ROLLEDBACK
- ✅ Session pinning during PREPARED state
- ✅ Idempotent commit/rollback operations
- ✅ Proper Xid object identity management
- ✅ Full error code propagation

## Supported Databases

| Database | XA Support | Pool Provider | Notes |
|----------|------------|---------------|-------|
| PostgreSQL | ✅ Yes | CommonsPool2XAProvider | Full support |
| SQL Server | ✅ Yes | CommonsPool2XAProvider | Full support |
| DB2 | ✅ Yes | CommonsPool2XAProvider | Full support |
| MySQL | ✅ Yes | CommonsPool2XAProvider | Full support |
| MariaDB | ✅ Yes | CommonsPool2XAProvider | Full support |
| Oracle | ✅ Yes | CommonsPool2XAProvider / OracleUCPXAProvider | Can use Oracle UCP for enhanced features |
| CockroachDB | ❌ No | N/A | Uses native distributed transactions |
| H2 | ❌ No | N/A | Experimental XA support only |

## Rollback Safety

The pass-through implementation is preserved but disabled by default:

```properties
# Disable XA pooling (falls back to pass-through)
ojp.xa.connection.pool.enabled=false
```

This allows easy rollback if issues are encountered.

## Module Structure

```
ojp-xa-pool-commons/
├── src/main/java/org/openjproxy/xa/pool/
│   ├── XAConnectionPoolProvider.java      # SPI interface
│   ├── CommonsPool2XAProvider.java        # Default implementation
│   ├── BackendSession.java                # Poolable session interface
│   ├── BackendSessionImpl.java            # Session implementation
│   ├── BackendSessionFactory.java         # Commons Pool 2 factory
│   ├── CommonsPool2XADataSource.java      # Pool wrapper
│   ├── XATransactionRegistry.java         # Transaction lifecycle manager
│   ├── TxContext.java                     # Transaction state machine
│   ├── TxState.java                       # State enum
│   ├── XidKey.java                        # Xid identifier
│   └── XAPoolConfig.java                  # Configuration
├── src/main/resources/
│   └── META-INF/services/
│       └── org.openjproxy.xa.pool.XAConnectionPoolProvider
└── pom.xml
```

## Contributing

When implementing a new XA pool provider:

1. Implement `XAConnectionPoolProvider` interface
2. Register via ServiceLoader mechanism
3. Set appropriate priority (higher = preferred)
4. Provide comprehensive tests
5. Document configuration options

## License

Apache License 2.0 - See LICENSE file for details

## Support

For issues, questions, or contributions:
- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- Documentation: https://github.com/Open-J-Proxy/ojp/tree/main/documents/xa-pool-spi

## Related Documentation

- [XA Transaction Flow Diagrams](./XA_TRANSACTION_FLOW_DIAGRAMS.md) - Visual diagrams of XA transaction flows
- [Database XA Pool Libraries Comparison](./DATABASE_XA_POOL_LIBRARIES_COMPARISON.md) - Analysis of XA pooling options
- [Atomikos Transaction Logging Research](./ATOMIKOS_TRANSACTION_LOGGING_RESEARCH.md) - Deep dive into transaction manager logging
- [XA Transaction Flow](../xa-deprecated/XA_TRANSACTION_FLOW.md)
- [Atomikos Integration](../xa-deprecated/ATOMIKOS_XA_INTEGRATION.md)
- [Multinode Failover](../xa-deprecated/XA_MULTINODE_FAILOVER.md)
