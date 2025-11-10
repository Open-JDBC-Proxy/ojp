package org.openjproxy.constants;

/**
 * Holds common constants used in both the JDBC driver and the OJP proxy server.
 */
public class CommonConstants {
    public static final int ROWS_PER_RESULT_SET_DATA_BLOCK = 100;
    public static final int MAX_LOB_DATA_BLOCK_SIZE = 1024;//1KB per block
    public static final int PREPARED_STATEMENT_BINARY_STREAM_INDEX = 1;
    public static final int PREPARED_STATEMENT_BINARY_STREAM_LENGTH = 2;
    public static final int PREPARED_STATEMENT_BINARY_STREAM_SQL = 3;
    public static final int PREPARED_STATEMENT_UUID_BINARY_STREAM = 4;
    public static final String PREPARED_STATEMENT_SQL_KEY = "PREPARED_STATEMENT_SQL_KEY";
    public static final String PREPARED_STATEMENT_ADD_BATCH_FLAG = "PREPARED_STATEMENT_ADD_BATCH_FLAG";
    public static final String PREPARED_STATEMENT_EXECUTE_BATCH_FLAG = "PREPARED_STATEMENT_EXECUTE_BATCH_FLAG";
    public static final String STATEMENT_RESULT_SET_TYPE_KEY = "STATEMENT_RESULT_SET_TYPE_KEY";
    public static final String STATEMENT_RESULT_SET_CONCURRENCY_KEY = "STATEMENT_RESULT_SET_CONCURRENCY_KEY";
    public static final String STATEMENT_RESULT_SET_HOLDABILITY_KEY = "STATEMENT_RESULT_SET_HOLDABILITY_KEY";
    public static final String STATEMENT_AUTO_GENERATED_KEYS_KEY = "STATEMENT_AUTO_GENERATED_KEYS_KEY";
    public static final String STATEMENT_COLUMN_INDEXES_KEY = "STATEMENT_COLUMN_INDEXES_KEY";
    public static final String STATEMENT_COLUMN_NAMES_KEY = "STATEMENT_COLUMN_NAMES_KEY";
    public static final String RESULT_SET_ROW_BY_ROW_MODE = "RESULT_SET_ROW_BY_ROW_MODE";
    public static final int DEFAULT_PORT_NUMBER = 1059;
    public static final String OJP_REGEX_PATTERN = "ojp\\[([^\\]]+)\\]";
    public static final String OJP_CLOB_PREFIX = "OJP_CLOB_PREFIX:";

    // Configuration property keys
    public static final String DATASOURCE_NAME_PROPERTY = "ojp.datasource.name";
    public static final String MAXIMUM_POOL_SIZE_PROPERTY = "ojp.connection.pool.maximumPoolSize";
    public static final String MINIMUM_IDLE_PROPERTY = "ojp.connection.pool.minimumIdle";
    public static final String IDLE_TIMEOUT_PROPERTY = "ojp.connection.pool.idleTimeout";
    public static final String MAX_LIFETIME_PROPERTY = "ojp.connection.pool.maxLifetime";
    public static final String CONNECTION_TIMEOUT_PROPERTY = "ojp.connection.pool.connectionTimeout";
    // NOTE: ojp.xa.maxTransactions and ojp.xa.startTimeoutMillis have been REMOVED
    // XA connection pooling is now managed by Atomikos pool sizing (ojp.connection.pool.maximumPoolSize, etc.)
    
    // Multinode configuration property keys
    public static final String MULTINODE_RETRY_ATTEMPTS_PROPERTY = "ojp.multinode.retryAttempts";
    public static final String MULTINODE_RETRY_DELAY_PROPERTY = "ojp.multinode.retryDelayMs";

    // HikariCP default connection pool settings - optimized for high concurrency
    // ISSUE #29 FIX: Updated these values to prevent indefinite blocking under high load
    public static final int DEFAULT_MAXIMUM_POOL_SIZE = 20;  // Increased from 10 to handle more concurrent requests
    public static final int DEFAULT_MINIMUM_IDLE = 5;        // Reduced from 10 to allow pool to scale down
    public static final long DEFAULT_IDLE_TIMEOUT = 600000;  // 10 minutes
    public static final long DEFAULT_MAX_LIFETIME = 1800000; // 30 minutes  
    public static final long DEFAULT_CONNECTION_TIMEOUT = 10000; // Reduced from 30s to 10s for faster failure
    
    // NOTE: DEFAULT_MAX_XA_TRANSACTIONS and DEFAULT_XA_START_TIMEOUT_MILLIS have been REMOVED
    // XA connection pooling is now managed by Atomikos using the same pool size settings as above
    
    // Multinode configuration defaults - addressing PR #39 review comment #1
    public static final int DEFAULT_MULTINODE_RETRY_ATTEMPTS = -1;  // -1 = retry indefinitely
    public static final long DEFAULT_MULTINODE_RETRY_DELAY_MS = 5000;  // 5 seconds between retries
    
    // Atomikos dynamic pool sizing configuration property keys and defaults
    // These properties control how Atomikos XA pools are sized at startup and resized during cluster health changes
    public static final String ATOMIKOS_PER_NODE_MIN_POOL_SIZE_PROPERTY = "ojp.atomikos.perNodeMinPoolSize";
    public static final String ATOMIKOS_PER_NODE_MAX_POOL_SIZE_PROPERTY = "ojp.atomikos.perNodeMaxPoolSize";
    public static final String ATOMIKOS_GLOBAL_MAX_POOL_SIZE_PROPERTY = "ojp.atomikos.globalMaxPoolSize";
    public static final String ATOMIKOS_SIZING_COOLDOWN_MS_PROPERTY = "ojp.atomikos.sizingCooldownMs";
    public static final String ATOMIKOS_STARTUP_SIZING_BEHAVIOR_PROPERTY = "ojp.atomikos.startupSizingBehavior";
    
    // Atomikos dynamic pool sizing defaults - matching behavior of HikariCP multinode coordination
    // These values ensure Atomikos pools are sized based on cluster health, similar to HikariCP
    public static final int DEFAULT_ATOMIKOS_PER_NODE_MIN_POOL_SIZE = 2;   // Minimum connections per healthy node
    public static final int DEFAULT_ATOMIKOS_PER_NODE_MAX_POOL_SIZE = 10;  // Maximum connections per healthy node
    public static final int DEFAULT_ATOMIKOS_GLOBAL_MAX_POOL_SIZE = 100;   // Global upper bound across all nodes
    public static final long DEFAULT_ATOMIKOS_SIZING_COOLDOWN_MS = 5000;   // 5 seconds cooldown between resize operations
    public static final String DEFAULT_ATOMIKOS_STARTUP_SIZING_BEHAVIOR = "EAGER"; // EAGER or LAZY
}
