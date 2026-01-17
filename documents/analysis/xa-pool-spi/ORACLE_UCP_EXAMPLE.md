# Oracle UCP XA Pool Provider Example

## Overview

This document provides a complete example of implementing an XA pool provider using Oracle Universal Connection Pool (UCP). Oracle UCP offers advanced features specifically optimized for Oracle databases.

## Why Oracle UCP?

Oracle UCP provides several advantages over generic pooling solutions:

- **Connection Affinity**: Sessions stick to specific Oracle RAC nodes
- **Fast Connection Failover (FCF)**: Automatic failover on node failure
- **Statement Caching**: Built-in prepared statement caching
- **Oracle-Specific Optimizations**: Query result caching, connection labeling
- **Native XA Support**: Optimized for Oracle's XA implementation

## Implementation

### 1. Maven Dependencies

```xml
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc11</artifactId>
    <version>23.3.0.23.09</version>
</dependency>
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ucp</artifactId>
    <version>23.3.0.23.09</version>
</dependency>
```

## Unified Implementation: Single Pool for Both XA and Non-XA

Oracle UCP's `PoolDataSource` natively supports both XA and non-XA connections from a single pool. This is the **recommended approach** for Oracle databases as it provides:
- Single pool manages all connections (non-XA + XA)
- Unified monitoring and statistics
- Shared resource limits
- Lower memory footprint than separate pools
- Matches UCP's design philosophy

### Unified Provider Implementation

```java
package org.openjproxy.pool.oracle;

import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import org.openjproxy.pool.ConnectionPoolProvider;
import org.openjproxy.xa.pool.XAConnectionPoolProvider;
import org.openjproxy.xa.pool.BackendSession;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Unified Oracle UCP provider that implements both non-XA and XA SPIs
 * using a single connection pool.
 */
public class OracleUCPUnifiedProvider implements 
    ConnectionPoolProvider,           // Non-XA SPI
    XAConnectionPoolProvider {        // XA SPI
    
    private static final Logger log = Logger.getLogger(OracleUCPUnifiedProvider.class.getName());
    private PoolDataSource poolDataSource;
    
    @Override
    public String getName() {
        return "OracleUCPUnifiedProvider";
    }
    
    @Override
    public int getPriority() {
        // Highest priority for Oracle databases
        return 100;
    }
    
    @Override
    public boolean supports(String databaseUrl) {
        return databaseUrl != null && databaseUrl.contains("oracle:");
    }
    
    /**
     * Initialize unified pool - called by either SPI
     */
    private synchronized void initializePool(Map<String, String> config) throws SQLException {
        if (poolDataSource != null) {
            return; // Already initialized
        }
        
        log.info("Initializing unified Oracle UCP pool");
        
        // Extract configuration with XA precedence (Option 1)
        String url = config.get("xa.url");
        if (url == null) {
            url = config.get("url");
        }
        
        String username = config.get("xa.username");
        if (username == null) {
            username = config.get("username");
        }
        
        String password = config.get("xa.password");
        if (password == null) {
            password = config.get("password");
        }
        
        // Pool sizing: XA properties take precedence
        int maxPoolSize = getConfigValue(config, "maxPoolSize", "maxTotal", 20);
        int minIdle = getConfigValue(config, "minIdle", "minIdle", 5);
        int maxWaitMillis = getConfigValue(config, "maxWaitMillis", "connectionTimeout", 30000);
        
        // Create UCP PoolDataSource (supports both XA and non-XA)
        poolDataSource = PoolDataSourceFactory.getPoolDataSource();
        
        // Set connection factory for XA support
        poolDataSource.setConnectionFactoryClassName("oracle.jdbc.xa.client.OracleXADataSource");
        poolDataSource.setURL(url);
        poolDataSource.setUser(username);
        poolDataSource.setPassword(password);
        
        // Set pool properties
        poolDataSource.setInitialPoolSize(minIdle);
        poolDataSource.setMinPoolSize(minIdle);
        poolDataSource.setMaxPoolSize(maxPoolSize);
        poolDataSource.setConnectionWaitTimeout(maxWaitMillis / 1000); // UCP uses seconds
        
        // Oracle UCP-specific configuration
        boolean enableFCF = Boolean.parseBoolean(config.getOrDefault("xa.oracle.ucp.enableFCF", 
            config.getOrDefault("oracle.ucp.enableFCF", "true")));
        int statementCacheSize = Integer.parseInt(config.getOrDefault("xa.oracle.ucp.statementCacheSize",
            config.getOrDefault("oracle.ucp.statementCacheSize", "50")));
        boolean validateOnBorrow = Boolean.parseBoolean(config.getOrDefault("xa.oracle.ucp.validateOnBorrow",
            config.getOrDefault("oracle.ucp.validateOnBorrow", "true")));
        
        if (validateOnBorrow) {
            poolDataSource.setValidateConnectionOnBorrow(true);
            poolDataSource.setInactiveConnectionTimeout(300);
        }
        
        if (enableFCF) {
            poolDataSource.setFastConnectionFailoverEnabled(true);
        }
        
        poolDataSource.setMaxStatements(statementCacheSize);
        
        log.info(String.format("Oracle UCP unified pool created: maxPoolSize=%d, minIdle=%d, " +
            "FCF=%b, statementCache=%d", maxPoolSize, minIdle, enableFCF, statementCacheSize));
    }
    
    /**
     * Get configuration value with XA precedence (Option 1).
     * 
     * Priority order:
     * 1. ojp.xa.connection.pool.{xaKey}
     * 2. ojp.connection.pool.{nonXaKey}
     * 3. defaultValue
     */
    private int getConfigValue(Map<String, String> config, String xaKey, String nonXaKey, int defaultValue) {
        String xaValue = config.get("xa." + xaKey);
        if (xaValue != null) {
            return Integer.parseInt(xaValue);
        }
        
        String nonXaValue = config.get(nonXaKey);
        if (nonXaValue != null) {
            return Integer.parseInt(nonXaValue);
        }
        
        return defaultValue;
    }
    
    // ====== Non-XA SPI Methods ======
    
    @Override
    public Connection getConnection(String url, Map<String, String> config) throws SQLException {
        initializePool(config);
        
        // Get regular connection from pool
        log.fine("Borrowing non-XA connection from unified UCP pool");
        return poolDataSource.getConnection();
    }
    
    @Override
    public void closePool() throws SQLException {
        if (poolDataSource != null) {
            log.info("Closing unified Oracle UCP pool");
            // Note: UCP doesn't have explicit close, connections released on GC
            poolDataSource = null;
        }
    }
    
    // ====== XA SPI Methods ======
    
    @Override
    public XADataSource createPooledXADataSource(Map<String, String> config) throws Exception {
        initializePool(config);
        
        // Return adapter that provides XAConnections from the same pool
        return new XADataSource() {
            @Override
            public XAConnection getXAConnection() throws SQLException {
                log.fine("Borrowing XA connection from unified UCP pool");
                return poolDataSource.getXAConnection();
            }
            
            @Override
            public XAConnection getXAConnection(String user, String password) throws SQLException {
                return poolDataSource.getXAConnection(user, password);
            }
            
            @Override
            public java.io.PrintWriter getLogWriter() throws SQLException {
                return poolDataSource.getLogWriter();
            }
            
            @Override
            public void setLogWriter(java.io.PrintWriter out) throws SQLException {
                poolDataSource.setLogWriter(out);
            }
            
            @Override
            public void setLoginTimeout(int seconds) throws SQLException {
                poolDataSource.setLoginTimeout(seconds);
            }
            
            @Override
            public int getLoginTimeout() throws SQLException {
                return poolDataSource.getLoginTimeout();
            }
            
            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getLogger("oracle.ucp");
            }
        };
    }
    
    @Override
    public BackendSession borrowSession(XADataSource pooledDataSource) throws Exception {
        // pooledDataSource is the adapter created above
        XAConnection xaConnection = pooledDataSource.getXAConnection();
        return new BackendSessionImpl(xaConnection);
    }
}
```

### Configuration Precedence Strategy (Option 1 - Recommended)

When using a unified Oracle UCP provider, configuration follows this precedence order:

**Priority: XA properties → Non-XA properties → Default values**

```properties
# XA-specific properties (highest priority)
ojp.xa.connection.pool.maxTotal=30
ojp.xa.connection.pool.minIdle=10
ojp.xa.connection.pool.connectionTimeout=30000

# Non-XA properties (fallback)
ojp.connection.pool.maxPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=20000

# With above config, unified UCP pool will use:
# - maxPoolSize=30 (from XA config)
# - minIdle=10 (from XA config)
# - connectionTimeout=30000ms (from XA config)
```

**Rationale:**
- XA connections are typically more resource-intensive and have stricter requirements
- If XA properties are set, they indicate the user wants specific XA pool sizing
- Non-XA connections can share the pool with XA-sized limits
- Single pool must accommodate both workloads, so use the more conservative (typically larger) XA settings

### Configuration Examples

#### Example 1: XA-Heavy Workload
```properties
# Optimize for XA transactions
ojp.xa.connection.pool.maxTotal=50
ojp.xa.connection.pool.minIdle=15
ojp.xa.connection.pool.connectionTimeout=60000

# Non-XA uses XA pool limits
ojp.connection.pool.maxPoolSize=20  # Ignored, XA precedence
ojp.connection.pool.minimumIdle=5   # Ignored, XA precedence

# Result: Single pool with maxPoolSize=50, minIdle=15
```

#### Example 2: Mixed Workload
```properties
# XA and non-XA share pool
ojp.xa.connection.pool.maxTotal=40
ojp.xa.connection.pool.minIdle=10

# Oracle-specific features
ojp.xa.oracle.ucp.enableFCF=true
ojp.xa.oracle.ucp.statementCacheSize=100

# Result: Single pool handles both, sized for peak XA + non-XA load
```

#### Example 3: Non-XA Only (Fallback)
```properties
# No XA properties set, use non-XA config
ojp.connection.pool.maxPoolSize=25
ojp.connection.pool.minimumIdle=8

# Result: Pool uses non-XA values as fallback
```

### ServiceLoader Registration

Create file: `src/main/resources/META-INF/services/org.openjproxy.pool.ConnectionPoolProvider`
```
org.openjproxy.pool.oracle.OracleUCPUnifiedProvider
```

Create file: `src/main/resources/META-INF/services/org.openjproxy.xa.pool.XAConnectionPoolProvider`
```
org.openjproxy.pool.oracle.OracleUCPUnifiedProvider
```

### Benefits of Unified Approach

1. **Resource Efficiency**: Single pool manages all connections
2. **Simplified Configuration**: One set of pool properties
3. **Unified Monitoring**: Single set of metrics and JMX beans
4. **Lower Memory Footprint**: No duplicate pool overhead
5. **Natural for UCP**: Matches UCP's design philosophy

### Trade-offs

- Non-XA and XA connections compete for same pool slots
- Need careful sizing: `maxPoolSize = max(non-XA peak) + max(XA peak)`
- UCP handles this well with connection request queuing

## Next Steps

- Review [Implementation Guide](./IMPLEMENTATION_GUIDE.md) for general provider patterns
- See [Configuration Reference](./CONFIGURATION.md) for all configuration options
- Check [Troubleshooting Guide](./TROUBLESHOOTING.md) for common issues
