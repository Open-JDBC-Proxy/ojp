package org.openjproxy.datasource;

import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Map;

/**
 * Service Provider Interface (SPI) for XA connection pool implementations.
 * 
 * <p>This interface extends the standard {@link ConnectionPoolProvider} to add
 * XA-specific functionality for managing pooled XA datasources. Implementations
 * should be registered via the standard Java {@link java.util.ServiceLoader}
 * mechanism.</p>
 * 
 * <p>XA connection pools manage {@link javax.sql.XADataSource} instances which
 * provide {@link javax.sql.XAConnection} objects for distributed transactions
 * coordinated by a JTA transaction manager (e.g., Narayana, Atomikos).</p>
 * 
 * <p>Example implementation:</p>
 * <pre>{@code
 * public class NarayanaXAProvider implements XAConnectionPoolProvider {
 *     @Override
 *     public String id() {
 *         return "narayana";
 *     }
 *     
 *     @Override
 *     public XADataSource createXADataSource(PoolConfig config) throws SQLException {
 *         // Create Narayana-managed XA pool
 *         return new NarayanaXADataSource(config);
 *     }
 * }
 * }</pre>
 */
public interface XAConnectionPoolProvider extends ConnectionPoolProvider {

    /**
     * Creates a new XA DataSource configured according to the provided settings.
     * 
     * <p>The returned XADataSource should be pooled by the implementation and
     * provide XA-capable connections for distributed transactions.</p>
     * 
     * <p>Implementations should:</p>
     * <ul>
     *   <li>Create and configure an XA connection pool based on the PoolConfig</li>
     *   <li>Wrap the underlying database XADataSource with pooling logic</li>
     *   <li>Configure connection validation for XA connections</li>
     *   <li>Set up transaction manager integration</li>
     *   <li>Apply pool sizing and timeout parameters</li>
     * </ul>
     * 
     * @param config the pool configuration settings
     * @return a configured and pooled XADataSource, never null
     * @throws SQLException if the XADataSource cannot be created
     * @throws IllegalArgumentException if config is null or invalid
     */
    XADataSource createXADataSource(PoolConfig config) throws SQLException;

    /**
     * Closes and releases all resources associated with the XA DataSource.
     * 
     * <p>This method should:</p>
     * <ul>
     *   <li>Close all XA connections in the pool</li>
     *   <li>Release any associated resources (transaction logs, threads, etc.)</li>
     *   <li>Unregister from the transaction manager</li>
     *   <li>Be idempotent (safe to call multiple times)</li>
     * </ul>
     * 
     * @param xaDataSource the XADataSource to close
     * @throws Exception if an error occurs during shutdown
     */
    void closeXADataSource(XADataSource xaDataSource) throws Exception;

    /**
     * Returns current statistics about the XA connection pool.
     * 
     * <p>The returned map may include XA-specific statistics such as:</p>
     * <ul>
     *   <li>{@code activeXAConnections} - number of currently active XA connections</li>
     *   <li>{@code idleXAConnections} - number of idle XA connections in the pool</li>
     *   <li>{@code totalXAConnections} - total XA connections (active + idle)</li>
     *   <li>{@code pendingTransactions} - prepared transactions awaiting commit/rollback</li>
     *   <li>{@code completedTransactions} - total completed transactions</li>
     *   <li>{@code rolledBackTransactions} - total rolled back transactions</li>
     * </ul>
     * 
     * <p>Implementations should return an empty map if statistics are not
     * available or the XADataSource is not recognized.</p>
     * 
     * @param xaDataSource the XADataSource to get statistics for
     * @return a map of statistic names to values, never null
     */
    Map<String, Object> getXAStatistics(XADataSource xaDataSource);

    /**
     * Checks if this provider supports dynamic pool resizing.
     * 
     * <p>Some transaction managers (e.g., Narayana) support changing pool
     * size at runtime, while others (e.g., Atomikos) require pool recreation.</p>
     * 
     * <p>Default implementation returns true. Providers that don't support
     * dynamic resizing should override to return false.</p>
     * 
     * @return true if the provider supports dynamic resizing, false otherwise
     */
    default boolean supportsDynamicResizing() {
        return true;
    }

    /**
     * Attempts to resize an existing XA DataSource pool.
     * 
     * <p>For providers that support dynamic resizing, this updates the pool
     * parameters immediately. For providers that don't support it, this
     * method should throw {@link UnsupportedOperationException}.</p>
     * 
     * <p>Implementations should:</p>
     * <ul>
     *   <li>Update maxPoolSize and minIdle if possible</li>
     *   <li>Trigger connection eviction if reducing pool size</li>
     *   <li>Throw UnsupportedOperationException if resizing not supported</li>
     * </ul>
     * 
     * @param xaDataSource the XADataSource to resize
     * @param newMaxPoolSize the new maximum pool size
     * @param newMinIdle the new minimum idle connections
     * @throws UnsupportedOperationException if dynamic resizing is not supported
     * @throws SQLException if resizing fails
     */
    default void resizeXADataSource(XADataSource xaDataSource, int newMaxPoolSize, int newMinIdle) 
            throws SQLException {
        if (!supportsDynamicResizing()) {
            throw new UnsupportedOperationException(
                    "Provider '" + id() + "' does not support dynamic XA pool resizing");
        }
        throw new UnsupportedOperationException(
                "resizeXADataSource not implemented by provider: " + id());
    }
}
