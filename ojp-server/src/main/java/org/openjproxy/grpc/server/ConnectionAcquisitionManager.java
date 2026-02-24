package org.openjproxy.grpc.server;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Manages connection acquisition with enhanced monitoring capabilities.
 * This class wraps connection acquisition to provide better error messages
 * and pool state information when connection acquisition fails.
 * 
 * ISSUE #29 FIX: This class was created to resolve the problem where OJP would
 * block indefinitely under high concurrent load (200+ threads) when the connection
 * pool was exhausted. The solution relies on pool implementation's built-in timeout
 * mechanisms while providing enhanced error reporting with pool statistics.
 * 
 * Note: Enhanced statistics are available when using HikariCP. Other pool
 * implementations will have basic error reporting.
 * 
 * @see <a href="https://github.com/Open-J-Proxy/ojp/issues/29">Issue #29</a>
 */
@Slf4j
public class ConnectionAcquisitionManager {
    
    /**
     * Acquires a connection from the given datasource with enhanced error reporting.
     * This method relies on the pool implementation's built-in connection timeout mechanism
     * to prevent indefinite blocking, while providing detailed error messages with pool statistics.
     * 
     * @param dataSource the datasource (supports HikariCP for enhanced statistics)
     * @param connectionHash the connection hash for logging purposes
     * @return a database connection
     * @throws SQLException if connection acquisition fails or times out
     */
    public static Connection acquireConnection(DataSource dataSource, String connectionHash) throws SQLException {
        return acquireConnection(dataSource, connectionHash, null);
    }

    /**
     * Acquires a connection from the given datasource with enhanced error reporting and metrics recording.
     * This method relies on the pool implementation's built-in connection timeout mechanism
     * to prevent indefinite blocking, while providing detailed error messages with pool statistics.
     * 
     * @param dataSource the datasource (supports HikariCP for enhanced statistics)
     * @param connectionHash the connection hash for logging purposes
     * @param metrics optional {@link OjpMetrics} instance for recording queue depth and wait time;
     *                pass {@code null} to skip metric recording
     * @return a database connection
     * @throws SQLException if connection acquisition fails or times out
     */
    public static Connection acquireConnection(DataSource dataSource, String connectionHash, OjpMetrics metrics)
            throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource is null for connection hash: " + connectionHash);
        }
        
        // Log current pool state before attempting acquisition (HikariCP-specific)
        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
            try {
                log.debug("Connection acquisition attempt for hash: {} - Active: {}, Idle: {}, Total: {}, Waiting: {}", 
                    connectionHash,
                    hikariDataSource.getHikariPoolMXBean().getActiveConnections(),
                    hikariDataSource.getHikariPoolMXBean().getIdleConnections(), 
                    hikariDataSource.getHikariPoolMXBean().getTotalConnections(),
                    hikariDataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
            } catch (Exception e) {
                log.debug("Could not retrieve pool statistics for hash: {}", connectionHash);
            }
        } else {
            log.debug("Connection acquisition attempt for hash: {} using {}", 
                connectionHash, dataSource.getClass().getSimpleName());
        }

        // Record that this thread is now waiting for a connection
        if (metrics != null) {
            metrics.connectionWaitStarted(connectionHash);
        }
        long waitStart = System.currentTimeMillis();

        try {
            // Use pool's built-in connection timeout - this prevents indefinite blocking
            Connection connection = dataSource.getConnection();
            long waitTimeMs = System.currentTimeMillis() - waitStart;
            log.debug("Successfully acquired connection for hash: {} in thread: {} (waited {}ms)",
                connectionHash, Thread.currentThread().getName(), waitTimeMs);
            if (metrics != null) {
                metrics.connectionAcquired(connectionHash, waitTimeMs, true);
            }
            return connection;
            
        } catch (SQLException e) {
            long waitTimeMs = System.currentTimeMillis() - waitStart;
            if (metrics != null) {
                metrics.connectionAcquired(connectionHash, waitTimeMs, false);
            }
            // Enhanced error message with pool statistics (HikariCP-specific)
            String enhancedMessage;
            if (dataSource instanceof HikariDataSource) {
                HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
                try {
                    enhancedMessage = String.format(
                        "Connection acquisition failed for hash: %s. Pool state - Active: %d, Max: %d, Waiting threads: %d. Original error: %s",
                        connectionHash,
                        hikariDataSource.getHikariPoolMXBean().getActiveConnections(),
                        hikariDataSource.getMaximumPoolSize(),
                        hikariDataSource.getHikariPoolMXBean().getThreadsAwaitingConnection(),
                        e.getMessage()
                    );
                } catch (Exception poolStatsException) {
                    enhancedMessage = String.format(
                        "Connection acquisition failed for hash: %s. Could not retrieve pool statistics. Original error: %s",
                        connectionHash, e.getMessage()
                    );
                }
            } else {
                enhancedMessage = String.format(
                    "Connection acquisition failed for hash: %s. Original error: %s",
                    connectionHash, e.getMessage()
                );
            }
            
            log.error(enhancedMessage);
            throw new SQLException(enhancedMessage, e.getSQLState(), e);
        }
    }
}