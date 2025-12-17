package org.openjproxy.datasource.narayana;

import com.arjuna.ats.jta.recovery.XAResourceRecoveryHelper;
import org.openjproxy.datasource.PoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pooled XA DataSource implementation for Narayana.
 * 
 * <p>This class provides connection pooling for an underlying XADataSource,
 * managing a pool of XAConnection objects that can be reused across transactions.</p>
 * 
 * <p>Thread-safe implementation using concurrent data structures and atomic operations.</p>
 */
public class NarayanaPooledXADataSource implements XADataSource, XAResourceRecoveryHelper {

    private static final Logger log = LoggerFactory.getLogger(NarayanaPooledXADataSource.class);
    
    private final String poolName;
    private final XADataSource underlyingXADataSource;
    private final PoolConfig config;
    
    private volatile int maxPoolSize;
    private volatile int minIdle;
    private final long connectionTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;
    
    private final Queue<PooledXAConnectionWrapper> idleConnections = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    private final ReentrantLock poolLock = new ReentrantLock();
    
    private PrintWriter logWriter;
    private int loginTimeout;

    /**
     * Creates a new pooled XA datasource.
     * 
     * @param config the pool configuration
     * @throws SQLException if the underlying XADataSource cannot be created
     */
    public NarayanaPooledXADataSource(PoolConfig config) throws SQLException {
        this.config = config;
        this.poolName = "NarayanaXAPool-" + System.currentTimeMillis();
        this.maxPoolSize = config.getMaxPoolSize();
        this.minIdle = config.getMinIdle();
        this.connectionTimeoutMs = config.getConnectionTimeoutMs();
        this.idleTimeoutMs = config.getIdleTimeoutMs();
        this.maxLifetimeMs = config.getMaxLifetimeMs();
        
        // Create the underlying XADataSource
        this.underlyingXADataSource = createUnderlyingXADataSource(config);
        
        // Pre-populate with minimum idle connections
        for (int i = 0; i < minIdle; i++) {
            try {
                XAConnection xaConn = underlyingXADataSource.getXAConnection();
                PooledXAConnectionWrapper wrapper = new PooledXAConnectionWrapper(xaConn, this);
                idleConnections.offer(wrapper);
                totalCount.incrementAndGet();
            } catch (SQLException e) {
                log.warn("Failed to create initial XA connection {}/{}: {}", i + 1, minIdle, e.getMessage());
            }
        }
        
        log.info("Initialized Narayana XA pool '{}': minIdle={}, maxPoolSize={}, totalCount={}", 
                poolName, minIdle, maxPoolSize, totalCount.get());
    }

    /**
     * Creates the underlying database XADataSource based on the JDBC URL.
     * This uses the existing XADataSourceFactory pattern from OJP.
     */
    private XADataSource createUnderlyingXADataSource(PoolConfig config) throws SQLException {
        String url = config.getUrl();
        String lowerUrl = url != null ? url.toLowerCase() : "";
        
        try {
            if (lowerUrl.contains("postgresql")) {
                return createPostgreSQLXADataSource(config);
            } else if (lowerUrl.contains("mysql")) {
                return createMySQLXADataSource(config);
            } else if (lowerUrl.contains("h2")) {
                return createH2XADataSource(config);
            } else if (lowerUrl.contains("oracle")) {
                return createOracleXADataSource(config);
            } else if (lowerUrl.contains("sqlserver")) {
                return createSQLServerXADataSource(config);
            } else {
                throw new SQLException("Unsupported database for XA transactions: " + url);
            }
        } catch (ClassNotFoundException e) {
            throw new SQLException("Database driver not found for URL: " + url, e);
        }
    }

    private XADataSource createPostgreSQLXADataSource(PoolConfig config) throws SQLException, ClassNotFoundException {
        try {
            Class<?> xaDSClass = Class.forName("org.postgresql.xa.PGXADataSource");
            XADataSource xaDS = (XADataSource) xaDSClass.getDeclaredConstructor().newInstance();
            xaDSClass.getMethod("setUrl", String.class).invoke(xaDS, config.getUrl());
            xaDSClass.getMethod("setUser", String.class).invoke(xaDS, config.getUsername());
            xaDSClass.getMethod("setPassword", String.class).invoke(xaDS, config.getPasswordAsString());
            return xaDS;
        } catch (ClassNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Failed to create PostgreSQL XADataSource", e);
        }
    }

    private XADataSource createMySQLXADataSource(PoolConfig config) throws SQLException, ClassNotFoundException {
        try {
            Class<?> xaDSClass = Class.forName("com.mysql.cj.jdbc.MysqlXADataSource");
            XADataSource xaDS = (XADataSource) xaDSClass.getDeclaredConstructor().newInstance();
            xaDSClass.getMethod("setUrl", String.class).invoke(xaDS, config.getUrl());
            xaDSClass.getMethod("setUser", String.class).invoke(xaDS, config.getUsername());
            xaDSClass.getMethod("setPassword", String.class).invoke(xaDS, config.getPasswordAsString());
            return xaDS;
        } catch (ClassNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Failed to create MySQL XADataSource", e);
        }
    }

    private XADataSource createH2XADataSource(PoolConfig config) throws SQLException, ClassNotFoundException {
        try {
            Class<?> xaDSClass = Class.forName("org.h2.jdbcx.JdbcDataSource");
            XADataSource xaDS = (XADataSource) xaDSClass.getDeclaredConstructor().newInstance();
            xaDSClass.getMethod("setURL", String.class).invoke(xaDS, config.getUrl());
            xaDSClass.getMethod("setUser", String.class).invoke(xaDS, config.getUsername());
            xaDSClass.getMethod("setPassword", String.class).invoke(xaDS, config.getPasswordAsString());
            return xaDS;
        } catch (ClassNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Failed to create H2 XADataSource", e);
        }
    }

    private XADataSource createOracleXADataSource(PoolConfig config) throws SQLException, ClassNotFoundException {
        throw new SQLException("Oracle XA support not yet implemented in pool");
    }

    private XADataSource createSQLServerXADataSource(PoolConfig config) throws SQLException, ClassNotFoundException {
        throw new SQLException("SQL Server XA support not yet implemented in pool");
    }

    @Override
    public XAConnection getXAConnection() throws SQLException {
        return getXAConnection(null, null);
    }

    @Override
    public XAConnection getXAConnection(String user, String password) throws SQLException {
        if (closed.get()) {
            throw new SQLException("XA DataSource is closed");
        }
        
        long startTime = System.currentTimeMillis();
        long deadline = startTime + connectionTimeoutMs;
        
        while (System.currentTimeMillis() < deadline) {
            // Try to get an idle connection
            PooledXAConnectionWrapper wrapper = idleConnections.poll();
            
            if (wrapper != null) {
                if (!wrapper.isValid()) {
                    // Connection is no longer valid, discard it
                    wrapper.physicalClose();
                    totalCount.decrementAndGet();
                    continue; // Try again
                }
                activeCount.incrementAndGet();
                wrapper.markActive();
                return wrapper;
            }
            
            // No idle connection available, try to create a new one
            if (totalCount.get() < maxPoolSize) {
                poolLock.lock();
                try {
                    if (totalCount.get() < maxPoolSize) {
                        XAConnection physicalConn = underlyingXADataSource.getXAConnection(user, password);
                        wrapper = new PooledXAConnectionWrapper(physicalConn, this);
                        totalCount.incrementAndGet();
                        activeCount.incrementAndGet();
                        wrapper.markActive();
                        log.debug("Created new XA connection, total={}, active={}", totalCount.get(), activeCount.get());
                        return wrapper;
                    }
                } finally {
                    poolLock.unlock();
                }
            }
            
            // Pool is full and no idle connections, wait a bit and retry
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for XA connection");
            }
        }
        
        throw new SQLException("Timeout waiting for XA connection after " + connectionTimeoutMs + "ms");
    }

    /**
     * Returns a connection to the pool.
     */
    void returnConnection(PooledXAConnectionWrapper wrapper) {
        if (closed.get()) {
            wrapper.physicalClose();
            totalCount.decrementAndGet();
            return;
        }
        
        activeCount.decrementAndGet();
        wrapper.markIdle();
        idleConnections.offer(wrapper);
        
        log.debug("Returned XA connection to pool, total={}, active={}, idle={}", 
                totalCount.get(), activeCount.get(), idleConnections.size());
    }

    /**
     * Resizes the pool.
     */
    public void resize(int newMaxPoolSize, int newMinIdle) {
        log.info("Resizing XA pool: maxPoolSize {} -> {}, minIdle {} -> {}", 
                this.maxPoolSize, newMaxPoolSize, this.minIdle, newMinIdle);
        
        this.maxPoolSize = newMaxPoolSize;
        this.minIdle = newMinIdle;
        
        // If reducing pool size, close excess idle connections
        if (totalCount.get() > newMaxPoolSize) {
            poolLock.lock();
            try {
                while (totalCount.get() > newMaxPoolSize && !idleConnections.isEmpty()) {
                    PooledXAConnectionWrapper wrapper = idleConnections.poll();
                    if (wrapper != null) {
                        wrapper.physicalClose();
                        totalCount.decrementAndGet();
                    }
                }
            } finally {
                poolLock.unlock();
            }
        }
        
        log.info("XA pool resized: total={}, active={}, idle={}", 
                totalCount.get(), activeCount.get(), idleConnections.size());
    }

    /**
     * Closes the pool and all connections.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("Closing XA pool '{}'", poolName);
            
            poolLock.lock();
            try {
                // Close all idle connections
                PooledXAConnectionWrapper wrapper;
                while ((wrapper = idleConnections.poll()) != null) {
                    wrapper.physicalClose();
                }
                
                log.info("XA pool '{}' closed. Active connections at close: {}", poolName, activeCount.get());
            } finally {
                poolLock.unlock();
            }
        }
    }

    // XAResourceRecoveryHelper implementation for Narayana recovery
    
    @Override
    public boolean initialise(String parameter) throws RuntimeException {
        // Initialize recovery helper with parameter (if needed)
        log.debug("Initializing XA recovery helper with parameter: {}", parameter);
        return true;
    }
    
    @Override
    public XAResource[] getXAResources() throws RuntimeException {
        // Return XA resources for recovery
        // This is called by Narayana recovery manager
        return new XAResource[0]; // TODO: Implement recovery resource enumeration
    }

    // Getters for statistics
    
    public String getPoolName() {
        return poolName;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public int getMinIdle() {
        return minIdle;
    }

    public int getActiveCount() {
        return activeCount.get();
    }

    public int getIdleCount() {
        return idleConnections.size();
    }

    public int getTotalCount() {
        return totalCount.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    // XADataSource methods
    
    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return loginTimeout;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("getParentLogger not supported");
    }
}
